package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/** True when the OS asks for less motion; movement then becomes a short fade or an instant change. */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** The one spring every layout movement uses. */
fun <T> layoutSpring(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

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
