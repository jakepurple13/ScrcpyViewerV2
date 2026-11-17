package com.programmersbox.scrcpyviewer.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaneWidthLimitsTest {
    @Test
    fun `drag deltas pass through before the first layout`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        assertEquals(37f, limits.consume(37f))
    }

    @Test
    fun `drag deltas are clamped to the min and max list width`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(300)
        assertEquals(-50f, limits.consume(-50f)) // 250
        assertEquals(-50f, limits.consume(-80f)) // clamped at 200
        assertEquals(0f, limits.consume(-10f))
        assertEquals(30f, limits.consume(30f)) // 230
        assertEquals(370f, limits.consume(1000f)) // clamped at 600
        assertEquals(0f, limits.consume(5f))
    }

    @Test
    fun `a new layout resyncs the tracked width`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(300)
        limits.consume(100f)
        limits.onFirstPaneMeasured(590)
        assertEquals(10f, limits.consume(50f))
    }

    @Test
    fun `when min exceeds max the min wins`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 300f, maxFirst = 250f)
        limits.onFirstPaneMeasured(300)
        assertEquals(0f, limits.consume(-40f))
        assertEquals(0f, limits.consume(40f))
    }

    @Test
    fun `reset forgets the laid out width`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(700)
        assertEquals(700, limits.laidOutFirstWidth)
        limits.reset()
        assertNull(limits.laidOutFirstWidth)
        assertNull(limits.stepped(24f))
        assertEquals(50f, limits.consume(50f))
    }

    @Test
    fun `keyboard steps stay within the limits`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(300)
        assertEquals(276, limits.stepped(-24f))
        assertEquals(324, limits.stepped(24f))
        limits.onFirstPaneMeasured(210)
        assertEquals(200, limits.stepped(-24f))
        limits.onFirstPaneMeasured(590)
        assertEquals(600, limits.stepped(24f))
    }
}
