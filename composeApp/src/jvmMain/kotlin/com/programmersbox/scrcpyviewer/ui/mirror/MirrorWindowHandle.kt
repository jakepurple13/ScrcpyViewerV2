package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

/**
 * What a window's code needs from the native window, whatever backs it (AWT now, Tao later). All sizes and positions
 * are in window units (AWT user space: points on macOS).
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

    /**
     * The window's NSWindow on macOS (or, until it is resolved on the main thread, a view in it: the functions in
     * MacVibrancy.kt take either). Null off macOS or before the window has a native peer.
     */
    val nsWindow: Pointer?

    /** Brings the window to the front and asks for the keyboard focus. */
    fun toFront()

    /** The screen the window mostly lies on, or null when the platform cannot say. */
    fun screen(): ScreenArea?
}

/** The window's handle, provided by [com.programmersbox.scrcpyviewer.ui.window.AppWindow]; null outside a window. */
val LocalMirrorWindowHandle: ProvidableCompositionLocal<MirrorWindowHandle?> = staticCompositionLocalOf { null }

/** A [MirrorWindowHandle] over an AWT window; kept until the windows move to Tao. */
class AwtMirrorWindowHandle(private val window: java.awt.Window, private val geometry: ScreenGeometry) : MirrorWindowHandle {
    private val focusState = MutableStateFlow(window.isFocused)
    private val focusChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)

    init {
        // Lives as long as the window: the listener goes with it.
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                focusState.value = true
                focusChanges.tryEmit(true)
            }

            override fun windowLostFocus(e: WindowEvent) {
                focusState.value = false
                focusChanges.tryEmit(false)
            }
        })
    }

    override var bounds: Rectangle
        get() = window.bounds
        set(value) {
            window.bounds = value
        }

    override val size: IntSize get() = IntSize(window.width, window.height)
    override val isShowing: Boolean get() = window.isShowing
    override val focused: StateFlow<Boolean> = focusState.asStateFlow()
    override val focusEvents: SharedFlow<Boolean> = focusChanges.asSharedFlow()

    override val nsWindow: Pointer?
        get() {
            if (!Os.isMac) return null
            // skiko's handle: an NSView or NSWindow, resolved to the window on the main thread by the callee.
            val handle = (window as? ComposeWindow)?.windowHandle ?: return null
            return if (handle == 0L) null else Pointer(handle)
        }

    override fun toFront() {
        window.toFront()
        window.requestFocus()
    }

    override fun screen(): ScreenArea? = screenFor(window.bounds, geometry.screens())
}

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
