package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The drop zone's dashed border: dash and gap lengths, and how long the dashes take to march one dash + gap. */
val DropDash = 10.dp
val DropGap = 7.dp
const val DROP_MARCH_MILLIS = 700

/** One bob of the drop icon: up [DROP_BOB_DP] and back, eased in and out. */
const val DROP_BOB_MILLIS = 1_600
const val DROP_BOB_DP = 4f

/** The not-allowed shake: horizontal offsets in dp, evenly spaced over [DROP_SHAKE_MILLIS], ending at rest. */
val DropShakeOffsets = listOf(-6f, 5f, -3f, 2f, 0f)
const val DROP_SHAKE_MILLIS = 360

/**
 * The dash phase (px, in `[0, cycle)`) that marches while [marching] and holds where it is while not. Read it in a
 * draw lambda only, so the march redraws without recomposing.
 */
@Composable
fun rememberMarchingPhase(marching: Boolean, cyclePx: Float): Animatable<Float, *> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(marching, cyclePx) {
        if (!marching || cyclePx <= 0f) return@LaunchedEffect
        while (true) {
            // Each leg is one full cycle at constant speed, whatever phase the last pause left.
            phase.animateTo(phase.value + cyclePx, tween(DROP_MARCH_MILLIS, easing = LinearEasing))
            phase.snapTo(phase.value % cyclePx)
        }
    }
    return phase
}

/** The icon's bob offset in dp (0 at rest, negative is up). Stops and settles at rest while not [bobbing]. */
@Composable
fun rememberBob(bobbing: Boolean): Animatable<Float, *> {
    val bob = remember { Animatable(0f) }
    LaunchedEffect(bobbing) {
        if (bobbing) {
            if (bob.value != 0f) bob.animateTo(0f, tween(150))
            bob.animateTo(
                -DROP_BOB_DP,
                infiniteRepeatable(tween(DROP_BOB_MILLIS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            )
        } else {
            bob.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        }
    }
    return bob
}

/** The horizontal shake offset in dp; it runs once each time [allowed] is false (on first show and on every flip). */
@Composable
fun rememberRefusalShake(allowed: Boolean): Animatable<Float, *> {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(allowed) {
        if (allowed) {
            shake.snapTo(0f)
            return@LaunchedEffect
        }
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = DROP_SHAKE_MILLIS
                val step = DROP_SHAKE_MILLIS / DropShakeOffsets.size
                DropShakeOffsets.dropLast(1).forEachIndexed { i, dp -> dp at step * (i + 1) using FastOutSlowInEasing }
            },
        )
    }
    return shake
}

/**
 * A [width] dashed rounded-rect border just inside the bounds. [color] and [phase] are read while drawing only, so a
 * color crossfade and the march redraw without recomposing or relayout.
 */
fun Modifier.marchingDashBorder(
    width: Dp,
    radius: Dp,
    dash: Dp = DropDash,
    gap: Dp = DropGap,
    color: () -> Color,
    phase: () -> Float,
): Modifier = drawBehind {
    val stroke = width.toPx()
    val inset = stroke / 2
    // Negative phase moves the dashes clockwise along the path.
    val effect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()), -phase())
    drawRoundRect(
        color = color(),
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx() - inset),
        style = Stroke(stroke, pathEffect = effect),
    )
}
