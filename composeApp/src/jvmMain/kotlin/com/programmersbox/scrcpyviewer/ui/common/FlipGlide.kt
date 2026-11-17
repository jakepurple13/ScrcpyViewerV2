package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A layout's discrete shape (a grid's column count, a row stacked or not), noted by its [FlipNotingPolicy] on each
 * lookahead pass. [glideOnFlip] children read [count]: it goes up each time the shape changes.
 */
class LayoutFlips {
    private var shape: Any? = null

    /** How many times the shape has changed. */
    var count: Int = 0
        private set

    internal fun note(shape: Any) {
        if (this.shape != null && this.shape != shape) count++
        this.shape = shape
    }
}

/**
 * A measure policy that tells [flips] its shape. Only the lookahead pass counts: the main pass may measure at
 * in-between sizes while a parent glides, and intrinsic queries measure with made-up constraints; neither is the
 * layout's real shape.
 */
abstract class FlipNotingPolicy(private val flips: LayoutFlips) : MeasurePolicy {
    private var intrinsic = false

    /** This policy's own measure, with Compose's default intrinsics (which measure through it). */
    private val plain = MeasurePolicy { measurables, constraints ->
        val scope = this
        with(this@FlipNotingPolicy) { scope.measure(measurables, constraints) }
    }

    /** Call from [measure] with the shape these constraints give. */
    protected fun MeasureScope.noteShape(shape: Any) {
        if (isLookingAhead && !intrinsic) flips.note(shape)
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val was = intrinsic
        intrinsic = true
        try {
            return with(plain) { minIntrinsicWidth(measurables, height) }
        } finally {
            intrinsic = was
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val was = intrinsic
        intrinsic = true
        try {
            return with(plain) { maxIntrinsicWidth(measurables, height) }
        } finally {
            intrinsic = was
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val was = intrinsic
        intrinsic = true
        try {
            return with(plain) { minIntrinsicHeight(measurables, width) }
        } finally {
            intrinsic = was
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val was = intrinsic
        intrinsic = true
        try {
            return with(plain) { maxIntrinsicHeight(measurables, width) }
        } finally {
            intrinsic = was
        }
    }
}

/**
 * Glides a child of a layout inside [lookahead] to its new bounds with [layoutSpring] when the layout flips ([flips]
 * counts a new shape). Every other change, such as the width following a window drag, lands at once, so the child
 * follows a drag directly; a drag during a glide re-aims it. Instant without [enabled] (reduced motion).
 */
@Composable
fun Modifier.glideOnFlip(lookahead: LookaheadScope, flips: LayoutFlips, enabled: Boolean = !LocalReduceMotion.current): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val glide = remember { FlipGlide() }
    return approachLayout(
        isMeasurementApproachInProgress = { glide.gliding },
        isPlacementApproachInProgress = { glide.gliding },
    ) { measurable, constraints ->
        val size = glide.sizeFor(lookaheadSize, flips.count, scope)
        val placeable = if (glide.gliding) {
            measurable.measure(Constraints.fixed(size.width, size.height))
        } else {
            measurable.measure(constraints)
        }
        layout(placeable.width, placeable.height) {
            val coordinates = coordinates
            if (coordinates == null) {
                placeable.place(0, 0)
            } else {
                val (target, placed) = with(lookahead) {
                    lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates).round() to
                        lookaheadScopeCoordinates.localPositionOf(coordinates, Offset.Zero).round()
                }
                placeable.place(glide.offsetFor(target, scope) - placed)
            }
        }
    }
}

/** Where and how big a child is shown: its target, or on the way there after a flip. */
private class FlipGlide {
    private var generation: Int? = null
    private var lastSize: IntSize? = null
    private var lastOffset: IntOffset? = null
    private var size: Animatable<IntSize, AnimationVector2D>? = null
    private var offset: Animatable<IntOffset, AnimationVector2D>? = null
    private var offsetFrom: IntOffset? = null
    private var running = 0

    /** True from a flip until the child has settled at its bounds. */
    val gliding: Boolean get() = running > 0 || offsetFrom != null

    fun sizeFor(target: IntSize, flips: Int, scope: CoroutineScope): IntSize {
        val previous = generation
        generation = flips
        val shownSize = size?.takeIf { gliding }?.value ?: lastSize
        if (previous != null && previous != flips && shownSize != null) {
            // A flip: start both glides from where the child is shown now (mid-glide too).
            size = Animatable(shownSize, IntSize.VectorConverter).also { animate(it, target, scope) }
            offsetFrom = offset?.takeIf { running > 0 }?.value ?: lastOffset
        } else {
            size?.let { if (gliding && it.targetValue != target) animate(it, target, scope) }
        }
        lastSize = target
        val shown = if (gliding) size?.value ?: target else target
        return IntSize(shown.width.coerceAtLeast(0), shown.height.coerceAtLeast(0))
    }

    fun offsetFor(target: IntOffset, scope: CoroutineScope): IntOffset {
        lastOffset = target
        offsetFrom?.let { from ->
            offsetFrom = null
            offset = Animatable(from, IntOffset.VectorConverter).also { animate(it, target, scope) }
        }
        val anim = offset?.takeIf { gliding } ?: return target
        if (anim.targetValue != target) animate(anim, target, scope)
        return anim.value
    }

    private fun <T> animate(anim: Animatable<T, AnimationVector2D>, target: T, scope: CoroutineScope) {
        running++
        scope.launch {
            try {
                anim.animateTo(target, layoutSpring())
            } finally {
                running--
            }
        }
    }
}
