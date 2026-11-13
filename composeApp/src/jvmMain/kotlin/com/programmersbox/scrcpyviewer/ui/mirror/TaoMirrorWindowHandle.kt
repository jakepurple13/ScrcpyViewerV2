package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import com.sun.jna.Pointer
import dev.nucleusframework.application.NucleusWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * A [MirrorWindowHandle] over a Nucleus window on the Tao backend. Tao reads bounds in physical pixels and sets them in
 * points: [NucleusWindow.boundsOnScreen] converts the former, so everything here is in window units (points).
 */
class TaoMirrorWindowHandle(private val window: NucleusWindow, private val geometry: ScreenGeometry) : MirrorWindowHandle {
    private val tao = window.unsafe.taoWindow
    private val focusChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    private val moves = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val aspect = nsWindow?.let(::MacAspectLock)

    // Shown / hidden as Tao reports it; off macOS, where there is no NSWindow to ask. A window shown before this
    // handle existed sent its event already, so start from "shown".
    @Volatile
    private var shownFlag = true

    init {
        // Tao's listeners are multicast and live as long as the window.
        tao?.onFocusChanged { focusChanges.tryEmit(it) }
        tao?.onShown { shownFlag = true }
        tao?.onWillHide { shownFlag = false }
        // contentAspectRatio is exact for the width it was computed at; recompute it after every step of a resize.
        tao?.onResized { _, _ ->
            aspect?.reapply()
            moves.tryEmit(Unit)
        }
        tao?.onMoved { _, _ -> moves.tryEmit(Unit) }
    }

    override var bounds: Rectangle
        get() = window.boundsOnScreen()
            ?.let { Rectangle(it.x.roundToInt(), it.y.roundToInt(), it.width.roundToInt(), it.height.roundToInt()) }
            ?: Rectangle()
        set(value) {
            val t = tao ?: return
            t.setOuterPosition(value.x.toDouble(), value.y.toDouble())
            t.setInnerSize(value.width.toDouble(), value.height.toDouble())
        }

    override val size: IntSize get() = bounds.let { IntSize(it.width, it.height) }

    override val isShowing: Boolean
        get() {
            val ns = nsWindow ?: return shownFlag && window.boundsOnScreen() != null
            return runCatching { ObjC.msgBool(ns, "isVisible") }.getOrDefault(shownFlag)
        }

    override val boundsChanges: Flow<Unit> = moves.asSharedFlow()
    override val focused: StateFlow<Boolean> = window.focusFlow
    override val focusEvents: SharedFlow<Boolean> = focusChanges.asSharedFlow()

    override val nsWindow: Pointer?
        get() {
            if (!Os.isMac) return null
            val handle = tao?.nsWindowHandle ?: return null
            return if (handle == 0L) null else Pointer(handle)
        }

    override val hwnd: Pointer?
        get() {
            if (!Os.isWindows) return null
            val handle = tao?.nativeHandle ?: return null
            return if (handle == 0L) null else Pointer(handle)
        }

    override fun toFront() {
        window.toFront()
        window.requestFocus()
    }

    override fun screen(): ScreenArea? = screenFor(bounds, geometry.screens())

    // Nucleus draws the title bar in Compose, so the content (Compose's root) starts at the window's top-left.
    override fun contentOrigin(): IntOffset = bounds.let { IntOffset(it.x, it.y) }

    override fun lockAspect(lock: AspectLock?): Boolean {
        val a = aspect ?: return false
        a.set(lock)
        return true
    }
}

/**
 * The live aspect ratio lock on macOS: NSWindow's `contentAspectRatio`, which AppKit holds during a live resize. The
 * ratio covers the whole content view (Nucleus' title bar included), so a fixed ratio is exact at one width only: it
 * is computed from the current width and set again after every resize step ([reapply]), so the stage stays at the
 * frame's aspect throughout the drag. Null clears it (`contentResizeIncrements` 1 x 1 replaces the ratio).
 */
private class MacAspectLock(private val nsWindow: Pointer) {
    @Volatile
    private var lock: AspectLock? = null

    fun set(value: AspectLock?) {
        if (value == lock) return
        lock = value
        apply(value)
    }

    fun reapply() {
        lock?.let(::apply)
    }

    private fun apply(value: AspectLock?) {
        ObjC.onMain {
            if (value == null) {
                ObjC.msgVoid(nsWindow, "setContentResizeIncrements:", ObjC.NSSize(1.0, 1.0))
                return@onMain
            }
            // The lock is in outer sizes; AppKit's ratio is the content view's, which may leave out a native title bar.
            val frame = ObjC.msgRect(nsWindow, "frame")
            val content = ObjC.msgPtr(nsWindow, "contentView")?.let { ObjC.msgRect(it, "frame") } ?: return@onMain
            if (frame[2] <= value.chromeWidth + 1) return@onMain
            val contentHeight = value.outerHeight(frame[2]) - (frame[3] - content[3])
            if (content[2] <= 0 || contentHeight <= 0) return@onMain
            ObjC.msgVoid(nsWindow, "setContentAspectRatio:", ObjC.NSSize(content[2], contentHeight))
        }
    }
}
