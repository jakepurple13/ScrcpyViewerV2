package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import com.programmersbox.scrcpyviewer.files.DragCache
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
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
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Files tab's one gesture: a row drag is a move inside the sheet and a drag-out past it (fake OS drag). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DragOutTabTest {
    private class Files0 : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
        val moves = mutableListOf<String>()
        override suspend fun list(dir: String): Listing = when (dir) {
            "/sdcard/Download" -> Listing.Entries(
                listOf(
                    DeviceEntry("notes.txt", false, 10, 1_731_000_000_000),
                    DeviceEntry("Telegram", true, 4096, 1_731_000_000_000),
                    DeviceEntry("a.jpg", false, 2_000, 1_731_000_000_000),
                ),
            )
            else -> Listing.Entries(emptyList())
        }
        override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 3 }
        override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
        override suspend fun move(dir: String, names: List<String>, toDir: String, renames: Map<String, String>, replace: Set<String>) {
            moves += "move ${names.joinToString(",")} from $dir to $toDir"
        }
    }

    private val files = Files0()
    private val scheduler = TestCoroutineScheduler()
    private val queued = mutableListOf<TransferRequest>()
    private val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
    private val root: Path = Files.createTempDirectory("drag-tab").resolve("scrcpy-vwr")
    private val starter = FakeStarter()
    private val stage = Offset(200f, 300f)

    private fun browser() = FilesBrowser(
        "192.168.1.5:5555", files, transfers, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("drag-tab").resolve("settings.json")), "/sdcard/Download",
        CoroutineScope(UnconfinedTestDispatcher(scheduler)), {},
        // As the queue does once a pull runs: the drag folder exists.
        enqueue = { r -> queued += r; r.forEach { Files.createDirectories(it.path) }; r.indices.map { 100L + queued.size - r.size + it } },
        jobs = emptyFlow(),
    )

    /** A 400 px stage on the left, the sheet with the tab on the right. */
    private fun scene(b: FilesBrowser) = SceneDriver(ImageComposeScene(900, 800, Density(1f)) {
        ScrcpyViewerTheme(ThemeMode.Light) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(400.dp).fillMaxHeight())
                Surface(Modifier.fillMaxSize()) {
                    FilesTab(
                        b, zone = ZoneOffset.UTC, locale = Locale.US, motion = false, dragData = { null },
                        dragOutStarter = starter, dragCache = DragCache(root),
                    )
                }
            }
        }
    }).also { it.frames(30) }

    private fun SceneDriver.texts(): List<String> {
        val all = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.let(all::add)
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.at(value: String): Offset {
        var found: Offset? = null
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == value) found = node.boundsInRoot.center
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return checkNotNull(found) { "no \"$value\" in ${texts()}" }
    }

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

    /** The queue says every drag-out pull finished: the files exist in their drag folder. */
    private fun SceneDriver.pullsDone() {
        transfers.jobs = queued.mapIndexed { i, r ->
            val host = r.path.resolve(r.name)
            if (Files.isDirectory(r.path)) Files.writeString(host, "data") // a cancelled drag's folder is gone
            TransferJob(100L + i, r, JobState.Done, r.size ?: 0, r.size, null, hostResult = host)
        }
        frames(10)
    }

    private fun dragRoot() = root.resolve("192.168.1.5_5555").resolve("drag")

    private fun waitGone(path: Path) {
        val until = System.currentTimeMillis() + 5_000
        while (Files.exists(path) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse(Files.exists(path), "$path left behind")
    }

    @Test
    fun `leaving the sheet pulls the dragged rows into a drag folder`() = onEdt {
        val b = browser()
        b.click("a.jpg")
        b.toggle("notes.txt")
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        assertEquals(listOf("a.jpg", "notes.txt"), queued.map { it.name })
        assertTrue(queued.all { it.kind == TransferKind.Pull && it.dragOut && it.deviceDir == "/sdcard/Download" })
        assertEquals(listOf(2_000L, 10L), queued.map { it.size })
        assertEquals(dragRoot(), queued.first().path.parent)
        assertTrue("2 items" in driver.texts(), "the ghost shows the pull: ${driver.texts()}")
        assertTrue(starter.starts.isEmpty())
        driver.mouseUp(stage)
    }

    @Test
    fun `ready with the button held starts the OS drag, then everything resets`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        driver.pullsDone()
        val dir = queued.single().path
        assertEquals(listOf(listOf(dir.resolve("a.jpg").toFile())), starter.starts.map { it.first })
        assertFalse("1 file" in driver.texts(), "the ghost leaves: ${driver.texts()}")
        // The OS loop took the release; whatever Compose sees next moves and drops nothing.
        driver.mouseTo(driver.at("Telegram"), stage)
        driver.mouseUp(driver.at("Telegram"))
        assertEquals(emptyList(), files.moves)
        assertEquals(1, starter.starts.size)
        assertTrue(Files.exists(dir), "kept for the drop target to copy")
    }

    @Test
    fun `releasing before the files are ready leaves nothing on the computer`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        val dir = queued.single().path
        driver.mouseUp(stage)
        assertFalse("1 file" in driver.texts())
        driver.pullsDone()
        assertTrue(starter.starts.isEmpty(), "never after a release")
        assertEquals(emptyList(), files.moves)
        waitGone(dir)
    }

    @Test
    fun `coming back inside before the drag starts goes back to a move`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        val dir = queued.single().path
        val telegram = driver.at("Telegram")
        driver.mouseTo(telegram, stage)
        assertTrue("Move into Telegram" in driver.texts(), driver.texts().toString())
        waitGone(dir)
        driver.pullsDone()
        assertTrue(starter.starts.isEmpty())
        driver.mouseUp(telegram)
        assertEquals(listOf("move a.jpg from /sdcard/Download to /sdcard/Download/Telegram"), files.moves)
    }

    @Test
    fun `Esc during the pull cancels the drag`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        val dir = queued.single().path
        driver.esc()
        driver.frames(5)
        assertFalse("1 file" in driver.texts(), "the ghost leaves")
        driver.pullsDone()
        assertTrue(starter.starts.isEmpty())
        waitGone(dir)
        driver.mouseUp(stage)
    }

    @Test
    fun `the device going away during the pull cancels the drag`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        b.onConnectionChanged(false)
        driver.frames(5)
        assertFalse("1 file" in driver.texts(), "the ghost leaves")
        driver.pullsDone()
        assertTrue(starter.starts.isEmpty())
        driver.mouseUp(stage)
    }

    @Test
    fun `drag-out pulls stay out of the transfers tray`() = onEdt {
        val b = browser()
        val driver = scene(b)
        val from = driver.at("a.jpg")
        driver.mouseDown(from)
        driver.mouseTo(stage, from)
        transfers.jobs = listOf(TransferJob(100, queued.single(), JobState.Running, 500, 2_000, 100))
        driver.frames(10)
        assertFalse(driver.texts().any { it.startsWith("Transfers ·") }, driver.texts().toString())
        driver.mouseUp(stage)
    }
}
