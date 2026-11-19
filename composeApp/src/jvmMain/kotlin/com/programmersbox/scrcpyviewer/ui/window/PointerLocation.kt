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
            val pt = WinPoint()
            if (cursorUser32.GetCursorPos(pt) == 0) {
                null
            } else {
                val byValue = WinPointByValue().apply { x = pt.x; y = pt.y }
                val scale = NativeScreenGeometry.monitorScale(cursorUser32.MonitorFromPoint(byValue, MONITOR_DEFAULTTONEAREST))
                intArrayOf((pt.x / scale).roundToInt(), (pt.y / scale).roundToInt())
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
