package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import java.awt.event.KeyEvent as AwtKeyEvent

/** Who handles a key event in the mirror window. */
enum class KeyTarget {
    /** The quick actions sheet (its fields and prompts): never forwarded to the device, never a window shortcut. */
    Sheet,

    /** The window itself: Esc closes the open sheet. */
    Window,

    /** The device, through the zoom, paste, capture and keyboard paths as before. */
    Device,
}

/**
 * Where a key goes. While keyboard focus is in the open quick actions sheet ([sheetFocused]) every key stays there:
 * Backspace, Delete, Enter, arrows, Tab and shortcuts edit the field, Cmd/Ctrl+V pastes into it, zoom shortcuts do not
 * fire, and Esc goes to the field first (the sheet closes on an Esc nothing in it used). With focus elsewhere, Esc
 * closes an open sheet and every other key goes to the device.
 */
fun keyTarget(sheetOpen: Boolean, sheetFocused: Boolean, keyCode: Int): KeyTarget = when {
    sheetOpen && sheetFocused -> KeyTarget.Sheet
    sheetOpen && keyCode == AwtKeyEvent.VK_ESCAPE -> KeyTarget.Window
    else -> KeyTarget.Device
}

/**
 * Whether keyboard focus is in the quick actions sheet. The sheet and the dialogs it opens (drawn as separate focus
 * trees) each report their focus as a holder; the sheet is focused while any holder is. Written by Compose and read by
 * the window's key dispatcher, both on the Swing thread.
 */
class SheetFocus {
    /** Called when focus enters the sheet from outside: keys the device still holds should be released. */
    var onGained: () -> Unit = {}

    private val holders = HashSet<Any>()

    val focused: Boolean get() = holders.isNotEmpty()

    fun update(holder: Any, hasFocus: Boolean) {
        val was = focused
        if (hasFocus) holders += holder else holders -= holder
        if (!was && focused) onGained()
    }
}

/** The mirror window's [SheetFocus]; null outside a mirror window (offscreen renders, the Actions page). */
val LocalSheetFocus = staticCompositionLocalOf<SheetFocus?> { null }

/** Reports focus in this node or its children to [LocalSheetFocus] until it leaves the composition. */
@Composable
fun Modifier.reportsSheetFocus(): Modifier {
    val focus = LocalSheetFocus.current ?: return this
    val holder = remember { Any() }
    DisposableEffect(focus) { onDispose { focus.update(holder, false) } }
    return onFocusChanged { focus.update(holder, it.hasFocus) }
}
