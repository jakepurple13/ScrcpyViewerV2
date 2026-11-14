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

/** A screenshot on show: its saved PNG and the copy of the frame it was made from. [id] is new for every screenshot. */
class Shot(val file: Path, val bitmap: ImageBitmap, val id: Long)

/**
 * The mirror window's screenshot thumbnail: which screenshot shows and for how long.
 *
 * It stays [THUMBNAIL_MILLIS]; [hover] holds the timer, and leaving resumes it with at least
 * [THUMBNAIL_RESUME_MIN_MILLIS] left. [touch] (copy, reveal, drag) restarts it at [THUMBNAIL_MILLIS]. A new [show]
 * replaces the screenshot and restarts the timer.
 *
 * The thumbnail owns its bitmaps. A replaced or dismissed screenshot is retired: its bitmap is closed once no slot of
 * the composition draws it any more ([acquire] / [release], tied to the slot's composition, so a stalled or minimized
 * window never draws a closed bitmap), or at once when none ever did. [dispose] closes them all.
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

    private val _hovered = MutableStateFlow(false)

    /** The timer is held (pointer on the card, or an OS drag): the countdown ring need not redraw. */
    val held: StateFlow<Boolean> = _hovered.asStateFlow()

    private var nextId = 0L
    private var disposed = false

    /** The latest screenshot [begin] handed out; an older one that finishes later is not shown. */
    private var lastTicket = 0L
    private var shownTicket = 0L

    /** While running: when the timer ends ([clock]). */
    private var deadline = 0L

    /** While hovered: the time left, held. */
    private var heldMillis = 0L
    private var timer: Job? = null
    private var copiedTimer: Job? = null

    /** How many composed slots draw each screenshot. */
    private val drawing = HashMap<Shot, Int>()

    /** Replaced or dismissed screenshots still drawn by some slot: closed when the last one leaves. */
    private val retired = HashSet<Shot>()

    private var hovered: Boolean
        get() = _hovered.value
        set(value) {
            _hovered.value = value
        }

    /** The time left, in milliseconds. */
    private val remainingMillis: Long
        get() = when {
            _shown.value == null -> 0L
            hovered -> heldMillis
            else -> (deadline - clock()).coerceIn(0L, THUMBNAIL_MILLIS)
        }

    /** The share of [THUMBNAIL_MILLIS] left, 1 to 0, for the countdown ring. Read it every frame; it is not state. */
    val remainingFraction: Float get() = remainingMillis.toFloat() / THUMBNAIL_MILLIS

    /** A screenshot is being taken: its ticket for [show]. Saves can finish out of order; only the latest shows. */
    fun begin(): Long = ++lastTicket

    /**
     * Shows [file]'s thumbnail, drawn from [bitmap], which the thumbnail now owns. Replaces the one on show. With a
     * [ticket] older than the one on show (a slower save of an earlier screenshot), [bitmap] is closed instead.
     */
    fun show(file: Path, bitmap: ImageBitmap, ticket: Long = begin()) {
        if (disposed || ticket < shownTicket) {
            close(bitmap)
            return
        }
        shownTicket = ticket
        _shown.value?.let(::retire)
        _shown.value = Shot(file, bitmap, ++nextId)
        clearCopied()
        restart()
    }

    /** The pointer entered ([on]) or left the thumbnail, or an OS drag of it started or ended. */
    fun hover(on: Boolean) {
        if (on == hovered) return
        if (_shown.value == null) {
            // Nothing to hold: a pointer left on a card that went away must not hold the next one.
            if (!on) hovered = false
            return
        }
        if (on) {
            heldMillis = remainingMillis
            hovered = true
            timer?.cancel()
            timer = null
        } else {
            hovered = false
            if (heldMillis < THUMBNAIL_RESUME_MIN_MILLIS) _refills.value++
            schedule(heldMillis.coerceAtLeast(THUMBNAIL_RESUME_MIN_MILLIS))
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
        retire(shot)
    }

    /** A slot of the composition draws [shot] from now on. False when its bitmap is already closed: draw nothing. */
    fun acquire(shot: Shot): Boolean {
        if (shot.bitmap.asSkiaBitmap().isClosed) return false
        drawing[shot] = (drawing[shot] ?: 0) + 1
        return true
    }

    /** A slot that [acquire]d [shot] left the composition; a retired shot nobody draws is closed. */
    fun release(shot: Shot) {
        val count = drawing[shot] ?: return
        if (count > 1) {
            drawing[shot] = count - 1
            return
        }
        drawing.remove(shot)
        if (retired.remove(shot)) close(shot.bitmap)
    }

    /** The window closed: the timers stop and every bitmap is closed now. Later [show]s close theirs at once. */
    fun dispose() {
        if (disposed) return
        disposed = true
        timer?.cancel()
        copiedTimer?.cancel()
        _shown.value?.let { close(it.bitmap) }
        _shown.value = null
        retired.forEach { close(it.bitmap) }
        retired.clear()
        drawing.clear()
    }

    private fun restart() {
        _refills.value++
        if (hovered) {
            heldMillis = THUMBNAIL_MILLIS
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

    /** [shot] left the screen: closed now when no slot draws it, else when the last one leaves ([release]). */
    private fun retire(shot: Shot) {
        if (drawing.containsKey(shot)) retired += shot else close(shot.bitmap)
    }

    private fun close(bitmap: ImageBitmap) {
        val skia = bitmap.asSkiaBitmap()
        if (!skia.isClosed) skia.close()
    }
}

/**
 * The rest of a toolbar screenshot once the frame is copied into [bitmap]: [save] writes the PNG, then by [mode] the
 * thumbnail shows (it takes the bitmap) or the image is copied ([copy] runs off the UI thread) and a snackbar says so.
 * A failed save shows "Could not save the screenshot: …" and nothing else. The bitmap is closed unless the thumbnail
 * took it. [ticket] is [ScreenshotThumbnailState.begin]'s, taken when the frame was copied: a save that finishes after
 * a later screenshot's is kept on disk but not shown.
 */
internal suspend fun finishScreenshot(
    bitmap: ImageBitmap,
    mode: AfterScreenshot,
    thumbnail: ScreenshotThumbnailState,
    save: suspend () -> Path,
    copy: suspend (Path) -> Result<Unit>,
    notify: (String) -> Unit,
    ticket: Long = thumbnail.begin(),
) {
    var kept = false
    try {
        val file = save()
        when (mode) {
            AfterScreenshot.Thumbnail -> {
                kept = true // show() takes it, or closes it when a later screenshot is already on show
                thumbnail.show(file, bitmap, ticket)
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
