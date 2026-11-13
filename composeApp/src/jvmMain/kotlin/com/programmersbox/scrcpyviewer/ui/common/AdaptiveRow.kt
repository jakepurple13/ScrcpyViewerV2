package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How much room a row's trailing control gets: its full form, icons only, or a single menu button. */
enum class ControlSize { Full, Icons, Menu }

/** Where an adaptive row puts its control ([stacked] under the text, or inline) and at which [size]. */
data class RowFit(val stacked: Boolean, val size: ControlSize)

/**
 * Inline at [ControlSize.Full] when the text keeps [textMin] beside the control and [gap]. Otherwise the
 * control goes under the text at the largest size that fits [available]: [full], then [icons], then [menu].
 */
fun rowFit(available: Int, textMin: Int, gap: Int, full: Int, icons: Int = full, menu: Int = icons): RowFit =
    when {
        available - full - gap >= textMin -> RowFit(stacked = false, size = ControlSize.Full)
        available >= full -> RowFit(stacked = true, size = ControlSize.Full)
        available >= icons -> RowFit(stacked = true, size = ControlSize.Icons)
        else -> RowFit(stacked = true, size = ControlSize.Menu)
    }

/** Space between the text and a control stacked under it. */
private val StackedGap = 8.dp

/**
 * Text with a trailing control. The text keeps at least [textMin]: when the control's widest form would leave
 * it less, the control moves under the text (start-aligned, or full width with [fillControlWhenStacked]) and
 * steps down through [ControlSize] until it fits. Both slots glide to their new bounds and the row height
 * springs, all with [layoutSpring]; under reduced motion they change instantly.
 *
 * Do not put it under a parent that asks for intrinsic sizes (`height(IntrinsicSize.Min)` and the like):
 * it is a `SubcomposeLayout`, which throws on intrinsic queries.
 *
 * [control] is also composed in hidden probe slots, once per [ControlSize] tried, to read its max intrinsic
 * width. Keep it free of side effects (effects, focus requests, state writes): they would run in the probes too.
 */
@Composable
fun AdaptiveRow(
    text: @Composable () -> Unit,
    control: @Composable (ControlSize) -> Unit,
    modifier: Modifier = Modifier,
    textMin: Dp = 160.dp,
    gap: Dp = 16.dp,
    fillControlWhenStacked: Boolean = false,
) {
    val currentText by rememberUpdatedState(text)
    val currentControl by rememberUpdatedState(control)
    val reduceMotion = LocalReduceMotion.current
    // One stable lambda per fit, so remeasuring (every frame of a bounds animation) does not recompose slots.
    val probes = remember { ControlSize.entries.associateWith { size -> @Composable { currentControl(size) } } }
    val contents = remember(reduceMotion, gap, fillControlWhenStacked) {
        HashMap<RowFit, @Composable () -> Unit>()
    }
    val scope = rememberCoroutineScope()
    val rowHeight = remember { SpringyHeight() }

    SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val textMinPx = textMin.roundToPx()

        fun widthAt(size: ControlSize): Int =
            subcompose(size, probes.getValue(size)).maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0

        val fit = if (!constraints.hasBoundedWidth) {
            RowFit(stacked = false, size = ControlSize.Full)
        } else {
            val available = constraints.maxWidth
            val full = widthAt(ControlSize.Full)
            // Smaller forms are only composed when the full one cannot fit.
            val icons = if (available >= full) full else widthAt(ControlSize.Icons)
            val menu = if (available >= icons) icons else widthAt(ControlSize.Menu)
            rowFit(available, textMinPx, gapPx, full, icons, menu)
        }

        val content = contents.getOrPut(fit) {
            @Composable {
                AdaptiveRowContent(
                    fit = fit,
                    text = { currentText() },
                    control = { currentControl(fit.size) },
                    gap = gap,
                    fillControlWhenStacked = fillControlWhenStacked,
                    animate = !reduceMotion,
                )
            }
        }
        val placeables = subcompose(SlotContent, content).map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val target = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        val height = rowHeight.toward(target, animate = !reduceMotion, scope)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private object SlotContent

/**
 * The row's reported height: springs toward each new target so siblings below move with the slots instead of
 * jumping. The first target, and every target under reduced motion, is taken at once.
 */
private class SpringyHeight {
    private var animatable: Animatable<Float, *>? = null

    /** Returns the height to report now; reading it from measure remeasures on every animation frame. */
    fun toward(target: Int, animate: Boolean, scope: CoroutineScope): Int {
        val current = animatable
        if (current == null || !animate) {
            if (current?.targetValue != target.toFloat() || current.isRunning) {
                animatable = Animatable(target.toFloat())
            }
        } else if (current.targetValue != target.toFloat()) {
            scope.launch { current.animateTo(target.toFloat(), layoutSpring()) }
        }
        return animatable!!.value.roundToInt().coerceAtLeast(0)
    }
}

/** Lays out the text and control for one [fit]; the slots keep their identity across fits so they can glide. */
@Composable
private fun AdaptiveRowContent(
    fit: RowFit,
    text: @Composable () -> Unit,
    control: @Composable () -> Unit,
    gap: Dp,
    fillControlWhenStacked: Boolean,
    animate: Boolean,
) {
    LookaheadScope {
        val slot = if (animate) {
            Modifier.animateBounds(this, boundsTransform = { _, _ -> layoutSpring() })
        } else {
            Modifier
        }
        Layout(
            // propagateMinConstraints: the text's fixed width and a full-width stacked control reach the content.
            contents = listOf(
                { Box(slot, propagateMinConstraints = true) { text() } },
                { Box(slot, propagateMinConstraints = true) { control() } },
            ),
        ) { (textMeasurables, controlMeasurables), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val bounded = constraints.hasBoundedWidth
            if (fit.stacked) {
                val width = constraints.maxWidth
                val textPlaceable = textMeasurables.firstOrNull()?.measure(loose.copy(minWidth = width))
                val controlMin = if (fillControlWhenStacked) width else 0
                val controlPlaceable = controlMeasurables.firstOrNull()?.measure(loose.copy(minWidth = controlMin))
                val textHeight = textPlaceable?.height ?: 0
                val controlY = if (textPlaceable != null && controlPlaceable != null) textHeight + StackedGap.roundToPx() else textHeight
                val height = controlY + (controlPlaceable?.height ?: 0)
                layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                    textPlaceable?.place(0, 0)
                    controlPlaceable?.place(0, controlY)
                }
            } else {
                val gapPx = gap.roundToPx()
                val controlPlaceable = controlMeasurables.firstOrNull()?.measure(loose)
                val controlWidth = controlPlaceable?.width ?: 0
                val textConstraints = if (bounded) {
                    val textWidth = (constraints.maxWidth - controlWidth - gapPx).coerceAtLeast(0)
                    loose.copy(minWidth = textWidth, maxWidth = textWidth)
                } else {
                    loose
                }
                val textPlaceable = textMeasurables.firstOrNull()?.measure(textConstraints)
                val textWidth = textPlaceable?.width ?: 0
                val width = if (bounded) constraints.maxWidth else textWidth + gapPx + controlWidth
                val height = maxOf(textPlaceable?.height ?: 0, controlPlaceable?.height ?: 0)
                layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                    textPlaceable?.place(0, (height - textPlaceable.height) / 2)
                    controlPlaceable?.place(width - controlWidth, (height - controlPlaceable.height) / 2)
                }
            }
        }
    }
}
