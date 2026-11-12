package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneDragAndDropNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.mirror.fileDropTarget
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun file(name: String) = DeviceEntry(name, false, 10, 1_731_000_000_000)
private fun dir(name: String) = DeviceEntry(name, true, 4096, 1_731_000_000_000)

/** Answers at once and records the moves it is asked to make. */
private class DropFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()
    val moves = mutableListOf<String>()
    val listed = mutableListOf<String>()

    override suspend fun list(dir: String): Listing {
        listed += dir
        return dirs[dir] ?: Listing.Failed("ls: $dir: No such file or directory")
    }
    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 3 }
    override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
    override suspend fun move(dir: String, names: List<String>, toDir: String, renames: Map<String, String>, replace: Set<String>) {
        moves += "move ${names.joinToString(",")} from $dir to $toDir"
        val source = (dirs[dir] as Listing.Entries).entries
        val moved = source.filter { it.name in names }
        dirs[dir] = Listing.Entries(source - moved.toSet())
        dirs[toDir] = Listing.Entries(((dirs[toDir] as? Listing.Entries)?.entries ?: emptyList()) + moved)
    }
}

/** Push drops onto rows, the list and crumbs; spring-open; in-tab moves; the mirror's drop staying off the sheet. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FilesDropTest {
    private val files = DropFiles().apply {
        dirs["/sdcard/Download"] = Listing.Entries(listOf(file("notes.txt"), dir("Telegram"), file("a.jpg"), dir("Old")))
        dirs["/sdcard/Download/Telegram"] = Listing.Entries(listOf(file("voice.ogg"), dir("Voice")))
        dirs["/sdcard/Download/Telegram/Voice"] = Listing.Entries(emptyList())
        dirs["/sdcard"] = Listing.Entries(listOf(dir("Download"), dir("DCIM")))
    }
    private val scheduler = TestCoroutineScheduler()
    private val queued = mutableListOf<TransferRequest>()
    private val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
    private val computer: List<Path> = listOf(Files.createTempDirectory("drop").resolve("photo.jpg"))

    private fun browser() = FilesBrowser(
        "SER", files, transfers, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("files-drop").resolve("settings.json")), "/sdcard/Download",
        CoroutineScope(UnconfinedTestDispatcher(scheduler)), {},
        enqueue = { queued += it; it.indices.map { i -> i.toLong() } }, jobs = emptyFlow(),
    )

    private val filesData: DragData = object : DragData.FilesList {
        override fun readFiles() = computer.map { it.toUri().toString() }
    }

    /** The stand-in window: a 400 px stage and the sheet holding the tab, under the mirror's own drop target. */
    private fun scene(browser: FilesBrowser, filesShown: Boolean = true): SceneDriver {
        val sheet = arrayOfNulls<Rect>(1)
        return SceneDriver(ImageComposeScene(900, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                val overSheet = remember { { p: Offset -> sheet[0]?.contains(p) == true } }
                Box(Modifier.fillMaxSize().fileDropTarget(transfers, overSheet) { filesData }) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(400.dp).fillMaxHeight())
                        Surface(Modifier.fillMaxSize().onGloballyPositioned { sheet[0] = it.boundsInRoot() }) {
                            // Another tab shows nothing that takes drops.
                            if (filesShown) {
                                FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = false, dragData = { filesData })
                            }
                        }
                    }
                }
            }
        }).also { it.frames(30) }
    }

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
    private fun SceneDriver.at(value: String): Offset =
        checkNotNull(nodes().lastOrNull { it.text() == value }) { "no \"$value\" in ${texts()}" }.boundsInRoot.center

    private fun SceneDriver.emptySpace(): Offset {
        val list = checkNotNull(nodes().firstOrNull { SemanticsActions.ScrollToIndex in it.config })
        return Offset(list.boundsInRoot.center.x, list.boundsInRoot.top + 400f)
    }

    // The window's drag-and-drop entry point, as the AWT / Tao manager drives it.
    private fun SceneDriver.dnd(): ComposeSceneDragAndDropNode {
        val field = ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }
        return (field.get(scene) as ComposeScene).rootDragAndDropNode
    }

    private fun event(at: Offset) = DragAndDropEvent(DragAndDropTransferAction.Copy, null, at)

    private fun SceneDriver.dragIn(at: Offset) {
        val start = event(at)
        assertTrue(dnd().acceptDragAndDropTransfer(start))
        dnd().onStarted(start)
        dnd().onEntered(start)
        dnd().onMoved(start)
        frames(5)
    }

    private fun SceneDriver.dragTo(at: Offset, frames: Int = 5) {
        dnd().onMoved(event(at))
        frames(frames)
    }

    private fun SceneDriver.dropAt(at: Offset): Boolean {
        dnd().onMoved(event(at))
        val accepted = dnd().onDrop(event(at))
        dnd().onEnded(event(at))
        frames(10)
        scheduler.advanceUntilIdle()
        frames(10)
        return accepted
    }

    // In-tab drags: plain mouse events.
    private fun SceneDriver.mouseDown(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
    }

    private fun SceneDriver.mouseTo(to: Offset, from: Offset) {
        for (i in 1..10) {
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f), type = PointerType.Mouse)
            frame()
        }
        frames(5)
    }

    private fun SceneDriver.mouseUp(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(10)
        scheduler.advanceUntilIdle()
        frames(10)
    }

    private fun SceneDriver.esc() {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(Key.Escape, type))
            frame()
        }
    }

    @Test
    fun `drop target table`() {
        val cur = "/sdcard/Download"
        assertEquals(
            FilesDropTarget.Folder("/sdcard/Download/Telegram"),
            dropTargetFor("Telegram", true, cur, emptySet(), blocked = false, internal = false),
        )
        assertEquals(FilesDropTarget.Current(cur), dropTargetFor("a.jpg", false, cur, emptySet(), blocked = false, internal = false))
        assertEquals(FilesDropTarget.Current(cur), dropTargetFor(null, false, cur, emptySet(), blocked = false, internal = false))
        assertEquals(
            FilesDropTarget.Folder("/sdcard/Download/Telegram"),
            dropTargetFor("Telegram", true, cur, setOf("a.jpg"), blocked = false, internal = true),
        )
        assertNull(dropTargetFor("Telegram", true, cur, setOf("Telegram"), blocked = false, internal = true), "into itself")
        assertNull(dropTargetFor("a.jpg", false, cur, setOf("notes.txt"), blocked = false, internal = true), "file row")
        assertNull(dropTargetFor(null, false, cur, setOf("notes.txt"), blocked = false, internal = true), "empty space")
        assertNull(dropTargetFor("Telegram", true, cur, emptySet(), blocked = true, internal = false))
        assertNull(dropTargetFor(null, false, cur, emptySet(), blocked = true, internal = false))
    }

    @Test
    fun `Android data and obb are known to be blocked`() {
        assertTrue(isKnownBlocked("/sdcard/Android/data"))
        assertTrue(isKnownBlocked("/sdcard/android/OBB/com.app"))
        assertFalse(isKnownBlocked("/sdcard/Android"))
        assertFalse(isKnownBlocked("/sdcard/Android/media"))
    }

    @Test
    fun `dropping on a folder row pushes into it`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.dragIn(driver.at("notes.txt"))
        driver.dragTo(driver.at("Telegram"))
        assertTrue("Push into Telegram" in driver.texts(), driver.texts().toString())
        assertTrue(driver.dropAt(driver.at("Telegram")))
        assertEquals(listOf("/sdcard/Download/Telegram"), queued.map { it.deviceDir })
        assertEquals(listOf(computer.single().toAbsolutePath()), queued.map { it.path.toAbsolutePath() })
        assertFalse("Push into Telegram" in driver.texts(), "the chip leaves after the drop")
    }

    @Test
    fun `holding over a folder row springs it open, leaving early does not`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.dragIn(driver.at("notes.txt"))
        driver.dragTo(driver.at("Telegram"), frames = 20) // 320 ms
        driver.dragTo(driver.at("a.jpg"), frames = 60)
        assertEquals("/sdcard/Download", b.path, "left before $SPRING_OPEN_MILLIS ms")
        driver.dragTo(driver.at("Telegram"), frames = 60) // 960 ms
        scheduler.advanceUntilIdle()
        driver.frames(10)
        assertEquals("/sdcard/Download/Telegram", b.path)
        // The drag goes on in the opened folder: the list now offers its own folder.
        driver.dragTo(driver.emptySpace())
        assertTrue("Push to /sdcard/Download/Telegram/" in driver.texts(), driver.texts().toString())
        driver.dropAt(driver.emptySpace())
        assertEquals(listOf("/sdcard/Download/Telegram"), queued.map { it.deviceDir })
    }

    @Test
    fun `dropping on empty space or a file row pushes to the open folder`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.dragIn(driver.emptySpace())
        driver.dragTo(driver.at("a.jpg"))
        assertTrue("Push to /sdcard/Download/" in driver.texts(), driver.texts().toString())
        driver.dragTo(driver.emptySpace())
        assertTrue("Push to /sdcard/Download/" in driver.texts(), driver.texts().toString())
        assertTrue(driver.dropAt(driver.emptySpace()))
        assertEquals(listOf("/sdcard/Download"), queued.map { it.deviceDir })
    }

    @Test
    fun `dropping on a crumb pushes into that folder`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.dragIn(driver.emptySpace())
        driver.dragTo(driver.at("Internal storage"))
        assertTrue("Push into Internal storage" in driver.texts(), driver.texts().toString())
        assertTrue(driver.dropAt(driver.at("Internal storage")))
        assertEquals(listOf("/sdcard"), queued.map { it.deviceDir })
    }

    @Test
    fun `a known-blocked folder row is no target`() = onEdt {
        files.dirs["/sdcard/Android"] = Listing.Entries(listOf(dir("data"), dir("media")))
        val b = browser()
        b.open("/sdcard/Android")
        scheduler.advanceUntilIdle()
        val driver = scene(b)
        driver.dragIn(driver.at("media"))
        driver.dragTo(driver.at("data"), frames = 60)
        assertFalse(driver.texts().any { it.startsWith("Push into") }, driver.texts().toString())
        assertEquals("/sdcard/Android", b.path, "no spring-open into a blocked folder")
        assertFalse(driver.dropAt(driver.at("data")))
        assertEquals(emptyList(), queued)
    }

    @Test
    fun `the mirror's drop overlay stays off while over the sheet`() = onEdt {
        val b = browser()
        val driver = scene(b)
        driver.dragIn(Offset(200f, 400f))
        assertTrue(transfers.dragging, "over the stage")
        driver.dragTo(driver.at("notes.txt"))
        assertFalse(transfers.dragging, "over a row")
        driver.dragTo(Offset(200f, 400f))
        assertTrue(transfers.dragging, "back over the stage")
        driver.dragTo(driver.at("Telegram"))
        assertFalse(transfers.dragging)
        driver.dropAt(driver.at("Telegram"))
        assertFalse(transfers.dragging)
        assertEquals(listOf("/sdcard/Download/Telegram"), queued.map { it.deviceDir }, "pushed once, by the tab")
    }

    @Test
    fun `over the sheet on another tab the mirror's drop does nothing`() = onEdt {
        val driver = scene(browser(), filesShown = false)
        val sheet = Offset(650f, 400f)
        driver.dragIn(sheet)
        assertFalse(transfers.dragging, "entered over the sheet")
        driver.dragTo(Offset(200f, 400f))
        assertTrue(transfers.dragging, "over the stage")
        driver.dragTo(sheet)
        assertFalse(transfers.dragging, "back over the sheet")
        assertFalse(driver.dropAt(sheet))
    }

    @Test
    fun `dragging two selected rows onto a folder moves them once`() = onEdt {
        val b = browser()
        b.click("a.jpg")
        b.toggle("notes.txt")
        val driver = scene(b)
        val from = driver.at("notes.txt")
        val to = driver.at("Telegram")
        driver.mouseDown(from)
        assertEquals(setOf("a.jpg", "notes.txt"), b.selection.names, "a press on a selected row keeps the selection")
        driver.mouseTo(to, from)
        assertTrue("2 items" in driver.texts(), driver.texts().toString())
        assertTrue("Move into Telegram" in driver.texts(), driver.texts().toString())
        driver.mouseUp(to)
        assertEquals(1, files.moves.size, files.moves.toString())
        assertTrue(files.moves.single().endsWith("from /sdcard/Download to /sdcard/Download/Telegram"), files.moves.toString())
        assertEquals(setOf("a.jpg", "notes.txt"), files.moves.single().removePrefix("move ").substringBefore(" ").split(",").toSet())
        assertFalse("2 items" in driver.texts(), "the ghost leaves")
    }

    @Test
    fun `a click on one of several selected rows selects just it`() = onEdt {
        val b = browser()
        b.click("a.jpg")
        b.toggle("notes.txt")
        val driver = scene(b)
        val at = driver.at("notes.txt")
        driver.mouseDown(at)
        driver.mouseUp(at)
        assertEquals(setOf("notes.txt"), b.selection.names)
    }

    @Test
    fun `dropping a move on empty space or the open folder's crumb does nothing`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(driver.emptySpace(), from)
        assertTrue("1 file" in driver.texts(), driver.texts().toString())
        assertFalse(driver.texts().any { it.startsWith("Move into") }, driver.texts().toString())
        driver.mouseUp(driver.emptySpace())
        val again = driver.at("a.jpg")
        driver.mouseDown(again)
        driver.mouseTo(driver.at("Download"), again)
        assertFalse(driver.texts().any { it.startsWith("Move into") }, driver.texts().toString())
        driver.mouseUp(driver.at("Download"))
        assertEquals(emptyList(), files.moves)
    }

    @Test
    fun `a move onto another crumb moves there`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(driver.at("Internal storage"), from)
        assertTrue("Move into Internal storage" in driver.texts(), driver.texts().toString())
        driver.mouseUp(driver.at("Internal storage"))
        assertEquals(listOf("move a.jpg from /sdcard/Download to /sdcard"), files.moves)
    }

    /** Presses a.jpg and holds the drag over Telegram until it springs open. */
    private fun SceneDriver.springMoveIntoTelegram(b: FilesBrowser): Offset {
        val from = at("a.jpg")
        val telegram = at("Telegram")
        mouseDown(from)
        mouseTo(telegram, from)
        assertTrue("Move into Telegram" in texts(), texts().toString())
        frames(60) // past SPRING_OPEN_MILLIS
        scheduler.advanceUntilIdle()
        frames(10)
        assertEquals("/sdcard/Download/Telegram", b.path)
        assertTrue("1 file" in texts(), "the drag goes on: ${texts()}")
        return telegram
    }

    @Test
    fun `a move that sprang open drops onto a folder row there`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val start = driver.springMoveIntoTelegram(b)
        val voice = driver.at("Voice")
        driver.mouseTo(voice, start)
        assertTrue("Move into Voice" in driver.texts(), driver.texts().toString())
        val listedBefore = files.listed.count { it == "/sdcard/Download/Telegram" }
        driver.mouseUp(voice)
        assertEquals(listOf("move a.jpg from /sdcard/Download to /sdcard/Download/Telegram/Voice"), files.moves)
        assertTrue(files.listed.count { it == "/sdcard/Download/Telegram" } > listedBefore, "the open folder refreshes")
    }

    @Test
    fun `a move that sprang open drops into the opened folder, its crumb too, not the source's`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val start = driver.springMoveIntoTelegram(b)
        val download = driver.at("Download")
        driver.mouseTo(download, start)
        assertFalse(driver.texts().any { it.startsWith("Move into") }, "the source folder's crumb: ${driver.texts()}")
        val crumb = driver.nodes().last { it.text() == "Telegram" }.boundsInRoot.center
        driver.mouseTo(crumb, download)
        assertTrue("Move into Telegram" in driver.texts(), "the open folder's crumb: ${driver.texts()}")
        val space = driver.emptySpace()
        driver.mouseTo(space, crumb)
        assertTrue("Move into Telegram" in driver.texts(), "empty space: ${driver.texts()}")
        driver.mouseUp(space)
        assertEquals(listOf("move a.jpg from /sdcard/Download to /sdcard/Download/Telegram"), files.moves)
        assertTrue("a.jpg" in b.visible.map { it.name }, "the opened folder shows what arrived: ${b.visible}")
    }

    @Test
    fun `no move starts while disconnected`() = onEdt {
        val b = browser()
        val driver = scene(b)
        b.onConnectionChanged(false)
        driver.frames(5)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(driver.at("Telegram"), from)
        assertFalse("1 file" in driver.texts(), driver.texts().toString())
        driver.mouseUp(driver.at("Telegram"))
        assertEquals(emptyList(), files.moves)
    }

    @Test
    fun `Esc during a move cancels it`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        val to = driver.at("Telegram")
        driver.mouseDown(from)
        driver.mouseTo(to, from)
        assertTrue("Move into Telegram" in driver.texts())
        driver.esc()
        driver.frames(5)
        assertFalse("1 file" in driver.texts(), "the ghost leaves")
        driver.mouseUp(to)
        assertEquals(emptyList(), files.moves)
        assertEquals(setOf("a.jpg"), b.selection.names, "Esc went to the drag, not the selection")
    }
}
