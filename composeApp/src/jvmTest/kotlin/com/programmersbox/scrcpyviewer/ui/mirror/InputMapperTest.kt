package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputMapperTest {
    private var size: IntSize? = IntSize(1344, 2992)
    private val view = Rect(0f, 0f, 500f, 500f)
    private val mapper = InputMapper(frameSize = { size }, viewRect = { view })
    private val center = Offset(250f, 250f)

    @Test
    fun `maps view point to device point with letterbox`() {
        val msgs = mapper.press(center, PointerButton.Primary)
        val touch = msgs.single() as ControlMessage.InjectTouch
        assertEquals(Position(672, 1496, 1344, 2992), touch.position)
        assertEquals(InputMapper.ACTION_DOWN, touch.action)
        assertEquals(ControlMessage.POINTER_ID_MOUSE, touch.pointerId)
        assertEquals(1f, touch.pressure)
        assertEquals(InputMapper.BUTTON_PRIMARY, touch.actionButton)
        assertEquals(InputMapper.BUTTON_PRIMARY, touch.buttons)

        val up = mapper.release(center, PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
        assertEquals(0f, up.pressure)
        assertEquals(0, up.buttons)
    }

    @Test
    fun `drops points in letterbox bars`() {
        // The frame is 224.6 px wide, centred: x in [137.7, 362.3) is video, the rest are bars.
        assertTrue(mapper.press(Offset(50f, 250f), PointerButton.Primary).isEmpty())
        assertTrue(mapper.press(Offset(450f, 250f), PointerButton.Primary).isEmpty())
        assertTrue(mapper.scroll(Offset(10f, 10f), Offset(0f, 1f)).isEmpty())
        assertTrue(mapper.move(Offset(50f, 250f)).isEmpty(), "hover in a bar is dropped")
    }

    @Test
    fun `right click is BackOrScreenOn`() {
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(0)), mapper.press(center, PointerButton.Secondary))
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(1)), mapper.release(center, PointerButton.Secondary))
    }

    @Test
    fun `wheel maps to scroll sign`() {
        // input_manager.c passes SDL's wheel y (positive = away from the user) as vscroll; Compose's
        // scrollDelta.y is positive towards the user, so a wheel-down notch is vscroll -1.
        val down = mapper.scroll(center, Offset(0f, 1f)).single() as ControlMessage.InjectScroll
        assertEquals(Position(672, 1496, 1344, 2992), down.position)
        assertEquals(0f, down.hscroll)
        assertEquals(-1f, down.vscroll)
        val right = mapper.scroll(center, Offset(1f, 0f)).single() as ControlMessage.InjectScroll
        assertEquals(1f, right.hscroll)
        assertEquals(0f, right.vscroll)
    }

    @Test
    fun `rotated frame size updates mapping`() {
        size = IntSize(2992, 1344)
        val touch = mapper.press(center, PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(Position(1496, 672, 2992, 1344), touch.position)
        // Now the bars are above and below.
        assertTrue(mapper.press(Offset(250f, 50f), PointerButton.Primary).isEmpty())
    }

    @Test
    fun `drag keeps going outside the frame and clamps`() {
        mapper.press(center, PointerButton.Primary)
        val move = mapper.move(Offset(10f, 250f)).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_MOVE, move.action)
        assertEquals(0, move.position.x)
        val up = mapper.release(Offset(10f, 250f), PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
    }

    @Test
    fun `no frame means no input`() {
        size = null
        assertTrue(mapper.press(center, PointerButton.Primary).isEmpty())
    }

    @Test
    fun `keys map to android keycodes and text`() {
        val enter = mapper.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ENTER, '\n', 0)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, 66, 0, 0)), enter)
        val a = mapper.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_A, 'a', AwtKeyEvent.SHIFT_DOWN_MASK)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, 29, 0, 0x41)), a)
        // Mixed mode: letters go as key events, other printable text as InjectText.
        assertTrue(mapper.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 'a', 0).isEmpty())
        assertEquals(
            listOf<ControlMessage>(ControlMessage.InjectText("!")),
            mapper.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '!', AwtKeyEvent.SHIFT_DOWN_MASK),
        )
        val up = mapper.awtKey(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_BACK_SPACE, '\b', 0)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(1, 67, 0, 0)), up)
    }

    @Test
    fun `AltGr characters type, reported as Ctrl+Alt (Windows) or AltGraph`() {
        val altGr = InputMapper(frameSize = { size }, viewRect = { view }, ctrlAltIsAltGr = true)
        val ctrlAlt = AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK
        for (c in listOf('@', '€', '{', '[', '\\', '}')) {
            assertEquals(listOf<ControlMessage>(ControlMessage.InjectText("$c")), altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, c, ctrlAlt), "typed $c")
        }
        // AltGr + Shift (e.g. a capital letter with an accent).
        assertEquals(
            listOf<ControlMessage>(ControlMessage.InjectText("Ł")),
            altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 'Ł', ctrlAlt or AwtKeyEvent.SHIFT_DOWN_MASK),
        )
        assertEquals(
            listOf<ControlMessage>(ControlMessage.InjectText("@")),
            mapper.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '@', AwtKeyEvent.ALT_GRAPH_DOWN_MASK),
        )
    }

    @Test
    fun `Ctrl or Cmd shortcuts still type nothing`() {
        val altGr = InputMapper(frameSize = { size }, viewRect = { view }, ctrlAltIsAltGr = true)
        val ctrl = AwtKeyEvent.CTRL_DOWN_MASK
        val alt = AwtKeyEvent.ALT_DOWN_MASK
        val meta = AwtKeyEvent.META_DOWN_MASK
        // Windows Ctrl+C / Ctrl+V type control characters; Ctrl without Alt is a shortcut whatever character it reports.
        assertTrue(altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '\u0003', ctrl).isEmpty())
        assertTrue(altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '\u0016', ctrl).isEmpty())
        assertTrue(altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 'c', ctrl).isEmpty())
        assertTrue(altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '@', ctrl or AwtKeyEvent.SHIFT_DOWN_MASK).isEmpty())
        // The Windows key with Ctrl+Alt is not AltGr.
        assertTrue(altGr.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '@', ctrl or alt or meta).isEmpty())
        // macOS: Ctrl+Option is a shortcut, not AltGr; Cmd never types.
        val mac = InputMapper(frameSize = { size }, viewRect = { view }, ctrlAltIsAltGr = false)
        assertTrue(mac.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '@', ctrl or alt).isEmpty())
        assertTrue(mac.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '@', meta).isEmpty())
    }

    @Test
    fun `default sdk bindings - right BACK, middle HOME, 4th APP_SWITCH, 5th notifications`() {
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, InputMapper.KEYCODE_HOME, 0, 0)), mapper.press(center, PointerButton.Tertiary))
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(1, InputMapper.KEYCODE_HOME, 0, 0)), mapper.release(center, PointerButton.Tertiary))
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, InputMapper.KEYCODE_APP_SWITCH, 0, 0)), mapper.press(center, PointerButton.Back))
        assertEquals(listOf<ControlMessage>(ControlMessage.ExpandNotificationPanel), mapper.press(center, PointerButton.Forward))
        assertTrue(mapper.release(center, PointerButton.Forward).isEmpty(), "panel is expanded on press only")
        assertEquals(listOf<ControlMessage>(ControlMessage.ExpandSettingsPanel), mapper.press(center, PointerButton.Forward, clicks = 2))
    }

    @Test
    fun `shift selects the secondary bindings, which forward clicks by default`() {
        val down = mapper.press(center, PointerButton.Secondary, shift = true).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_DOWN, down.action)
        assertEquals(2, down.actionButton) // AMOTION_EVENT_BUTTON_SECONDARY
        assertEquals(2, down.buttons)
        val move = mapper.move(Offset(260f, 250f)).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_MOVE, move.action)
        assertEquals(2, move.buttons)
        val up = mapper.release(center, PointerButton.Secondary, shift = true).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
        assertEquals(2, up.actionButton)
        assertEquals(0, up.buttons)
        val x2 = mapper.press(center, PointerButton.Forward, shift = true).single() as ControlMessage.InjectTouch
        assertEquals(16, x2.actionButton) // AMOTION_EVENT_BUTTON_FORWARD
    }

    @Test
    fun `custom bindings, ignore and disabled keyboard`() {
        val m = InputMapper(frameSize = { size }, viewRect = { view }, bindings = MouseBindings("-+bh", "-+bh"), keyboardEnabled = false)
        assertTrue(m.press(center, PointerButton.Secondary).isEmpty(), "'-' ignores the click")
        assertEquals(4, (m.press(center, PointerButton.Tertiary).single() as ControlMessage.InjectTouch).actionButton)
        assertTrue(m.press(center, PointerButton.Back).isEmpty(), "key shortcuts need a keyboard (input_manager.c checks im->kp)")
        assertTrue(m.press(center, PointerButton.Forward).isEmpty())
    }

    @Test
    fun `disabled mouse still runs the bindings but forwards nothing`() {
        val m = InputMapper(frameSize = { size }, viewRect = { view }, mouseEnabled = false)
        assertTrue(m.press(center, PointerButton.Primary).isEmpty())
        assertTrue(m.move(center).isEmpty())
        assertTrue(m.scroll(center, Offset(0f, 1f)).isEmpty())
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(0)), m.press(center, PointerButton.Secondary))
    }

    @Test
    fun `uhid mouse sends relative HID reports, bindings first`() {
        val m = InputMapper.forOptions(OptionValues(mapOf("mouse" to "uhid")), { size }, { view }, { false }, hidMouse = HidMouse())
        fun List<ControlMessage>.hid() = (single() as ControlMessage.UhidInput).also { assertEquals(HidMouse.ID, it.id) }.data.map { it.toInt() }
        assertEquals(listOf(1, 0, 0, 0, 0), m.press(Offset(1f, 1f), PointerButton.Primary).hid(), "no position: bars do not matter")
        assertEquals(listOf(1, 7, -3, 0, 0), m.relativeMove(7, -3).hid())
        assertEquals(listOf(3, 0, 0, 0, 0), m.press(center, PointerButton.Secondary).hid(), "++++ forwards the right button")
        assertTrue(m.move(center).isEmpty(), "absolute moves are ignored in relative mode")
        assertEquals(listOf(2, 0, 0, 0, 0), m.release(center, PointerButton.Primary).hid())
        assertEquals(listOf(0, 0, 0, -1, 0), m.scroll(center, Offset(0f, 1f)).hid())
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(0)), m.press(center, PointerButton.Secondary, shift = true), "Shift: bhsn")
    }

    @Test
    fun `releaseButtons ends held SDK and HID buttons`() {
        assertTrue(mapper.releaseButtons().isEmpty())
        mapper.press(center, PointerButton.Primary)
        mapper.move(Offset(260f, 250f))
        val up = mapper.releaseButtons().single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
        assertEquals(InputMapper.BUTTON_PRIMARY, up.actionButton)
        assertEquals(0, up.buttons)
        assertEquals((mapper.move(Offset(260f, 250f)).single() as ControlMessage.InjectTouch).position, up.position)
        assertTrue(mapper.releaseButtons().isEmpty())
        assertTrue(mapper.release(center, PointerButton.Primary).isEmpty(), "the late release is dropped")

        val hid = InputMapper.forOptions(OptionValues(mapOf("mouse" to "uhid")), { size }, { view }, { false }, hidMouse = HidMouse())
        hid.press(center, PointerButton.Primary)
        hid.press(center, PointerButton.Secondary)
        assertEquals(listOf(0, 0, 0, 0, 0), (hid.releaseButtons().single() as ControlMessage.UhidInput).data.map { it.toInt() })
        assertTrue(hid.releaseButtons().isEmpty())
    }

    @Test
    fun `no playback means no SDK mouse unless asked for`() {
        val m = InputMapper.forOptions(OptionValues(mapOf("no-playback" to null)), { size }, { view }, { false })
        assertTrue(m.press(center, PointerButton.Primary).isEmpty())
        assertTrue(m.press(center, PointerButton.Secondary).isEmpty(), "disabled mode default ++++ forwards, and forwards nothing")
    }
}
