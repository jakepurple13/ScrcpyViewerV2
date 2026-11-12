package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun file(name: String, size: Long = 10) = DeviceEntry(name, false, size, 1_731_000_000_000)
private fun dir(name: String) = DeviceEntry(name, true, 4096, 1_731_000_000_000)

/** Answers at once and records every change it is asked to make. */
private class RecordingFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()
    val mutations = mutableListOf<String>()

    override suspend fun list(dir: String): Listing = dirs[dir] ?: Listing.Failed("ls: $dir: No such file or directory")
    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 3 }
    override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
    override suspend fun mkdir(dir: String, name: String) { mutations += "mkdir $name" }
    override suspend fun rename(dir: String, from: String, to: String) { mutations += "rename $from $to" }
    override suspend fun delete(dir: String, names: List<String>) { mutations += "delete ${names.joinToString(",")}" }
    override suspend fun move(dir: String, names: List<String>, toDir: String, renames: Map<String, String>, replace: Set<String>) {
        mutations += "move ${names.joinToString(",")} $toDir"
        val left = (dirs[dir] as Listing.Entries).entries.filter { it.name !in names }
        dirs[dir] = Listing.Entries(left)
    }
}

/** Selection bar, menus, rename, dialogs, keys and the snackbar, over a scripted device. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FilesInteractionTest {
    private val files = RecordingFiles().apply {
        dirs["/sdcard/Download"] = Listing.Entries(
            listOf(file("notes.txt", 50), dir("Telegram"), file("app.apk", 900), file("a.jpg", 20)),
        )
        dirs["/sdcard/Documents"] = Listing.Entries(listOf(file("cv.pdf")))
    }
    private val scheduler = TestCoroutineScheduler()

    /** Set when an Esc reached the stand-in sheet (nothing in the tab used it). */
    private var sheetClosed = false

    private fun browser() = FilesBrowser(
        "SER", files, FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("files-interaction").resolve("settings.json")), "/sdcard/Download",
        CoroutineScope(UnconfinedTestDispatcher(scheduler)), {}, enqueue = { emptyList() }, jobs = emptyFlow(),
    )

    private fun scene(browser: FilesBrowser, motion: Boolean = false): SceneDriver =
        SceneDriver(ImageComposeScene(440, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    // The sheet's own handler: an Esc nothing in the tab used closes it.
                    Box(
                        Modifier.fillMaxSize().onKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                                sheetClosed = true
                                true
                            } else {
                                false
                            }
                        },
                    ) {
                        FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = motion)
                    }
                }
            }
        }).also { it.frames(30) }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun SceneDriver.texts(): List<String> = nodes().mapNotNull { it.text() }

    private fun SceneDriver.text(value: String): SemanticsNode =
        checkNotNull(nodes().lastOrNull { it.text() == value }) { "no \"$value\" in ${texts()}" }

    private fun SceneDriver.enabled(node: SemanticsNode): Boolean {
        val clickable = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        return SemanticsProperties.Disabled !in clickable.config
    }

    private fun SceneDriver.renameField(): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == RENAME_FIELD_TAG }

    private fun SceneDriver.press(node: SemanticsNode, button: PointerButton = PointerButton.Primary) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = button)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = button)
        frames(30)
    }

    /** A primary click on the list well below its rows. */
    private fun SceneDriver.clickEmptySpace() {
        val list = checkNotNull(nodes().firstOrNull { SemanticsActions.ScrollToIndex in it.config })
        val at = androidx.compose.ui.geometry.Offset(list.boundsInRoot.center.x, list.boundsInRoot.top + 400f)
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(30)
        scheduler.advanceUntilIdle()
        frames(30)
    }

    private fun SceneDriver.described(description: String): SemanticsNode = checkNotNull(
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true },
    ) { "nothing described \"$description\"" }

    private fun SceneDriver.typeInRename(text: String) {
        val field = checkNotNull(renameField()) { "no rename field" }
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
        frames(5)
    }

    private fun SceneDriver.rightClick(name: String) = press(text(name), PointerButton.Secondary)

    private fun SceneDriver.key(key: Key, cmd: Boolean = false, shift: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(
                KeyEvent(key, type, isMetaPressed = cmd && Os.isMac, isCtrlPressed = cmd && !Os.isMac, isShiftPressed = shift),
            )
            frame()
        }
        scheduler.advanceUntilIdle()
        frames(30)
    }

    private val itemMenu = listOf("Pull to Downloads", "Pull to…", "Rename", "Move to…", "Copy path", "Delete")

    @Test
    fun `right-clicking a file shows its menu`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.rightClick("notes.txt")
        assertEquals(setOf("notes.txt"), b.selection.names, "the row becomes the selection")
        val texts = driver.texts()
        assertTrue((itemMenu + "Open on computer").all { it in texts }, "item menu: $texts")
        assertFalse("Install on this device" in texts)
        assertFalse("Open" in texts, "Open is for one folder")
    }

    @Test
    fun `after the browser is swapped a click reaches the new browser only`() = onEdt {
        val a = browser()
        val b = browser()
        var shown by mutableStateOf(a)
        val driver = SceneDriver(ImageComposeScene(440, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { FilesTab(shown, zone = ZoneOffset.UTC, locale = Locale.US, motion = false) }
            }
        }).also { it.frames(30) }
        a.click("notes.txt")
        driver.frames(5)
        shown = b
        driver.frames(30)
        scheduler.advanceUntilIdle()
        driver.frames(30)
        driver.press(driver.text("a.jpg"))
        assertEquals(setOf("a.jpg"), b.selection.names, "the click selects in the new browser")
        assertEquals(setOf("notes.txt"), a.selection.names, "the old browser is left alone")
        driver.clickEmptySpace()
        assertTrue(b.selection.names.isEmpty(), "empty space clears the new browser's selection")
        assertEquals(setOf("notes.txt"), a.selection.names, "and still not the old one's")
    }

    @Test
    fun `Move to offers the fixed folders minus the open one, folders never into themselves`() {
        assertEquals(
            listOf("Internal storage", "DCIM/Camera", "Documents", "Pictures", "Movies"),
            moveTargets("/sdcard/Download", emptyList()).map { it.label },
        )
        val fromRoot = moveTargets("/sdcard", listOf("/sdcard/DCIM"))
        assertEquals(listOf("Download", "DCIM/Camera", "Documents", "Pictures", "Movies"), fromRoot.map { it.label })
        assertEquals(listOf("DCIM/Camera"), fromRoot.filterNot { it.enabled }.map { it.label })
    }

    @Test
    fun `right-clicking an apk adds Install`() = onEdt {
        val driver = scene(browser())
        driver.rightClick("app.apk")
        assertTrue("Install on this device" in driver.texts())
        assertTrue("Open on computer" in driver.texts())
    }

    @Test
    fun `with two selected Rename is disabled and paths are plural`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        b.toggle("app.apk")
        driver.frames(10)
        assertTrue("2 selected" in driver.texts(), "the selection bar: ${driver.texts()}")
        driver.rightClick("app.apk")
        assertEquals(setOf("notes.txt", "app.apk"), b.selection.names, "a row already selected keeps the selection")
        assertFalse(driver.enabled(driver.text("Rename")))
        assertTrue("Copy paths" in driver.texts())
    }

    @Test
    fun `Delete on two asks once and deletes once`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        b.toggle("app.apk")
        driver.key(Key.Delete)
        val texts = driver.texts()
        assertTrue("Delete 2 items?" in texts, "dialog: $texts")
        assertTrue("This removes it from the phone and can’t be undone." in texts)
        assertTrue(texts.count { it == "app.apk" } >= 2 && texts.count { it == "notes.txt" } >= 2, "the names listed")
        driver.press(driver.text("Delete"))
        scheduler.advanceUntilIdle()
        driver.frames(30)
        assertEquals(listOf("delete app.apk,notes.txt"), files.mutations)
        assertNull(b.dialog)
    }

    @Test
    fun `Esc cancels the delete dialog before the selection`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.Delete)
        assertTrue("Delete “notes.txt”?" in driver.texts())
        driver.key(Key.Escape)
        assertNull(b.dialog)
        assertEquals(setOf("notes.txt"), b.selection.names)
        assertFalse(sheetClosed)
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun `renaming to a name in use is refused in place`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.F2)
        assertEquals("notes.txt", b.renaming)
        val field = checkNotNull(driver.renameField()) { "no rename field" }
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("a.jpg"))
        driver.frames(5)
        driver.key(Key.Enter)
        assertTrue("Name already used" in driver.texts(), "${driver.texts()}")
        assertEquals("notes.txt", b.renaming, "the field stays open")
        assertTrue(driver.renameField() != null)
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun `new folder shortcut adds a row in rename and Esc drops it`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.N, cmd = true, shift = true)
        assertEquals("New folder", b.pendingNewFolder)
        assertEquals("New folder", b.renaming)
        assertTrue(driver.renameField() != null)
        driver.key(Key.Escape)
        assertNull(b.pendingNewFolder)
        assertNull(driver.renameField())
        assertTrue(files.mutations.none { it.startsWith("mkdir") })
        assertFalse(sheetClosed)
    }

    @Test
    fun `Esc clears the selection first, then closes the sheet`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.Escape)
        assertTrue(b.selection.names.isEmpty())
        assertFalse(sheetClosed, "the sheet stays open")
        driver.key(Key.Escape)
        assertTrue(sheetClosed)
    }

    @Test
    fun `Move to Documents shows a snackbar whose Open opens it`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.rightClick("notes.txt")
        driver.press(driver.text("Move to…"))
        assertTrue("DCIM/Camera" in driver.texts(), "the Move to… list: ${driver.texts()}")
        driver.press(driver.text("Documents"))
        scheduler.advanceUntilIdle()
        driver.frames(30)
        assertEquals(listOf("move notes.txt /sdcard/Documents"), files.mutations)
        assertTrue("Moved notes.txt to Documents" in driver.texts(), "${driver.texts()}")
        driver.press(driver.text("Open"))
        scheduler.advanceUntilIdle()
        driver.frames(30)
        assertEquals("/sdcard/Documents", b.path)
        assertTrue("cv.pdf" in driver.texts())
    }

    @Test
    fun `Esc closes an open menu before anything else`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.rightClick("notes.txt")
        assertTrue("Copy path" in driver.texts())
        driver.key(Key.Escape)
        assertFalse("Copy path" in driver.texts(), "the menu closed")
        assertEquals(setOf("notes.txt"), b.selection.names, "the selection stays")
        assertFalse(sheetClosed)
    }

    @Test
    fun `with motion the menu and dialog animate in and out`() = onEdt {
        val b = browser()
        val driver = scene(b, motion = true)
        driver.frames(60)
        driver.rightClick("notes.txt")
        driver.frames(30)
        driver.press(driver.text("Delete"))
        driver.frames(40)
        assertTrue("Delete “notes.txt”?" in driver.texts(), "${driver.texts()}")
        assertFalse("Copy path" in driver.texts(), "the menu has gone")
        driver.press(driver.text("Cancel"))
        driver.frames(40)
        assertNull(b.dialog)
        assertFalse("Delete “notes.txt”?" in driver.texts())
    }

    @Test
    fun `clicking empty space commits a rename`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.F2)
        driver.typeInRename("memo.txt")
        driver.clickEmptySpace()
        assertEquals(listOf("rename notes.txt memo.txt"), files.mutations)
        assertNull(b.renaming)
    }

    @Test
    fun `clicking empty space creates a new folder`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.N, cmd = true, shift = true)
        driver.typeInRename("Stuff")
        driver.clickEmptySpace()
        assertEquals(listOf("mkdir Stuff"), files.mutations)
    }

    @Test
    fun `clicking a toolbar button commits a rename first`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.F2)
        driver.typeInRename("memo.txt")
        driver.press(driver.described("Refresh"))
        scheduler.advanceUntilIdle()
        driver.frames(30)
        assertEquals(listOf("rename notes.txt memo.txt"), files.mutations)
    }

    @Test
    fun `a name refused as focus leaves keeps the field focused`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.F2)
        driver.typeInRename("a.jpg")
        driver.clickEmptySpace()
        assertTrue("Name already used" in driver.texts(), "${driver.texts()}")
        assertEquals(true, driver.renameField()?.config?.getOrNull(SemanticsProperties.Focused), "focus came back")
        driver.typeInRename("memo.txt")
        driver.key(Key.Enter)
        assertEquals(listOf("rename notes.txt memo.txt"), files.mutations)
    }

    @Test
    fun `the snackbar sits above the selection bar`() = onEdt {
        val b = browser()
        val driver = scene(b)
        b.move(listOf("notes.txt"), "/sdcard/Documents")
        scheduler.advanceUntilIdle()
        driver.frames(30)
        driver.press(driver.text("a.jpg"))
        val snack = driver.text("Moved notes.txt to Documents").boundsInRoot
        val bar = driver.text("1 selected").boundsInRoot
        assertTrue(snack.bottom <= bar.top, "snackbar $snack over the bar $bar")
    }

    @Test
    fun `Backspace in the rename field edits it, no delete dialog`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.key(Key.F2)
        driver.key(Key.Backspace)
        assertNull(b.dialog)
        assertEquals("notes.txt", b.renaming)
    }

    @Test
    fun `Backspace in the filter edits it, no delete dialog`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        driver.press(driver.described("Filter this folder"))
        driver.key(Key.Backspace)
        assertNull(b.dialog)
        assertEquals(setOf("notes.txt"), b.selection.names)
    }

    @Test
    fun `the delete dialog marks folders`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.press(driver.text("notes.txt"))
        b.toggle("Telegram")
        driver.key(Key.Delete)
        val texts = driver.texts()
        assertTrue("Telegram/" in texts, "$texts")
        assertTrue("Folders are deleted with everything inside." in texts)
    }
}
