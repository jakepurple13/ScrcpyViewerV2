package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.sun.jna.NativeLibrary
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The UHID keyboard of v5.0 `hid/hid_keyboard.c` and `uhid/keyboard_uhid.c`, fed with AWT key events.
 *
 * Every state change sends the whole 8-byte report (modifiers, reserved, up to 6 pressed keys in usage order; more
 * than 6 is the phantom state, all 0x01). AWT key codes and locations are mapped to the HID usages that SDL
 * scancodes are; keys past usage 0x65 (the descriptor's last key) other than modifiers are ignored. Auto-repeated
 * presses are dropped: Android repeats HID keys itself. Text is never sent.
 *
 * As the scrcpy client with its default `--shortcut-mod=lalt,lsuper`, left Alt and left Meta (Cmd) are shortcut keys:
 * they and the keys pressed while one is held are not forwarded. Releases of keys the device saw pressed are always
 * forwarded, so nothing stays stuck.
 *
 * Caps Lock and Num Lock follow `keyboard_uhid.c`: before each other key, the host lock state ([lockState], a mask
 * of [MOD_CAPS] and [MOD_NUM]) is compared with the device state and the difference is sent as a lock key press; the
 * device state is set from its LED output report ([onOutput]) and toggled by forwarded lock keys. With
 * [forwardLockKeys] false (macOS, where AWT reports a Caps Lock toggle rather than a press and a release) lock keys
 * are dropped and only the synchronization applies.
 *
 * Not thread-safe; call from one thread (the AWT event thread).
 */
class HidKeyboard(
    private val lockState: () -> Int = ::hostLockState,
    private val forwardLockKeys: Boolean = !isMacOs(),
) {
    private val keys = BooleanArray(KEYS)
    /** Pressed modifier usages, 0xE0..0xE7, as a bit mask (bit 0 = left Ctrl, as the report's modifier byte). */
    private var mods = 0
    /** Left Alt / left Meta held (shortcut modifiers, never forwarded). */
    private var shortcutHeld = 0
    private var deviceMod = 0

    fun open(): ControlMessage.UhidCreate = ControlMessage.UhidCreate(ID, 0, 0, "", REPORT_DESC)

    fun close(): ControlMessage.UhidDestroy = ControlMessage.UhidDestroy(ID)

    /** Reports for an AWT key event; KEY_TYPED and unsupported keys give nothing. */
    fun awtKey(id: Int, keyCode: Int, location: Int): List<ControlMessage> {
        if (id != AwtKeyEvent.KEY_PRESSED && id != AwtKeyEvent.KEY_RELEASED) return emptyList()
        val down = id == AwtKeyEvent.KEY_PRESSED
        val usage = usage(keyCode, location) ?: return emptyList()

        val shortcutBit = shortcutBit(usage)
        if (shortcutBit != 0) {
            shortcutHeld = if (down) shortcutHeld or shortcutBit else shortcutHeld and shortcutBit.inv()
            return emptyList()
        }
        if (!forwardLockKeys && (usage == USAGE_CAPS_LOCK || usage == USAGE_NUM_LOCK)) return emptyList()

        if (usage >= USAGE_LEFT_CTRL) {
            val bit = 1 shl (usage - USAGE_LEFT_CTRL)
            val held = mods and bit != 0
            if (down == held) return emptyList() // auto-repeat, or a release the device never saw
            if (down && shortcutHeld != 0) return emptyList()
            mods = if (down) mods or bit else mods and bit.inv()
        } else {
            if (down == keys[usage]) return emptyList()
            if (down && shortcutHeld != 0) return emptyList()
            keys[usage] = down
        }

        val out = ArrayList<ControlMessage>(2)
        when (usage) {
            USAGE_CAPS_LOCK -> deviceMod = deviceMod xor MOD_CAPS
            USAGE_NUM_LOCK -> deviceMod = deviceMod xor MOD_NUM
            else -> synchronizeLocks()?.let(out::add)
        }
        out += input(report())
        return out
    }

    /** Releases everything the device sees pressed (focus lost); empty when nothing is pressed. */
    fun releaseAll(): List<ControlMessage> {
        shortcutHeld = 0
        if (mods == 0 && keys.none { it }) return emptyList()
        mods = 0
        keys.fill(false)
        return listOf(input(report()))
    }

    /** `sc_keyboard_uhid_process_hid_output()`: the LED report (bit 0 Num Lock, bit 1 Caps Lock). */
    fun onOutput(data: ByteArray) {
        if (data.isEmpty()) return
        val led = data[0].toInt()
        deviceMod = (if (led and 0x01 != 0) MOD_NUM else 0) or (if (led and 0x02 != 0) MOD_CAPS else 0)
    }

    /** `sc_keyboard_uhid_synchronize_mod()` + `sc_hid_keyboard_generate_input_from_mods()`. */
    private fun synchronizeLocks(): ControlMessage? {
        val host = lockState() and (MOD_CAPS or MOD_NUM)
        val diff = host xor deviceMod
        if (diff == 0) return null
        deviceMod = host
        val data = ByteArray(INPUT_SIZE)
        var i = INDEX_KEYS
        if (diff and MOD_CAPS != 0) data[i++] = USAGE_CAPS_LOCK.toByte()
        if (diff and MOD_NUM != 0) data[i] = USAGE_NUM_LOCK.toByte()
        return input(data)
    }

    /** `sc_hid_keyboard_generate_input_from_key()`. */
    private fun report(): ByteArray {
        val data = ByteArray(INPUT_SIZE)
        data[INDEX_MODS] = mods.toByte()
        var count = 0
        for (i in 0 until KEYS) {
            if (!keys[i]) continue
            if (count >= MAX_KEYS) {
                data.fill(ERROR_ROLL_OVER, INDEX_KEYS, INPUT_SIZE)
                break
            }
            data[INDEX_KEYS + count++] = i.toByte()
        }
        return data
    }

    private fun input(data: ByteArray) = ControlMessage.UhidInput(ID, data)

    private fun shortcutBit(usage: Int): Int = when (usage) {
        USAGE_LEFT_ALT -> 1
        USAGE_LEFT_GUI -> 2
        else -> 0
    }

    companion object {
        const val ID = 1
        const val MOD_CAPS = 1
        const val MOD_NUM = 2

        /** SC_HID_KEYBOARD_KEYS: usages 0x00..0x65. */
        private const val KEYS = 0x66
        private const val MAX_KEYS = 6
        private const val INDEX_MODS = 0
        private const val INDEX_KEYS = 2
        private const val INPUT_SIZE = INDEX_KEYS + MAX_KEYS
        private const val ERROR_ROLL_OVER: Byte = 0x01

        private const val USAGE_CAPS_LOCK = 0x39
        private const val USAGE_NUM_LOCK = 0x53
        private const val USAGE_LEFT_CTRL = 0xE0
        private const val USAGE_LEFT_ALT = 0xE2
        private const val USAGE_LEFT_GUI = 0xE3

        /** `SC_HID_KEYBOARD_REPORT_DESC` of v5.0 `hid_keyboard.c`. */
        val REPORT_DESC: ByteArray = intArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x06, // Usage (Keyboard)
            0xA1, 0x01, // Collection (Application)
            0x05, 0x07, // Usage Page (Key Codes)
            0x19, 0xE0, // Usage Minimum (224)
            0x29, 0xE7, // Usage Maximum (231)
            0x15, 0x00, // Logical Minimum (0)
            0x25, 0x01, // Logical Maximum (1)
            0x75, 0x01, // Report Size (1)
            0x95, 0x08, // Report Count (8)
            0x81, 0x02, // Input (Data, Variable, Absolute): Modifier byte
            0x75, 0x08, // Report Size (8)
            0x95, 0x01, // Report Count (1)
            0x81, 0x01, // Input (Constant): Reserved byte
            0x05, 0x08, // Usage Page (LEDs)
            0x19, 0x01, // Usage Minimum (1)
            0x29, 0x05, // Usage Maximum (5)
            0x75, 0x01, // Report Size (1)
            0x95, 0x05, // Report Count (5)
            0x91, 0x02, // Output (Data, Variable, Absolute): LED report
            0x75, 0x03, // Report Size (3)
            0x95, 0x01, // Report Count (1)
            0x91, 0x01, // Output (Constant): LED report padding
            0x05, 0x07, // Usage Page (Key Codes)
            0x19, 0x00, // Usage Minimum (0)
            0x29, KEYS - 1, // Usage Maximum (101)
            0x15, 0x00, // Logical Minimum (0)
            0x25, KEYS - 1, // Logical Maximum (101)
            0x75, 0x08, // Report Size (8)
            0x95, MAX_KEYS, // Report Count (6)
            0x81, 0x00, // Input (Data, Array): Keys
            0xC0, // End Collection
        ).map { it.toByte() }.toByteArray()

        /**
         * HID usage (= SDL scancode) of an AWT key, or null when the HID keyboard has no such key. The numeric keypad
         * is told apart by [location]; with Num Lock off AWT reports its keys as navigation keys (or `VK_KP_*`).
         */
        fun usage(keyCode: Int, location: Int): Int? {
            if (location == AwtKeyEvent.KEY_LOCATION_NUMPAD) NUMPAD[keyCode]?.let { return it }
            if (location == AwtKeyEvent.KEY_LOCATION_RIGHT) RIGHT_MODIFIERS[keyCode]?.let { return it }
            return STANDARD[keyCode]
        }

        private val STANDARD: Map<Int, Int> = buildMap {
            for (k in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z) put(k, 0x04 + (k - AwtKeyEvent.VK_A))
            for (k in AwtKeyEvent.VK_1..AwtKeyEvent.VK_9) put(k, 0x1E + (k - AwtKeyEvent.VK_1))
            put(AwtKeyEvent.VK_0, 0x27)
            put(AwtKeyEvent.VK_ENTER, 0x28)
            put(AwtKeyEvent.VK_ESCAPE, 0x29)
            put(AwtKeyEvent.VK_BACK_SPACE, 0x2A)
            put(AwtKeyEvent.VK_TAB, 0x2B)
            put(AwtKeyEvent.VK_SPACE, 0x2C)
            put(AwtKeyEvent.VK_MINUS, 0x2D)
            put(AwtKeyEvent.VK_EQUALS, 0x2E)
            put(AwtKeyEvent.VK_OPEN_BRACKET, 0x2F)
            put(AwtKeyEvent.VK_CLOSE_BRACKET, 0x30)
            put(AwtKeyEvent.VK_BACK_SLASH, 0x31)
            put(AwtKeyEvent.VK_SEMICOLON, 0x33)
            put(AwtKeyEvent.VK_QUOTE, 0x34)
            put(AwtKeyEvent.VK_BACK_QUOTE, 0x35)
            put(AwtKeyEvent.VK_COMMA, 0x36)
            put(AwtKeyEvent.VK_PERIOD, 0x37)
            put(AwtKeyEvent.VK_SLASH, 0x38)
            put(AwtKeyEvent.VK_CAPS_LOCK, USAGE_CAPS_LOCK)
            for (k in AwtKeyEvent.VK_F1..AwtKeyEvent.VK_F12) put(k, 0x3A + (k - AwtKeyEvent.VK_F1))
            put(AwtKeyEvent.VK_PRINTSCREEN, 0x46)
            put(AwtKeyEvent.VK_SCROLL_LOCK, 0x47)
            put(AwtKeyEvent.VK_PAUSE, 0x48)
            put(AwtKeyEvent.VK_INSERT, 0x49)
            put(AwtKeyEvent.VK_HOME, 0x4A)
            put(AwtKeyEvent.VK_PAGE_UP, 0x4B)
            put(AwtKeyEvent.VK_DELETE, 0x4C)
            put(AwtKeyEvent.VK_END, 0x4D)
            put(AwtKeyEvent.VK_PAGE_DOWN, 0x4E)
            put(AwtKeyEvent.VK_RIGHT, 0x4F)
            put(AwtKeyEvent.VK_LEFT, 0x50)
            put(AwtKeyEvent.VK_DOWN, 0x51)
            put(AwtKeyEvent.VK_UP, 0x52)
            put(AwtKeyEvent.VK_NUM_LOCK, USAGE_NUM_LOCK)
            // Keypad keys AWT may report with the standard location.
            put(AwtKeyEvent.VK_DIVIDE, 0x54)
            put(AwtKeyEvent.VK_MULTIPLY, 0x55)
            put(AwtKeyEvent.VK_SUBTRACT, 0x56)
            put(AwtKeyEvent.VK_ADD, 0x57)
            for (k in AwtKeyEvent.VK_NUMPAD1..AwtKeyEvent.VK_NUMPAD9) put(k, 0x59 + (k - AwtKeyEvent.VK_NUMPAD1))
            put(AwtKeyEvent.VK_NUMPAD0, 0x62)
            put(AwtKeyEvent.VK_DECIMAL, 0x63)
            put(AwtKeyEvent.VK_KP_DOWN, 0x5A)
            put(AwtKeyEvent.VK_KP_LEFT, 0x5C)
            put(AwtKeyEvent.VK_KP_RIGHT, 0x5E)
            put(AwtKeyEvent.VK_KP_UP, 0x60)
            put(AwtKeyEvent.VK_LESS, 0x64) // the ISO key between left Shift and Z
            put(AwtKeyEvent.VK_CONTEXT_MENU, 0x65)
            // Modifiers (left unless the location says right).
            put(AwtKeyEvent.VK_CONTROL, 0xE0)
            put(AwtKeyEvent.VK_SHIFT, 0xE1)
            put(AwtKeyEvent.VK_ALT, 0xE2)
            put(AwtKeyEvent.VK_META, 0xE3)
            put(AwtKeyEvent.VK_WINDOWS, 0xE3)
            put(AwtKeyEvent.VK_ALT_GRAPH, 0xE6)
        }

        private val RIGHT_MODIFIERS = mapOf(
            AwtKeyEvent.VK_CONTROL to 0xE4,
            AwtKeyEvent.VK_SHIFT to 0xE5,
            AwtKeyEvent.VK_ALT to 0xE6,
            AwtKeyEvent.VK_META to 0xE7,
            AwtKeyEvent.VK_WINDOWS to 0xE7,
        )

        private val NUMPAD = mapOf(
            AwtKeyEvent.VK_ENTER to 0x58,
            AwtKeyEvent.VK_END to 0x59,
            AwtKeyEvent.VK_DOWN to 0x5A,
            AwtKeyEvent.VK_PAGE_DOWN to 0x5B,
            AwtKeyEvent.VK_LEFT to 0x5C,
            AwtKeyEvent.VK_CLEAR to 0x5D,
            AwtKeyEvent.VK_BEGIN to 0x5D,
            AwtKeyEvent.VK_RIGHT to 0x5E,
            AwtKeyEvent.VK_HOME to 0x5F,
            AwtKeyEvent.VK_UP to 0x60,
            AwtKeyEvent.VK_PAGE_UP to 0x61,
            AwtKeyEvent.VK_INSERT to 0x62,
            AwtKeyEvent.VK_DELETE to 0x63,
        )

        /**
         * Caps Lock and Num Lock of the computer, from the OS (no AWT toolkit): CGEventSourceFlagsState on macOS,
         * GetKeyState on Windows. macOS has no Num Lock (the keypad types digits), and Linux has no reader here: Num
         * Lock is reported on there, which keeps the device keypad typing digits, and Caps Lock off on Linux.
         */
        fun hostLockState(): Int {
            val caps = runCatching { hostCapsLock() }.getOrDefault(false)
            val num = if (Os.isWindows) runCatching { windowsKeyToggled(VK_NUMLOCK_WIN) }.getOrDefault(true) else true
            return (if (caps) MOD_CAPS else 0) or (if (num) MOD_NUM else 0)
        }

        private const val CG_EVENT_SOURCE_STATE_HID_SYSTEM = 1
        private const val CG_EVENT_FLAG_MASK_ALPHA_SHIFT = 0x10000L
        private const val VK_CAPITAL_WIN = 0x14
        private const val VK_NUMLOCK_WIN = 0x90

        private fun hostCapsLock(): Boolean = when {
            Os.isMac -> {
                val flags = NativeLibrary.getInstance("ApplicationServices").getFunction("CGEventSourceFlagsState")
                    .invokeLong(arrayOf(CG_EVENT_SOURCE_STATE_HID_SYSTEM))
                flags and CG_EVENT_FLAG_MASK_ALPHA_SHIFT != 0L
            }
            Os.isWindows -> windowsKeyToggled(VK_CAPITAL_WIN)
            else -> false
        }

        /** GetKeyState's low bit: the key's toggle (lock) state. */
        private fun windowsKeyToggled(vk: Int): Boolean =
            NativeLibrary.getInstance("user32").getFunction("GetKeyState").invokeInt(arrayOf(vk)) and 1 != 0
    }
}

internal fun isMacOs(): Boolean = Os.isMac
