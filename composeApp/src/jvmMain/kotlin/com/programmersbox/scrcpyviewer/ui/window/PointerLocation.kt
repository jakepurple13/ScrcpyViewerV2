package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.math.roundToInt

/**
 * A pointer location as [pointerLocation] reads it. [bottomLeft]: a raw Cocoa point (origin at the bottom-left of the
 * main screen), read without touching NSScreen so it is safe off the main thread; [inWindowUnits] flips it. Otherwise
 * it is already in window units (top-left origin, like [ScreenArea]).
 */
data class PointerPoint(val x: Int, val y: Int, val bottomLeft: Boolean = false) {
    /**
     * [x], [y] in window units. A Cocoa point is flipped by the main screen's height ([screens]' first, as
     * [NativeScreenGeometry] lists them); null when that is unknown.
     */
    fun inWindowUnits(screens: List<ScreenArea>): IntArray? {
        if (!bottomLeft) return intArrayOf(x, y)
        val main = screens.firstOrNull() ?: return null
        return intArrayOf(x, main.bounds.y + main.bounds.height - y)
    }
}

/**
 * The mouse pointer; null on Linux or on any failure. No AWT. Any thread: on macOS it reads only
 * `NSEvent.mouseLocation` and returns the raw Cocoa point (see [PointerPoint.inWindowUnits]).
 */
fun pointerLocation(): PointerPoint? = try {
    when {
        Os.isMac -> {
            val p = ObjC.msgPoint(ObjC.cls("NSEvent"), "mouseLocation")
            PointerPoint(p[0].roundToInt(), p[1].roundToInt(), bottomLeft = true)
        }
        Os.isWindows -> {
            val pt = WinPoint()
            if (cursorUser32.GetCursorPos(pt) == 0) {
                null
            } else {
                val byValue = WinPointByValue().apply { x = pt.x; y = pt.y }
                val scale = NativeScreenGeometry.monitorScale(cursorUser32.MonitorFromPoint(byValue, MONITOR_DEFAULTTONEAREST))
                PointerPoint((pt.x / scale).roundToInt(), (pt.y / scale).roundToInt())
            }
        }
        else -> null
    }
} catch (_: Throwable) {
    null
}

private const val MONITOR_DEFAULTTONEAREST = 2

private val cursorUser32: CursorUser32 by lazy { Native.load("user32", CursorUser32::class.java) }

/** POINT, passed by reference (LPPOINT) to GetCursorPos. */
@Structure.FieldOrder("x", "y")
internal open class WinPoint : Structure() {
    @JvmField var x = 0
    @JvmField var y = 0
}

/** POINT passed by value, as MonitorFromPoint takes it. */
internal class WinPointByValue : WinPoint(), Structure.ByValue

internal interface CursorUser32 : Library {
    fun GetCursorPos(pt: WinPoint): Int
    fun MonitorFromPoint(pt: WinPointByValue, flags: Int): Pointer
}
