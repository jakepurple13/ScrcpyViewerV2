package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mirror window's device keys ([MirrorKeys]). Events are built as Compose makes them without an AWT event behind
 * them (as under Tao), so every test goes through [awtKeyOf].
 */
@OptIn(InternalComposeUiApi::class)
class MirrorKeysTest {
    private val sent = mutableListOf<ControlMessage>()
    private val followerSent = mutableListOf<ControlMessage>()
    private val zooms = mutableListOf<ZoomCommand>()
    private val captureFocus = mutableListOf<Boolean>()
    private var sheetOpen = false
    private var sheetFocused = false
    private var sheetCloses = 0
    private var clipboard: String? = null
    private val mapper = InputMapper(frameSize = { IntSize(1080, 2400) }, viewRect = { Rect(0f, 0f, 540f, 1200f) })

    private fun keys(
        hid: HidKeyboard? = null,
        legacyPaste: Boolean = false,
        followerKeyRepeat: Boolean = true,
    ) = MirrorKeys(
        mapper = mapper,
        hidKeyboard = hid,
        send = { sent += it },
        followerSend = { followerSent += it },
        clipboardText = { clipboard },
        scope = CoroutineScope(Dispatchers.Unconfined),
        sheetOpen = { sheetOpen },
        sheetFocused = { sheetFocused },
        closeSheet = { sheetCloses++ },
        applyZoom = { zooms += it },
        captureFocus = { captureFocus += it },
        mac = true,
        legacyPaste = legacyPaste,
        followerKeyRepeat = followerKeyRepeat,
    )

    private fun down(key: Key, meta: Boolean = false) = KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = meta)
    private fun up(key: Key, meta: Boolean = false) = KeyEvent(key, KeyEventType.KeyUp, isMetaPressed = meta)
    private fun typed(c: Char, meta: Boolean = false) = KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = c.code, isMetaPressed = meta)

    private fun hidReports() = sent.map { assertIs<ControlMessage.UhidInput>(it).data.map { b -> b.toInt() and 0xff } }

    // --- awtKeyOf ---

    @Test
    fun composeKeyCodes_areAwtVkCodes() {
        assertEquals(AwtKeyEvent.VK_A, Key.A.nativeKeyCode)
        assertEquals(AwtKeyEvent.VK_ENTER, Key.Enter.nativeKeyCode)
        assertEquals(AwtKeyEvent.VK_SHIFT, Key.ShiftLeft.nativeKeyCode)
        assertEquals(AwtKeyEvent.VK_V, Key.V.nativeKeyCode)
    }

    @Test
    fun awtKeyOf_pressReleaseTyped() {
        assertEquals(
            AwtKeyTuple(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_A, AwtKeyEvent.CHAR_UNDEFINED, 0, AwtKeyEvent.KEY_LOCATION_STANDARD),
            awtKeyOf(down(Key.A)),
        )
        assertEquals(
            AwtKeyTuple(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ENTER, AwtKeyEvent.CHAR_UNDEFINED, 0, AwtKeyEvent.KEY_LOCATION_STANDARD),
            awtKeyOf(up(Key.Enter)),
        )
        // A key-down that carries its character keeps it, as AWT's KEY_PRESSED does.
        assertEquals('a', awtKeyOf(KeyEvent(Key.A, KeyEventType.KeyDown, codePoint = 'a'.code))?.keyChar)
        assertEquals(
            AwtKeyTuple(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 'x', 0, AwtKeyEvent.KEY_LOCATION_UNKNOWN),
            awtKeyOf(typed('x')),
        )
        assertNull(awtKeyOf(KeyEvent(Key.Unknown, KeyEventType.Unknown)), "a typed event with no character")
        assertNull(awtKeyOf(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = 0x1F600)), "not one UTF-16 unit")
    }

    @Test
    fun awtKeyOf_modifiers() {
        val all = KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true, isMetaPressed = true, isAltPressed = true, isShiftPressed = true)
        assertEquals(
            AwtKeyEvent.SHIFT_DOWN_MASK or AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.META_DOWN_MASK,
            awtKeyOf(all)?.modifiersEx,
        )
        assertEquals(AwtKeyEvent.SHIFT_DOWN_MASK, awtKeyOf(KeyEvent(Key.A, KeyEventType.KeyDown, isShiftPressed = true))?.modifiersEx)
    }

    @Test
    fun awtKeyOf_locations() {
        fun loc(key: Key) = awtKeyOf(down(key))!!.let { it.keyCode to it.location }
        assertEquals(AwtKeyEvent.VK_SHIFT to AwtKeyEvent.KEY_LOCATION_LEFT, loc(Key.ShiftLeft))
        assertEquals(AwtKeyEvent.VK_SHIFT to AwtKeyEvent.KEY_LOCATION_RIGHT, loc(Key.ShiftRight))
        assertEquals(AwtKeyEvent.VK_CONTROL to AwtKeyEvent.KEY_LOCATION_LEFT, loc(Key.CtrlLeft))
        assertEquals(AwtKeyEvent.VK_CONTROL to AwtKeyEvent.KEY_LOCATION_RIGHT, loc(Key.CtrlRight))
        assertEquals(AwtKeyEvent.VK_ALT to AwtKeyEvent.KEY_LOCATION_LEFT, loc(Key.AltLeft))
        assertEquals(AwtKeyEvent.VK_ALT to AwtKeyEvent.KEY_LOCATION_RIGHT, loc(Key.AltRight))
        assertEquals(AwtKeyEvent.VK_META to AwtKeyEvent.KEY_LOCATION_LEFT, loc(Key.MetaLeft))
        assertEquals(AwtKeyEvent.VK_META to AwtKeyEvent.KEY_LOCATION_RIGHT, loc(Key.MetaRight))
        assertEquals(AwtKeyEvent.VK_NUMPAD5 to AwtKeyEvent.KEY_LOCATION_NUMPAD, loc(Key.NumPad5))
        assertEquals(AwtKeyEvent.VK_ENTER to AwtKeyEvent.KEY_LOCATION_NUMPAD, loc(Key.NumPadEnter))
        assertEquals(AwtKeyEvent.VK_ENTER to AwtKeyEvent.KEY_LOCATION_STANDARD, loc(Key.Enter))
    }

    // --- MirrorKeys ---

    @Test
    fun keys_goToTheDevice() {
        val keys = keys()
        assertTrue(keys.onKey(down(Key.Enter)))
        assertTrue(keys.onKey(up(Key.Enter)))
        assertEquals(2, sent.size)
        sent.forEach { assertIs<ControlMessage.InjectKeycode>(it) }
    }

    @Test
    fun sheetFocused_keysGoToSheetNotDevice() {
        val keys = keys(HidKeyboard(lockState = { 0 }, forwardLockKeys = true))
        sheetOpen = true
        sheetFocused = true
        for (ev in listOf(down(Key.A), typed('a'), up(Key.A), down(Key.Escape), up(Key.Escape), down(Key.V, meta = true))) {
            assertFalse(keys.onKey(ev), "Compose gets $ev")
        }
        assertTrue(sent.isEmpty())
        assertEquals(0, sheetCloses, "Esc goes to the field first")

        // Focus elsewhere: Esc closes the sheet (on press only) and is consumed; other keys reach the device.
        sheetFocused = false
        assertTrue(keys.onKey(down(Key.Escape)))
        assertTrue(keys.onKey(up(Key.Escape)))
        assertEquals(1, sheetCloses)
        assertTrue(sent.isEmpty())
        assertTrue(keys.onKey(down(Key.A)))
        assertEquals(1, sent.size)
    }

    @Test
    fun focusMovesToSheet_heldHidKeysReleased() {
        val keys = keys(HidKeyboard(lockState = { 0 }, forwardLockKeys = true))
        val focus = SheetFocus().apply { onGained = keys::onSheetFocusGained }
        assertTrue(keys.onKey(down(Key.A)))
        assertEquals(0x04, hidReports().single()[2], "A held on the device")
        sent.clear()

        sheetOpen = true
        focus.update(Any(), true)
        sheetFocused = focus.focused
        assertEquals(listOf(List(8) { 0 }), hidReports(), "every key released")
        sent.clear()
        assertFalse(keys.onKey(up(Key.A)), "the release goes to the sheet")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun paste_emptyClipboard_consumedNothingSent() {
        for (empty in listOf(null, "")) {
            clipboard = empty
            val keys = keys()
            assertTrue(keys.onKey(down(Key.V, meta = true)), "the press is consumed")
            assertTrue(keys.onKey(typed('v', meta = true)), "its typed character too")
            assertTrue(keys.onKey(up(Key.V, meta = true)), "and its release")
            assertTrue(sent.isEmpty(), "nothing sent for clipboard '$empty'")
            // Released: V reaches the device again.
            assertTrue(keys.onKey(down(Key.V)))
            assertIs<ControlMessage.InjectKeycode>(sent.single())
            sent.clear()
        }
    }

    @Test
    fun paste_text_sendsSetClipboardPaste() {
        clipboard = "hello"
        val keys = keys()
        assertTrue(keys.onKey(down(Key.V, meta = true)))
        assertEquals(listOf<ControlMessage>(ControlMessage.SetClipboard(0, paste = true, text = "hello")), sent)
        assertTrue(keys.onKey(up(Key.V, meta = true)))
        assertEquals(1, sent.size)

        sent.clear()
        clipboard = "a".repeat(150)
        val legacy = keys(legacyPaste = true)
        assertTrue(legacy.onKey(down(Key.V, meta = true)))
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectText("a".repeat(100)), ControlMessage.InjectText("a".repeat(50))), sent)
    }

    @Test
    fun paste_clipboardArrivesLater_sentWhenItDoes() {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val keys = MirrorKeys(
            mapper = mapper, hidKeyboard = null, send = { sent += it }, clipboardText = { gate.await() }, scope = scope,
            sheetOpen = { false }, sheetFocused = { false }, closeSheet = {}, applyZoom = {}, mac = true,
        )
        assertTrue(keys.onKey(down(Key.V, meta = true)), "consumed before the clipboard answers")
        assertTrue(sent.isEmpty())
        gate.complete("later")
        assertEquals(listOf<ControlMessage>(ControlMessage.SetClipboard(0, paste = true, text = "later")), sent)
    }

    @Test
    fun zoomKeys_neverForwarded() {
        for (hid in listOf(null, HidKeyboard(lockState = { 0 }, forwardLockKeys = true))) {
            zooms.clear()
            val keys = keys(hid)
            assertTrue(keys.onKey(down(Key.Equals, meta = true)))
            assertTrue(keys.onKey(down(Key.Equals, meta = true)), "repeat")
            assertTrue(keys.onKey(typed('=', meta = true)))
            assertTrue(keys.onKey(up(Key.Equals, meta = true)))
            assertTrue(keys.onKey(down(Key.Minus, meta = true)))
            assertTrue(keys.onKey(up(Key.Minus, meta = true)))
            assertTrue(keys.onKey(down(Key.Zero, meta = true)))
            assertTrue(keys.onKey(up(Key.Zero, meta = true)))
            assertEquals(listOf(ZoomCommand.In, ZoomCommand.In, ZoomCommand.Out, ZoomCommand.Fit), zooms, "a held shortcut repeats")
            assertTrue(sent.isEmpty(), "hid=${hid != null}: nothing reached the device")
        }

        // Focus lost with a zoom key down: its release happens in another window, so a later one is not the zoom's
        // (the mapper, in mixed mode, has nothing for a bare Equals release: not consumed).
        val keys = keys()
        assertTrue(keys.onKey(down(Key.Equals, meta = true)))
        assertTrue(keys.onKey(up(Key.Equals)), "held: the zoom's release")
        assertTrue(keys.onKey(down(Key.Equals, meta = true)))
        keys.onFocusChanged(false)
        assertFalse(keys.onKey(up(Key.Equals)), "forgotten on focus loss")
    }

    @Test
    fun noControl_andKeyboardDisabled_leaveKeysToCompose() {
        val noControl = MirrorKeys(
            mapper = mapper, hidKeyboard = null, send = { sent += it }, clipboardText = { "x" },
            scope = CoroutineScope(Dispatchers.Unconfined), sheetOpen = { false }, sheetFocused = { false },
            closeSheet = {}, applyZoom = { zooms += it }, mac = true, noControl = true,
        )
        assertFalse(noControl.onKey(down(Key.A)))
        assertTrue(noControl.onKey(down(Key.Equals, meta = true)), "zoom still works")
        val disabled = MirrorKeys(
            mapper = mapper, hidKeyboard = null, send = { sent += it }, clipboardText = { "x" },
            scope = CoroutineScope(Dispatchers.Unconfined), sheetOpen = { false }, sheetFocused = { false },
            closeSheet = {}, applyZoom = {}, mac = true, keyboardDisabled = true,
            captureKey = { _, code, _ -> code == AwtKeyEvent.VK_ALT },
        )
        assertTrue(disabled.onKey(down(Key.AltLeft)), "capture keys before keyboard=disabled")
        assertFalse(disabled.onKey(down(Key.A)))
        assertFalse(disabled.onKey(down(Key.V, meta = true)), "no paste either")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun focusLost_releasesHidAndButtons() {
        val keys = keys(HidKeyboard(lockState = { 0 }, forwardLockKeys = true))
        assertTrue(keys.onKey(down(Key.A)))
        mapper.press(Offset(270f, 600f), PointerButton.Primary)
        assertTrue(keys.onKey(down(Key.Equals, meta = true)))
        sent.clear()

        keys.onFocusChanged(false)
        assertEquals(listOf(false), captureFocus)
        assertEquals(2, sent.size)
        assertEquals(List(8) { 0 }, assertIs<ControlMessage.UhidInput>(sent[0]).data.map { it.toInt() and 0xff })
        assertEquals(InputMapper.ACTION_UP, assertIs<ControlMessage.InjectTouch>(sent[1]).action)

        // Each loss releases again, even with no gain seen in between.
        sent.clear()
        assertTrue(keys.onKey(down(Key.B)))
        sent.clear()
        keys.onFocusChanged(false)
        assertEquals(List(8) { 0 }, assertIs<ControlMessage.UhidInput>(sent.single()).data.map { it.toInt() and 0xff })

        keys.onFocusChanged(true)
        assertEquals(listOf(false, false, true), captureFocus)
    }

    // --- follower output ---

    @Test
    fun follower_mixedMode_getsSameKeycodeAsLeader() {
        val k = keys()
        k.onKey(down(Key.A))
        k.onKey(up(Key.A))
        assertEquals(sent, followerSent)
        assertEquals(2, followerSent.size)
        k.onKey(typed('!'))
        assertEquals(ControlMessage.InjectText("!"), followerSent.last())
    }

    @Test
    fun follower_uhidLeader_followerGetsPlainKeys() {
        val k = keys(hid = HidKeyboard(lockState = { 0 }, forwardLockKeys = true))
        k.onKey(down(Key.A))
        k.onKey(typed('!'))
        hidReports()
        assertEquals(2, followerSent.size)
        assertIs<ControlMessage.InjectKeycode>(followerSent[0])
        assertEquals(ControlMessage.InjectText("!"), followerSent[1])
    }

    @Test
    fun follower_paste_getsSetClipboard() {
        clipboard = "hello"
        keys().onKey(down(Key.V, meta = true))
        assertEquals(listOf<ControlMessage>(ControlMessage.SetClipboard(0, paste = true, text = "hello")), followerSent)
    }

    @Test
    fun follower_zoomKey_getsNothing() {
        keys().onKey(down(Key.Equals, meta = true))
        assertTrue(followerSent.isEmpty())
    }

    @Test
    fun follower_sheetFocus_releasesHeldKey() {
        val k = keys()
        k.onKey(down(Key.A))
        followerSent.clear()
        k.onSheetFocusGained()
        val up = assertIs<ControlMessage.InjectKeycode>(followerSent.single())
        assertEquals(InputMapper.KEY_ACTION_UP, up.action)
        assertTrue(k.followerHeldKeyUp().isEmpty(), "forgotten: nothing left to release")
    }

    @Test
    fun follower_focusLoss_releasesHeldKey() {
        val k = keys()
        k.onKey(down(Key.A))
        followerSent.clear()
        k.onFocusChanged(false)
        val up = assertIs<ControlMessage.InjectKeycode>(followerSent.single())
        assertEquals(InputMapper.KEY_ACTION_UP, up.action)
    }

    @Test
    fun follower_keyRepeat_followsTheLeadersOption() {
        val repeating = keys()
        repeat(3) { repeating.onKey(down(Key.A)) }
        assertEquals(3, followerSent.size)
        followerSent.clear()
        val noRepeat = keys(followerKeyRepeat = false)
        repeat(3) { noRepeat.onKey(down(Key.A)) }
        assertEquals(1, followerSent.size, "no-key-repeat: only the first press reaches followers")
    }

    @Test
    fun follower_heldKeyUp_releasesTheHeldKeyAndKeepsItHeld() {
        val k = keys()
        assertTrue(k.followerHeldKeyUp().isEmpty())
        k.onKey(down(Key.ShiftLeft))
        val up = assertIs<ControlMessage.InjectKeycode>(k.followerHeldKeyUp().single())
        assertEquals(InputMapper.KEY_ACTION_UP, up.action)
        // Still held for the followers that stay: asking again gives the same UP, and its real release still goes.
        assertEquals(listOf<ControlMessage>(up), k.followerHeldKeyUp())
        followerSent.clear()
        k.onKey(up(Key.ShiftLeft))
        assertEquals(InputMapper.KEY_ACTION_UP, assertIs<ControlMessage.InjectKeycode>(followerSent.single()).action)
        assertTrue(k.followerHeldKeyUp().isEmpty())
    }
}
