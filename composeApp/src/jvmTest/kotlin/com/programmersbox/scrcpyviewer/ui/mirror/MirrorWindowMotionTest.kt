package com.programmersbox.scrcpyviewer.ui.mirror

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorWindowMotionTest {
    @Test
    fun `bounds interpolate from the button to the window`() {
        val from = Rectangle(100, 200, 120, 40)
        val to = Rectangle(500, 100, 480, 900)
        assertEquals(from, lerpBounds(from, to, 0f))
        assertEquals(to, lerpBounds(from, to, 1f))
        assertEquals(Rectangle(300, 150, 300, 470), lerpBounds(from, to, 0.5f))
    }

    @Test
    fun `target is centered on the screen unless the options place it`() {
        val screen = Rectangle(0, 25, 1440, 875)
        assertEquals(Rectangle(480, 112, 480, 700), zoomTarget(null, null, 480, 700, screen))
        assertEquals(Rectangle(40, 60, 480, 700), zoomTarget(40, 60, 480, 700, screen))
    }

    @Test
    fun `target never outgrows the screen`() {
        val screen = Rectangle(0, 25, 1440, 875)
        assertEquals(Rectangle(480, 25, 480, 875), zoomTarget(null, null, 480, 900, screen))
    }

    @Test
    fun `screen is hidden asleep, a widening bright line, then opens and dims`() {
        val off = wakeFrame(0f)
        assertFalse(off.visible)
        val line = wakeFrame(0.2f)
        assertTrue(line.visible)
        assertEquals(WAKE_LINE, line.scaleY)
        assertTrue(line.scaleX > 0.6f && line.scaleX < 1f)
        assertEquals(1f, line.flash)
        val half = wakeFrame(0.675f)
        assertEquals(1f, half.scaleX)
        assertTrue(half.scaleY > 0.4f && half.scaleY < 0.6f)
        assertEquals(0.5f, half.flash, 0.001f)
        assertEquals(WakeFrame(1f, 1f, 0f, visible = true), wakeFrame(1f))
    }

    @Test
    fun `an origin is taken once and only while fresh`() {
        val button = Rectangle(10, 20, 100, 36)
        MirrorOrigins.record(button, nowMs = 1_000)
        assertEquals(button, MirrorOrigins.take(nowMs = 1_400))
        assertNull(MirrorOrigins.take(nowMs = 1_400))
        MirrorOrigins.record(button, nowMs = 1_000)
        assertNull(MirrorOrigins.take(nowMs = 5_000))
        assertNull(MirrorOrigins.take(nowMs = 5_000))
    }
}
