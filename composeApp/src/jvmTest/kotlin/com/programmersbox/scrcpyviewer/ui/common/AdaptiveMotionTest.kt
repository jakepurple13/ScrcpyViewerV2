package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.core.SpringSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdaptiveMotionTest {
    @Test
    fun switchDirectionForwardWhenTargetIsLater() = assertEquals(1, switchDirection(0, 2))

    @Test
    fun switchDirectionBackwardWhenTargetIsEarlier() = assertEquals(-1, switchDirection(3, 1))

    @Test
    fun switchDirectionForwardWhenSame() = assertEquals(1, switchDirection(2, 2))

    @Test
    fun layoutSpringIsSpringWithBounce() {
        val spec = layoutSpring<Float>()
        assertIs<SpringSpec<Float>>(spec)
        assertEquals(0.8f, spec.dampingRatio)
    }
}
