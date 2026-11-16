package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape

/** One choice of an [AdaptiveSegmentedButtons]: its [value], visible [label] and optional [icon]. */
data class SegmentOption<T>(val value: T, val label: String, val icon: ImageVector? = null)

/** Cross-fade length between the segmented and menu forms (also under reduced motion). */
private const val CrossFadeMillis = 150

/**
 * A single-choice control that follows its [size]: the M3 segmented row at [ControlSize.Full]; the same row with the
 * labels animated away at [ControlSize.Icons] (each segment gets a tooltip with its label; with no option icon it stays
 * Full); one dropdown button at [ControlSize.Menu]. Full/Icons and Menu cross-fade.
 *
 * [AdaptiveRow] also composes it in hidden probe slots, so it has no effects; the menu's open flag is its only state.
 * [fillWidth] stretches the control (segments stay equal), capped by [maxWidth].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> AdaptiveSegmentedButtons(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    size: ControlSize,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    maxWidth: Dp = Dp.Unspecified,
) {
    val reduceMotion = LocalReduceMotion.current
    val effective = if (size == ControlSize.Icons && options.none { it.icon != null }) ControlSize.Full else size
    val sized = modifier
        .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)

    AnimatedContent(
        targetState = effective == ControlSize.Menu,
        modifier = sized,
        transitionSpec = {
            (fadeIn(tween(CrossFadeMillis)) togetherWith fadeOut(tween(CrossFadeMillis)))
                .using(SizeTransform(clip = false) { _, _ -> if (reduceMotion) snap() else layoutSpring() })
        },
        label = "AdaptiveSegmented",
    ) { menu ->
        if (menu) {
            MenuForm(options, selected, onSelect, fillWidth)
        } else {
            SegmentedForm(options, selected, onSelect, effective == ControlSize.Icons, reduceMotion, fillWidth)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> SegmentedForm(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    labelsHidden: Boolean,
    reduceMotion: Boolean,
    fillWidth: Boolean,
) {
    SingleChoiceSegmentedButtonRow(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
        options.forEachIndexed { index, option ->
            val isSelected = option.value == selected
            TooltipArea(
                tooltip = { if (labelsHidden) LabelTooltip(option.label) },
                modifier = Modifier.weight(1f),
            ) {
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(option.value) },
                    shape = styledSegmentShape(index, options.size),
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        val vector = option.icon
                        if (vector == null) {
                            SegmentedButtonDefaults.Icon(isSelected)
                        } else {
                            SegmentedButtonDefaults.Icon(
                                active = isSelected && !labelsHidden,
                                // Icons only: the icon carries the label, so the segment keeps an accessible name.
                                inactiveContent = {
                                    Icon(vector, option.label.takeIf { labelsHidden }, Modifier.size(SegmentedButtonDefaults.IconSize))
                                },
                            )
                        }
                    },
                    label = {
                        ShrinkingLabel(visible = !labelsHidden, reduceMotion = reduceMotion) {
                            Text(option.label, maxLines = 1)
                        }
                    },
                )
            }
        }
    }
}

/**
 * [content] whose width follows a spring between its natural width and 0, faded with it. Unlike AnimatedVisibility it
 * reports the animated width as its intrinsic width too, so the segmented row (sized by IntrinsicSize.Min) shrinks with it
 * instead of holding the full width until the label is gone. Once hidden and settled, [content] leaves the composition.
 */
@Composable
private fun ShrinkingLabel(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val fraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else layoutSpring(),
        label = "labelWidth",
    )
    if (!visible && fraction <= 0f) return
    val policy = remember(fraction) { ShrinkPolicy(fraction) }
    Layout(content, Modifier.graphicsLayer { alpha = fraction; clip = true }, policy)
}

private class ShrinkPolicy(private val fraction: Float) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0))
        return layout((placeable.width * fraction).roundToInt(), placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        (measurables.single().minIntrinsicWidth(height) * fraction).roundToInt()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        (measurables.single().maxIntrinsicWidth(height) * fraction).roundToInt()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.single().minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.single().maxIntrinsicHeight(width)
}

@Composable
internal fun LabelTooltip(label: String) {
    Surface(shape = styledCorner(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
private fun <T> MenuForm(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    fillWidth: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected } ?: options.firstOrNull()
    Box(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
            contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
        ) {
            current?.icon?.let {
                Icon(it, null, Modifier.size(SegmentedButtonDefaults.IconSize))
                Spacer(Modifier.size(8.dp))
            }
            Text(current?.label.orEmpty(), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = option.icon?.let { vector -> { Icon(vector, null) } },
                    trailingIcon = if (option.value == selected) {
                        { Icon(Icons.Filled.Check, "Selected") }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}
