package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatRun
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keyboard focus in the quick actions sheet, as the mirror window's key dispatcher sees it ([SheetFocus]). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SheetKeyFocusTest {
    private class NoRunner : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
        override fun stream(cmd: String): Flow<ShellEvent> = emptyFlow()
    }

    private val source = Canvas()

    /** A key event as the window's mediator makes it from AWT's (KEY_TYPED carries the text). */
    private fun key(id: Int, code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED): KeyEvent {
        val typed = id == AwtKeyEvent.KEY_TYPED
        val awt = AwtKeyEvent(source, id, 0L, 0, if (typed) AwtKeyEvent.VK_UNDEFINED else code, char)
        val type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }
        return KeyEvent(Key(if (typed) AwtKeyEvent.VK_UNDEFINED else code), type, codePoint = char.code, nativeEvent = awt)
    }

    private fun ImageComposeScene.press(code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED) {
        sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code, char))
        if (char != AwtKeyEvent.CHAR_UNDEFINED) sendKeyEvent(key(AwtKeyEvent.KEY_TYPED, code, char))
        sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code, char))
    }

    private fun SceneDriver.click(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    @Test
    fun `the Shell tab's field holds sheet focus, edits with Backspace, and a stage click gives the keys back`() = onEdt {
        val focus = SheetFocus()
        val console = ShellConsole(NoRunner(), ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
        val closed = mutableListOf<Unit>()
        var open by mutableStateOf(true)
        val frame = mutableStateOf<VideoFrame?>(null)
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSheetFocus provides focus) {
                        MirrorContent(
                            MirrorUiState(
                                model = "Pixel", details = "", status = SessionStatus.Running, frameSize = IntSize(1080, 2400),
                                quickActions = QuickActionsUiState(open = open, enabled = true, tab = SheetTab.Shell),
                            ),
                            frame, {}, {}, {}, {}, {}, {},
                            onQuickAction = { if (it == QuickActionEvent.Close) closed += Unit },
                            shellConsole = console,
                        )
                    }
                }
            }
        })
        try {
            driver.frames(30)
            // Switching to Shell focuses the command field.
            assertTrue(focus.focused, "the command field has focus")
            listOf('l' to AwtKeyEvent.VK_L, 's' to AwtKeyEvent.VK_S, 'x' to AwtKeyEvent.VK_X).forEach { (c, code) -> driver.scene.press(code, c) }
            driver.frames(2)
            assertEquals("lsx", console.input)
            driver.scene.press(AwtKeyEvent.VK_BACK_SPACE, '\b')
            driver.frames(2)
            assertEquals("ls", console.input)
            // Arrows and Enter reach the field's own handling (history, run), not the device.
            driver.scene.press(AwtKeyEvent.VK_LEFT)
            driver.frames(2)
            driver.scene.press(AwtKeyEvent.VK_BACK_SPACE, '\b')
            driver.frames(2)
            assertEquals("s", console.input)

            // Esc the field does not use closes the sheet.
            driver.scene.press(AwtKeyEvent.VK_ESCAPE)
            driver.frames(2)
            assertEquals(1, closed.size)
            closed.clear()

            // A press elsewhere in the sheet (its title) keeps the focus there.
            driver.click(1100f, 60f)
            assertTrue(focus.focused, "still in the sheet")

            // A press on the stage takes focus out of the sheet: keys go to the device again.
            driver.click(200f, 300f)
            assertFalse(focus.focused, "the stage click moved focus out of the sheet")
            assertTrue(closed.isEmpty(), "a click is not an Esc")

            // Closing the sheet leaves no focus behind in it.
            open = false
            driver.frames(30)
            assertFalse(focus.focused)
        } finally {
            driver.scene.close()
        }
    }

    private class SilentLogcat : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
        override fun lines(since: String?): Flow<LogcatLine> = Channel<LogcatLine>(Channel.UNLIMITED).consumeAsFlow()
        override suspend fun processes(): Map<Int, String> = emptyMap()
    }

    @Test
    fun `Logcat keys in the sheet don't reach the device`() = onEdt {
        val focus = SheetFocus()
        val logcat = LogcatController(SilentLogcat(), CoroutineScope(Dispatchers.Unconfined), {}, io = EmptyCoroutineContext)
        val sent = mutableListOf<ControlMessage>()
        val frame = mutableStateOf<VideoFrame?>(null)
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSheetFocus provides focus) {
                        MirrorContent(
                            MirrorUiState(
                                model = "Pixel", details = "", status = SessionStatus.Running, frameSize = IntSize(1080, 2400),
                                quickActions = QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Logcat),
                            ),
                            frame, {}, {}, {}, {}, {}, {},
                            onQuickAction = {},
                            logcat = logcat,
                        )
                    }
                }
            }
        })
        // The window's dispatcher: MirrorKeys first, Compose only for keys it leaves.
        val keys = MirrorKeys(
            mapper = InputMapper(frameSize = { IntSize(1080, 2400) }, viewRect = { Rect(0f, 0f, 540f, 1200f) }),
            hidKeyboard = null,
            send = { sent += it },
            clipboardText = { null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            sheetOpen = { true },
            sheetFocused = { focus.focused },
            closeSheet = {},
            applyZoom = {},
            mac = Os.isMac,
        )
        val cmd = if (Os.isMac) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
        fun window(id: Int, code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED, mods: Int = 0) {
            val typed = id == AwtKeyEvent.KEY_TYPED
            val awt = AwtKeyEvent(source, id, 0L, mods, if (typed) AwtKeyEvent.VK_UNDEFINED else code, char)
            val type = when (id) {
                AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            }
            val event = KeyEvent(
                Key(if (typed) AwtKeyEvent.VK_UNDEFINED else code), type, codePoint = char.code,
                isCtrlPressed = mods and AwtKeyEvent.CTRL_DOWN_MASK != 0,
                isMetaPressed = mods and AwtKeyEvent.META_DOWN_MASK != 0,
                nativeEvent = awt,
            )
            if (!keys.onKey(event)) driver.scene.sendKeyEvent(event)
        }
        try {
            logcat.play()
            driver.frames(30)
            // A press on the list's empty space puts focus in the sheet.
            driver.click(1000f, 500f)
            assertTrue(focus.focused, "the list click focused the sheet")

            window(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_SPACE, ' ')
            window(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_SPACE, ' ')
            window(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_SPACE, ' ')
            driver.frames(5)
            assertEquals(LogcatRun.Paused, logcat.run, "Space paused")

            window(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_F, mods = cmd)
            window(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_F, mods = cmd)
            driver.frames(5)
            window(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_X, 'x')
            window(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_X, 'x')
            window(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_X, 'x')
            driver.frames(5)
            assertEquals("x", logcat.query, "the shortcut focused the filter, which took the typing")

            window(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_K, mods = cmd)
            window(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_K, mods = cmd)
            driver.frames(5)
            assertEquals("x", logcat.query, "the clear shortcut typed nothing")

            assertTrue(sent.isEmpty(), "nothing reached the device: $sent")
        } finally {
            driver.scene.close()
        }
    }
}
