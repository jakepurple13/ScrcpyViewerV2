package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

/**
 * The client-side "show clicks" dot drawn with `show-touches`. Android's show_touches only draws physical finger
 * touches, not the clicks scrcpy injects, so the mirror window marks them itself: a primary-button press on the
 * frame shows a dot at that view point, the dot follows the pointer while the button is held (clamped to the
 * frame). On release a ring ripples out of it ([RING_MS], radius from [RING_START] of the full radius up to the full
 * radius, fading out) and, [DOT_DELAY_MS] later, the dot fades and shrinks to [DOT_END_SCALE] over [fadeMs]. Presses
 * on the letterbox bars show nothing.
 *
 * [displayedRect] is where the frame is drawn ([InputMapper.displayedRect]), so the dot sits on the same image the
 * input mapping uses. It only draws: what is sent to the device does not change. Times are milliseconds on one
 * monotonic clock. The state is snapshot state, so a Canvas reading [state] redraws when it changes.
 */
class ClickIndicator(private val displayedRect: () -> Rect?, private val fadeMs: Long = DEFAULT_FADE_MS) {
    /**
     * The dot at [position] (view coordinates) with [alpha] from 1 (held) down to 0 and [scale] from 1 down to
     * [DOT_END_SCALE]; [ring] is the release ripple around it, if one is running.
     */
    data class Dot(val position: Offset, val alpha: Float, val scale: Float = 1f, val ring: Ring? = null)

    /** The release ripple: its radius as a [radiusFraction] of the full radius, and its [alpha]. */
    data class Ring(val radiusFraction: Float, val alpha: Float)

    private var position by mutableStateOf<Offset?>(null)
    private var held by mutableStateOf(false)
    private var releasedAtMs by mutableStateOf<Long?>(null)

    fun press(point: Offset) {
        val rect = displayedRect() ?: return
        if (!rect.contains(point)) return // letterbox bar
        position = point
        held = true
        releasedAtMs = null
    }

    fun move(point: Offset) {
        if (!held) return
        val rect = displayedRect() ?: return
        position = Offset(point.x.coerceIn(rect.left, rect.right), point.y.coerceIn(rect.top, rect.bottom))
    }

    fun release(nowMs: Long) {
        if (!held) return
        held = false
        releasedAtMs = nowMs
    }

    /** Feeds a pointer event of the mirror view (primary button only). */
    @OptIn(ExperimentalComposeUiApi::class)
    fun onPointer(event: PointerEvent, nowMs: Long) {
        val pos = event.changes.firstOrNull()?.position ?: return
        when (event.type) {
            PointerEventType.Press -> if (event.button == PointerButton.Primary) press(pos)
            PointerEventType.Move -> move(pos)
            PointerEventType.Release -> if (event.button == PointerButton.Primary) release(nowMs)
        }
    }

    /** How long after a release something is still drawn: the ring, or the dot's delayed fade. */
    private val activeMs: Long get() = maxOf(RING_MS, DOT_DELAY_MS + fadeMs)

    /** The dot to draw at [nowMs], or null. */
    fun state(nowMs: Long): Dot? {
        val p = position ?: return null
        val released = releasedAtMs ?: return Dot(p, 1f)
        val elapsed = (nowMs - released).coerceAtLeast(0)
        if (elapsed >= activeMs) return null
        val fade = ((elapsed - DOT_DELAY_MS).toFloat() / fadeMs).coerceIn(0f, 1f)
        val ring = if (elapsed < RING_MS) {
            val t = elapsed.toFloat() / RING_MS
            Ring(RING_START + (1f - RING_START) * EmphasizedDecelerate.transform(t), 1f - t)
        } else {
            null
        }
        return Dot(p, alpha = 1f - fade, scale = 1f - (1f - DOT_END_SCALE) * fade, ring = ring)
    }

    /** A fade is running at [nowMs] (the view must keep redrawing until it ends). */
    fun isFading(nowMs: Long): Boolean = releasedAtMs?.let { nowMs - it < activeMs } == true

    /** When the last release happened, to restart the fade animation. */
    val releaseTime: Long? get() = releasedAtMs

    companion object {
        const val DEFAULT_FADE_MS = 300L

        /** How long the release ring takes to ripple out (emphasized-decelerate radius, linear fade). */
        const val RING_MS = 520L

        /** The ring's starting radius, as a fraction of its full radius. */
        const val RING_START = 0.2f

        /** The dot holds this long after the release before it fades. */
        const val DOT_DELAY_MS = 120L

        /** The scale the dot shrinks to as it fades. */
        const val DOT_END_SCALE = 0.6f
    }
}
