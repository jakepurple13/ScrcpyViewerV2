package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Room kept clear around a dialog, so it never touches the window edge. */
private val DialogMargin = 16.dp

/**
 * Sizes a dialog card: [designed] wide in a roomy window, otherwise as wide as the window less a 16 dp margin on
 * each side, and never taller than the window less that margin (its body then scrolls).
 */
fun Modifier.dialogWidth(designed: Dp): Modifier = layout { measurable, constraints ->
    val margin = (DialogMargin * 2).roundToPx()
    val availableWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - margin).coerceAtLeast(0) else Int.MAX_VALUE
    val width = minOf(designed.roundToPx(), availableWidth)
    val maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - margin).coerceAtLeast(0) else Constraints.Infinity
    val placeable = measurable.measure(
        Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxHeight),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Slides a child to its new bounds when a dialog restacks (side by side to stacked); a short move under reduced motion. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.stackMotion(scope: LookaheadScope): Modifier {
    val reduce = LocalReduceMotion.current
    return animateBounds(
        scope,
        boundsTransform = { _, _ -> if (reduce) tween(120) else layoutSpring() },
    )
}

/**
 * Follows the content's height with [layoutSpring] when it changes (a restack makes the card taller or shorter).
 * Unlike `animateContentSize` the width is never animated, so dragging the window edge stays 1:1.
 * Clips to the animated height, so content taller than it does not draw outside during a grow.
 * Changes instantly under reduced motion.
 */
@Composable
fun Modifier.animateHeight(): Modifier =
    if (LocalReduceMotion.current) this else this then AnimateHeightElement then Modifier.clipToBounds()

private object AnimateHeightElement : ModifierNodeElement<AnimateHeightNode>() {
    override fun create() = AnimateHeightNode()
    override fun update(node: AnimateHeightNode) = Unit
    override fun hashCode() = 1
    override fun equals(other: Any?) = other === this
}

private class AnimateHeightNode : Modifier.Node(), LayoutModifierNode {
    private var height: Animatable<Int, *>? = null

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val target = placeable.height
        val animatable = height ?: Animatable(target, Int.VectorConverter).also { height = it }
        if (animatable.targetValue != target) {
            coroutineScope.launch { animatable.animateTo(target, layoutSpring()) }
        }
        val shown = animatable.value.coerceAtLeast(0)
        return layout(placeable.width, shown) { placeable.place(0, 0) }
    }
}
