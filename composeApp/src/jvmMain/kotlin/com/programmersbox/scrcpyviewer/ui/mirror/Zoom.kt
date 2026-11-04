package com.programmersbox.scrcpyviewer.ui.mirror

import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a zoom control or shortcut asks for. */
enum class ZoomCommand { In, Out, Fit, Actual }

/**
 * Zoom of the mirror image. A zoom is a scale (1 = one frame pixel per screen pixel); `null` is the separate
 * "Fit to window" mode.
 */
object Zoom {
    val STEPS = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    private const val EPS = 1e-3f

    /** The step closest to [scale]. */
    fun nearest(scale: Float): Float = STEPS.minBy { abs(it - scale) }

    /**
     * The zoom after [command] from [current] (`null` = fit, whose scale on screen is [fitted]). In and Out move one
     * step and clamp at the ends; from fit they start at the step nearest to [fitted]. Fit leaves zoom mode; Actual
     * is 100 %, or back to fit when already there.
     */
    fun step(current: Float?, fitted: Float, command: ZoomCommand): Float? = when (command) {
        ZoomCommand.Fit -> null
        ZoomCommand.Actual -> if (current != null && abs(current - 1f) < EPS) null else 1f
        ZoomCommand.In -> {
            val base = current ?: nearest(fitted)
            STEPS.firstOrNull { it > base + EPS } ?: STEPS.last()
        }
        ZoomCommand.Out -> {
            val base = current ?: nearest(fitted)
            STEPS.lastOrNull { it < base - EPS } ?: STEPS.first()
        }
    }

    /**
     * The zoom a new window starts with, given the `render-fit` option and the scale the image has when it first
     * fits ([fitted]). `unscaled` is 100 %; an explicit `letterbox` or `stretched` asks for Fit (`null`); otherwise
     * Fit is off and the window starts at [fitted], so the image shows at the size Fit would have given it.
     */
    fun initialZoom(renderFit: String?, fitted: Float): Float? = when (renderFit) {
        "unscaled" -> 1f
        "letterbox", "stretched" -> null
        else -> fitted
    }

    fun label(scale: Float): String = "${(scale * 100).roundToInt()}%"

    /**
     * Cmd (macOS) or Ctrl + `=`/`+` zoom in, `-` zoom out, `0` fit. Null for any other key. [modifiersEx] is the AWT
     * extended modifier mask.
     */
    fun shortcut(keyCode: Int, modifiersEx: Int, mac: Boolean): ZoomCommand? {
        val mod = if (mac) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
        if (modifiersEx and mod == 0) return null
        return when (keyCode) {
            AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_PLUS, AwtKeyEvent.VK_ADD -> ZoomCommand.In
            AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_SUBTRACT -> ZoomCommand.Out
            AwtKeyEvent.VK_0, AwtKeyEvent.VK_NUMPAD0 -> ZoomCommand.Fit
            else -> null
        }
    }
}

/**
 * The window's zoom-shortcut key handling. [handle] returns true for events the window consumes: the shortcut press
 * (it also calls the zoom), repeats, the key-up of a handled key and the typed character of a Cmd/Ctrl chord while a
 * handled key is down. [reset] forgets held keys (the window lost focus, so their releases never arrive).
 */
class ZoomKeyFilter(private val mac: Boolean) {
    private val down = mutableSetOf<Int>()

    fun handle(id: Int, keyCode: Int, modifiersEx: Int, apply: (ZoomCommand) -> Unit): Boolean {
        if (id == AwtKeyEvent.KEY_PRESSED) {
            Zoom.shortcut(keyCode, modifiersEx, mac)?.let {
                down += keyCode
                apply(it)
                return true
            }
        }
        if (down.isEmpty()) return false
        val chord = AwtKeyEvent.META_DOWN_MASK or AwtKeyEvent.CTRL_DOWN_MASK
        return when (id) {
            AwtKeyEvent.KEY_RELEASED -> down.remove(keyCode)
            AwtKeyEvent.KEY_TYPED -> modifiersEx and chord != 0
            AwtKeyEvent.KEY_PRESSED -> keyCode in down
            else -> false
        }
    }

    fun reset() = down.clear()
}
