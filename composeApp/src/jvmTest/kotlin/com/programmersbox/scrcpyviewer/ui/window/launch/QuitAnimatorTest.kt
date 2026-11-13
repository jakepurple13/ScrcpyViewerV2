package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import dev.nucleusframework.application.NucleusWindowBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuitAnimatorTest {
    private val at = NucleusWindowBounds(10f, 20f, 800f, 600f)
    private val overlay = LaunchEffect.Overlay(LaunchQuitAnimation.ScreenOn)

    @Test
    fun `overlay keeps the window and dims it only while sessions are open`() {
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(overlay, sessionsOpen = true)
            assertTrue(keepsWindow)
            assertTrue(dimmed)
        }
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(overlay, sessionsOpen = false)
            assertTrue(keepsWindow)
            assertFalse(dimmed)
        }
    }

    @Test
    fun `play waits for the fade to finish`() = runBlocking<Unit> {
        val q = QuitAnimator(WindowPicture()) { at }
        q.begin(LaunchEffect.Fade, sessionsOpen = false)
        val playing = async { q.play() }
        kotlinx.coroutines.delay(100)
        assertFalse(playing.isCompleted, "exit must wait for the 150 ms fade")
        q.fadeFinished()
        withTimeout(1_000) { playing.await() }
    }

    @Test
    fun `play lets the scrim fade out before taking the picture`() = runBlocking<Unit> {
        val q = QuitAnimator(WindowPicture()) { at }
        q.begin(overlay, sessionsOpen = true)
        val started = System.nanoTime()
        val playing = async { q.play() }
        withTimeout(2_000) { while (q.playing == null) yield() }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs >= QUIT_SCRIM_EXIT_MILLIS, "picture taken after $waitedMs ms, scrim fades for $QUIT_SCRIM_EXIT_MILLIS ms")
        q.overlayEnded()
        withTimeout(1_000) { playing.await() }
    }

    @Test
    fun `fade and none keep today's flow`() {
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(LaunchEffect.Fade, sessionsOpen = true)
            assertFalse(keepsWindow)
            assertFalse(dimmed)
            assertFalse(faded, "the window waits for the fade")
            fadeFinished()
            assertTrue(faded)
        }
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(LaunchEffect.None, sessionsOpen = true)
            assertFalse(keepsWindow)
            assertTrue(faded)
        }
    }

    @Test
    fun `only the first begin counts`() {
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(overlay, sessionsOpen = false)
            begin(LaunchEffect.None, sessionsOpen = false)
            assertEquals(overlay, effect)
        }
    }

    @Test
    fun `play opens the overlay, undims, and returns when it ends`() = runBlocking<Unit> {
        val q = QuitAnimator(WindowPicture()) { at }
        q.begin(overlay, sessionsOpen = true)
        val playing = async { q.play() }
        withTimeout(2_000) { while (q.playing == null) yield() }
        assertFalse(q.dimmed)
        assertEquals(QuitAnimator.Play(at, LaunchQuitAnimation.ScreenOn, picture = null), q.playing)
        assertFalse(q.hidden)
        q.overlayFirstFrame()
        assertTrue(q.hidden)
        assertFalse(playing.isCompleted)
        q.overlayEnded()
        withTimeout(2_000) { playing.await() }
    }

    @Test
    fun `play returns at once without the overlay or without bounds`() = runBlocking<Unit> {
        QuitAnimator(WindowPicture()) { at }.apply {
            begin(LaunchEffect.None, sessionsOpen = false)
            withTimeout(1_000) { play() }
            assertNull(playing)
        }
        QuitAnimator(WindowPicture()) { null }.apply {
            begin(overlay, sessionsOpen = false)
            withTimeout(1_000) { play() }
            assertNull(playing)
        }
    }
}
