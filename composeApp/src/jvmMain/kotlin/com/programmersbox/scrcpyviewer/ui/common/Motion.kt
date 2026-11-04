package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/** The app's emphasized curves: things arriving decelerate, things leaving accelerate. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Duration of something growing in (emphasized-decelerate) and of something shrinking away (emphasized-accelerate). */
const val OPEN_MILLIS = 260
const val CLOSE_MILLIS = 200

/** Content that opens its space downward while fading in. */
fun expandFadeIn(): EnterTransition =
    expandVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) + fadeIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate))

/** Content that fades out while its space closes upward. */
fun shrinkFadeOut(): ExitTransition =
    shrinkVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) + fadeOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate))
