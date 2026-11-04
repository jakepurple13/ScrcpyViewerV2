package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AspectLockTest {
    private val portrait = IntSize(1080, 2400)
    private val landscape = IntSize(2400, 1080)

    @Test
    fun `a user window resize is corrected`() {
        assertTrue(aspectLockNeeded(windowResized = true, lastFrame = portrait, frame = portrait, wasFit = true, fit = true))
    }

    @Test
    fun `a rotation is corrected though the window kept its size`() {
        assertTrue(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = landscape, wasFit = true, fit = true))
    }

    @Test
    fun `a new frame size is corrected`() {
        assertTrue(aspectLockNeeded(false, lastFrame = portrait, frame = IntSize(720, 1600), wasFit = true, fit = true))
        assertTrue(aspectLockNeeded(false, lastFrame = null, frame = portrait, wasFit = true, fit = true))
    }

    @Test
    fun `turning Fit back on is corrected`() {
        assertTrue(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = portrait, wasFit = false, fit = true))
    }

    @Test
    fun `a stage resized only by the sheet's drag bar is left alone`() {
        assertFalse(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = portrait, wasFit = true, fit = true))
    }
}
