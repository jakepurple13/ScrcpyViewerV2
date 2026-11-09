package com.programmersbox.scrcpyviewer.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionExtrasTest {
    @Test
    fun `stagger delay grows per index`() {
        assertEquals(0, staggerDelayMillis(0))
        assertEquals(45, staggerDelayMillis(1))
        assertEquals(135, staggerDelayMillis(3))
    }

    @Test
    fun `stagger delay is capped so long lists fill in quickly`() {
        val last = staggerDelayMillis(STAGGER_MAX_STEPS)
        assertEquals(STAGGER_MAX_STEPS * STAGGER_STEP_MILLIS, last)
        assertEquals(last, staggerDelayMillis(STAGGER_MAX_STEPS + 1))
        assertEquals(last, staggerDelayMillis(500))
    }

    @Test
    fun `stagger delay ignores a negative index`() = assertEquals(0, staggerDelayMillis(-3))
}
