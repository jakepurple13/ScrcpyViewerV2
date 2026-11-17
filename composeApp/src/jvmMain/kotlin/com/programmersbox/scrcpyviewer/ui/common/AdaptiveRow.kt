package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * steps down through [ControlSize] until it fits. Inline, the control sits at the row's end, or right after the
 * text's own width with [packInline]; inline, both slots sit at [verticalAlignment]. When the control moves under
 * the text or back, both slots and the row height glide there with [layoutSpring]; under reduced motion they move at
 * once. Any other resize (a window drag) lays them out at once, so they follow the drag directly.
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
    packInline: Boolean = false,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
) {
    val currentText by rememberUpdatedState(text)
    val currentControl by rememberUpdatedState(control)
    val reduceMotion = LocalReduceMotion.current
    // One stable lambda per fit, so remeasuring (every frame of a glide) does not recompose slots.
    val probes = remember { ControlSize.entries.associateWith { size -> @Composable { currentControl(size) } } }
    val contents = remember(reduceMotion, gap, fillControlWhenStacked, packInline, verticalAlignment) {
        HashMap<RowFit, @Composable () -> Unit>()
    }

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
                    packInline = packInline,
                    verticalAlignment = verticalAlignment,
                    animate = !reduceMotion,
                )
            }
        }
        val placeables = subcompose(SlotContent, content).map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private object SlotContent

/**
 * Lays out the text and control for one [fit]. The slots keep their identity across fits; the stacked share (0 inline,
 * 1 stacked) springs when the fit flips, and every frame interpolates between the two layouts at the current width.
 */
@Composable
private fun AdaptiveRowContent(
    fit: RowFit,
    text: @Composable () -> Unit,
    control: @Composable () -> Unit,
    gap: Dp,
    fillControlWhenStacked: Boolean,
    packInline: Boolean,
    verticalAlignment: Alignment.Vertical,
    animate: Boolean,
) {
    val stack = animateFloatAsState(
        targetValue = if (fit.stacked) 1f else 0f,
        animationSpec = if (animate) layoutSpring() else snap(),
        label = "adaptiveRowStack",
    )
    val shown = if (animate) stack else rememberUpdatedState(if (fit.stacked) 1f else 0f)
    Layout(
        // propagateMinConstraints: the text's width and a full-width stacked control reach the content.
        contents = listOf(
            { Box(propagateMinConstraints = true) { text() } },
            { Box(propagateMinConstraints = true) { control() } },
        ),
    ) { (textMeasurables, controlMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        if (!constraints.hasBoundedWidth) {
            // No width to share: the text's own width, then the control.
            val controlPlaceable = controlMeasurables.firstOrNull()?.measure(loose)
            val textPlaceable = textMeasurables.firstOrNull()?.measure(loose)
            val textWidth = textPlaceable?.width ?: 0
            val height = maxOf(textPlaceable?.height ?: 0, controlPlaceable?.height ?: 0)
            layout(textWidth + gapPx + (controlPlaceable?.width ?: 0), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                textPlaceable?.place(0, verticalAlignment.offset(textPlaceable.height, height))
                controlPlaceable?.place(textWidth + gapPx, verticalAlignment.offset(controlPlaceable.height, height))
            }
        } else {
            layoutBetween(
                shown, textMeasurables.firstOrNull(), controlMeasurables.firstOrNull(), loose, constraints, gapPx,
                fillControlWhenStacked, packInline, verticalAlignment,
            )
        }
    }
}

/** The inline layout at [stack] 0, the stacked one at 1, and every slot's size and place in between. */
private fun MeasureScope.layoutBetween(
    stack: State<Float>,
    textMeasurable: Measurable?,
    controlMeasurable: Measurable?,
    loose: Constraints,
    constraints: Constraints,
    gapPx: Int,
    fillControlWhenStacked: Boolean,
    packInline: Boolean,
    verticalAlignment: Alignment.Vertical,
): MeasureResult {
    val f = stack.value.coerceIn(0f, 1f)
    fun lerp(a: Int, b: Int) = if (f == 0f) a else if (f == 1f) b else (a + (b - a) * f).roundToInt()
    val width = constraints.maxWidth
    val controlPlaceable = controlMeasurable?.measure(loose.copy(minWidth = lerp(0, if (fillControlWhenStacked) width else 0)))
    val controlWidth = controlPlaceable?.width ?: 0
    val inlineTextWidth = (width - controlWidth - gapPx).coerceAtLeast(0)
    val textMax = lerp(inlineTextWidth, width)
    val textMin = lerp(if (packInline) 0 else inlineTextWidth, width)
    val textPlaceable = textMeasurable?.measure(loose.copy(minWidth = textMin, maxWidth = textMax))
    val textWidth = textPlaceable?.width ?: 0
    val textHeight = textPlaceable?.height ?: 0
    val controlHeight = controlPlaceable?.height ?: 0
    val inlineHeight = maxOf(textHeight, controlHeight)
    val stackedControlY = if (textPlaceable != null && controlPlaceable != null) textHeight + StackedGap.roundToPx() else textHeight
    val height = lerp(inlineHeight, stackedControlY + controlHeight)
    val inlineControlX = if (packInline) textWidth + gapPx else width - controlWidth
    return layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
        textPlaceable?.place(0, lerp(verticalAlignment.offset(textHeight, inlineHeight), 0))
        controlPlaceable?.place(lerp(inlineControlX, 0), lerp(verticalAlignment.offset(controlHeight, inlineHeight), stackedControlY))
    }
}

/** Where a slot [size] tall sits in [space]; centred rounds down. */
private fun Alignment.Vertical.offset(size: Int, space: Int): Int =
    if (this == Alignment.CenterVertically) (space - size) / 2 else align(size, space)
