package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import kotlin.math.hypot
import kotlinx.coroutines.launch
import kotlin.math.max

/** How long the accent fill takes to grow over a tile, and to shrink away. */
const val TILE_FILL_MILLIS = 420
const val TILE_UNFILL_MILLIS = 320

/** The on-accent text and icon colors follow the fill after this delay, over [TILE_CONTENT_MILLIS]. */
const val TILE_CONTENT_DELAY_MILLIS = 100
const val TILE_CONTENT_MILLIS = 220

/** The icon's squash-and-pop on a state change: down to [TILE_SQUASH_SCALE], then back with an overshoot. */
const val TILE_SQUASH_SCALE = 0.75f
const val TILE_SQUASH_MILLIS = 380
private const val SQUASH_DOWN_MILLIS = 120

/** A spring-like curve that overshoots its target a little before settling. */
val OvershootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * The radius a circle centred at [origin] needs to cover a [size] rectangle: the distance to its furthest corner.
 * An [origin] outside the rectangle is clamped to its edge first, so the fill always grows from the tile.
 */
fun tileFillRadius(origin: Offset, size: Size): Float {
    val x = origin.x.coerceIn(0f, size.width)
    val y = origin.y.coerceIn(0f, size.height)
    return hypot(max(x, size.width - x), max(y, size.height - y))
}

/** Where the fill grows from: the last press, or the centre of [size] when there is none (a keyboard toggle). */
fun tileFillOrigin(press: Offset?, size: Size): Offset = press ?: Offset(size.width / 2, size.height / 2)

/**
 * A toggle tile's radial fill. [progress] is 0 (empty) to 1 (covers the tile); the circle's radius is progress times
 * [tileFillRadius] from [origin], the last press on the tile (a key press reports the centre).
 */
@Stable
class TileFillState(on: Boolean) {
    val progress = Animatable(if (on) 1f else 0f)
    val iconScale = Animatable(1f)

    /** The press point the fill grows from and shrinks toward; null means the centre. Plain field: read in draw only. */
    var origin: Offset? = null
        private set

    fun pressed(at: Offset) {
        origin = at
    }

    suspend fun animateTo(on: Boolean) {
        if (on) {
            progress.animateTo(1f, tween(TILE_FILL_MILLIS, easing = EmphasizedDecelerate))
        } else {
            progress.animateTo(0f, tween(TILE_UNFILL_MILLIS, easing = EmphasizedAccelerate))
        }
    }

    suspend fun squash() {
        iconScale.animateTo(TILE_SQUASH_SCALE, tween(SQUASH_DOWN_MILLIS, easing = FastOutSlowInEasing))
        iconScale.animateTo(1f, tween(TILE_SQUASH_MILLIS - SQUASH_DOWN_MILLIS, easing = OvershootEasing))
    }
}

/**
 * Remembers a tile's fill, animating it whenever [on] changes, whether from a click here or a state read back from
 * the device; the first composition shows [on] as is.
 */
@Composable
fun rememberTileFill(on: Boolean): TileFillState {
    val fill = remember { TileFillState(on) }
    LaunchedEffect(fill, on) {
        if ((fill.progress.targetValue == 1f) == on) return@LaunchedEffect
        launch { fill.squash() }
        fill.animateTo(on)
    }
    return fill
}

/**
 * Feeds [fill] the press points of the clickable using [interactions]: a pointer press reports where it went down, a
 * key press (Enter, Space) the tile's centre. The clickable may report a quick tap only on release; the fill reads
 * its origin while drawing, so a late press still lands before the state change it causes is drawn.
 */
@Composable
fun TrackTilePresses(fill: TileFillState, interactions: InteractionSource) {
    LaunchedEffect(fill, interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Press) fill.pressed(it.pressPosition) }
    }
}

/** Draws [fill]'s circle in [color]; clip the tile to its shape before this. Reads the animation in draw only. */
fun Modifier.tileFill(fill: TileFillState, color: Color): Modifier = drawBehind {
    val p = fill.progress.value
    if (p <= 0f) return@drawBehind
    val origin = tileFillOrigin(fill.origin, size)
    drawCircle(color, radius = p * tileFillRadius(origin, size), center = origin)
}
