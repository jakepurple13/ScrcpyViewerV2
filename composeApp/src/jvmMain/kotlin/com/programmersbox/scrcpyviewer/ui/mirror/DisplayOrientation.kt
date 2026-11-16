package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues

/**
 * The client-side display transform of v5.0 `--display-orientation` (`enum sc_orientation`): [quarterTurns]
 * clockwise rotations, after a horizontal flip when [flip] is set. It only changes how the frame is drawn and how
 * window points map back to the frame (`screen.c`); the device and the recording are not affected.
 */
enum class DisplayOrientation(val quarterTurns: Int, val flip: Boolean) {
    R0(0, false), R90(1, false), R180(2, false), R270(3, false),
    Flip0(0, true), Flip90(1, true), Flip180(2, true), Flip270(3, true);

    /** Clockwise rotation in degrees. */
    val degrees: Float get() = 90f * quarterTurns

    /** Width and height trade places (90 and 270). */
    val swaps: Boolean get() = quarterTurns % 2 == 1

    /** Size of the displayed image for a frame of [frame]. */
    fun orientedSize(frame: IntSize): IntSize = if (swaps) IntSize(frame.height, frame.width) else frame

    /**
     * Frame coordinates of the point ([x], [y]) of the displayed image, whose size is [w] x [h] (the oriented size):
     * `sc_screen_convert_window_to_frame_coords()`.
     */
    fun toFrame(x: Double, y: Double, w: Double, h: Double): Pair<Double, Double> = when (this) {
        R0 -> x to y
        R90 -> y to w - x
        R180 -> w - x to h - y
        R270 -> h - y to x
        Flip0 -> w - x to y
        Flip90 -> h - y to w - x
        Flip180 -> x to h - y
        Flip270 -> y to x
    }

    /** The inverse of [toFrame]: where the frame point ([x], [y]) shows in the displayed image of [w] x [h]. */
    fun toDisplayed(x: Double, y: Double, w: Double, h: Double): Pair<Double, Double> = when (this) {
        R0 -> x to y
        R90 -> w - y to x
        R180 -> w - x to h - y
        R270 -> y to h - x
        Flip0 -> w - x to y
        Flip90 -> w - y to h - x
        Flip180 -> x to h - y
        Flip270 -> y to x
    }

    companion object {
        /** `0`..`270`, `flip0`..`flip270` (cli.c `parse_orientation`); anything else is [R0]. */
        fun parse(value: String?): DisplayOrientation {
            val flip = value?.startsWith("flip") == true
            val turns = when (value?.removePrefix("flip")) {
                "90" -> 1
                "180" -> 2
                "270" -> 3
                else -> 0
            }
            return entries.first { it.quarterTurns == turns && it.flip == flip }
        }

        /** `display-orientation`, else `orientation` (v5.0 cli.c: `--orientation` sets display and record). */
        fun forOptions(values: OptionValues): DisplayOrientation =
            parse(values.entries["display-orientation"] ?: values.entries["orientation"])
    }
}
