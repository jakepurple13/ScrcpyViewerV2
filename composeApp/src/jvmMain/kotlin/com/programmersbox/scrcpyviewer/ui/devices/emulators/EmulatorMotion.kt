package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import kotlin.math.pow
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring

// The Emulators section's motion (spec "Motion" table). Every change takes [motion]; without it (reduced motion) each
// one is instant or a [EMULATOR_REDUCED_MILLIS] fade, and the spinners still turn.

/** The chevron turns -90 degrees as the section folds. */
internal const val FOLD_CHEVRON_MILLIS = 350

/** The section body fades over this long while its height follows the layout spring. */
internal const val FOLD_BODY_MILLIS = 400

/** New row controls fade up [CONTROLS_RISE] over this long; the old ones fade out over [CONTROLS_OUT_MILLIS]. */
internal const val CONTROLS_IN_MILLIS = 320
internal const val CONTROLS_OUT_MILLIS = 150
internal val CONTROLS_RISE = 6.dp

/** A row's subtitle crossfades with a [SUBTITLE_RISE] upward slide. */
internal const val SUBTITLE_MILLIS = 200
internal val SUBTITLE_RISE = 4.dp

/** A state chip's colours ease to the new state's. */
internal const val CHIP_COLOR_MILLIS = 300

/** The boot progress track grows in, moves its fill each second, and shrinks away after ready. */
internal const val TRACK_IN_MILLIS = 200
internal const val TRACK_TICK_MILLIS = 300
internal const val TRACK_OUT_MILLIS = 300

/** The header's AVD count: the old number slides up and out, the new one in from below. */
internal const val COUNT_MILLIS = 200

/** An empty or error state and the rows swap with a crossfade and a height change. */
internal const val BODY_SWAP_MILLIS = 300

/** Reduced motion: a plain fade. */
internal const val EMULATOR_REDUCED_MILLIS = 150

/** A More menu item that comes or goes (Cold boot), and Wipe data's enabled state, crossfade. */
internal const val MENU_ITEM_MILLIS = 200

/** Wipe data's alpha while disabled. */
internal const val MENU_ITEM_DISABLED_ALPHA = 0.38f

/** The wipe dialog's scrim fades in over this long. */
internal const val WIPE_SCRIM_MILLIS = 300

/** The wipe dialog's card scales from [WIPE_CARD_FROM_SCALE] and rises [WIPE_CARD_RISE] over this long. */
internal const val WIPE_OPEN_MILLIS = 450
internal const val WIPE_CARD_FROM_SCALE = 0.88f
internal val WIPE_CARD_RISE = 14.dp

/** The card fades in over this long while it scales. */
internal const val WIPE_CARD_FADE_MILLIS = 250

/** Closing reverses the opening (scrim, scale, rise and fade) over this long. */
internal const val WIPE_CLOSE_MILLIS = 150

/** The progress track's estimate when this AVD has no saved warm boot. */
internal const val DEFAULT_BOOT_MILLIS = 30_000L

/** A cold boot is expected to take this much longer than a warm one. */
internal const val COLD_BOOT_FACTOR = 1.6

/** The fill never passes this until the emulator is ready. */
internal const val TRACK_CEILING = 0.96f

/** How long a boot is expected to take: the last warm boot, else [DEFAULT_BOOT_MILLIS]; [COLD_BOOT_FACTOR] for cold. */
internal fun expectedBootMillis(lastBootMillis: Long?, cold: Boolean): Long {
    val warm = lastBootMillis?.takeIf { it > 0 } ?: DEFAULT_BOOT_MILLIS
    return if (cold) (warm * COLD_BOOT_FACTOR).toLong() else warm
}

/** The fill after [elapsedMillis] of a boot expected to take [expectedMillis]: eases out towards [TRACK_CEILING]. */
internal fun bootProgress(elapsedMillis: Long, expectedMillis: Long): Float {
    val t = (elapsedMillis.toDouble() / expectedMillis.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    return (TRACK_CEILING * (1 - (1 - t).pow(3))).toFloat()
}

private fun <T> reduced(): FiniteAnimationSpec<T> = tween(EMULATOR_REDUCED_MILLIS, easing = LinearEasing)

internal fun foldEnter(motion: Boolean): EnterTransition =
    if (motion) {
        expandVertically(layoutSpring(), expandFrom = Alignment.Top) +
            fadeIn(tween(FOLD_BODY_MILLIS, easing = EmphasizedDecelerate))
    } else {
        fadeIn(reduced())
    }

internal fun foldExit(motion: Boolean): ExitTransition =
    if (motion) {
        shrinkVertically(layoutSpring(), shrinkTowards = Alignment.Top) +
            fadeOut(tween(FOLD_BODY_MILLIS, easing = EmphasizedAccelerate))
    } else {
        fadeOut(reduced())
    }

/** The chevron's turn: −90 degrees when folded. */
internal fun chevronAngle(expanded: Boolean): Float = if (expanded) 0f else -90f

internal fun chevronSpec(motion: Boolean): FiniteAnimationSpec<Float> =
    if (motion) tween(FOLD_CHEVRON_MILLIS, easing = EmphasizedDecelerate) else snap()

/** A size change rides the shared [layoutSpring]; it snaps without [motion]. */
private fun sizeSpec(motion: Boolean): FiniteAnimationSpec<IntSize> = if (motion) layoutSpring() else snap()

/** Row controls: new ones fade up [risePx] over 320 ms while the old fade out in 150 ms; the width follows on the layout spring. */
internal fun <S> AnimatedContentTransitionScope<S>.controlsTransform(motion: Boolean, risePx: Int): ContentTransform =
    if (motion) {
        (fadeIn(tween(CONTROLS_IN_MILLIS, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(CONTROLS_IN_MILLIS, easing = EmphasizedDecelerate)) { risePx }) togetherWith
            fadeOut(tween(CONTROLS_OUT_MILLIS, easing = EmphasizedAccelerate)) using
            SizeTransform(clip = false) { _, _ -> sizeSpec(motion) }
    } else {
        fadeIn(reduced()) togetherWith fadeOut(reduced()) using SizeTransform(clip = false) { _, _ -> snap() }
    }

/** The state chip appears beside the button (Start → booting) or leaves (back to Stopped), widening the controls. */
internal fun chipEnter(motion: Boolean, risePx: Int): EnterTransition =
    if (motion) {
        fadeIn(tween(CONTROLS_IN_MILLIS, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(CONTROLS_IN_MILLIS, easing = EmphasizedDecelerate)) { risePx } +
            expandHorizontally(layoutSpring(), expandFrom = Alignment.End)
    } else {
        fadeIn(reduced())
    }

internal fun chipExit(motion: Boolean): ExitTransition =
    if (motion) {
        fadeOut(tween(CONTROLS_OUT_MILLIS, easing = EmphasizedAccelerate)) +
            shrinkHorizontally(layoutSpring(), shrinkTowards = Alignment.End)
    } else {
        fadeOut(reduced())
    }

/** Subtitles crossfade with a [risePx] upward slide over 200 ms. */
internal fun <S> AnimatedContentTransitionScope<S>.subtitleTransform(motion: Boolean, risePx: Int): ContentTransform =
    if (motion) {
        (fadeIn(tween(SUBTITLE_MILLIS, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(SUBTITLE_MILLIS, easing = EmphasizedDecelerate)) { risePx }) togetherWith
            (fadeOut(tween(SUBTITLE_MILLIS, easing = EmphasizedAccelerate)) +
                slideOutVertically(tween(SUBTITLE_MILLIS, easing = EmphasizedAccelerate)) { -risePx }) using
            SizeTransform(clip = false) { _, _ -> sizeSpec(motion) }
    } else {
        fadeIn(reduced()) togetherWith fadeOut(reduced()) using SizeTransform(clip = false) { _, _ -> snap() }
    }

/** The count: the old number slides up and out, the new one in from below. */
internal fun <S> AnimatedContentTransitionScope<S>.countTransform(motion: Boolean): ContentTransform =
    if (motion) {
        (slideInVertically(tween(COUNT_MILLIS, easing = EmphasizedDecelerate)) { it } +
            fadeIn(tween(COUNT_MILLIS, easing = EmphasizedDecelerate))) togetherWith
            (slideOutVertically(tween(COUNT_MILLIS, easing = EmphasizedAccelerate)) { -it } +
                fadeOut(tween(COUNT_MILLIS, easing = EmphasizedAccelerate))) using
            SizeTransform(clip = true) { _, _ -> sizeSpec(motion) }
    } else {
        fadeIn(reduced()) togetherWith fadeOut(reduced()) using SizeTransform(clip = true) { _, _ -> snap() }
    }

/** Empty or error state ↔ rows: a crossfade while the height changes. */
internal fun <S> AnimatedContentTransitionScope<S>.bodyTransform(motion: Boolean): ContentTransform =
    if (motion) {
        fadeIn(tween(BODY_SWAP_MILLIS, easing = EmphasizedDecelerate)) togetherWith
            fadeOut(tween(BODY_SWAP_MILLIS, easing = EmphasizedAccelerate)) using
            SizeTransform(clip = true) { _, _ -> sizeSpec(motion) }
    } else {
        fadeIn(reduced()) togetherWith fadeOut(reduced()) using SizeTransform(clip = true) { _, _ -> snap() }
    }

internal fun chipColorSpec(motion: Boolean): FiniteAnimationSpec<Color> =
    if (motion) tween(CHIP_COLOR_MILLIS, easing = EmphasizedDecelerate) else snap()

internal fun trackEnter(motion: Boolean): EnterTransition =
    if (motion) {
        expandVertically(layoutSpring(), expandFrom = Alignment.Top) +
            fadeIn(tween(TRACK_IN_MILLIS, easing = EmphasizedDecelerate))
    } else {
        fadeIn(reduced())
    }

internal fun trackExit(motion: Boolean): ExitTransition =
    if (motion) {
        shrinkVertically(layoutSpring(), shrinkTowards = Alignment.Top) +
            fadeOut(tween(TRACK_OUT_MILLIS, easing = EmphasizedAccelerate))
    } else {
        fadeOut(reduced())
    }

/** A More menu item coming in: a 200 ms fade while its height grows; reduced motion fades only. */
internal fun menuItemEnter(motion: Boolean): EnterTransition =
    if (motion) {
        fadeIn(tween(MENU_ITEM_MILLIS, easing = EmphasizedDecelerate)) +
            expandVertically(layoutSpring(), expandFrom = Alignment.Top)
    } else {
        fadeIn(reduced())
    }

internal fun menuItemExit(motion: Boolean): ExitTransition =
    if (motion) {
        fadeOut(tween(MENU_ITEM_MILLIS, easing = EmphasizedAccelerate)) +
            shrinkVertically(layoutSpring(), shrinkTowards = Alignment.Top)
    } else {
        fadeOut(reduced())
    }

/** An item's enabled state crossfades over 200 ms (150 ms with reduced motion). */
internal fun menuItemAlphaSpec(motion: Boolean): FiniteAnimationSpec<Float> =
    if (motion) tween(MENU_ITEM_MILLIS, easing = EmphasizedDecelerate) else reduced()

/** The wipe dialog's scrim: fades in over 300 ms, out over 150 ms; reduced motion 150 ms both ways. */
internal fun wipeScrimSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> reduced()
    opening -> tween(WIPE_SCRIM_MILLIS, easing = LinearEasing)
    else -> tween(WIPE_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

/** The card's scale and rise, 0 (closed) to 1 (open): 450 ms in, 150 ms back; reduced motion keeps it still. */
internal fun wipeCardSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> snap()
    opening -> tween(WIPE_OPEN_MILLIS, easing = EmphasizedDecelerate)
    else -> tween(WIPE_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

/** The card's opacity: 250 ms in, 150 ms out; reduced motion a 150 ms fade. */
internal fun wipeCardFadeSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> reduced()
    opening -> tween(WIPE_CARD_FADE_MILLIS, easing = EmphasizedDecelerate)
    else -> tween(WIPE_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

/** Each second's fill moves linearly; reduced motion jumps to it. */
internal fun trackFillSpec(motion: Boolean): FiniteAnimationSpec<Float> =
    if (motion) tween(TRACK_TICK_MILLIS, easing = LinearEasing) else snap()
