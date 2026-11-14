package com.programmersbox.scrcpyviewer.ui.mirror.shots

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The thumbnail's timer, on virtual time: 6 s, hover pause, resume with at least 2 s, restarts, replacement. */
class ScreenshotThumbnailStateTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val state = ScreenshotThumbnailState(scope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("/tmp/caps/Pixel 8 Pro #1.png")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun bitmap() = ImageBitmap(4, 8)

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private val ImageBitmap.closed get() = asSkiaBitmap().isClosed

    @Test
    fun `shows for 6 seconds then leaves`() {
        state.show(file, bitmap())
        assertNotNull(state.shown.value)
        assertEquals(1f, state.remainingFraction)
        advance(3_000)
        assertEquals(0.5f, state.remainingFraction, 0.001f)
        advance(2_999)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
        assertEquals(0f, state.remainingFraction)
    }

    @Test
    fun `hovering holds the timer`() {
        state.show(file, bitmap())
        advance(1_000)
        state.hover(true)
        advance(60_000)
        assertNotNull(state.shown.value)
        assertEquals(5_000f / 6_000f, state.remainingFraction, 0.001f)
        state.hover(false)
        advance(4_999)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
    }

    @Test
    fun `leaving just before expiry resumes with at least 2 seconds`() {
        state.show(file, bitmap())
        advance(5_900)
        state.hover(true)
        advance(10_000)
        val refills = state.refills.value
        state.hover(false)
        assertEquals(2_000f / 6_000f, state.remainingFraction, 0.001f)
        assertEquals(refills + 1, state.refills.value, "the ring refills up to 2 s")
        advance(1_999)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
    }

    @Test
    fun `interaction restarts at 6 seconds`() {
        state.show(file, bitmap())
        advance(4_000)
        val refills = state.refills.value
        state.touch()
        assertEquals(1f, state.remainingFraction)
        assertEquals(refills + 1, state.refills.value)
        advance(5_999)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
    }

    @Test
    fun `interaction while hovered keeps holding at 6 seconds`() {
        state.show(file, bitmap())
        advance(4_000)
        state.hover(true)
        state.touch()
        advance(30_000)
        assertEquals(1f, state.remainingFraction)
        state.hover(false)
        advance(5_999)
        assertNotNull(state.shown.value)
    }

    @Test
    fun `a new screenshot replaces the shown one, restarts the timer and releases the old bitmap`() {
        val first = bitmap()
        val second = bitmap()
        state.show(file, first)
        val firstShot = state.shown.value!!
        assertTrue(state.acquire(firstShot)) // the crossfade's slot draws it
        advance(5_000)
        state.show(Paths.get("/tmp/caps/two.png"), second)
        val shot = state.shown.value!!
        assertSame(second, shot.bitmap)
        assertTrue(shot.id != firstShot.id)
        assertEquals(1f, state.remainingFraction)
        // Kept while its slot is composed, however long that takes (no wall clock), then closed when it leaves.
        state.hover(true) // held, so the new one stays while time passes
        advance(60_000)
        assertFalse(first.closed)
        state.release(firstShot)
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `rapid screenshots keep one thumbnail with the latest image and release every earlier bitmap`() {
        val bitmaps = List(10) { bitmap() }
        bitmaps.forEach { state.show(file, it); advance(50) }
        assertSame(bitmaps.last(), state.shown.value?.bitmap)
        // Never composed: closed at once.
        assertTrue(bitmaps.dropLast(1).all { it.closed })
        assertFalse(bitmaps.last().closed)
    }

    @Test
    fun `dismiss keeps the bitmap while a slot draws it and closes it when the slot leaves`() {
        val b = bitmap()
        state.show(file, b)
        val shot = state.shown.value!!
        state.acquire(shot)
        state.acquire(shot) // two slots (the leaving card and a crossfade)
        state.dismiss()
        assertNull(state.shown.value)
        state.release(shot)
        assertFalse(b.closed)
        state.release(shot)
        assertTrue(b.closed)
    }

    @Test
    fun `dismiss of a never drawn screenshot closes it at once`() {
        val b = bitmap()
        state.show(file, b)
        state.dismiss()
        assertTrue(b.closed)
    }

    @Test
    fun `a closed shot is not acquired`() {
        val b = bitmap()
        state.show(file, b)
        val shot = state.shown.value!!
        state.show(file, bitmap()) // closes b: nobody drew it
        assertFalse(state.acquire(shot))
        state.release(shot) // no-op, no double close
    }

    @Test
    fun `dispose closes a shot a slot still draws`() {
        val b = bitmap()
        state.show(file, b)
        state.acquire(state.shown.value!!)
        state.dispose()
        assertTrue(b.closed)
    }

    @Test
    fun `a pointer left on a card that went away does not hold the next one`() {
        state.hover(true) // nothing shown: ignored
        state.show(file, bitmap())
        state.hover(true)
        state.dismiss() // the card leaves with the pointer on it; no Exit comes
        state.hover(true) // nothing shown: ignored
        state.show(file, bitmap())
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `an earlier screenshot whose save finishes last is not shown`() {
        val older = bitmap()
        val newer = bitmap()
        val olderTicket = state.begin()
        val newerTicket = state.begin()
        scope.launch { finishScreenshot(newer, AfterScreenshot.Thumbnail, state, { Paths.get("/tmp/new.png") }, { Result.success(Unit) }, {}, newerTicket) }
        scheduler.runCurrent()
        scope.launch { finishScreenshot(older, AfterScreenshot.Thumbnail, state, { Paths.get("/tmp/old.png") }, { Result.success(Unit) }, {}, olderTicket) }
        scheduler.runCurrent()
        assertSame(newer, state.shown.value?.bitmap)
        assertTrue(older.closed)
        assertFalse(newer.closed)
    }

    @Test
    fun `dispose closes the shown and pending bitmaps at once and stops the timer`() {
        val old = bitmap()
        val current = bitmap()
        state.show(file, old)
        state.show(file, current)
        state.dispose()
        assertTrue(old.closed)
        assertTrue(current.closed)
        assertNull(state.shown.value)
        // Nothing runs after: no timer, no double close.
        advance(10_000)
        val late = bitmap()
        state.show(file, late)
        assertTrue(late.closed, "a screenshot that lands after the window closed is released")
        assertNull(state.shown.value)
    }

    @Test
    fun `copied shows for a second and a half and counts as interaction`() {
        state.show(file, bitmap())
        advance(3_000)
        state.markCopied()
        assertTrue(state.copied.value)
        assertEquals(1f, state.remainingFraction)
        advance(COPIED_MILLIS - 1)
        assertTrue(state.copied.value)
        advance(1)
        assertFalse(state.copied.value)
    }

    @Test
    fun `a new screenshot clears copied`() {
        state.show(file, bitmap())
        state.markCopied()
        state.show(file, bitmap())
        assertFalse(state.copied.value)
    }

    // The window's screenshot flow, by setting.

    private fun finish(
        mode: AfterScreenshot,
        b: ImageBitmap,
        save: suspend () -> Path = { file },
        copy: suspend (Path) -> Result<Unit> = { Result.success(Unit) },
    ): List<String> {
        val messages = mutableListOf<String>()
        scope.launch { finishScreenshot(b, mode, state, save, copy, notify = { messages += it }) }
        scheduler.runCurrent()
        return messages
    }

    @Test
    fun `Thumbnail mode shows the thumbnail and keeps the bitmap`() {
        val b = bitmap()
        val messages = finish(AfterScreenshot.Thumbnail, b)
        assertEquals(emptyList(), messages)
        assertSame(b, state.shown.value?.bitmap)
        assertEquals(file, state.shown.value?.file)
        assertFalse(b.closed)
    }

    @Test
    fun `Copy mode copies, says so, shows no thumbnail and closes the bitmap`() {
        val b = bitmap()
        val copied = mutableListOf<Path>()
        val messages = finish(AfterScreenshot.Copy, b, copy = { copied.add(it); Result.success(Unit) })
        assertEquals(listOf(file), copied)
        assertEquals(listOf("Screenshot saved and copied"), messages)
        assertNull(state.shown.value)
        assertTrue(b.closed)
    }

    @Test
    fun `Copy mode failure gives the reason`() {
        val b = bitmap()
        val messages = finish(AfterScreenshot.Copy, b, copy = { Result.failure(IOException("Install wl-clipboard or xclip to copy images")) })
        assertEquals(listOf("Saved, but could not copy the image: Install wl-clipboard or xclip to copy images"), messages)
        assertNull(state.shown.value)
        assertTrue(b.closed)
    }

    @Test
    fun `failed save shows the existing message and no thumbnail`() {
        for (mode in AfterScreenshot.entries) {
            val b = bitmap()
            val messages = finish(mode, b, save = { throw IOException("disk full") })
            assertEquals(listOf("Could not save the screenshot: disk full"), messages)
            assertNull(state.shown.value)
            assertTrue(b.closed)
        }
    }
}
