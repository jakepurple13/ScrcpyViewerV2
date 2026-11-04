package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoomTest {
    private val fitted = 0.37f

    @Test
    fun `a new window starts at the fitted scale with Fit off`() {
        assertEquals(0.37f, Zoom.initialZoom(null, 0.37f))
    }

    @Test
    fun `unscaled starts at 100 percent`() {
        assertEquals(1f, Zoom.initialZoom("unscaled", 0.37f))
    }

    @Test
    fun `an explicit letterbox or stretched render fit starts in Fit`() {
        assertNull(Zoom.initialZoom("letterbox", 0.37f))
        assertNull(Zoom.initialZoom("stretched", 0.37f))
    }

    @Test
    fun `zoom in steps up through the list`() {
        assertEquals(1.25f, Zoom.step(1f, fitted, ZoomCommand.In))
        assertEquals(0.5f, Zoom.step(0.33f, fitted, ZoomCommand.In))
        assertEquals(2f, Zoom.step(1.5f, fitted, ZoomCommand.In))
    }

    @Test
    fun `zoom out steps down through the list`() {
        assertEquals(0.75f, Zoom.step(1f, fitted, ZoomCommand.Out))
        assertEquals(0.67f, Zoom.step(0.75f, fitted, ZoomCommand.Out))
        assertEquals(1f, Zoom.step(1.25f, fitted, ZoomCommand.Out))
    }

    @Test
    fun `steps clamp at both ends`() {
        assertEquals(4f, Zoom.step(4f, fitted, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(0.25f, fitted, ZoomCommand.Out))
    }

    @Test
    fun `from fit starts at the nearest step to the fitted scale`() {
        // 0.37 is nearest to 33 %: in goes to 50, out goes to 25.
        assertEquals(0.5f, Zoom.step(null, 0.37f, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(null, 0.37f, ZoomCommand.Out))
        // 0.9 is nearest to 100 %.
        assertEquals(1.25f, Zoom.step(null, 0.9f, ZoomCommand.In))
        assertEquals(0.75f, Zoom.step(null, 0.9f, ZoomCommand.Out))
        // Fitted beyond the ends stays clamped.
        assertEquals(4f, Zoom.step(null, 9f, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(null, 0.05f, ZoomCommand.Out))
    }

    @Test
    fun `fit and actual size commands`() {
        assertNull(Zoom.step(0.5f, fitted, ZoomCommand.Fit))
        assertEquals(1f, Zoom.step(null, fitted, ZoomCommand.Actual))
        // Actual toggles back to fit when already at 100 %.
        assertNull(Zoom.step(1f, fitted, ZoomCommand.Actual))
    }

    @Test
    fun `percent label`() {
        assertEquals("67%", Zoom.label(0.67f))
        assertEquals("200%", Zoom.label(2f))
        assertEquals("37%", Zoom.label(0.37f))
    }

    // ---- shortcuts ----

    @Test
    fun `shortcut keys with the platform modifier`() {
        val cmd = AwtKeyEvent.META_DOWN_MASK
        val ctrl = AwtKeyEvent.CTRL_DOWN_MASK
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_EQUALS, cmd, mac = true))
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_PLUS, cmd or AwtKeyEvent.SHIFT_DOWN_MASK, mac = true))
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_ADD, ctrl, mac = false))
        assertEquals(ZoomCommand.Out, Zoom.shortcut(AwtKeyEvent.VK_MINUS, ctrl, mac = false))
        assertEquals(ZoomCommand.Out, Zoom.shortcut(AwtKeyEvent.VK_SUBTRACT, cmd, mac = true))
        assertEquals(ZoomCommand.Fit, Zoom.shortcut(AwtKeyEvent.VK_0, cmd, mac = true))
        assertEquals(ZoomCommand.Fit, Zoom.shortcut(AwtKeyEvent.VK_NUMPAD0, ctrl, mac = false))
    }

    @Test
    fun `no shortcut without the platform modifier`() {
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_EQUALS, 0, mac = true))
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_MINUS, AwtKeyEvent.CTRL_DOWN_MASK, mac = true)) // Ctrl is not Cmd on a Mac
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_0, AwtKeyEvent.META_DOWN_MASK, mac = false))
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_A, AwtKeyEvent.META_DOWN_MASK, mac = true))
    }

    @Test
    fun `zoom shortcut keys inject nothing when they do reach the mapper`() {
        // The window consumes them first; the mapper must not turn Cmd/Ctrl + these into device keys either way.
        val mapper = InputMapper(frameSize = { IntSize(100, 200) }, viewRect = { Rect(0f, 0f, 100f, 200f) })
        for (mods in listOf(AwtKeyEvent.META_DOWN_MASK, AwtKeyEvent.CTRL_DOWN_MASK)) {
            for (code in listOf(AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_PLUS, AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_0)) {
                assertEquals(emptyList(), mapper.awtKey(AwtKeyEvent.KEY_PRESSED, code, '=', mods), "pressed $code")
                assertEquals(emptyList(), mapper.awtKey(AwtKeyEvent.KEY_TYPED, code, '=', mods), "typed $code")
            }
        }
    }

    // ---- geometry: the canvas is the zoomed image, pointer points are canvas-local ----

    private val frame = IntSize(1000, 2000)

    private fun mapperAt(zoom: Float) =
        InputMapper(frameSize = { frame }, viewRect = { Rect(0f, 0f, frame.width * zoom, frame.height * zoom) })

    private fun InputMapper.at(x: Float, y: Float) =
        (press(Offset(x, y), PointerButton.Primary).single() as ControlMessage.InjectTouch).position

    @Test
    fun `200 percent maps canvas-local points, also far into a scrolled canvas`() {
        val m = mapperAt(2f) // canvas 2000 x 4000
        assertEquals(Position(0, 0, 1000, 2000), m.at(0f, 0f))
        assertEquals(Position(350, 750, 1000, 2000), m.at(700f, 1500f))
        // After scrolling the stage the same screen spot is a larger canvas-local point.
        assertEquals(Position(999, 1999, 1000, 2000), m.at(1999f, 3999f))
    }

    @Test
    fun `50 percent maps the whole canvas, which the stage centres`() {
        val m = mapperAt(0.5f) // canvas 500 x 1000
        assertEquals(Position(500, 1000, 1000, 2000), m.at(250f, 500f))
        assertEquals(Rect(0f, 0f, 500f, 1000f), m.displayedRect())
    }

    @Test
    fun `click indicator uses the canvas rect`() {
        val dots = ClickIndicator(mapperAt(2f)::displayedRect)
        dots.press(Offset(1500f, 3000f))
        assertEquals(Offset(1500f, 3000f), dots.state(0)?.position)
        dots.press(Offset(2500f, 100f)) // outside the canvas
        assertEquals(Offset(1500f, 3000f), dots.state(0)?.position)
    }

    // ---- ZoomKeyFilter ----

    private val cmd = AwtKeyEvent.META_DOWN_MASK
    private val P = AwtKeyEvent.KEY_PRESSED
    private val R = AwtKeyEvent.KEY_RELEASED
    private val T = AwtKeyEvent.KEY_TYPED

    @Test
    fun `filter consumes press, typed and release of a zoom chord`() {
        val f = ZoomKeyFilter(mac = true)
        val got = mutableListOf<ZoomCommand>()
        assertEquals(true, f.handle(P, AwtKeyEvent.VK_EQUALS, cmd) { got += it })
        assertEquals(true, f.handle(T, AwtKeyEvent.VK_UNDEFINED, cmd) {})
        assertEquals(true, f.handle(R, AwtKeyEvent.VK_EQUALS, 0) {}) // Cmd already released
        assertEquals(listOf(ZoomCommand.In), got)
        // Back to normal: nothing is swallowed afterwards.
        assertEquals(false, f.handle(P, AwtKeyEvent.VK_EQUALS, 0) {})
        assertEquals(false, f.handle(T, AwtKeyEvent.VK_UNDEFINED, cmd) {})
    }

    @Test
    fun `filter forgets held keys on reset so the next plain press passes`() {
        val f = ZoomKeyFilter(mac = true)
        f.handle(P, AwtKeyEvent.VK_0, cmd) {}
        f.reset() // window lost focus before the release
        assertEquals(false, f.handle(P, AwtKeyEvent.VK_0, 0) {})
        assertEquals(false, f.handle(T, AwtKeyEvent.VK_UNDEFINED, cmd) {})
    }
}
