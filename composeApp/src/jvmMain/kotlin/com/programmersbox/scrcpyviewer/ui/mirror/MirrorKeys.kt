package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent as AwtKeyEvent

/** A key event as AWT describes it: what [InputMapper.awtKey], [HidKeyboard.awtKey] and [ZoomKeyFilter] take. */
data class AwtKeyTuple(val id: Int, val keyCode: Int, val keyChar: Char, val modifiersEx: Int, val location: Int)

/**
 * [event] as the AWT key event it stands for, from the Compose event alone (under Tao there is no AWT event behind it).
 * Compose desktop's key codes are AWT virtual key codes and its [androidx.compose.ui.input.key.Key] values carry the
 * key's location (left / right modifier, numpad). A typed character arrives as a [KeyEventType.Unknown] event with its
 * text in [utf16CodePoint]. Null for a typed event with no single UTF-16 character.
 */
fun awtKeyOf(event: KeyEvent): AwtKeyTuple? {
    val mods = (if (event.isShiftPressed) AwtKeyEvent.SHIFT_DOWN_MASK else 0) or
        (if (event.isCtrlPressed) AwtKeyEvent.CTRL_DOWN_MASK else 0) or
        (if (event.isAltPressed) AwtKeyEvent.ALT_DOWN_MASK else 0) or
        (if (event.isMetaPressed) AwtKeyEvent.META_DOWN_MASK else 0)
    val codePoint = event.utf16CodePoint
    val char = if (codePoint in 1..0xFFFF) codePoint.toChar() else null
    val id = when (event.type) {
        KeyEventType.KeyDown -> AwtKeyEvent.KEY_PRESSED
        KeyEventType.KeyUp -> AwtKeyEvent.KEY_RELEASED
        else -> {
            // As AWT's KEY_TYPED: no key code, no location.
            return char?.let { AwtKeyTuple(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, it, mods, AwtKeyEvent.KEY_LOCATION_UNKNOWN) }
        }
    }
    val location = when (val l = event.key.nativeKeyLocation) {
        AwtKeyEvent.KEY_LOCATION_LEFT, AwtKeyEvent.KEY_LOCATION_RIGHT, AwtKeyEvent.KEY_LOCATION_NUMPAD -> l
        else -> AwtKeyEvent.KEY_LOCATION_STANDARD
    }
    return AwtKeyTuple(id, event.key.nativeKeyCode, char ?: AwtKeyEvent.CHAR_UNDEFINED, mods, location)
}

/**
 * The mirror window's device keys, called from the window's `onPreviewKeyEvent` (it sees only its own window's keys)
 * and its focus events. A key goes, in order: to the quick actions sheet when it has focus ([keyTarget]: Compose gets
 * it), Esc to close the open sheet, the zoom shortcuts (never forwarded), then unless [noControl]: capture keys
 * ([captureKey]), and unless [keyboardDisabled]: Cmd+V (Ctrl+V off macOS) pastes the computer's clipboard on the
 * device, the [hidKeyboard] when there is one (always consumed: HID keys never become text), else the [mapper]
 * (consumed when it produced messages).
 *
 * Paste reads the clipboard in a coroutine on [scope] and sends when the text arrives; the V press is consumed at
 * once, and V and typed characters are swallowed until V is released.
 */
class MirrorKeys(
    private val mapper: InputMapper,
    private val hidKeyboard: HidKeyboard?,
    private val send: (List<ControlMessage>) -> Unit,
    private val clipboardText: suspend () -> String?,
    private val scope: CoroutineScope,
    private val sheetOpen: () -> Boolean,
    private val sheetFocused: () -> Boolean,
    private val closeSheet: () -> Unit,
    private val applyZoom: (ZoomCommand) -> Unit,
    /** A capture key (`WindowPointerCapture.key`): true when consumed. */
    private val captureKey: (id: Int, keyCode: Int, location: Int) -> Boolean = { _, _, _ -> false },
    /** Tells the pointer capture about focus (`focusGained` / `focusLost`). */
    private val captureFocus: (Boolean) -> Unit = {},
    private val mac: Boolean = Os.isMac,
    private val noControl: Boolean = false,
    private val keyboardDisabled: Boolean = false,
    /** `legacy-paste`: the text is typed (`InjectText` in 100-char chunks) instead of set as the clipboard. */
    private val legacyPaste: Boolean = false,
    /**
     * Following windows' typing: plain `InjectKeycode`/`InjectText` (Mixed mode, never UHID) for every key the window
     * handles as device input, the paste, and focus-loss key releases. Zoom, capture and sheet keys never reach it.
     */
    private val followerSend: (List<ControlMessage>) -> Unit = {},
) {
    /** Keys only (no geometry needed); its own state, as [InputMapper] is stateful and not thread-safe. */
    private val followerMapper = InputMapper(frameSize = { null }, viewRect = { Rect.Zero }, keyMode = KeyInjectMode.Mixed)
    private val zoomKeys = ZoomKeyFilter(mac)
    private val pasteMask = if (mac) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
    private var pasteDown = false

    /** True when the window consumed [event] (converted with [awtKeyOf]: Tao has no AWT event behind it). */
    fun onKey(event: KeyEvent): Boolean {
        val key = awtKeyOf(event) ?: return false
        return onKey(key)
    }

    private fun onKey(ev: AwtKeyTuple): Boolean {
        when (keyTarget(sheetOpen(), sheetFocused(), ev.keyCode)) {
            // Typed into the quick actions sheet: Compose gets every key, the device none (keyTarget).
            KeyTarget.Sheet -> return false
            KeyTarget.Window -> {
                if (ev.id == AwtKeyEvent.KEY_PRESSED) closeSheet()
                return true
            }
            KeyTarget.Device -> Unit
        }
        // Zoom shortcuts belong to the window: never forwarded to the device (any keyboard mode).
        if (zoomKeys.handle(ev.id, ev.keyCode, ev.modifiersEx, applyZoom)) return true
        if (noControl) return false
        if (captureKey(ev.id, ev.keyCode, ev.location)) return true
        if (keyboardDisabled) return false
        if (ev.keyCode == AwtKeyEvent.VK_V && ev.modifiersEx and pasteMask != 0 && ev.id == AwtKeyEvent.KEY_PRESSED) {
            pasteDown = true
            // The clipboard read is suspending: the press is consumed now, the text is sent when it arrives.
            scope.launch {
                val text = clipboardText()
                if (!text.isNullOrEmpty()) {
                    val msgs = if (legacyPaste) text.chunked(100).map { ControlMessage.InjectText(it) } else listOf(ControlMessage.SetClipboard(0, paste = true, text = text))
                    send(msgs)
                    followerSend(msgs)
                }
            }
            return true
        }
        if (pasteDown && (ev.keyCode == AwtKeyEvent.VK_V || ev.id == AwtKeyEvent.KEY_TYPED)) {
            if (ev.id == AwtKeyEvent.KEY_RELEASED) pasteDown = false
            return true
        }
        // Followers always get plain keys, even when this window types through UHID.
        val plain = followerMapper.awtKey(ev.id, ev.keyCode, ev.keyChar, ev.modifiersEx, ev.location)
        if (plain.isNotEmpty()) followerSend(plain)
        if (hidKeyboard != null) {
            send(hidKeyboard.awtKey(ev.id, ev.keyCode, ev.location))
            return true // HID keys never become text
        }
        val msgs = mapper.awtKey(ev.id, ev.keyCode, ev.keyChar, ev.modifiersEx, ev.location)
        send(msgs)
        return msgs.isNotEmpty()
    }

    /**
     * Every focus event of the window, gains and losses alike (not a merged state: a quick loss and gain must still
     * release). On a loss, releases that happen in another window never arrive: release what the device holds.
     */
    fun onFocusChanged(focused: Boolean) {
        if (focused) {
            captureFocus(true)
        } else {
            zoomKeys.reset()
            captureFocus(false)
            hidKeyboard?.let { send(it.releaseAll()) }
            send(mapper.releaseButtons())
            followerSend(followerMapper.releaseKey())
        }
    }

    /** Focus entered the quick actions sheet: keys held for the device would never see their release. */
    fun onSheetFocusGained() {
        hidKeyboard?.let { send(it.releaseAll()) }
    }
}
