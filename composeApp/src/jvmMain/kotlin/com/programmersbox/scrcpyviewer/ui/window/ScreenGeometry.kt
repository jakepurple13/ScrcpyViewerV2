package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/** One screen, in window units (AWT user space: points on macOS): its full [bounds] and its [workArea] (less menu bar, Dock, taskbar). */
data class ScreenArea(val bounds: Rectangle, val workArea: Rectangle)

/** Where the screens are. [screens] is empty when the platform cannot say. */
interface ScreenGeometry {
    fun screens(): List<ScreenArea>
}

/** The screen [window] mostly lies on (largest overlap), or the nearest one when it overlaps none; null with no screens. */
fun screenFor(window: Rectangle, screens: List<ScreenArea>): ScreenArea? {
    if (screens.isEmpty()) return null
    fun overlap(s: ScreenArea): Long {
        val i = window.intersection(s.bounds)
        return if (i.isEmpty) 0L else i.width.toLong() * i.height
    }
    screens.maxByOrNull(::overlap)?.takeIf { overlap(it) > 0 }?.let { return it }
    // Squared gap between the rectangles, per axis, 0 where they overlap on that axis.
    fun distance(s: ScreenArea): Long {
        val b = s.bounds
        val dx = maxOf(b.x - (window.x + window.width), window.x - (b.x + b.width), 0).toLong()
        val dy = maxOf(b.y - (window.y + window.height), window.y - (b.y + b.height), 0).toLong()
        return dx * dx + dy * dy
    }
    return screens.minByOrNull(::distance)
}

/** A Cocoa rect (origin bottom-left of the main screen, which is [mainHeight] tall) as an AWT rect (origin top-left). */
fun flipMacRect(x: Double, y: Double, w: Double, h: Double, mainHeight: Double): Rectangle =
    Rectangle(x.roundToInt(), (mainHeight - (y + h)).roundToInt(), w.roundToInt(), h.roundToInt())

/** The screens through AWT; kept until the AWT windows are gone. */
object AwtScreenGeometry : ScreenGeometry {
    override fun screens(): List<ScreenArea> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val config = it.defaultConfiguration
            val b = config.bounds
            val i = Toolkit.getDefaultToolkit().getScreenInsets(config)
            ScreenArea(Rectangle(b), Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom))
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * The screens from the OS, without AWT: NSScreen on macOS, EnumDisplayMonitors on Windows. Elsewhere (Linux) empty:
 * callers fall back to a default. Never throws.
 */
object NativeScreenGeometry : ScreenGeometry {
    override fun screens(): List<ScreenArea> = try {
        when {
            Os.isMac -> macScreens()
            Os.isWindows -> windowsScreens()
            else -> emptyList()
        }
    } catch (t: Throwable) {
        System.err.println("NativeScreenGeometry failed: $t")
        emptyList()
    }

    private fun macScreens(): List<ScreenArea> {
        val list = ObjC.msgPtr(ObjC.cls("NSScreen"), "screens") ?: return emptyList()
        val count = ObjC.msgLong(list, "count").toInt()
        if (count == 0) return emptyList()
        val frames = (0 until count).map { i ->
            val screen = ObjC.msgPtr(list, "objectAtIndex:", i.toLong()) ?: return emptyList()
            ObjC.msgRect(screen, "frame") to ObjC.msgRect(screen, "visibleFrame")
        }
        val mainHeight = frames[0].first[3] // NSScreen.screens[0] is the main screen
        fun flip(r: DoubleArray) = flipMacRect(r[0], r[1], r[2], r[3], mainHeight)
        return frames.map { (frame, visible) -> ScreenArea(flip(frame), flip(visible)) }
    }

    private fun windowsScreens(): List<ScreenArea> {
        val user32 = Native.load("user32", User32::class.java)
        val out = ArrayList<ScreenArea>()
        val callback = object : MonitorEnumProc {
            override fun invoke(monitor: Pointer?, dc: Pointer?, rect: Pointer?, data: Pointer?): Int {
                val info = MonitorInfo().apply { cbSize = size() }
                if (monitor != null && user32.GetMonitorInfoW(monitor, info) != 0) {
                    // The primary monitor first, like the other platforms' main screen.
                    val area = ScreenArea(info.monitor.toRectangle(), info.work.toRectangle())
                    if (info.flags and MONITORINFOF_PRIMARY != 0) out.add(0, area) else out.add(area)
                }
                return 1
            }
        }
        user32.EnumDisplayMonitors(null, null, callback, null)
        return out
    }

    private const val MONITORINFOF_PRIMARY = 1

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class WinRect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
        fun toRectangle() = Rectangle(left, top, right - left, bottom - top)
    }

    @Structure.FieldOrder("cbSize", "monitor", "work", "flags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 0
        @JvmField var monitor = WinRect()
        @JvmField var work = WinRect()
        @JvmField var flags = 0
    }

    fun interface MonitorEnumProc : Callback {
        fun invoke(monitor: Pointer?, dc: Pointer?, rect: Pointer?, data: Pointer?): Int
    }

    interface User32 : Library {
        fun EnumDisplayMonitors(dc: Pointer?, clip: Pointer?, proc: MonitorEnumProc, data: Pointer?): Int
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Int
    }
}
