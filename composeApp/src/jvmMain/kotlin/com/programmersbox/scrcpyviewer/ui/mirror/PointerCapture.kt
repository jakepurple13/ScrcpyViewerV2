package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.min

/**
 * The mirror window's pointer capture for the relative (UHID) mouse: [MouseCapture] state, deltas from screen
 * positions ([PointerDeltaTracker]), and recentring and hiding the pointer with [cursor].
 *
 * The capture only starts in a focused window ([requestCapture] waits for [focusGained] otherwise), and recentring
 * only happens while focused, so an unfocused window never traps the pointer. The pointer is hidden exactly while
 * captured and focused. Capture keys (left Alt / left Meta) are also fed to [hidKeyboard], which tracks them as
 * shortcut modifiers: MOD+key never reaches the device.
 *
 * The first move after capturing recentres the pointer, which tells whether [cursor] can warp at all. When it cannot
 * (Wayland, or a failed call) the capture turns off for good and [onUnavailable] is called once: the pointer then stays
 * visible and its motion still goes to the device as deltas while it is over the video.
 *
 * Call from the UI thread.
 */
class WindowPointerCapture(
    private val capture: MouseCapture,
    private val mapper: InputMapper,
    private val hidKeyboard: HidKeyboard?,
    private val send: (List<ControlMessage>) -> Unit,
    private val isFocused: () -> Boolean,
    private val cursor: CursorControl = NativeCursorControl,
    private val onUnavailable: () -> Unit = {},
) {
    val active: StateFlow<Boolean> get() = capture.active

    private val tracker = PointerDeltaTracker()
    private var pending = false
    /** The next move recentres, to check that warping works. */
    private var probe = false
    /** Warping failed: no capture from now on. */
    private var unavailable = false
    private var hidden = false

    /** Capture as the scrcpy client does at start (first frame, or at once without video), once focused. */
    fun requestCapture() {
        if (isFocused()) activate() else pending = true
    }

    fun focusGained() {
        if (pending) {
            pending = false
            activate()
        }
    }

    /** Releases the capture and shows the pointer; the window releases the device's buttons and keys. */
    fun focusLost() {
        capture.focusLost()
        tracker.reset()
        syncCursor(focused = false)
    }

    /** The window is going: the pointer must not stay hidden. */
    fun dispose() {
        capture.setActive(false)
        syncCursor(focused = false)
    }

    /** Returns true when the key event is a capture key (consumed). */
    fun key(id: Int, keyCode: Int, location: Int): Boolean {
        if (!capture.key(id, keyCode, location)) return false
        if (unavailable) capture.setActive(false)
        // Only updates the keyboard's shortcut-modifier state: capture keys never produce HID reports.
        hidKeyboard?.awtKey(id, keyCode, location)
        if (capture.active.value) probe = true
        syncCursor()
        return true
    }

    /**
     * A pointer event on the video view of [viewSize] px whose top-left is at [viewOrigin] in the window (px).
     * [density] converts px to window units. [stage] is the visible area (window px), recentred on when given.
     */
    fun pointer(
        ev: PointerEvent,
        window: MirrorWindowHandle,
        viewSize: IntSize,
        density: Float,
        stage: Rect? = null,
        viewOrigin: Offset = stage?.topLeft ?: Offset.Zero,
    ) {
        val kind = when (ev.type) {
            PointerEventType.Move -> PointerKind.Move
            PointerEventType.Press -> PointerKind.Press
            PointerEventType.Release -> PointerKind.Release
            PointerEventType.Scroll -> PointerKind.Scroll
            else -> null
        }
        val local = ev.changes.firstOrNull()?.position
        val origin = window.contentOrigin()
        val screen = local?.let { screenPoint(origin, viewOrigin + it, density) }
        if (kind == null) {
            if (ev.type == PointerEventType.Exit && capture.active.value && screen != null) {
                recentre(window, origin, screen, viewOrigin, viewSize, density, force = true, stage)
            }
            return
        }
        if (!unavailable && capture.pointer(kind)) {
            tracker.reset()
            if (capture.active.value) probe = true
            syncCursor()
            return
        }
        if (kind != PointerKind.Move) {
            send(mapper.pointer(ev))
            return
        }
        if (screen == null) return
        val d = tracker.move(screen.x, screen.y)
        send(mapper.relativeMove(d.x, d.y))
        if (!unavailable) recentre(window, origin, screen, viewOrigin, viewSize, density, force = probe, stage)
    }

    private fun activate() {
        if (unavailable) return
        tracker.reset()
        capture.setActive(true)
        probe = true
        syncCursor()
    }

    private fun syncCursor(focused: Boolean = isFocused()) {
        val hide = capture.active.value && focused && !unavailable
        if (hide == hidden) return
        hidden = hide
        cursor.setHidden(hide)
    }

    /**
     * Moves the pointer back to the centre of the video view once it is more than a quarter of the view away (or
     * always with [force]: when it left the view, or to check warping works), so it never hits the screen edge.
     * [screen] is the event position on screen; [contentOrigin] the window content's. The target is kept inside the
     * window.
     */
    private fun recentre(
        window: MirrorWindowHandle,
        contentOrigin: IntOffset,
        screen: IntOffset,
        viewOrigin: Offset,
        viewSize: IntSize,
        density: Float,
        force: Boolean,
        stage: Rect?,
    ) {
        if (!capture.active.value || !isFocused()) return
        // With a [stage] (the visible area, window px) recentre on it: the zoomed canvas can be larger than the
        // window and scrolled, so its own origin and size are not on screen.
        val area = stage ?: Rect(viewOrigin, Size(viewSize.width.toFloat(), viewSize.height.toFloat()))
        if (area.width <= 0f || area.height <= 0f) return
        val w = window.bounds
        val centre = screenPoint(contentOrigin, area.center, density)
        val cx = centre.x.coerceIn(w.x + 40, maxOf(w.x + 40, w.x + w.width - 40))
        val cy = centre.y.coerceIn(w.y + 40, maxOf(w.y + 40, w.y + w.height - 40))
        val limit = min(area.width, area.height) / 4f / density
        if (!force && abs(screen.x - cx) <= limit && abs(screen.y - cy) <= limit) return
        probe = false
        if (!cursor.warp(cx, cy)) {
            giveUp()
            return
        }
        tracker.warped(IntOffset(cx, cy))
    }

    private fun giveUp() {
        unavailable = true
        pending = false
        capture.setActive(false)
        syncCursor()
        onUnavailable()
    }
}
