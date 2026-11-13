package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Apps tab's search field against a view model that echoes its text back one frame late. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AppsSearchFieldTest {
    private val source = Canvas()

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

    private fun ImageComposeScene.type(char: Char) {
        val code = AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
        sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code, char))
        sendKeyEvent(key(AwtKeyEvent.KEY_TYPED, code, char))
        sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code, char))
    }

    /** Hosts the field: what it reports lands in [pending] and reaches its query only on the next [deliver]. */
    private class Harness {
        var query by mutableStateOf("")
        var pending: String? = null
        val sent = mutableListOf<String>()
        fun deliver() {
            pending?.let { query = it }
            pending = null
        }
    }

    private fun scene(h: Harness): SceneDriver = SceneDriver(
        ImageComposeScene(300, 200, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Dark) {
                Surface(Modifier.fillMaxSize()) {
                    SearchField(h.query, onChange = { h.sent += it; h.pending = it })
                }
            }
        },
    )

    private fun SceneDriver.click(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Mouse)
        frame()
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    @Test
    fun `typing through a lagging round trip keeps every character`() = onEdt {
        val h = Harness()
        val driver = scene(h)
        try {
            driver.frames(5)
            driver.click(100f, 100f)
            // One key per frame; each echo reaches the field only after the next key.
            "android".forEach { c ->
                driver.scene.type(c)
                driver.frame()
                h.deliver()
            }
            driver.frames(10)
            h.deliver()
            driver.frames(10)
            assertEquals("android", h.sent.last(), h.sent.toString())
            assertEquals("android", h.query)
            // Typing on appends at the end.
            driver.scene.type('x')
            driver.frames(5)
            h.deliver()
            driver.frames(5)
            assertEquals("androidx", h.sent.last())
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `the clear button empties the field and reports it`() = onEdt {
        val h = Harness()
        h.query = "pix"
        val driver = scene(h)
        try {
            driver.frames(30)
            driver.click(278f, 100f)
            driver.frames(5)
            h.deliver()
            driver.frames(30)
            assertEquals("", h.sent.last())
            assertEquals("", h.query)
            // The field really is empty: the next key makes the whole text.
            driver.click(100f, 100f)
            driver.scene.type('a')
            driver.frames(5)
            assertEquals("a", h.sent.last())
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `a change from outside replaces the text`() = onEdt {
        val h = Harness()
        val driver = scene(h)
        try {
            driver.frames(5)
            driver.click(100f, 100f)
            "ab".forEach { driver.scene.type(it); driver.frames(2) }
            h.deliver()
            driver.frames(5)
            h.query = "zzz"
            driver.frames(5)
            driver.scene.type('q')
            driver.frames(5)
            assertEquals("zzzq", h.sent.last(), h.sent.toString())
        } finally {
            driver.scene.close()
        }
    }
}
