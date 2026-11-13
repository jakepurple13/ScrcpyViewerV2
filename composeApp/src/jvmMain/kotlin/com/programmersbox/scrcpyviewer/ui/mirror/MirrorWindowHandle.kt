package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * What a window's code needs from the native window (a Nucleus Tao window, see [TaoMirrorWindowHandle]). All sizes and
 * positions are in window units (points on macOS).
 */
interface MirrorWindowHandle {
    /** The window's outer bounds on screen; setting them moves and resizes the window in one step. */
    var bounds: Rectangle

    /** The window's outer size. */
    val size: IntSize
    val isShowing: Boolean

    /** Whether the window has the keyboard focus; the current value first, then each change. */
    val focused: StateFlow<Boolean>

    /**
     * Every focus change as it happens (true gained, false lost), none replayed. Unlike [focused], a quick loss and
     * gain are two events, so held device keys are still released.
     */
    val focusEvents: SharedFlow<Boolean>

    /** The window's NSWindow on macOS. Null off macOS or before the window has a native peer. */
    val nsWindow: Pointer?

    /** The window's HWND on Windows. Null elsewhere or before the window has a native peer. */
    val hwnd: Pointer? get() = null

    /** Brings the window to the front and asks for the keyboard focus. */
    fun toFront()

    /** The screen the window mostly lies on, or null when the platform cannot say. */
    fun screen(): ScreenArea?

    /** Where the window's content area (Compose's root) starts on screen, below any native title bar. */
    fun contentOrigin(): IntOffset

    /**
     * Keeps the window's stage at [lock]'s aspect ratio while the user resizes it (live, natively), or stops doing so
     * when null. Returns false when the platform has no live lock: the caller then corrects the size once the resize
     * settles.
     */
    fun lockAspect(lock: AspectLock?): Boolean = false
}

/**
 * The aspect ratio a window keeps while the user resizes it: its stage shows a [frameWidth] x [frameHeight] frame, and
 * the rest of the window around the stage (title bar, toolbars, an open sheet) is [chromeWidth] x [chromeHeight]
 * window units of its outer size, which do not scale with the window.
 */
data class AspectLock(val frameWidth: Int, val frameHeight: Int, val chromeWidth: Double, val chromeHeight: Double) {
    /** The outer height (window units) that keeps the stage at the frame's aspect ratio for an outer [width]. */
    fun outerHeight(width: Double): Double =
        chromeHeight + (width - chromeWidth).coerceAtLeast(1.0) * frameHeight / frameWidth
}

/** The window's handle, provided by the window's content (Main.kt, MirrorWindow); null outside a window. */
val LocalMirrorWindowHandle: ProvidableCompositionLocal<MirrorWindowHandle?> = staticCompositionLocalOf { null }

/**
 * Animates [growth] (window units the window has grown by for the quick actions sheet) to [to] along [spec], moving
 * and resizing [handle] once per frame: [base] grown by the growth, moved left only as far as [screenRight] needs, at
 * the window's current height (the user may drag it meanwhile). [onFrame] gets each frame's left shift (`base.x` less
 * the window's x) and how far along the animation is (0 to 1).
 */
internal suspend fun growForSheet(
    handle: MirrorWindowHandle,
    growth: Animatable<Float, AnimationVector1D>,
    base: Rectangle,
    to: Float,
    spec: AnimationSpec<Float>,
    screenRight: Int,
    onFrame: (shift: Int, progress: Float) -> Unit,
) {
    val from = growth.value
    growth.animateTo(to, spec) {
        val grown = sheetFrameBounds(base, value.roundToInt(), screenRight, handle.size.height)
        handle.bounds = grown
        onFrame(base.x - grown.x, if (to == from) 1f else (value - from) / (to - from))
    }
}
