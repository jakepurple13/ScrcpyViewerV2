package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Glides a picker tile in a `FlowRow` (laid out inside [lookahead]) to its new place with [layoutSpring] when it wraps
 * onto another row. Every other change, such as the tile's width or a small shift while the window is dragged, lands
 * at once, so the tiles follow a drag directly. Under reduced motion the tile always moves at once.
 */
@Composable
internal fun Modifier.glideOnWrap(lookahead: LookaheadScope): Modifier {
    if (LocalReduceMotion.current) return this
    val scope = rememberCoroutineScope()
    val glide = remember { WrapGlide() }
    return approachLayout(
        isMeasurementApproachInProgress = { false },
        isPlacementApproachInProgress = { glide.gliding },
    ) { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val coordinates = coordinates
            if (coordinates == null) {
                placeable.place(0, 0)
            } else {
                val (target, placed) = with(lookahead) {
                    lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates).round() to
                        lookaheadScopeCoordinates.localPositionOf(coordinates, Offset.Zero).round()
                }
                placeable.place(glide.shownAt(target, placeable.height, scope) - placed)
            }
        }
    }
}

/** Where a tile is shown: its target, or on the way there after it wrapped onto another row. */
private class WrapGlide {
    private var last: IntOffset? = null
    private var anim: Animatable<IntOffset, AnimationVector2D>? = null

    /** True from a wrap until the tile has settled at its target. */
    var gliding = false
        private set

    fun shownAt(target: IntOffset, height: Int, scope: CoroutineScope): IntOffset {
        val previous = last
        last = target
        val running = anim
        if (previous != null && previous != target) {
            when {
                // Already on the way: head for the new target from where it is.
                gliding && running != null -> glide(running, target, scope)
                // Onto another row: start from where it was.
                abs(target.y - previous.y) > height / 2 -> {
                    val next = Animatable(previous, IntOffset.VectorConverter)
                    anim = next
                    glide(next, target, scope)
                }
            }
        }
        return if (gliding) anim?.value ?: target else target
    }

    private fun glide(anim: Animatable<IntOffset, AnimationVector2D>, target: IntOffset, scope: CoroutineScope) {
        gliding = true
        scope.launch {
            anim.animateTo(target, layoutSpring())
            // Not reached when a newer target cancels this one.
            gliding = false
        }
    }
}
