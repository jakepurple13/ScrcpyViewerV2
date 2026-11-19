package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.math.roundToInt

/** The mouse pointer as [x, y] in window units (top-left origin, like [ScreenArea]); null on Linux or on any failure. No AWT. */
fun pointerLocation(): IntArray? = try {
    when {
        Os.isMac -> {
            val p = ObjC.msgPoint(ObjC.cls("NSEvent"), "mouseLocation")
            val main = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSScreen"), "screens")!!, "objectAtIndex:", 0L)!!
            val mainHeight = ObjC.msgRect(main, "frame")[3]
            intArrayOf(p[0].roundToInt(), (mainHeight - p[1]).roundToInt())
        }
        Os.isWindows -> {
            val user32 = Native.load("user32", CursorUser32::class.java)
            val pt = WinPoint()
            if (user32.GetCursorPos(pt) == 0) {
                null
            } else {
                val scale = NativeScreenGeometry.monitorScale(user32.MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST))
                intArrayOf((pt.x / scale).roundToInt(), (pt.y / scale).roundToInt())
            }
        }
        else -> null
    }
} catch (_: Throwable) {
    null
}

private const val MONITOR_DEFAULTTONEAREST = 2

@Structure.FieldOrder("x", "y")
class WinPoint : Structure(), Structure.ByValue {
    @JvmField var x = 0
    @JvmField var y = 0
}

interface CursorUser32 : Library {
    fun GetCursorPos(pt: WinPoint): Int
    fun MonitorFromPoint(pt: WinPoint, flags: Int): Pointer
}
