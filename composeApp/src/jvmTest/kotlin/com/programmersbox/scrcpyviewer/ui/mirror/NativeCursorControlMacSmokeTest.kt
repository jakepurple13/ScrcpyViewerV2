package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the macOS cursor calls for real: warp, read back through CoreGraphics, hide and show. It moves the real
 * pointer, so the build excludes it unless asked (and it is skipped off macOS):
 * `./gradlew :composeApp:jvmTest -PmacCursorSmoke --tests '*NativeCursorControlMacSmokeTest'`
 */
class NativeCursorControlMacSmokeTest {
    @Structure.FieldOrder("x", "y")
    class CGPoint(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0) : Structure(), Structure.ByValue

    private fun location(): CGPoint {
        val cg = NativeLibrary.getInstance("CoreGraphics")
        val create: Function = cg.getFunction("CGEventCreate")
        val get: Function = cg.getFunction("CGEventGetLocation")
        val release = NativeLibrary.getInstance("CoreFoundation").getFunction("CFRelease")
        val event = create.invokePointer(arrayOf<Any?>(null as Pointer?))
        try {
            return get.invoke(CGPoint::class.java, arrayOf<Any?>(event)) as CGPoint
        } finally {
            release.invokeVoid(arrayOf<Any?>(event))
        }
    }

    private fun awaitNear(x: Int, y: Int): CGPoint {
        var p = location()
        val deadline = System.currentTimeMillis() + 200
        while ((abs(p.x - x) > 1 || abs(p.y - y) > 1) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            p = location()
        }
        return p
    }

    @Test
    fun warpMovesTheRealCursor_andHideShowDoNotThrow() {
        assumeTrue("macOS-only smoke", Os.isMac)
        val start = location()
        println("cursor start: ${start.x}, ${start.y}")
        try {
            assertTrue(NativeCursorControl.warp(200, 200), "warp returned false")
            val at = awaitNear(200, 200)
            println("cursor after warp(200,200): ${at.x}, ${at.y}")
            assertTrue(abs(at.x - 200) <= 1 && abs(at.y - 200) <= 1, "read back ${at.x}, ${at.y}")

            // NSCursor runs on the main dispatch queue, which a bare test JVM may never drain: only no-throw is proven.
            NativeCursorControl.setHidden(true)
            NativeCursorControl.setHidden(false)
        } finally {
            NativeCursorControl.setHidden(false)
            NativeCursorControl.warp(start.x.toInt(), start.y.toInt())
        }
    }
}
