package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Rectangle

/**
 * Whether a mouse press just started outside the popup: a button is [down] now and was not on the last sample
 * ([wasDown]), with the [pointer] (window units) outside [bounds]. An unknown pointer or bounds never counts.
 */
internal fun outsideClickStarted(wasDown: Boolean, down: Boolean, pointer: IntArray?, bounds: Rectangle?): Boolean {
    if (!down || wasDown || pointer == null || bounds == null) return false
    return !bounds.contains(pointer[0], pointer[1])
}

/**
 * Whether any mouse button is held now, anywhere on screen (not only over the app's windows). No AWT. macOS:
 * `NSEvent.pressedMouseButtons`; Windows: `GetAsyncKeyState`. Always false on Linux or on any failure.
 */
internal fun mouseButtonDown(): Boolean = try {
    when {
        Os.isMac -> ObjC.msgLong(ObjC.cls("NSEvent"), "pressedMouseButtons") != 0L
        Os.isWindows -> PopupUser32.lib.let { u ->
            listOf(VK_LBUTTON, VK_RBUTTON, VK_MBUTTON).any { (u.GetAsyncKeyState(it).toInt() and 0x8000) != 0 }
        }
        else -> false
    }
} catch (_: Throwable) {
    false
}

/**
 * Windows: makes [hwnd] the foreground window, so the popup takes focus (and losing it closes the popup). A tray icon
 * click gives the app the right to do so. False when not on Windows or on failure.
 */
internal fun setForegroundWindow(hwnd: Long): Boolean = try {
    Os.isWindows && hwnd != 0L && PopupUser32.lib.SetForegroundWindow(Pointer(hwnd))
} catch (t: Throwable) {
    System.err.println("SetForegroundWindow failed: $t")
    false
}

private const val VK_LBUTTON = 0x01
private const val VK_RBUTTON = 0x02
private const val VK_MBUTTON = 0x04

private interface PopupUser32 : Library {
    fun SetForegroundWindow(hwnd: Pointer): Boolean
    fun GetAsyncKeyState(vKey: Int): Short

    companion object {
        val lib: PopupUser32 by lazy { Native.load("user32", PopupUser32::class.java) }
    }
}
