package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation

/** Which way the overlay plays: the window appearing, or the window going away. */
enum class LaunchPhase { Launch, Quit }

/** A rectangle in fractions of the main window's bounds (0..1 on each axis). */
data class FractionRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * One frame of a launch or quit animation, in the main window's own terms. Fractions are of the window's bounds,
 * alphas 0..1. The overlay draws it (see OverlayScene).
 */
data class OverlayFrame(
    /** Where the window picture is visible. */
    val windowClip: FractionRect,
    /** The clip's corner radius in dp. */
    val windowCorner: Float,
    val windowAlpha: Float,
    /** Scale about the window's centre (Screen on's quit). */
    val windowScaleX: Float,
    val windowScaleY: Float,
    /** 1 = unchanged; above 1 brightens toward white. */
    val windowBrightness: Float,
    /** The app icon, centred on the window. */
    val iconScale: Float,
    val iconAlpha: Float,
    /** Downward offset in fractions of the window's height. */
    val iconOffsetY: Float,
    /** 0 = the back phone hidden behind the front one, 1 = slid out to its place in the icon. */
    val backPhoneProgress: Float,
    /** 0..1 teal on the icon's phone screen. */
    val screenFlash: Float,
    /** Screen on's scan line, as a fraction of the window's height; null when there is none. */
    val beamY: Float?,
    val beamAlpha: Float,
    /** The launch has finished: the real window may replace the overlay. */
    val showRealWindow: Boolean,
)

/** The launch and quit animations as pure functions of time (ms from the start). Timings from the design doc. */
object LaunchMotion {
    /** The icon's phone screen in window fractions: where Icon unfold grows from and folds into. */
    val PhoneScreen = FractionRect(0.44f, 0.30f, 0.56f, 0.75f)
    private val Full = FractionRect(0f, 0f, 1f, 1f)

    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    private val Sweep = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val Crt = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)

    private const val WINDOW_CORNER = 12f
    private const val PHONE_CORNER = 18f

    fun durationMs(anim: LaunchQuitAnimation, phase: LaunchPhase): Int = when (anim) {
        LaunchQuitAnimation.IconUnfold -> if (phase == LaunchPhase.Launch) 720 else 420
        LaunchQuitAnimation.ScreenOn -> if (phase == LaunchPhase.Launch) 650 else 380
        LaunchQuitAnimation.Off -> throw IllegalArgumentException("Off has no animation")
    }

    fun frame(anim: LaunchQuitAnimation, phase: LaunchPhase, tMs: Int): OverlayFrame {
        val t = tMs.coerceIn(0, durationMs(anim, phase))
        return when (anim) {
            LaunchQuitAnimation.IconUnfold -> if (phase == LaunchPhase.Launch) iconUnfoldLaunch(t) else iconUnfoldQuit(t)
            LaunchQuitAnimation.ScreenOn -> if (phase == LaunchPhase.Launch) screenOnLaunch(t) else screenOnQuit(t)
            LaunchQuitAnimation.Off -> error("unreachable")
        }
    }

    private fun base() = OverlayFrame(
        windowClip = Full, windowCorner = WINDOW_CORNER, windowAlpha = 1f, windowScaleX = 1f, windowScaleY = 1f,
        windowBrightness = 1f, iconScale = 1f, iconAlpha = 0f, iconOffsetY = 0f, backPhoneProgress = 1f,
        screenFlash = 0f, beamY = null, beamAlpha = 0f, showRealWindow = false,
    )

    // Icon scales in (0–260), back phone slides out (140–400), the screen flashes teal (180–540), then the window
    // grows out of the phone screen (360–700) while the icon fades and swells away (360–560).
    private fun iconUnfoldLaunch(t: Int): OverlayFrame {
        val iconIn = seg(t, 0, 260, Decelerate)
        val iconOut = seg(t, 360, 560, Accelerate)
        val grow = seg(t, 360, 700, Emphasized)
        return base().copy(
            windowClip = lerp(PhoneScreen, Full, grow),
            windowCorner = lerp(PHONE_CORNER, WINDOW_CORNER, grow),
            windowAlpha = seg(t, 360, 460, LinearEasing),
            iconScale = lerp(0.6f, 1f, iconIn) + 0.25f * iconOut,
            iconAlpha = iconIn * (1f - iconOut),
            backPhoneProgress = seg(t, 140, 400, Decelerate),
            screenFlash = triangle(t, 180, 360, 540),
            showRealWindow = t >= 720,
        )
    }

    // The window folds into the phone screen (0–260, gone by 260); the icon appears over it (160–250), then shrinks and
    // drops toward the bottom of the window (250–420).
    private fun iconUnfoldQuit(t: Int): OverlayFrame {
        val fold = seg(t, 0, 260, Accelerate)
        val iconIn = seg(t, 160, 250, Decelerate)
        val drop = seg(t, 250, 420, Accelerate)
        return base().copy(
            windowClip = lerp(Full, PhoneScreen, fold),
            windowCorner = lerp(WINDOW_CORNER, PHONE_CORNER, fold),
            windowAlpha = 1f - seg(t, 195, 260, LinearEasing),
            iconScale = lerp(lerp(1.1f, 1f, iconIn), 0.25f, drop),
            iconAlpha = iconIn * (1f - drop),
            iconOffsetY = 0.6f * drop,
        )
    }

    // A teal line sweeps from top to bottom (0–520), revealing the window behind it, then fades (520–600); the window
    // gets a brief flash that settles by the end (520–650).
    private fun screenOnLaunch(t: Int): OverlayFrame {
        val y = seg(t, 0, 520, Sweep)
        val beamAlpha = if (t < 520) seg(t, 0, 52, LinearEasing) else 1f - seg(t, 520, 600, LinearEasing)
        return base().copy(
            windowClip = FractionRect(0f, 0f, 1f, y),
            beamY = y,
            beamAlpha = beamAlpha,
            windowBrightness = if (t < 520) 1f else lerp(1.4f, 1f, seg(t, 520, 650, Decelerate)),
            showRealWindow = t >= 650,
        )
    }

    // Old-TV power-off: squash to a bright line (0–220), the line to a dot (220–320), fade out (320–380).
    private fun screenOnQuit(t: Int): OverlayFrame {
        val squash = seg(t, 0, 220, Crt)
        val shrink = seg(t, 220, 320, Crt)
        val fade = seg(t, 320, 380, LinearEasing)
        return base().copy(
            windowScaleY = lerp(1f, 0.012f, squash),
            windowScaleX = lerp(1f, 0.02f, shrink),
            windowBrightness = lerp(1f, 2.5f, squash) + 0.5f * shrink,
            windowAlpha = 1f - fade,
        )
    }

    /** Eased progress 0..1 of [t] through [start]..[end]. */
    private fun seg(t: Int, start: Int, end: Int, easing: Easing): Float =
        easing.transform(((t - start).toFloat() / (end - start)).coerceIn(0f, 1f))

    /** 0 at [start], 1 at [peak], 0 again at [end]. */
    private fun triangle(t: Int, start: Int, peak: Int, end: Int): Float =
        if (t < peak) seg(t, start, peak, LinearEasing) else 1f - seg(t, peak, end, LinearEasing)

    private fun lerp(a: Float, b: Float, f: Float) = a * (1f - f) + b * f
    private fun lerp(a: FractionRect, b: FractionRect, f: Float) =
        FractionRect(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))
}
