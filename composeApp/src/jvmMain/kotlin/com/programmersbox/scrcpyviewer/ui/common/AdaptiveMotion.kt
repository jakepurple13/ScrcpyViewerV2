package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp

/** True when the OS asks for less motion; movement then becomes a short fade or an instant change. */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** The one spring every layout movement uses. */
fun <T> layoutSpring(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * 1 when [on], else 0: gliding there with [layoutSpring] when [glide], else the target itself in the same frame (an
 * animation's snap lands a frame later, so parts of a screen would arrive one after another). Without [glide] no
 * animation is composed.
 */
@Composable
internal fun layoutFraction(on: Boolean, glide: Boolean): State<Float> {
    val target = if (on) 1f else 0f
    return if (glide) animateFloatAsState(target, layoutSpring()) else rememberUpdatedState(target)
}

/** [target], gliding with [layoutSpring] when [glide], else at once (see [layoutFraction]). */
@Composable
internal fun layoutDp(target: Dp, glide: Boolean): State<Dp> =
    if (glide) animateDpAsState(target, layoutSpring()) else rememberUpdatedState(target)

/**
 * True while [width] changes (a window drag): from the frame it changes until a frame after it stops. A lazy list
 * passes no placement spec then, so its items follow the drag at once instead of gliding behind it.
 */
@Composable
fun resizing(width: Dp): Boolean {
    val settled = remember { mutableStateOf(width) }
    LaunchedEffect(width) {
        withFrameNanos { }
        settled.value = width
    }
    return settled.value != width
}

/** How long a [layoutSpring] glide takes to settle, near enough: what [flipGliding] waits (in frame time). */
const val LAYOUT_SPRING_SETTLE_MILLIS = 600L

/**
 * True from the frame [shape] changes (a list's column count, a row's stacked shape) until a [layoutSpring] glide
 * started then has settled ([LAYOUT_SPRING_SETTLE_MILLIS] of frame time). False on first composition.
 */
@Composable
fun flipGliding(shape: Any): Boolean {
    val seen = remember { mutableStateOf(shape) }
    val gliding = remember { mutableStateOf(false) }
    LaunchedEffect(shape) {
        if (seen.value == shape && !gliding.value) return@LaunchedEffect
        seen.value = shape
        gliding.value = true
        try {
            val start = withFrameMillis { it }
            while (withFrameMillis { it } - start < LAYOUT_SPRING_SETTLE_MILLIS) Unit
        } finally {
            gliding.value = false
        }
    }
    return gliding.value || seen.value != shape
}

/** 1 when moving to a later (or the same) item, -1 when moving back. */
fun switchDirection(fromIndex: Int, toIndex: Int): Int = if (toIndex >= fromIndex) 1 else -1

/** Item-switch content transition: a vertical slide and fade, or a plain fade under reduced motion. */
fun AnimatedContentTransitionScope<*>.itemSwitchTransform(direction: Int, reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
    } else {
        (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(220)) { direction * it / 12 })
            .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -direction * it / 24 })
    }
