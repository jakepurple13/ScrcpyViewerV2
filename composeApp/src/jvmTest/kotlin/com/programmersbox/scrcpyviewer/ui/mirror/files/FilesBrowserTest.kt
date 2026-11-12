package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun file(name: String, size: Long = 10, mtime: Long = 1000) = DeviceEntry(name, false, size, mtime)
private fun dir(name: String, mtime: Long = 1000) = DeviceEntry(name, true, 4096, mtime)

/** Scripted device: [dirs] holds each folder's listing; mutations change it so a refresh sees them. */
private class FakeFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val calls = mutableListOf<String>()
    var fail: String? = null

    /** When set, every mutation waits for it. */
    var mutationGate: CompletableDeferred<Unit>? = null

    /** Calls that change the device. */
    val mutations get() = calls.filter { it.split(' ').first() in setOf("mkdir", "rename", "move", "delete") }
    val lists get() = calls.filter { it.startsWith("list ") }

    private fun entries(d: String) = (dirs[d] as? Listing.Entries)?.entries.orEmpty()
    private fun put(d: String, e: List<DeviceEntry>) { dirs[d] = Listing.Entries(e) }

    override suspend fun list(dir: String): Listing {
        calls += "list $dir"
        gates[dir]?.await()
        return dirs[dir] ?: Listing.Failed("$dir: No such file or directory")
    }

    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> {
        calls += "counts $dir"
        return folders.associateWith { 3 }
    }

    override suspend fun freeSpace() = DeviceSpace(5_000, 20_000)

    override suspend fun mkdir(dir: String, name: String) {
        calls += "mkdir $dir $name"
        mutationGate?.await()
        fail?.let { throw IOException(it) }
        put(dir, entries(dir) + dir(name))
    }

    override suspend fun rename(dir: String, from: String, to: String) {
        calls += "rename $dir $from $to"
        fail?.let { throw IOException(it) }
        put(dir, entries(dir).map { if (it.name == from) it.copy(name = to) else it })
    }

    override suspend fun move(dir: String, names: List<String>, toDir: String, renames: Map<String, String>, replace: Set<String>) {
        calls += "move $dir $names $toDir $renames $replace"
        fail?.let { throw IOException(it) }
        val moving = entries(dir).filter { it.name in names }
        put(dir, entries(dir).filter { it.name !in names })
        val arriving = moving.map { it.copy(name = renames[it.name] ?: it.name) }
        put(toDir, entries(toDir).filter { e -> arriving.none { it.name == e.name } } + arriving)
    }

    override suspend fun delete(dir: String, names: List<String>) {
        calls += "delete $dir $names"
        mutationGate?.await()
        fail?.let { throw IOException(it) }
        put(dir, entries(dir).filter { it.name !in names })
    }
}

class FilesBrowserTest {
    private val files = FakeFiles().apply {
        dirs["/sdcard"] = Listing.Entries(listOf(dir("Download"), dir("Documents"), dir("Android")))
        dirs["/sdcard/Download"] = Listing.Entries(
            listOf(file("notes.txt", 50), dir("Telegram"), file("app.apk", 900), file(".nomedia", 0), file("a.jpg", 20)),
        )
        dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(file("voice.ogg")))
        dirs["/sdcard/Documents"] = Listing.Entries(emptyList())
        dirs["/sdcard/Android"] = Listing.Entries(listOf(dir("data")))
        dirs["/sdcard/Android/data"] = Listing.Blocked
    }
    private val memory = QuickActionsMemory()
    private val settings = AppSettings(Files.createTempDirectory("files-browser").resolve("settings.json"))
    private val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
    private val snacks = mutableListOf<Snack>()
    private val requests = mutableListOf<TransferRequest>()
    private val jobs = MutableStateFlow<List<TransferJob>>(emptyList())

    private fun TestScope.browser(start: String = "/sdcard/Download"): FilesBrowser {
        val b = FilesBrowser(
            "SER", files, transfers, memory, settings, start, backgroundScope, { snacks += it },
            enqueue = { r -> requests += r; r.indices.map { it.toLong() + 1 } },
            jobs = jobs,
        )
        runCurrent()
        return b
    }

    /** advanceUntilIdle ignores backgroundScope, where the browser runs: let every delay pass instead. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private val FilesBrowser.names get() = visible.map { it.name }

    @Test
    fun opensAtStartFoldersFirstThenCountsAndFreeSpace() = runTest {
        val b = browser()
        assertEquals("/sdcard/Download", b.path)
        assertEquals(listOf("Telegram", "a.jpg", "app.apk", "notes.txt"), b.names, "hidden files are left out")
        assertEquals(3, b.visible.first().itemCount, "counts arrive in a second step")
        assertEquals(DeviceSpace(5_000, 20_000), b.free)
        assertFalse(b.loading)
        assertFalse(b.canGoBack)
        assertTrue(b.canGoUp)
        assertEquals(FilesLocation("/sdcard/Download", emptyList()), memory.filesLocation("SER"))
    }

    @Test
    fun opensAtRememberedLocation() = runTest {
        memory.setFilesLocation("SER", FilesLocation("/sdcard/Download/Telegram", listOf("/sdcard/Download")))
        val b = browser()
        assertEquals("/sdcard/Download/Telegram", b.path)
        assertTrue(b.canGoBack)
        b.back()
        runCurrent()
        assertEquals("/sdcard/Download", b.path)
        assertEquals(FilesLocation("/sdcard/Download", emptyList()), memory.filesLocation("SER"))
    }

    @Test
    fun navigationKeepsHistoryAndDirection() = runTest {
        val b = browser()
        b.openEntry("Telegram")
        runCurrent()
        assertEquals("/sdcard/Download/Telegram", b.path)
        assertEquals(1, b.navDirection)
        assertEquals(listOf("voice.ogg"), b.names)
        assertEquals(FilesLocation("/sdcard/Download/Telegram", listOf("/sdcard/Download")), memory.filesLocation("SER"))
        b.up()
        runCurrent()
        assertEquals("/sdcard/Download", b.path)
        assertEquals(-1, b.navDirection)
        b.up()
        runCurrent()
        assertEquals("/sdcard", b.path)
        assertFalse(b.canGoUp)
        b.up()
        assertEquals("/sdcard", b.path)
        b.back()
        runCurrent()
        assertEquals("/sdcard/Download", b.path)
        assertEquals(-1, b.navDirection)
        b.refresh()
        assertEquals(0, b.navDirection)
        b.open("/sdcard")
        assertEquals(-1, b.navDirection, "opening an ancestor (a crumb) moves back")
        b.open("/storage/emulated/0/Documents/")
        assertEquals("/sdcard/Documents", b.path)
        assertEquals(1, b.navDirection)
    }

    @Test
    fun previousRowsStayWhileLoading() = runTest {
        val b = browser()
        val gate = CompletableDeferred<Unit>().also { files.gates["/sdcard/Download/Telegram"] = it }
        b.open("/sdcard/Download/Telegram")
        runCurrent()
        assertTrue(b.loading)
        assertEquals(listOf("Telegram", "a.jpg", "app.apk", "notes.txt"), b.names)
        gate.complete(Unit)
        runCurrent()
        assertFalse(b.loading)
        assertEquals(listOf("voice.ogg"), b.names)
    }

    @Test
    fun aStaleListingNeverOverwritesANewerFolder() = runTest {
        val b = browser()
        val slow = CompletableDeferred<Unit>().also { files.gates["/sdcard/Download/Telegram"] = it }
        b.open("/sdcard/Download/Telegram")
        runCurrent()
        b.open("/sdcard")
        runCurrent()
        slow.complete(Unit)
        settle()
        assertEquals("/sdcard", b.path)
        assertEquals(listOf("Android", "Documents", "Download"), b.names)
    }

    @Test
    fun listingFailureAndBlocked() = runTest {
        val b = browser()
        b.open("/sdcard/Nope")
        runCurrent()
        assertEquals(Listing.Failed("/sdcard/Nope: No such file or directory"), b.listing)
        b.open("/sdcard/Android/data")
        runCurrent()
        assertEquals(Listing.Blocked, b.listing)
        assertEquals(emptyList(), b.names)
        assertFalse(b.push(listOf(Paths.get("/x/a.jpg")), "/sdcard/Android/data"), "blocked folders refuse pushes")
        assertTrue(requests.isEmpty())
    }

    @Test
    fun arrivingHoldsNewNamesForTheGrowAndFade() = runTest {
        val b = browser()
        files.dirs["/sdcard/Download"] = Listing.Entries((files.dirs["/sdcard/Download"] as Listing.Entries).entries + file("new.pdf"))
        b.refresh()
        runCurrent()
        assertEquals(setOf("new.pdf"), b.arriving)
        advanceTimeBy(ARRIVING_MILLIS - 1)
        assertEquals(setOf("new.pdf"), b.arriving)
        advanceTimeBy(2)
        assertEquals(emptySet(), b.arriving)
        b.open("/sdcard/Download/Telegram")
        runCurrent()
        assertEquals(emptySet(), b.arriving, "another folder's rows are not arrivals")
    }

    @Test
    fun deleteAsksThenCollapsesRowsThenRefreshes() = runTest {
        val b = browser()
        b.click("notes.txt")
        b.toggle("Telegram")
        b.askDelete()
        assertEquals(FilesDialog.Delete(listOf("Telegram", "notes.txt"), hasFolder = true), b.dialog)
        assertTrue(files.mutations.isEmpty())
        val listsBefore = files.lists.size
        b.confirmDelete()
        assertNull(b.dialog)
        runCurrent()
        assertEquals(listOf("delete /sdcard/Download [Telegram, notes.txt]"), files.mutations)
        assertEquals(setOf("Telegram", "notes.txt"), b.leaving)
        assertTrue("notes.txt" in b.names, "rows stay while they collapse")
        advanceTimeBy(CLOSE_MILLIS + 30L - 1)
        assertEquals(setOf("Telegram", "notes.txt"), b.leaving)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(emptySet(), b.leaving)
        assertEquals(listOf("a.jpg", "app.apk"), b.names)
        assertEquals(listsBefore + 1, files.lists.size, "refreshed")
        assertEquals(listOf("Deleted 2 items"), snacks.map { it.message })
        assertEquals(emptySet(), b.selection.names)

        b.click("a.jpg")
        b.askDelete()
        assertEquals(FilesDialog.Delete(listOf("a.jpg"), hasFolder = false), b.dialog)
        b.confirmDelete()
        settle()
        assertEquals("Deleted a.jpg", snacks.last().message)
    }

    @Test
    fun deleteFailureShowsTheDeviceLineAndRefreshes() = runTest {
        val b = browser()
        files.fail = "rm: notes.txt: Permission denied"
        b.click("notes.txt")
        b.askDelete()
        b.confirmDelete()
        val listsBefore = files.lists.size
        settle()
        assertEquals("Could not delete: rm: notes.txt: Permission denied", snacks.single().message)
        assertEquals(emptySet(), b.leaving)
        assertEquals(listsBefore + 1, files.lists.size)
        b.dismissDialog()
        b.askDelete()
        b.dismissDialog()
        assertNull(b.dialog)
    }

    @Test
    fun newFolderRunsMkdirOnlyOnCommit() = runTest {
        files.dirs["/sdcard/Download"] = Listing.Entries(listOf(dir("New folder"), file("a.txt")))
        val b = browser()
        b.newFolder()
        assertEquals("New folder (1)", b.pendingNewFolder)
        assertEquals("New folder (1)", b.renaming)
        assertEquals(setOf("New folder (1)"), b.selection.names)
        assertEquals(listOf("New folder", "New folder (1)", "a.txt"), b.names)
        b.cancelRename()
        assertNull(b.pendingNewFolder)
        assertNull(b.renaming)
        assertEquals(listOf("New folder", "a.txt"), b.names)
        assertTrue(files.mutations.isEmpty())

        b.newFolder()
        b.commitRename("New folder")
        assertEquals("Name already used", b.renameError)
        assertEquals("New folder (1)", b.renaming, "the field stays open")
        b.commitRename("Photos")
        assertNull(b.renaming)
        assertNull(b.renameError)
        advanceTimeBy(10)
        assertEquals(listOf("mkdir /sdcard/Download Photos"), files.mutations)
        assertNull(b.pendingNewFolder)
        assertEquals(listOf("New folder", "Photos", "a.txt"), b.names)
        assertEquals(setOf("Photos"), b.arriving)
        assertEquals(setOf("Photos"), b.selection.names)
    }

    @Test
    fun newFolderFailureRemovesTheRow() = runTest {
        val b = browser()
        files.fail = "mkdir: Read-only file system"
        b.newFolder()
        b.commitRename("X")
        settle()
        assertNull(b.pendingNewFolder)
        assertEquals("Could not create folder: mkdir: Read-only file system", snacks.single().message)
        assertFalse("X" in b.names)
    }

    @Test
    fun renameRefusesPerNameProblemAndKeepsTheFieldOpen() = runTest {
        val b = browser()
        b.startRename("notes.txt")
        b.commitRename(" ")
        assertEquals("Name can’t be empty", b.renameError)
        b.commitRename("a/b")
        assertEquals("No “/” in names", b.renameError)
        b.commitRename("a.jpg")
        assertEquals("Name already used", b.renameError)
        assertEquals("notes.txt", b.renaming)
        assertTrue(files.mutations.isEmpty())
        b.commitRename("notes.txt")
        assertNull(b.renaming, "an unchanged name just closes the field")
        assertTrue(files.mutations.isEmpty())

        b.click("notes.txt")
        b.startRename("notes.txt")
        b.commitRename("todo.txt")
        assertNull(b.renaming)
        advanceTimeBy(10)
        assertEquals(listOf("rename /sdcard/Download notes.txt todo.txt"), files.mutations)
        assertTrue("todo.txt" in b.names)
        assertEquals(emptySet(), b.arriving, "a renamed row is not an arrival")
        assertEquals(setOf("todo.txt"), b.selection.names, "the selection follows the new name")
    }

    @Test
    fun renameFailureShowsTheDeviceLine() = runTest {
        val b = browser()
        files.fail = "mv: Permission denied"
        b.startRename("notes.txt")
        b.commitRename("x.txt")
        settle()
        assertEquals("Could not rename: mv: Permission denied", snacks.single().message)
        assertTrue("notes.txt" in b.names)
    }

    @Test
    fun moveCollapsesRowsAndOffersOpen() = runTest {
        val b = browser()
        b.move(listOf("notes.txt"), "/sdcard/Documents")
        runCurrent()
        assertEquals(listOf("move /sdcard/Download [notes.txt] /sdcard/Documents {} []"), files.mutations)
        assertEquals(setOf("notes.txt"), b.leaving)
        settle()
        assertEquals(emptySet(), b.leaving)
        assertFalse("notes.txt" in b.names)
        val snack = snacks.single()
        assertEquals("Moved notes.txt to Documents", snack.message)
        assertEquals("Open", snack.actionLabel)
        snack.action!!()
        runCurrent()
        assertEquals("/sdcard/Documents", b.path)
        assertEquals(listOf("notes.txt"), b.names)

        b.move(listOf("notes.txt"), "/sdcard")
        settle()
        assertEquals("Moved notes.txt to Internal storage", snacks.last().message)
    }

    @Test
    fun moveSkipsItselfAndOwnSubfolders() = runTest {
        val b = browser()
        b.move(listOf("notes.txt"), "/sdcard/Download/")
        b.move(listOf("Telegram"), "/sdcard/Download/Telegram")
        b.move(listOf("Telegram"), "/sdcard/Download/Telegram/sub")
        settle()
        assertTrue(files.mutations.isEmpty(), "no command when nothing is left")
        assertTrue(snacks.isEmpty())

        b.move(listOf("Telegram", "a.jpg"), "/sdcard/Download/Telegram")
        settle()
        assertEquals(listOf("move /sdcard/Download [a.jpg] /sdcard/Download/Telegram {} []"), files.mutations)
        assertEquals("Moved a.jpg to Telegram", snacks.single().message)
    }

    @Test
    fun moveFailureShowsTheDeviceLine() = runTest {
        val b = browser()
        files.fail = "notes.txt: already exists"
        b.move(listOf("notes.txt"), "/sdcard/Documents")
        settle()
        assertEquals("Could not move: notes.txt: already exists", snacks.single().message)
        assertEquals(emptySet(), b.leaving)
    }

    private fun clashSetup() {
        files.dirs["/sdcard/Documents"] = Listing.Entries(listOf(file("notes.txt"), file("a.jpg"), file("a (1).jpg")))
    }

    @Test
    fun moveClashOpensOneDialogAndTheRestGoesAhead() = runTest {
        clashSetup()
        val b = browser()
        b.move(listOf("notes.txt", "a.jpg", "app.apk"), "/sdcard/Documents")
        runCurrent()
        assertEquals(listOf("move /sdcard/Download [app.apk] /sdcard/Documents {} []"), files.mutations)
        val clash = assertIs<FilesDialog.Clash>(b.dialog)
        assertEquals("/sdcard/Documents", clash.toDir)
        assertEquals(listOf("notes.txt", "a.jpg"), clash.clashing)
        clash.onChoice(ClashChoice.KeepBoth)
        assertNull(b.dialog)
        settle()
        assertEquals(
            "move /sdcard/Download [notes.txt, a.jpg] /sdcard/Documents {notes.txt=notes (1).txt, a.jpg=a (2).jpg} []",
            files.mutations.last(),
        )
    }

    @Test
    fun moveClashReplaceAndSkip() = runTest {
        clashSetup()
        val b = browser()
        b.move(listOf("notes.txt"), "/sdcard/Documents")
        runCurrent()
        assertIs<FilesDialog.Clash>(b.dialog).onChoice(ClashChoice.Replace)
        settle()
        assertEquals(listOf("move /sdcard/Download [notes.txt] /sdcard/Documents {} [notes.txt]"), files.mutations)

        b.move(listOf("a.jpg"), "/sdcard/Documents")
        runCurrent()
        assertIs<FilesDialog.Clash>(b.dialog).onChoice(ClashChoice.Skip)
        settle()
        assertEquals(1, files.mutations.size)
        assertNull(b.dialog)
    }

    @Test
    fun pushIntoTheCurrentFolderWithAClash() = runTest {
        val b = browser()
        val a = Paths.get("/x/a.jpg")
        val c = Paths.get("/x/c.jpg")
        assertTrue(b.push(listOf(a, c), "/sdcard/Download/"))
        runCurrent()
        assertEquals(listOf(TransferRequest(c, TransferKind.Push, "/sdcard/Download", "c.jpg")), requests)
        val clash = assertIs<FilesDialog.Clash>(b.dialog)
        assertEquals(listOf("a.jpg"), clash.clashing)
        clash.onChoice(ClashChoice.KeepBoth)
        assertEquals(TransferRequest(a, TransferKind.Push, "/sdcard/Download", "a (1).jpg"), requests.last())

        assertTrue(b.push(listOf(a), "/sdcard/Download"))
        runCurrent()
        assertIs<FilesDialog.Clash>(b.dialog).onChoice(ClashChoice.Replace)
        assertEquals(TransferRequest(a, TransferKind.Push, "/sdcard/Download", "a.jpg"), requests.last())
        val count = requests.size
        assertTrue(b.push(listOf(a), "/sdcard/Download"))
        runCurrent()
        assertIs<FilesDialog.Clash>(b.dialog).onChoice(ClashChoice.Skip)
        assertEquals(count, requests.size)
    }

    @Test
    fun pushIntoAnotherFolderListsItFirst() = runTest {
        clashSetup()
        val b = browser()
        assertTrue(b.push(listOf(Paths.get("/x/notes.txt"), Paths.get("/x/b.txt")), "/sdcard/Documents"))
        runCurrent()
        assertTrue("list /sdcard/Documents" in files.calls)
        assertEquals(listOf(TransferRequest(Paths.get("/x/b.txt"), TransferKind.Push, "/sdcard/Documents", "b.txt")), requests)
        assertEquals(listOf("notes.txt"), assertIs<FilesDialog.Clash>(b.dialog).clashing)

        b.dismissDialog()
        b.push(listOf(Paths.get("/x/z.txt")), "/sdcard/Android/data")
        runCurrent()
        assertEquals(1, requests.size, "a blocked folder gets nothing")
        assertEquals(listOf("Skipped notes.txt, already in Documents", "Android blocks this folder"), snacks.map { it.message })
    }

    @Test
    fun aFinishedPushIntoTheCurrentFolderRefreshes() = runTest {
        val b = browser()
        val before = files.lists.size
        fun job(id: Long, dir: String?, state: JobState) =
            TransferJob(id, TransferRequest(Paths.get("/x/n.txt"), TransferKind.Push, dir, "n.txt"), state, 0, null, null)
        jobs.value = listOf(job(1, "/sdcard/Download", JobState.Running), job(2, "/sdcard/Documents", JobState.Running))
        runCurrent()
        assertEquals(before, files.lists.size)
        jobs.value = listOf(job(1, "/sdcard/Download", JobState.Done), job(2, "/sdcard/Documents", JobState.Done))
        runCurrent()
        assertEquals(before + 1, files.lists.size, "only the push into the current folder refreshes")
        jobs.value = jobs.value + job(3, null, JobState.Done)
        runCurrent()
        assertEquals(before + 2, files.lists.size, "no folder means the session's push target")
    }

    @Test
    fun disconnectMakesEveryOpANoOpAndReconnectRefreshes() = runTest {
        val b = browser()
        b.click("notes.txt")
        b.onConnectionChanged(false)
        assertFalse(b.connected)
        val before = files.calls.size
        b.refresh(); b.open("/sdcard"); b.up(); b.openEntry("Telegram"); b.newFolder(); b.askDelete()
        b.startRename("notes.txt"); b.move(listOf("notes.txt"), "/sdcard/Documents"); b.pull(listOf("notes.txt"), Paths.get("/tmp"))
        assertFalse(b.push(listOf(Paths.get("/x/a.txt")), "/sdcard/Download"))
        settle()
        assertEquals(before, files.calls.size)
        assertEquals("/sdcard/Download", b.path)
        assertNull(b.dialog)
        assertNull(b.renaming)
        assertNull(b.pendingNewFolder)
        assertTrue(requests.isEmpty())
        assertEquals(listOf("Telegram", "a.jpg", "app.apk", "notes.txt"), b.names, "the list stays")
        b.onConnectionChanged(true)
        runCurrent()
        assertEquals(listOf("list /sdcard/Download"), files.calls.drop(before).take(1))
    }

    @Test
    fun sortAndHiddenAreSavedToSettings() = runTest {
        val b = browser()
        b.setSort(SortKey.Size)
        assertEquals(SortKey.Size, b.sort)
        assertTrue(b.descending, "size starts descending")
        assertEquals(listOf("Telegram", "app.apk", "notes.txt", "a.jpg"), b.names)
        assertEquals("size", settings.settings.value.filesSort)
        assertTrue(settings.settings.value.filesSortDescending)
        b.setSort(SortKey.Size)
        assertFalse(b.descending)
        assertEquals(listOf("Telegram", "a.jpg", "notes.txt", "app.apk"), b.names)
        b.setSort(SortKey.Modified)
        assertTrue(b.descending)
        assertEquals("mtime", settings.settings.value.filesSort)
        b.setSort(SortKey.Name)
        assertFalse(b.descending)
        assertEquals("name", settings.settings.value.filesSort)
        b.toggleHidden()
        assertTrue(b.showHidden)
        assertTrue(settings.settings.value.filesShowHidden)
        assertEquals(listOf("Telegram", ".nomedia", "a.jpg", "app.apk", "notes.txt"), b.names)

        settings.update { it.copy(filesSort = "mtime", filesSortDescending = true, filesShowHidden = false) }
        val c = browser()
        assertEquals(SortKey.Modified, c.sort)
        assertTrue(c.descending)
        assertFalse(c.showHidden)
    }

    @Test
    fun filterIsKeptOnRefreshAndClearedOnOpen() = runTest {
        val b = browser()
        b.click("notes.txt")
        b.setFilter("P")
        assertEquals(listOf("a.jpg", "app.apk"), b.names)
        assertEquals(emptySet(), b.selection.names, "hidden rows leave the selection")
        b.refresh()
        runCurrent()
        assertEquals("P", b.filter)
        b.openEntry("Telegram")
        assertEquals("", b.filter)
    }

    @Test
    fun selectionForwardsToVisibleOrder() = runTest {
        val b = browser()
        b.click("a.jpg")
        b.shiftClick("notes.txt")
        assertEquals(setOf("a.jpg", "app.apk", "notes.txt"), b.selection.names)
        b.selectAll()
        assertEquals(4, b.selection.names.size)
        b.clearSelection()
        b.moveSelection(1, extend = false)
        assertEquals(setOf("Telegram"), b.selection.names)
        b.moveSelection(1, extend = true)
        assertEquals(setOf("Telegram", "a.jpg"), b.selection.names)
    }

    @Test
    fun openEntryInstallsApksAndOpensFiles() = runTest {
        val b = browser()
        b.openEntry("app.apk")
        b.openEntry("notes.txt")
        assertEquals(
            listOf(
                TransferRequest(Paths.get(""), TransferKind.InstallFromDevice, "/sdcard/Download", "app.apk", size = 900),
                TransferRequest(Paths.get(""), TransferKind.Pull, "/sdcard/Download", "notes.txt", opensAfter = true, size = 50),
            ),
            requests,
        )
    }

    @Test
    fun pullPassesSizeAndFolderFlag() = runTest {
        val b = browser()
        val to: Path = Paths.get("/home/me/Downloads")
        b.pull(listOf("notes.txt", "Telegram"), to)
        assertEquals(
            listOf(
                TransferRequest(to, TransferKind.Pull, "/sdcard/Download", "notes.txt", size = 50),
                TransferRequest(to, TransferKind.Pull, "/sdcard/Download", "Telegram", size = 4096, isDirectory = true),
            ),
            requests,
        )
    }

    @Test
    fun aNewFolderRowNeverVanishesWhileTheRefreshRuns() = runTest {
        val b = browser()
        b.newFolder()
        b.commitRename("Photos")
        val gate = CompletableDeferred<Unit>().also { files.gates["/sdcard/Download"] = it }
        runCurrent()
        assertEquals(listOf("mkdir /sdcard/Download Photos"), files.mutations)
        assertTrue(b.loading)
        assertNull(b.pendingNewFolder)
        assertEquals(1, b.names.count { it == "Photos" }, "the row stays while the listing runs")
        assertEquals(setOf("Photos"), b.arriving)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, b.names.count { it == "Photos" })
    }

    @Test
    fun aPendingNameThatAppearsOnTheDeviceIsShownOnce() = runTest {
        val b = browser()
        b.newFolder()
        files.dirs["/sdcard/Download"] = Listing.Entries((files.dirs["/sdcard/Download"] as Listing.Entries).entries + dir("New folder"))
        b.refresh()
        runCurrent()
        assertEquals("New folder", b.pendingNewFolder)
        assertEquals(1, b.names.count { it == "New folder" })
    }

    @Test
    fun moveIntoTheSameFolderInAnotherCaseDoesNothing() = runTest {
        val b = browser()
        b.move(listOf("notes.txt"), "/sdcard/download/")
        settle()
        assertTrue(files.mutations.isEmpty())
        assertNull(b.dialog)
    }

    @Test
    fun anUnansweredClashSaysWhatWasSkipped() = runTest {
        clashSetup()
        val b = browser()
        b.move(listOf("notes.txt", "a.jpg"), "/sdcard/Documents")
        runCurrent()
        assertIs<FilesDialog.Clash>(b.dialog)
        b.push(listOf(Paths.get("/x/a.jpg")), "/sdcard/Download")
        assertEquals(listOf("Skipped 2 items already in Documents"), snacks.map { it.message }, "replaced by a newer clash")
        b.open("/sdcard")
        assertEquals("Skipped a.jpg, already in Download", snacks.last().message, "closed by navigating")
        runCurrent()
        b.push(listOf(Paths.get("/x/Download")), "/sdcard")
        b.onConnectionChanged(false)
        assertEquals("Skipped Download, already in Internal storage", snacks.last().message, "closed by a disconnect")
        val count = snacks.size
        b.onConnectionChanged(true)
        runCurrent()
        b.push(listOf(Paths.get("/x/Download")), "/sdcard")
        assertIs<FilesDialog.Clash>(b.dialog).onChoice(ClashChoice.Skip)
        assertEquals(count, snacks.size, "an answered clash says nothing")
    }

    @Test
    fun leavingAndArrivingStayWithTheirFolder() = runTest {
        val b = browser()
        files.mutationGate = CompletableDeferred()
        b.click("notes.txt")
        b.askDelete()
        b.confirmDelete()
        runCurrent()
        b.open("/sdcard/Download/Telegram")
        runCurrent()
        files.mutationGate!!.complete(Unit)
        runCurrent()
        assertEquals(emptySet(), b.leaving, "rows of a folder no longer shown do not collapse")
        assertEquals(listOf("voice.ogg"), b.names)

        files.dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(file("voice.ogg"), file("new.ogg")))
        b.refresh()
        runCurrent()
        assertEquals(setOf("new.ogg"), b.arriving)
        b.up()
        assertEquals(emptySet(), b.arriving)
    }
}
