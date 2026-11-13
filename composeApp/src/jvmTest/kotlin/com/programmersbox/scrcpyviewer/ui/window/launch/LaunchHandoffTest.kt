package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchHandoff.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchHandoffTest {
    private val overlay = LaunchEffect.Overlay(LaunchQuitAnimation.IconUnfold)

    @Test
    fun `overlay launch hides the real window until the overlay ends`() {
        val h = LaunchHandoff(overlay)
        assertEquals(Step.Playing, h.step.value)
        assertFalse(h.realVisible.value)
        assertTrue(h.overlayOpen.value)

        h.overlayEnded()
        assertEquals(Step.ShowReal, h.step.value)
        assertTrue(h.realVisible.value)
        assertTrue(h.overlayOpen.value, "the overlay stays until the real window has drawn")

        h.realWindowDrew()
        assertEquals(Step.Done, h.step.value)
        assertFalse(h.overlayOpen.value)
        assertTrue(h.realVisible.value)
    }

    @Test
    fun `fade and none show the real window at once with no overlay`() {
        for (effect in listOf(LaunchEffect.Fade, LaunchEffect.None)) {
            val h = LaunchHandoff(effect)
            assertEquals(Step.Done, h.step.value, "$effect")
            assertTrue(h.realVisible.value, "$effect")
            assertFalse(h.overlayOpen.value, "$effect")
        }
    }

    @Test
    fun `cancel during playing shows the real window and closes the overlay`() {
        val h = LaunchHandoff(overlay)
        h.cancel()
        assertEquals(Step.Done, h.step.value)
        assertTrue(h.realVisible.value)
        assertFalse(h.overlayOpen.value)
    }

    @Test
    fun `realWindowDrew before overlayEnded is ignored`() {
        val h = LaunchHandoff(overlay)
        h.realWindowDrew()
        assertEquals(Step.Playing, h.step.value)
        assertFalse(h.realVisible.value)
        assertTrue(h.overlayOpen.value)
    }
}
