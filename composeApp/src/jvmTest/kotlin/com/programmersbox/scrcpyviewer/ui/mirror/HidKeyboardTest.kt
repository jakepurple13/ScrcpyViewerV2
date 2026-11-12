package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HidKeyboardTest {
    private var locks = 0
    private val kb = HidKeyboard(lockState = { locks }, forwardLockKeys = true)

    private fun press(code: Int, location: Int = AwtKeyEvent.KEY_LOCATION_STANDARD) =
        kb.awtKey(AwtKeyEvent.KEY_PRESSED, code, location)

    private fun release(code: Int, location: Int = AwtKeyEvent.KEY_LOCATION_STANDARD) =
        kb.awtKey(AwtKeyEvent.KEY_RELEASED, code, location)

    private fun List<ControlMessage>.reports(): List<List<Int>> = map {
        val input = it as ControlMessage.UhidInput
        assertEquals(HidKeyboard.ID, input.id)
        input.data.map { b -> b.toInt() and 0xff }
    }

    private fun report(mods: Int, vararg keys: Int) = listOf(mods, 0) + keys.toList() + List(6 - keys.size) { 0 }

    @Test
    fun `report descriptor is byte identical to hid_keyboard c`() {
        val expected = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
            0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x75, 0x01, 0x95, 0x05, 0x91, 0x02,
            0x75, 0x03, 0x95, 0x01, 0x91, 0x01,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00,
            0xC0,
        ).map { it.toByte() }.toByteArray()
        assertContentEquals(expected, HidKeyboard.REPORT_DESC)
    }

    @Test
    fun `open and close messages as keyboard_uhid c`() {
        val open = kb.open()
        assertEquals(1, open.id)
        assertEquals(0, open.vendorId)
        assertEquals(0, open.productId)
        assertEquals("", open.name)
        val bytes = open.serialize()
        // type, id, vendor, product, name length 0, descriptor size 63, descriptor
        assertContentEquals(byteArrayOf(12, 0, 1, 0, 0, 0, 0, 0, 0, 63), bytes.copyOf(10))
        assertContentEquals(HidKeyboard.REPORT_DESC, bytes.copyOfRange(10, bytes.size))
        assertContentEquals(byteArrayOf(14, 0, 1), kb.close().serialize())
    }

    @Test
    fun `key press and release`() {
        assertEquals(listOf(report(0, 0x04)), press(AwtKeyEvent.VK_A).reports())
        assertEquals(listOf(report(0)), release(AwtKeyEvent.VK_A).reports())
        val bytes = (press(AwtKeyEvent.VK_ENTER).single()).serialize()
        assertContentEquals(byteArrayOf(13, 0, 1, 0, 8, 0, 0, 0x28, 0, 0, 0, 0, 0), bytes)
    }

    @Test
    fun `modifiers set the modifier byte, left and right`() {
        assertEquals(listOf(report(0x02)), press(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.KEY_LOCATION_LEFT).reports())
        assertEquals(listOf(report(0x02, 0x04)), press(AwtKeyEvent.VK_A).reports())
        assertEquals(listOf(report(0x22, 0x04)), press(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.KEY_LOCATION_RIGHT).reports())
        assertEquals(listOf(report(0x32, 0x04)), press(AwtKeyEvent.VK_CONTROL, AwtKeyEvent.KEY_LOCATION_RIGHT).reports())
        assertEquals(listOf(report(0x33, 0x04)), press(AwtKeyEvent.VK_CONTROL, AwtKeyEvent.KEY_LOCATION_LEFT).reports())
        assertEquals(listOf(report(0x73, 0x04)), press(AwtKeyEvent.VK_ALT_GRAPH).reports(), "AltGr is right Alt")
        assertEquals(listOf(report(0xF3, 0x04)), press(AwtKeyEvent.VK_META, AwtKeyEvent.KEY_LOCATION_RIGHT).reports())
        assertEquals(listOf(report(0xF1, 0x04)), release(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.KEY_LOCATION_LEFT).reports())
        assertEquals(listOf(report(0xF1)), release(AwtKeyEvent.VK_A).reports())
    }

    @Test
    fun `pressed keys are listed in usage order`() {
        press(AwtKeyEvent.VK_W)
        assertEquals(listOf(report(0, 0x04, 0x1A)), press(AwtKeyEvent.VK_A).reports())
    }

    @Test
    fun `more than six keys is the phantom state`() {
        val keys = listOf(AwtKeyEvent.VK_A, AwtKeyEvent.VK_B, AwtKeyEvent.VK_C, AwtKeyEvent.VK_D, AwtKeyEvent.VK_E, AwtKeyEvent.VK_F)
        keys.forEach { press(it) }
        press(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.KEY_LOCATION_LEFT)
        assertEquals(listOf(report(0x02, 1, 1, 1, 1, 1, 1)), press(AwtKeyEvent.VK_G).reports())
        assertEquals(listOf(report(0x02, 4, 5, 6, 7, 8, 9)), release(AwtKeyEvent.VK_G).reports())
    }

    @Test
    fun `auto repeat and unsupported keys send nothing`() {
        press(AwtKeyEvent.VK_A)
        assertTrue(press(AwtKeyEvent.VK_A).isEmpty(), "repeat is handled by Android")
        assertTrue(press(AwtKeyEvent.VK_F13).isEmpty(), "F13 is past the 0x65 keys of the descriptor")
        assertTrue(kb.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 0).isEmpty(), "never text")
    }

    @Test
    fun `shortcut mod keys and keys pressed with them are not forwarded`() {
        // Default --shortcut-mod is lalt,lsuper (options.c): those keys and MOD+key are scrcpy shortcuts.
        assertTrue(press(AwtKeyEvent.VK_ALT, AwtKeyEvent.KEY_LOCATION_LEFT).isEmpty())
        assertTrue(press(AwtKeyEvent.VK_H).isEmpty())
        assertTrue(release(AwtKeyEvent.VK_H).isEmpty())
        assertTrue(release(AwtKeyEvent.VK_ALT, AwtKeyEvent.KEY_LOCATION_LEFT).isEmpty())
        assertTrue(press(AwtKeyEvent.VK_META, AwtKeyEvent.KEY_LOCATION_LEFT).isEmpty())
        assertTrue(release(AwtKeyEvent.VK_META, AwtKeyEvent.KEY_LOCATION_LEFT).isEmpty())
        assertEquals(listOf(report(0, 0x0B)), press(AwtKeyEvent.VK_H).reports())
    }

    @Test
    fun `usage table`() {
        val l = AwtKeyEvent.KEY_LOCATION_STANDARD
        val np = AwtKeyEvent.KEY_LOCATION_NUMPAD
        assertEquals(0x1D, HidKeyboard.usage(AwtKeyEvent.VK_Z, l))
        assertEquals(0x1E, HidKeyboard.usage(AwtKeyEvent.VK_1, l))
        assertEquals(0x27, HidKeyboard.usage(AwtKeyEvent.VK_0, l))
        assertEquals(0x3A, HidKeyboard.usage(AwtKeyEvent.VK_F1, l))
        assertEquals(0x45, HidKeyboard.usage(AwtKeyEvent.VK_F12, l))
        assertEquals(0x4F, HidKeyboard.usage(AwtKeyEvent.VK_RIGHT, l))
        assertEquals(0x52, HidKeyboard.usage(AwtKeyEvent.VK_UP, l))
        assertEquals(0x58, HidKeyboard.usage(AwtKeyEvent.VK_ENTER, np))
        assertEquals(0x59, HidKeyboard.usage(AwtKeyEvent.VK_NUMPAD1, np))
        assertEquals(0x62, HidKeyboard.usage(AwtKeyEvent.VK_NUMPAD0, np))
        assertEquals(0x60, HidKeyboard.usage(AwtKeyEvent.VK_UP, np), "keypad 8 with Num Lock off")
        assertEquals(0x60, HidKeyboard.usage(AwtKeyEvent.VK_KP_UP, l))
        assertEquals(0x35, HidKeyboard.usage(AwtKeyEvent.VK_BACK_QUOTE, l))
        assertEquals(0x65, HidKeyboard.usage(AwtKeyEvent.VK_CONTEXT_MENU, l))
        assertEquals(0xE0, HidKeyboard.usage(AwtKeyEvent.VK_CONTROL, AwtKeyEvent.KEY_LOCATION_LEFT))
        assertEquals(0xE7, HidKeyboard.usage(AwtKeyEvent.VK_META, AwtKeyEvent.KEY_LOCATION_RIGHT))
        assertEquals(null, HidKeyboard.usage(AwtKeyEvent.VK_F13, l))
    }

    @Test
    fun `lock keys are forwarded and toggle the device state instead of a sync`() {
        locks = HidKeyboard.MOD_CAPS // the host toggled caps lock with this press
        assertEquals(listOf(report(0, 0x39)), press(AwtKeyEvent.VK_CAPS_LOCK).reports())
        assertEquals(listOf(report(0, 0x04, 0x39)), press(AwtKeyEvent.VK_A).reports(), "device state already toggled: no sync")
        // keyboard_uhid.c also toggles on the release; the device LED report then sets the real state.
        assertEquals(listOf(report(0, 0x04)), release(AwtKeyEvent.VK_CAPS_LOCK).reports())
        kb.onOutput(byteArrayOf(0x02))
        assertEquals(listOf(report(0)), release(AwtKeyEvent.VK_A).reports())
    }

    @Test
    fun `host lock state is synchronized before a key, then from the device LED report`() {
        locks = HidKeyboard.MOD_CAPS or HidKeyboard.MOD_NUM
        assertEquals(listOf(report(0, 0x39, 0x53), report(0, 0x04)), press(AwtKeyEvent.VK_A).reports())
        assertEquals(listOf(report(0)), release(AwtKeyEvent.VK_A).reports(), "no change, no sync")
        // The device reports Num Lock only (LED bit 0): caps lock must be toggled again.
        kb.onOutput(byteArrayOf(0x01))
        assertEquals(listOf(report(0, 0x39), report(0, 0x05)), press(AwtKeyEvent.VK_B).reports())
        kb.onOutput(byteArrayOf(0x03))
        assertEquals(listOf(report(0)), release(AwtKeyEvent.VK_B).reports())
    }

    @Test
    fun `lock keys are dropped when not forwarded`() {
        val mac = HidKeyboard(lockState = { HidKeyboard.MOD_CAPS }, forwardLockKeys = false)
        assertTrue(mac.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_CAPS_LOCK, AwtKeyEvent.KEY_LOCATION_STANDARD).isEmpty())
        val msgs = mac.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_A, AwtKeyEvent.KEY_LOCATION_STANDARD).reports()
        assertEquals(listOf(report(0, 0x39), report(0, 0x04)), msgs)
    }

    @Test
    fun `releaseAll clears held keys and modifiers`() {
        press(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.KEY_LOCATION_LEFT)
        press(AwtKeyEvent.VK_A)
        assertEquals(listOf(report(0)), kb.releaseAll().reports())
        assertTrue(kb.releaseAll().isEmpty())
        assertEquals(listOf(report(0, 0x04)), press(AwtKeyEvent.VK_A).reports())
    }

    @Test
    fun `host lock state comes from the OS without throwing`() {
        val state = HidKeyboard.hostLockState()
        assertEquals(0, state and (HidKeyboard.MOD_CAPS or HidKeyboard.MOD_NUM).inv(), "only lock bits")
        // No Num Lock on macOS (keypad types digits) nor a reader on Linux: reported on.
        if (!com.programmersbox.scrcpyviewer.Os.isWindows) assertTrue(state and HidKeyboard.MOD_NUM != 0)
    }
}
