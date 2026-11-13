package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.IconUnfold
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.Off
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.ScreenOn
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Launch
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Quit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchMotionTest {
    private val full = FractionRect(0f, 0f, 1f, 1f)
    private fun near(expected: Float, actual: Float, tolerance: Float = 0.001f, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected ± $tolerance, was $actual")

    @Test
    fun durations() {
        assertEquals(720, LaunchMotion.durationMs(IconUnfold, Launch))
        assertEquals(650, LaunchMotion.durationMs(ScreenOn, Launch))
        assertEquals(420, LaunchMotion.durationMs(IconUnfold, Quit))
        assertEquals(380, LaunchMotion.durationMs(ScreenOn, Quit))
        assertFailsWith<IllegalArgumentException> { LaunchMotion.durationMs(Off, Launch) }
    }

    @Test
    fun `icon unfold launch keyframes`() {
        LaunchMotion.frame(IconUnfold, Launch, 0).let {
            near(0.6f, it.iconScale, what = "icon scale at 0")
            near(0f, it.iconAlpha, what = "icon alpha at 0")
            near(0f, it.windowAlpha, what = "window alpha at 0")
            near(0f, it.backPhoneProgress, what = "back phone at 0")
        }
        LaunchMotion.frame(IconUnfold, Launch, 260).let {
            near(1f, it.iconScale, what = "icon scale at 260")
            near(1f, it.iconAlpha, what = "icon alpha at 260")
        }
        assertEquals(LaunchMotion.PhoneScreen, LaunchMotion.frame(IconUnfold, Launch, 360).windowClip)
        LaunchMotion.frame(IconUnfold, Launch, 700).let {
            assertEquals(full, it.windowClip)
            near(1f, it.windowAlpha, what = "window alpha at 700")
            near(0f, it.iconAlpha, what = "icon alpha at 700")
            assertFalse(it.showRealWindow)
        }
        assertTrue(LaunchMotion.frame(IconUnfold, Launch, 720).showRealWindow)
    }

    @Test
    fun `icon unfold quit keyframes`() {
        LaunchMotion.frame(IconUnfold, Quit, 0).let {
            assertEquals(full, it.windowClip)
            near(1f, it.windowAlpha, what = "window alpha at 0")
        }
        LaunchMotion.frame(IconUnfold, Quit, 260).let {
            assertEquals(LaunchMotion.PhoneScreen, it.windowClip)
            near(0f, it.windowAlpha, what = "window alpha at 260")
        }
        LaunchMotion.frame(IconUnfold, Quit, 420).let {
            near(0f, it.iconAlpha, what = "icon alpha at 420")
            assertTrue(it.iconScale <= 0.25f, "icon scale at 420 was ${it.iconScale}")
            assertTrue(it.iconOffsetY > 0f, "icon should move down, offset ${it.iconOffsetY}")
        }
    }

    @Test
    fun `screen on launch keyframes`() {
        LaunchMotion.frame(ScreenOn, Launch, 0).let {
            assertEquals(0f, it.beamY)
            near(0f, it.windowClip.bottom, what = "clip bottom at 0")
        }
        LaunchMotion.frame(ScreenOn, Launch, 520).let {
            assertEquals(1f, it.beamY)
            near(1f, it.windowClip.bottom, what = "clip bottom at 520")
        }
        LaunchMotion.frame(ScreenOn, Launch, 650).let {
            near(0f, it.beamAlpha, what = "beam alpha at 650")
            near(1f, it.windowBrightness, what = "brightness at 650")
            assertTrue(it.showRealWindow)
        }
    }

    @Test
    fun `screen on quit keyframes`() {
        LaunchMotion.frame(ScreenOn, Quit, 220).let {
            near(0.012f, it.windowScaleY, tolerance = 0.002f, what = "scaleY at 220")
            near(1f, it.windowScaleX, what = "scaleX at 220")
            assertTrue(it.windowBrightness >= 2.5f, "brightness at 220 was ${it.windowBrightness}")
        }
        near(0.02f, LaunchMotion.frame(ScreenOn, Quit, 320).windowScaleX, tolerance = 0.005f, what = "scaleX at 320")
        near(0f, LaunchMotion.frame(ScreenOn, Quit, 380).windowAlpha, what = "alpha at 380")
    }

    @Test
    fun `times past the end clamp to the last frame`() {
        for (anim in listOf(IconUnfold, ScreenOn)) for (phase in LaunchPhase.entries) {
            assertEquals(
                LaunchMotion.frame(anim, phase, LaunchMotion.durationMs(anim, phase)),
                LaunchMotion.frame(anim, phase, 10_000),
                "$anim $phase",
            )
        }
    }
}
