package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsPanel
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.SheetTab
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

private class QuietSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    override fun lines(since: String?): Flow<LogcatLine> = flow { awaitCancellation() }
    override suspend fun processes(): Map<Int, String> = emptyMap()
}

/** Logcat in its own window: the sheet's card while it is out, the pop-out button, and the title bar's pin. */
@OptIn(ExperimentalComposeUiApi::class)
class LogcatWindowTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val toasts = mutableListOf<String>()
    private val controller = LogcatController(QuietSource(), scope, { toasts += it }, io = EmptyCoroutineContext)
    private val drivers = mutableListOf<SceneDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.scene.close() }
        scope.cancel()
    }

    private fun scene(width: Int = 640, content: @Composable () -> Unit): SceneDriver = SceneDriver(
        ImageComposeScene(width, 600, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) { Surface(Modifier.fillMaxSize()) { content() } }
        },
    ).also {
        drivers += it
        it.frames(5)
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
    fun `Logcat tab shows the own-window card with Show window and Move back here while popped`() = onEdt {
        val driver = scene(600) {
            QuickActionsPanel(QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Logcat), {}, console = null, logcat = controller, motion = true)
        }
        assertNull(driver.text("Logcat is open in its own window"))
        assertNotNull(driver.described("Open in new window"))

        controller.popOut()
        driver.frames(3)
        // The card rises into place as it fades in.
        val rising = checkNotNull(driver.text("Logcat is open in its own window")).boundsInRoot.top
        driver.frames(60)
        val settled = checkNotNull(driver.text("Logcat is open in its own window")).boundsInRoot.top
        assertTrue(rising > settled, "the card rises: $rising then $settled")
        assertNotNull(driver.text("It keeps running while you use the other tabs. Close that window to move it back here."))
        assertNull(driver.described("Open in new window")) // the panel went with the window

        val raises = controller.raiseRequests
        driver.click(checkNotNull(driver.text("Show window")))
        assertEquals(raises + 1, controller.raiseRequests)
        assertTrue(controller.popped)

        driver.click(checkNotNull(driver.text("Move back here")))
        assertFalse(controller.popped)
        driver.frames(60)
        assertNull(driver.text("Logcat is open in its own window"))
        assertNotNull(driver.described("Open in new window"))
    }

    @Test
    fun `the pop-out button reads Open in new window, then Move back to the sheet`() = onEdt {
        val sheet = scene { LogcatTab(controller, 640.dp, motion = true) }
        assertNull(sheet.described("Move back to the sheet"))
        sheet.click(checkNotNull(sheet.described("Open in new window")))
        assertTrue(controller.popped)
        val window = scene { LogcatTab(controller, 720.dp, motion = true, inWindow = true) }
        assertNull(window.described("Open in new window"))
        window.click(checkNotNull(window.described("Move back to the sheet")))
        assertFalse(controller.popped)
    }

    @Test
    fun `the title bar shows the title and pins the window with a toast`() = onEdt {
        controller.popOut()
        val driver = scene { LogcatTitleBar("Logcat · Pixel 8", controller, motion = true) }
        assertNotNull(driver.text("Logcat · Pixel 8"))
        val pin = checkNotNull(driver.described("Always on top"))
        assertEquals(ToggleableState.Off, pin.config.getOrNull(SemanticsProperties.ToggleableState))

        driver.click(pin)
        assertTrue(controller.alwaysOnTop)
        assertEquals(listOf("Logcat window stays on top"), toasts)
        driver.frames(60)
        assertEquals(
            ToggleableState.On,
            checkNotNull(driver.described("Always on top")).config.getOrNull(SemanticsProperties.ToggleableState),
        )

        driver.click(checkNotNull(driver.described("Always on top")))
        assertFalse(controller.alwaysOnTop)
        assertEquals("Logcat window no longer stays on top", toasts.last())
    }

    @Test
    fun `the window's toasts show in its snackbar while popped and go back to the sheet once it docks`() = onEdt {
        controller.popOut()
        var composed by mutableStateOf(true)
        val driver = scene { if (composed) LogcatWindowContent(controller, motion = false, shown = true, onRaise = {}) }
        controller.toast("Copied 3 lines")
        driver.frames(10)
        assertNotNull(driver.text("Copied 3 lines"))
        assertEquals(emptyList(), toasts)

        // Docked, the window stays composed while it fades out; its toasts already go to the sheet.
        controller.dock()
        driver.frames(2)
        assertNull(controller.windowToast)
        controller.toast("Cleared")
        driver.frames(5)
        assertEquals(listOf("Cleared"), toasts)
        assertNull(driver.text("Cleared"))

        // Popped out again in the same window: back to its snackbar. Gone: back to the sheet.
        controller.popOut()
        driver.frames(2)
        assertNotNull(controller.windowToast)
        composed = false
        driver.frames(2)
        assertNull(controller.windowToast)
    }

    @Test
    fun `raise requests reach the window once each and an old count is not replayed when it composes again`() = onEdt {
        controller.popOut()
        controller.raise() // before the window composes: not for it
        var raised = 0
        var composed by mutableStateOf(true)
        val driver = scene { if (composed) LogcatWindowContent(controller, motion = false, shown = true, onRaise = { raised++ }) }
        driver.frames(5)
        assertEquals(0, raised)

        controller.raise()
        driver.frames(3)
        assertEquals(1, raised)
        driver.frames(10)
        assertEquals(1, raised) // once per increment, not per frame
        controller.raise()
        driver.frames(3)
        assertEquals(2, raised)

        composed = false
        driver.frames(3)
        composed = true
        driver.frames(5)
        assertEquals(2, raised) // the count from before is not replayed
        controller.raise()
        driver.frames(3)
        assertEquals(3, raised)
    }

    @OptIn(InternalComposeUiApi::class)
    private fun SceneDriver.press(key: Key) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key = key, type = type))
            frame()
        }
    }

    /** What [block] prints to stdout, where Compose prints its focus warnings, and still echoes it. */
    private fun printed(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString().also { original.print(it) }
    }

    @Test
    fun `the panel takes focus once the window shows, with no focus warning, so Space plays at once`() = onEdt {
        controller.popOut()
        var shown by mutableStateOf(false)
        lateinit var driver: SceneDriver
        val out = printed {
            driver = scene { LogcatWindowContent(controller, motion = true, shown = shown, onRaise = {}) }
            driver.frames(5)
            shown = true
            driver.frames(5)
        }
        assertFalse("FocusRequester is not initialized" in out, "focus requested before its requester attached:\n$out")

        assertEquals(LogcatRun.NotStarted, controller.run)
        driver.press(Key.Spacebar)
        assertEquals(LogcatRun.Running, controller.run)
        driver.press(Key.Spacebar)
        assertEquals(LogcatRun.Paused, controller.run)
    }
}
