package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/** One sweep of the skeleton's shimmer. */
const val SHIMMER_MILLIS = 1000

/** A phone's screen shape (portrait), for the skeleton before the frame size is known. */
private const val PHONE_ASPECT = 9f / 19.5f

/** Room left around the skeleton, and below it for the caption. */
private val SKELETON_MARGIN = 32.dp
private val CAPTION_ROOM = 40.dp

/**
 * The skeleton's size inside a [maxWidth] x [maxHeight] stage: the [frame]'s shape (a phone's, portrait, before it
 * is known), as large as fits with a margin and room for the caption, but never more than 70 % of the stage's height.
 */
internal fun skeletonSize(maxWidth: Dp, maxHeight: Dp, frame: IntSize?): Pair<Dp, Dp> {
    val aspect = frame?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: PHONE_ASPECT
    val availW = (maxWidth - SKELETON_MARGIN * 2).coerceAtLeast(24.dp)
    val availH = min(maxHeight - SKELETON_MARGIN * 2 - CAPTION_ROOM, maxHeight * 0.7f).coerceAtLeast(40.dp)
    val h = min(availH, availW / aspect)
    return (h * aspect) to h
}

/**
 * The stage while the session connects: a phone-shaped outline with a diagonal shimmer sweeping across it, and a
 * "Connecting…" caption. [frame] is the displayed frame size, once the session knows it. The shimmer runs in the
 * draw phase only.
 */
@Composable
fun StageSkeleton(frame: IntSize?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.outlineVariant
    val fill = colors.surfaceContainerHigh.copy(alpha = 0.5f)
    val shine = colors.onSurface.copy(alpha = 0.08f)
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val (w, h) = skeletonSize(maxWidth, maxHeight, frame)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(w, h).drawWithCache {
                    val corner = (minOf(size.width, size.height) * 0.09f).coerceIn(8.dp.toPx(), 28.dp.toPx())
                    val stroke = 1.5.dp.toPx()
                    val inset = stroke / 2
                    val shape = RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner))
                    val path = Path().apply { addRoundRect(shape) }
                    // A diagonal band (x + y within [start, start + 2 * band]) moving from off the top left corner
                    // to off the bottom right one.
                    val band = minOf(size.width, size.height) * 0.6f
                    val travel = size.width + size.height + band * 2
                    onDrawBehind {
                        drawPath(path, fill)
                        clipPath(path) {
                            val start = -band * 2 + travel * sweep
                            drawRect(
                                Brush.linearGradient(
                                    0f to Color.Transparent, 0.5f to shine, 1f to Color.Transparent,
                                    start = Offset(start, 0f),
                                    end = Offset(start + band, band),
                                ),
                            )
                        }
                        drawPath(path, outline, style = Stroke(stroke))
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Text("Connecting…", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}
