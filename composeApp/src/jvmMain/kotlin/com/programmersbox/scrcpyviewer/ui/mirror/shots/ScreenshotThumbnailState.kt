package com.programmersbox.scrcpyviewer.ui.mirror.shots

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** How long the thumbnail stays when nothing touches it. */
const val THUMBNAIL_MILLIS = 6_000L

/** Leaving the thumbnail resumes its timer with at least this much left. */
const val THUMBNAIL_RESUME_MIN_MILLIS = 2_000L

/** How long the Copy button shows its check. */
const val COPIED_MILLIS = 1_500L

/**
 * How long a replaced or dismissed screenshot's bitmap is kept before its native pixels are released: longer than the
 * crossfade (250 ms) and the leave animation (300 ms), which still draw it.
 */
const val BITMAP_RELEASE_MILLIS = 1_000L

/** A screenshot on show: its saved PNG and the copy of the frame it was made from. [id] is new for every screenshot. */
class Shot(val file: Path, val bitmap: ImageBitmap, val id: Long)

/**
 * The mirror window's screenshot thumbnail: which screenshot shows and for how long.
 *
 * It stays [THUMBNAIL_MILLIS]; [hover] holds the timer, and leaving resumes it with at least
 * [THUMBNAIL_RESUME_MIN_MILLIS] left. [touch] (copy, reveal, drag) restarts it at [THUMBNAIL_MILLIS]. A new [show]
 * replaces the screenshot and restarts the timer. The thumbnail owns its bitmaps: a replaced or dismissed one is closed
 * [BITMAP_RELEASE_MILLIS] later (the animations still draw it), and [dispose] closes them all at once.
 *
 * Timers run on [scope] and read [clock] (milliseconds), so tests drive both on virtual time. UI thread only.
 */
@Stable
class ScreenshotThumbnailState(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _shown = MutableStateFlow<Shot?>(null)

    /** The screenshot on show; null when none (or it is leaving). */
    val shown: StateFlow<Shot?> = _shown.asStateFlow()

    private val _copied = MutableStateFlow(false)

    /** The Copy button shows its check (for [COPIED_MILLIS] after [markCopied]). */
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    private val _refills = MutableStateFlow(0)

    /** Bumped whenever the time left jumps up (a new screenshot, an interaction, a resume to 2 s): the ring refills. */
    val refills: StateFlow<Int> = _refills.asStateFlow()

    private var nextId = 0L
    private var hovered = false
    private var disposed = false

    /** While running: when the timer ends ([clock]). */
    private var deadline = 0L

    /** While hovered: the time left, held. */
    private var held = 0L
    private var timer: Job? = null
    private var copiedTimer: Job? = null

    /** Replaced or dismissed bitmaps waiting for their animations to end. */
    private val retiring = mutableListOf<ImageBitmap>()

    /** The time left, in milliseconds. */
    private val remainingMillis: Long
        get() = when {
            _shown.value == null -> 0L
            hovered -> held
            else -> (deadline - clock()).coerceIn(0L, THUMBNAIL_MILLIS)
        }

    /** The share of [THUMBNAIL_MILLIS] left, 1 to 0, for the countdown ring. Read it every frame; it is not state. */
    val remainingFraction: Float get() = remainingMillis.toFloat() / THUMBNAIL_MILLIS

    /** Shows [file]'s thumbnail, drawn from [bitmap], which the thumbnail now owns. Replaces the one on show. */
    fun show(file: Path, bitmap: ImageBitmap) {
        if (disposed) {
            close(bitmap)
            return
        }
        _shown.value?.let { retire(it.bitmap) }
        _shown.value = Shot(file, bitmap, ++nextId)
        clearCopied()
        restart()
    }

    /** The pointer entered ([on]) or left the thumbnail. */
    fun hover(on: Boolean) {
        if (on == hovered) return
        if (_shown.value == null) {
            hovered = on
            return
        }
        if (on) {
            held = remainingMillis
            hovered = true
            timer?.cancel()
            timer = null
        } else {
            hovered = false
            if (held < THUMBNAIL_RESUME_MIN_MILLIS) _refills.value++
            schedule(held.coerceAtLeast(THUMBNAIL_RESUME_MIN_MILLIS))
        }
    }

    /** A copy, reveal or drag: the timer restarts at [THUMBNAIL_MILLIS]. */
    fun touch() {
        if (_shown.value != null) restart()
    }

    /** The copy worked: the Copy button shows its check for [COPIED_MILLIS]. Counts as interaction. */
    fun markCopied() {
        if (_shown.value == null) return
        touch()
        _copied.value = true
        copiedTimer?.cancel()
        copiedTimer = scope.launch {
            delay(COPIED_MILLIS)
            _copied.value = false
        }
    }

    /** The thumbnail leaves (timeout, close, a drop elsewhere). */
    fun dismiss() {
        val shot = _shown.value ?: return
        _shown.value = null
        timer?.cancel()
        timer = null
        hovered = false
        clearCopied()
        retire(shot.bitmap)
    }

    /** The window closed: the timers stop and every bitmap is closed now. Later [show]s close theirs at once. */
    fun dispose() {
        if (disposed) return
        disposed = true
        timer?.cancel()
        copiedTimer?.cancel()
        _shown.value?.let { close(it.bitmap) }
        _shown.value = null
        retiring.forEach(::close)
        retiring.clear()
    }

    private fun restart() {
        _refills.value++
        if (hovered) {
            held = THUMBNAIL_MILLIS
            timer?.cancel()
            timer = null
        } else {
            schedule(THUMBNAIL_MILLIS)
        }
    }

    private fun schedule(millis: Long) {
        deadline = clock() + millis
        timer?.cancel()
        timer = scope.launch {
            delay(millis)
            timer = null
            dismiss()
        }
    }

    private fun clearCopied() {
        copiedTimer?.cancel()
        copiedTimer = null
        _copied.value = false
    }

    private fun retire(bitmap: ImageBitmap) {
        retiring += bitmap
        scope.launch {
            delay(BITMAP_RELEASE_MILLIS)
            if (retiring.remove(bitmap)) close(bitmap)
        }
    }

    private fun close(bitmap: ImageBitmap) = bitmap.asSkiaBitmap().close()
}

/**
 * The rest of a toolbar screenshot once the frame is copied into [bitmap]: [save] writes the PNG, then by [mode] the
 * thumbnail shows (it takes the bitmap) or the image is copied ([copy] runs off the UI thread) and a snackbar says so.
 * A failed save shows "Could not save the screenshot: …" and nothing else. The bitmap is closed unless the thumbnail
 * took it.
 */
internal suspend fun finishScreenshot(
    bitmap: ImageBitmap,
    mode: AfterScreenshot,
    thumbnail: ScreenshotThumbnailState,
    save: suspend () -> Path,
    copy: suspend (Path) -> Result<Unit>,
    notify: (String) -> Unit,
) {
    var kept = false
    try {
        val file = save()
        when (mode) {
            AfterScreenshot.Thumbnail -> {
                thumbnail.show(file, bitmap)
                kept = true
            }
            AfterScreenshot.Copy -> notify(
                copy(file).fold(
                    onSuccess = { "Screenshot saved and copied" },
                    onFailure = { "Saved, but could not copy the image: ${it.message ?: it}" },
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        notify("Could not save the screenshot: ${e.message ?: e}")
    } finally {
        if (!kept) bitmap.asSkiaBitmap().close() // the copy's native pixels
    }
}
