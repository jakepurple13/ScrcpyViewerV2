package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.IconUnfold
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.Off
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.ScreenOn
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Launch
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Quit
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchPreviewTest {
    @Test
    fun `one loop is launch, a hold, quit, then a gap`() {
        assertEquals(720 + 700 + 420 + 450, LaunchPreview.loopMs(IconUnfold))
        assertEquals(650 + 700 + 380 + 450, LaunchPreview.loopMs(ScreenOn))
    }

    @Test
    fun `the loop plays the launch, holds the open window, then plays the quit`() {
        assertEquals(LaunchMotion.frame(ScreenOn, Launch, 0), LaunchPreview.frame(ScreenOn, 0))
        assertEquals(LaunchMotion.frame(ScreenOn, Launch, 300), LaunchPreview.frame(ScreenOn, 300))
        assertEquals(LaunchPreview.resting(ScreenOn), LaunchPreview.frame(ScreenOn, 650 + 350))
        assertEquals(LaunchMotion.frame(ScreenOn, Quit, 100), LaunchPreview.frame(ScreenOn, 650 + 700 + 100))
        // The gap after the quit shows the window gone.
        assertEquals(LaunchMotion.frame(ScreenOn, Quit, 380), LaunchPreview.frame(ScreenOn, 650 + 700 + 380 + 200))
    }

    @Test
    fun `the loop repeats`() {
        val loop = LaunchPreview.loopMs(IconUnfold)
        assertEquals(LaunchPreview.frame(IconUnfold, 123), LaunchPreview.frame(IconUnfold, loop + 123))
    }

    @Test
    fun `at rest the tile shows the open window`() {
        assertEquals(LaunchMotion.frame(IconUnfold, Launch, 720), LaunchPreview.resting(IconUnfold))
        assertEquals(LaunchMotion.frame(IconUnfold, Launch, 720), LaunchPreview.resting(Off))
    }

    @Test
    fun `off blinks the window away and back`() {
        assertEquals(1f, LaunchPreview.frame(Off, 100).windowAlpha)
        assertEquals(0f, LaunchPreview.frame(Off, LaunchPreview.OFF_SHOWN_MS + 50).windowAlpha)
        assertEquals(1f, LaunchPreview.frame(Off, LaunchPreview.loopMs(Off) + 100).windowAlpha)
    }
}
