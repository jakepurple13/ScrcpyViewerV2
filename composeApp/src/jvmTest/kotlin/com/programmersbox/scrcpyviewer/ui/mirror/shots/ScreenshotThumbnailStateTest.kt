package com.programmersbox.scrcpyviewer.ui.mirror.shots

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.media.GifExportException
import com.programmersbox.scrcpyviewer.media.RecordResult
import com.programmersbox.scrcpyviewer.media.RecordingGoneException
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.media.RecordingProbe
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

    private var editorFree = true
    private val opened = mutableListOf<Shot>()

    private fun finish(
        mode: AfterScreenshot,
        b: ImageBitmap,
        save: suspend () -> Path = { file },
        copy: suspend (Path) -> Result<Unit> = { Result.success(Unit) },
    ): List<String> {
        val messages = mutableListOf<String>()
        scope.launch {
            finishScreenshot(
                b, mode, state, save, copy, notify = { messages += it },
                canOpenEditor = { editorFree }, openEditor = { opened += it },
            )
        }
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

    @Test
    fun `OpenEditor mode opens the editor on the saved shot and shows no thumbnail`() {
        val b = bitmap()
        val messages = finish(AfterScreenshot.OpenEditor, b)
        assertEquals(emptyList(), messages)
        assertNull(state.shown.value)
        val shot = opened.single()
        assertSame(b, shot.bitmap)
        assertEquals(file, shot.file)
        assertFalse(shot.edited)
        assertFalse(b.closed, "the editor draws it")
        // The editor's slot draws it, then leaves: closed.
        assertTrue(state.acquire(shot))
        state.release(shot)
        assertTrue(b.closed)
    }

    @Test
    fun `OpenEditor mode with the editor already open shows the thumbnail instead`() {
        editorFree = false
        val b = bitmap()
        finish(AfterScreenshot.OpenEditor, b)
        assertEquals(emptyList(), opened)
        assertSame(b, state.shown.value?.bitmap)
    }

    @Test
    fun `a kept shot is closed when the window closes before the editor draws it`() {
        val b = bitmap()
        val shot = assertNotNull(state.keep(file, b, state.begin()))
        assertFalse(b.closed)
        state.dispose()
        assertTrue(b.closed)
        assertFalse(state.acquire(shot))
    }

    @Test
    fun `a kept shot from an older screenshot than the one on show is closed at once`() {
        val older = state.begin()
        state.show(file, bitmap(), state.begin())
        val b = bitmap()
        assertNull(state.keep(file, b, older))
        assertTrue(b.closed)
    }

    @Test
    fun `editing holds the timer and closing the editor restarts it at 6 seconds`() {
        state.show(file, bitmap())
        advance(4_000)
        state.editing(state.shown.value)
        assertTrue(state.held.value)
        advance(60_000)
        assertNotNull(state.shown.value)
        assertEquals(2_000f / 6_000f, state.remainingFraction, 0.001f)
        val refills = state.refills.value
        state.editing(null)
        assertFalse(state.held.value)
        assertEquals(1f, state.remainingFraction)
        assertEquals(refills + 1, state.refills.value)
        advance(THUMBNAIL_MILLIS - 1)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
    }

    @Test
    fun `hovering out while editing keeps the timer held`() {
        state.show(file, bitmap())
        state.hover(true)
        state.editing(state.shown.value)
        state.hover(false)
        advance(60_000)
        assertNotNull(state.shown.value)
        assertTrue(state.held.value)
        state.editing(null)
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `the pointer left on the card after editing holds the 6 seconds until it leaves`() {
        state.show(file, bitmap())
        state.editing(state.shown.value)
        state.hover(true)
        state.editing(null)
        assertTrue(state.held.value)
        advance(60_000)
        assertEquals(1f, state.remainingFraction)
        state.hover(false)
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `a screenshot shown while editing is held until the editor closes`() {
        state.editing(Shot(file, bitmap(), id = -1))
        state.show(file, bitmap())
        advance(60_000)
        assertNotNull(state.shown.value)
        state.editing(null)
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `an edited shot replaces the one on show, tagged, and keeps the original's name for the next edit`() {
        val first = bitmap()
        state.show(file, first)
        val edited = bitmap()
        val editedFile = Paths.get("/tmp/caps/Pixel 8 Pro #1-edited.png")
        state.showEdited(editedFile, edited, original = file)
        val shot = assertNotNull(state.shown.value)
        assertTrue(shot.edited)
        assertEquals(editedFile, shot.file)
        assertEquals(file, shot.original)
        assertSame(edited, shot.bitmap)
        assertTrue(first.closed, "the replaced one, drawn by no slot, is closed")
        assertEquals(1f, state.remainingFraction)
    }

    // Recordings.

    private val recording: Path = Paths.get("/tmp/caps/Pixel 8 Pro #2 ü.mp4")
    private val info = RecordingInfo(18_030, 1080, 2340)

    @Test
    fun `a recording shows like a screenshot, with its length and size, for 6 seconds`() {
        val first = bitmap()
        state.showRecording(recording, first, info)
        val shot = assertNotNull(state.shown.value)
        assertTrue(shot.isRecording)
        assertEquals(info, shot.video)
        assertEquals(recording, shot.file)
        assertSame(first, shot.bitmap)
        advance(THUMBNAIL_MILLIS - 1)
        assertNotNull(state.shown.value)
        advance(1)
        assertNull(state.shown.value)
        assertTrue(first.closed)
    }

    @Test
    fun `an Export GIF window holds the timer while open, and only its recording is marked`() {
        state.showRecording(recording, bitmap(), info)
        val id = state.shown.value!!.id
        advance(2_000)
        state.makingGif(id, true)
        assertEquals(setOf(id), state.gifShotIds.value)
        assertTrue(state.held.value)
        assertNull(state.editingShotId.value, "not the markup editor's")
        advance(60_000)
        assertNotNull(state.shown.value)
        // Leaving the card keeps it held: the window is still open.
        state.hover(true)
        state.hover(false)
        advance(60_000)
        assertNotNull(state.shown.value)
        state.makingGif(id, false)
        assertTrue(state.gifShotIds.value.isEmpty())
        assertFalse(state.held.value)
        assertEquals(1f, state.remainingFraction, "restarts at 6 s")
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `two Export GIF windows hold until the last one closes, and the editor's hold is its own`() {
        state.showRecording(recording, bitmap(), info)
        state.makingGif(1, true)
        state.makingGif(2, true)
        state.makingGif(1, false)
        advance(60_000)
        assertNotNull(state.shown.value)
        state.editing(Shot(file, bitmap(), id = -1))
        state.makingGif(2, false)
        advance(60_000)
        assertNotNull(state.shown.value, "the editor still holds it")
        state.editing(null)
        advance(THUMBNAIL_MILLIS)
        assertNull(state.shown.value)
    }

    @Test
    fun `a saved recording shows its thumbnail once probed`() = kotlinx.coroutines.runBlocking {
        val messages = mutableListOf<String>()
        val first = bitmap()
        finishRecording(RecordResult.Saved(recording), state, { RecordingProbe(info, first) }, { messages += it })
        assertTrue(messages.isEmpty(), "no snackbar: the thumbnail says it")
        val shot = assertNotNull(state.shown.value)
        assertEquals(info, shot.video)
        assertSame(first, shot.bitmap)
    }

    @Test
    fun `a failed, a deleted and an unreadable recording each say so, with no thumbnail`() = kotlinx.coroutines.runBlocking {
        val messages = mutableListOf<String>()
        finishRecording(RecordResult.Failed(recording, "Disk full"), state, { error("not probed") }, { messages += it })
        finishRecording(RecordResult.Saved(recording), state, { throw RecordingGoneException(it) }, { messages += it })
        finishRecording(RecordResult.Saved(recording), state, { throw GifExportException("No video in the recording") }, { messages += it })
        assertEquals(
            listOf(
                "Recording failed: Disk full",
                "The recording is no longer at $recording",
                "Recording saved to $recording",
            ),
            messages,
        )
        assertNull(state.shown.value)
    }

    @Test
    fun `on Linux without FFmpeg's libraries the saved message says which to install`() = kotlinx.coroutines.runBlocking {
        val messages = mutableListOf<String>()
        val missing = com.programmersbox.scrcpyviewer.media.ffmpegUnavailableReason(UnsatisfiedLinkError("no jniavdevice"), linux = true)
        finishRecording(RecordResult.Saved(recording), state, { throw GifExportException(missing) }, { messages += it })
        assertEquals(
            listOf("Recording saved to $recording. To export GIFs, install libasound2t64 (libasound2 on older systems), libpulse0, libxcb1, libxcb-shm0, libdrm2 and libudev1"),
            messages,
        )
    }

    @Test
    fun `the FFmpeg reason names the Linux libraries only for a native load failure on Linux`() {
        val link = RuntimeException("load", UnsatisfiedLinkError("no jniavdevice"))
        assertEquals(
            "FFmpeg needs a few system libraries: install libasound2t64 (libasound2 on older systems), libpulse0, libxcb1, libxcb-shm0, libdrm2 and libudev1, then try again",
            com.programmersbox.scrcpyviewer.media.ffmpegUnavailableReason(link, linux = true),
        )
        assertEquals("FFmpeg isn't available: load", com.programmersbox.scrcpyviewer.media.ffmpegUnavailableReason(link, linux = false))
        assertEquals(
            "FFmpeg isn't available: no jar",
            com.programmersbox.scrcpyviewer.media.ffmpegUnavailableReason(IllegalStateException("no jar"), linux = true),
        )
    }

    @Test
    fun `a recording probed after a later screenshot does not replace it, and its frame is closed`() = kotlinx.coroutines.runBlocking {
        val ticket = state.begin()
        state.show(file, bitmap())
        val first = bitmap()
        finishRecording(RecordResult.Saved(recording), state, { RecordingProbe(info, first) }, {}, ticket)
        assertFalse(state.shown.value!!.isRecording)
        assertTrue(first.closed)
    }
}
