package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
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
}
