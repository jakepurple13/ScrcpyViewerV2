package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.ExportDevice
import com.programmersbox.scrcpyviewer.logcat.ExportFormat
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.common.revealLabel
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

private class ExportUiSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val stream = Channel<LogcatLine>(Channel.UNLIMITED)

    override fun lines(since: String?): Flow<LogcatLine> = stream.consumeAsFlow()

    override suspend fun processes(): Map<Int, String> = emptyMap()

    override suspend fun exportDevice(hostNow: ZonedDateTime): ExportDevice = ExportDevice(
        "SER", "samsung", "SM-A156U1", "16", 36, "HANDHELD", LocalDate.of(2026, 11, 16), ZoneOffset.UTC,
    )
}

/** The export button, its menu, the progress ring with cancel, the status text and the toast. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LogcatExportUiTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val source = ExportUiSource()
    private val controller = LogcatController(source, scope, {}, clock = { scheduler.currentTime }, io = EmptyCoroutineContext)
    private val dir: Path = createTempDirectory("export-ui-test")
    private val picks = mutableListOf<Pair<String, String>>()
    private var target: Path? = null
    private val pick: suspend (String, String) -> Path? = { name, ext ->
        picks += name to ext
        target
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun withScene(block: SceneDriver.() -> Unit) {
        val driver = SceneDriver(
            ImageComposeScene(640, 600, Density(1f)) {
                ScrcpyViewerTheme(AppTheme(mode = ThemeMode.Light)) {
                    Surface(Modifier.fillMaxSize()) { LogcatTab(controller, 640.dp, motion = false, pick = pick) }
                }
            },
        ).also { it.frames(5) }
        try {
            driver.block()
        } finally {
            driver.scene.close()
            scope.cancel()
        }
    }

    private fun settle(millis: Long = 100) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun feed(count: Int) {
        controller.play()
        settle()
        repeat(count) { source.stream.trySend(LogcatLine("11-16", "10:00:00.00$it", 100, 100, LogLevel.Info, "Tag", "msg $it")) }
        settle()
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

    private fun SceneDriver.texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun SceneDriver.text(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } }

    private fun SceneDriver.described(d: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    @Test
    fun `export button sits between play and pop out`() = onEdt {
        withScene {
            val play = checkNotNull(described("Start logcat")) { "no Play" }.boundsInRoot
            val export = checkNotNull(described("Export logs")) { "no export button" }.boundsInRoot
            val popOut = checkNotNull(described("Open in new window")) { "no pop out" }.boundsInRoot
            assertTrue(play.right <= export.left, "after Play: $play, $export")
            assertTrue(export.right <= popOut.left, "before pop out: $export, $popOut")
            assertEquals(36f, export.width)
        }
    }

    @Test
    fun `disabled with no lines`() = onEdt {
        withScene {
            val button = checkNotNull(described("Export logs"))
            assertTrue(SemanticsProperties.Disabled in button.config, "disabled with no lines")
            val at = button.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            frames(2)
            scene.sendPointerEvent(PointerEventType.Move, at.copy(x = at.x + 1f), type = PointerType.Mouse)
            val until = System.currentTimeMillis() + 3_000
            while (text("No lines to export") == null && System.currentTimeMillis() < until) {
                Thread.sleep(50)
                frames(1)
            }
            assertNotNull(text("No lines to export"), "the tooltip says why")
        }
    }

    @Test
    fun `menu shows count and filter`() = onEdt {
        feed(3)
        controller.query = "level:info"
        withScene {
            click(checkNotNull(described("Export logs")))
            frames(5)
            val t = texts()
            assertTrue("Export 3 lines" in t, "header: $t")
            assertTrue("Android Studio (.logcat)" in t, "studio item: $t")
            assertTrue("level:info" in t, "filter chip: $t")
            assertTrue("Plain text (.txt)" in t, "text item: $t")
            assertTrue("No filter saved" in t, "text item note: $t")
        }
    }

    @Test
    fun `picking calls pick with the suggested name`() = onEdt {
        feed(3)
        withScene {
            click(checkNotNull(described("Export logs")))
            click(checkNotNull(text("Plain text (.txt)")))
            settle()
            assertEquals(1, picks.size)
            assertEquals("txt", picks.single().second)
            assertTrue(picks.single().first.endsWith(".txt"), picks.single().first)
            frames(30) // the menu's own exit
            assertNull(text("Plain text (.txt)"), "menu closed")
        }
    }

    @Test
    fun `running shows cancel and clicking cancels`() = onEdt {
        feed(3)
        target = dir.resolve("a.logcat")
        withScene {
            controller.export(ExportFormat.Studio, pick)
            settle(50) // written, then held as running for EXPORT_MIN_RUN_MILLIS
            assertIs<LogcatExport.Running>(controller.export)
            frames(5)
            assertNull(described("Export logs"), "running: the button cancels instead")
            val cancel = checkNotNull(described("Cancel export"))
            click(cancel)
            assertEquals(LogcatExport.Canceled, controller.export)
            frames(5)
            assertNotNull(text("Export canceled"), "toast")
        }
    }

    @Test
    fun `status text`() {
        assertEquals("Exporting 4,120 of 12,340 lines…", exportStatusText(LogcatExport.Running(ExportFormat.Studio, 4120, 12340)))
        assertEquals("Saved a.logcat", exportStatusText(LogcatExport.Done(Path.of("/x/a.logcat"), 3)))
        assertEquals("Export canceled · partial file removed", exportStatusText(LogcatExport.Canceled))
        assertNull(exportStatusText(LogcatExport.Idle))
    }

    @Test
    fun `the next export's ring and bar start empty, not from the last one`() {
        var shown = 0f
        shown = exportFraction(LogcatExport.Running(ExportFormat.Text, 50, 100), shown)
        assertEquals(0.5f, shown)
        shown = exportFraction(LogcatExport.Done(Path.of("/x/a.txt"), 100), shown)
        assertEquals(1f, shown)
        shown = exportFraction(LogcatExport.Idle, shown)
        assertEquals(0f, shown, "idle empties it while hidden")
        shown = exportFraction(LogcatExport.Running(ExportFormat.Text, 0, 100), shown)
        assertEquals(0f, shown)
        shown = exportFraction(LogcatExport.Running(ExportFormat.Text, 30, 100), shown)
        shown = exportFraction(LogcatExport.Canceled, shown)
        assertEquals(0.3f, shown, "canceled keeps its fraction while the ring fades")
        assertEquals(0f, exportFraction(LogcatExport.Idle, shown))
        assertEquals(0f, exportFraction(LogcatExport.Running(ExportFormat.Text, 0, 0), 1f))
    }

    @Test
    fun `done toast offers reveal`() = onEdt {
        feed(3)
        target = dir.resolve("a.logcat")
        withScene {
            controller.export(ExportFormat.Studio, pick)
            settle(500)
            assertIs<LogcatExport.Done>(controller.export)
            frames(5)
            assertNotNull(text("Saved 3 lines to a.logcat"), "toast: ${texts()}")
            assertNotNull(text(revealLabel(OpenPlatform.current())), "reveal button: ${texts()}")
            assertNotNull(text("Saved a.logcat"), "status bar: ${texts()}")
        }
    }

    @Test
    fun `clicking the button while done opens the menu`() = onEdt {
        feed(3)
        target = dir.resolve("a.logcat")
        withScene {
            controller.export(ExportFormat.Studio, pick)
            settle(500)
            assertIs<LogcatExport.Done>(controller.export)
            frames(5)
            click(checkNotNull(described("Export logs")))
            assertEquals(LogcatExport.Idle, controller.export)
            frames(5)
            assertNotNull(text("Android Studio (.logcat)"), "menu open: ${texts()}")
        }
    }
}
