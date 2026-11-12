package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.math.abs
import kotlin.math.roundToInt

/** Moves and hides the system pointer, for the pointer capture. Positions are screen coordinates in window units. */
interface CursorControl {
    /** Moves the pointer to [screenX], [screenY]; false when the platform cannot (Wayland) or the call failed. */
    fun warp(screenX: Int, screenY: Int): Boolean

    /** Hides or shows the pointer. Calls are idempotent: hiding twice needs one show. */
    fun setHidden(hidden: Boolean)
}

/**
 * [CursorControl] through the platform's own calls, without `java.awt.Robot` or an AWT cursor:
 * - macOS: `CGWarpMouseCursorPosition` then `CGAssociateMouseAndMouseCursorPosition(1)` (no lag after the warp),
 *   `NSCursor hide` / `unhide` on the main thread.
 * - Windows: `SetCursorPos` (scaled by the system DPI to physical pixels) and `ShowCursor`.
 * - Linux X11: `XWarpPointer` on the root window, `XFixesHideCursor` / `XFixesShowCursor` where libXfixes exists.
 * - Wayland, or any failure: [warp] is false and the capture stays off.
 */
object NativeCursorControl : CursorControl {
    private var hidden = false

    override fun warp(screenX: Int, screenY: Int): Boolean = try {
        when {
            Os.isMac -> Mac.warp(screenX, screenY)
            Os.isWindows -> Win.warp(screenX, screenY)
            else -> X11.warp(screenX, screenY)
        }
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    override fun setHidden(hidden: Boolean) {
        if (this.hidden == hidden) return
        this.hidden = hidden
        try {
            when {
                Os.isMac -> Mac.setHidden(hidden)
                Os.isWindows -> Win.setHidden(hidden)
                else -> X11.setHidden(hidden)
            }
        } catch (_: Throwable) {
            // The pointer stays visible: capture still works.
        }
    }

    private object Mac {
        @Structure.FieldOrder("x", "y")
        class CGPoint(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0) : Structure(), Structure.ByValue

        private val cg = NativeLibrary.getInstance("CoreGraphics")
        private val warpFn: Function = cg.getFunction("CGWarpMouseCursorPosition")
        private val associateFn: Function = cg.getFunction("CGAssociateMouseAndMouseCursorPosition")

        // CoreGraphics' global display space: points from the main display's top-left, as AWT's screen coordinates.
        fun warp(x: Int, y: Int): Boolean {
            if (warpFn.invokeInt(arrayOf(CGPoint(x.toDouble(), y.toDouble()))) != 0) return false
            associateFn.invokeInt(arrayOf(1))
            return true
        }

        fun setHidden(hidden: Boolean) {
            ObjC.onMain { ObjC.msgVoid(ObjC.cls("NSCursor"), if (hidden) "hide" else "unhide") }
        }
    }

    private object Win {
        private val user32 = NativeLibrary.getInstance("user32")

        // Java is DPI aware: window units are the physical pixels divided by the display scale.
        private val scale: Double by lazy {
            runCatching { user32.getFunction("GetDpiForSystem").invokeInt(emptyArray()) / 96.0 }.getOrDefault(1.0)
        }

        fun warp(x: Int, y: Int): Boolean =
            user32.getFunction("SetCursorPos").invokeInt(arrayOf((x * scale).roundToInt(), (y * scale).roundToInt())) != 0

        fun setHidden(hidden: Boolean) {
            user32.getFunction("ShowCursor").invokeInt(arrayOf(if (hidden) 0 else 1))
        }
    }

    private object X11 {
        private val wayland: Boolean =
            System.getenv("WAYLAND_DISPLAY") != null || System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)

        private val x11: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("X11") }.getOrNull() }
        private val xfixes: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("Xfixes") }.getOrNull() }

        private val display: Pointer? by lazy { x11?.getFunction("XOpenDisplay")?.invokePointer(arrayOf<Any?>(null)) }

        private fun root(lib: NativeLibrary, d: Pointer): Long = lib.getFunction("XDefaultRootWindow").invokeLong(arrayOf(d))

        fun warp(x: Int, y: Int): Boolean {
            // XWayland ignores warps of a pointer it does not own.
            if (wayland) return false
            val lib = x11 ?: return false
            val d = display ?: return false
            lib.getFunction("XWarpPointer").invokeInt(arrayOf(d, 0L, root(lib, d), 0, 0, 0, 0, x, y))
            lib.getFunction("XFlush").invokeInt(arrayOf(d))
            return true
        }

        fun setHidden(hidden: Boolean) {
            if (wayland) return
            val lib = x11 ?: return
            val fixes = xfixes ?: return
            val d = display ?: return
            fixes.getFunction(if (hidden) "XFixesHideCursor" else "XFixesShowCursor").invokeVoid(arrayOf(d, root(lib, d)))
            lib.getFunction("XFlush").invokeInt(arrayOf(d))
        }
    }
}

/**
 * The screen position (window units) of [local], a position in the window's content in pixels, for content that
 * starts at [contentOrigin] on screen. [density] is pixels per window unit.
 */
fun screenPoint(contentOrigin: IntOffset, local: Offset, density: Float): IntOffset =
    IntOffset(contentOrigin.x + (local.x / density).roundToInt(), contentOrigin.y + (local.y / density).roundToInt())

/**
 * Counts consecutive presses as AWT's click count did: a press within [intervalMs] of the previous one and within
 * [slopPx] of it on each axis continues the count, any other press starts again at 1.
 */
class ClickCounter(private val intervalMs: Long = 500, private val slopPx: Float = 4f) {
    private var lastAt: Offset? = null
    private var lastMs = 0L
    private var count = 0

    /** The click count of a press at [at] (pixels) at [nowMs]. */
    fun press(at: Offset, nowMs: Long): Int {
        val prev = lastAt
        count = if (prev != null && nowMs - lastMs in 0..intervalMs && abs(at.x - prev.x) <= slopPx && abs(at.y - prev.y) <= slopPx) {
            count + 1
        } else {
            1
        }
        lastAt = at
        lastMs = nowMs
        return count
    }
}
