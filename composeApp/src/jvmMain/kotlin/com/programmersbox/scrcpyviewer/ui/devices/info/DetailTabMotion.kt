package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape
import kotlin.math.roundToInt

/** Options ↔ Info (motion A): the old tab slides [TAB_SLIDE] and fades out, then the new one slides in from the other side. */
internal const val TAB_OUT_MILLIS = 160
internal const val TAB_IN_MILLIS = 320
internal val TAB_SLIDE = 36.dp

/** Another device while Info shows: a vertical shared axis on the pane, matching the rail. */
internal const val DEVICE_OUT_MILLIS = 160
internal const val DEVICE_IN_MILLIS = 340
internal val DEVICE_OUT_SLIDE = 14.dp
internal val DEVICE_IN_SLIDE = 18.dp

/** How long the tab control takes to open its row when a device gets selected, and to close it when none is. */
private const val TAB_ROW_OPEN_MILLIS = 260
private const val TAB_ROW_CLOSE_MILLIS = 200

/**
 * One shared-axis switch along [axis]: the outgoing content moves to [exitOffset] and fades out over [exitMillis];
 * the incoming content waits [enterDelayMillis], then moves in from [enterOffset] and fades in over [enterMillis].
 * Offsets are signed (positive is right or down); zero offsets make it a plain fade.
 */
internal data class AxisSwitch(
    val axis: Axis,
    val exitOffset: Dp,
    val exitMillis: Int,
    val enterOffset: Dp,
    val enterMillis: Int,
    val enterDelayMillis: Int,
) {
    enum class Axis { Horizontal, Vertical }
}

private fun plainFade(axis: AxisSwitch.Axis) =
    AxisSwitch(axis, 0.dp, REDUCED_FADE_MILLIS, 0.dp, REDUCED_FADE_MILLIS, enterDelayMillis = 0)

/** Options is the left tab and Info the right: moving right, content travels left (and back). */
internal fun detailTabSwitch(from: DetailTab, to: DetailTab, reduced: Boolean): AxisSwitch {
    if (reduced) return plainFade(AxisSwitch.Axis.Horizontal)
    val d = if (to.ordinal >= from.ordinal) 1 else -1
    return AxisSwitch(
        AxisSwitch.Axis.Horizontal,
        exitOffset = TAB_SLIDE * -d, exitMillis = TAB_OUT_MILLIS,
        enterOffset = TAB_SLIDE * d, enterMillis = TAB_IN_MILLIS, enterDelayMillis = TAB_OUT_MILLIS,
    )
}

/**
 * Moving to a device lower in the list ([toIndex] after [fromIndex]) lifts the old pane and brings the new one up
 * from below; moving up the list reverses both. A position of -1 (not in the list) counts as moving down.
 */
internal fun deviceSwitch(fromIndex: Int, toIndex: Int, reduced: Boolean): AxisSwitch {
    if (reduced) return plainFade(AxisSwitch.Axis.Vertical)
    val d = if (fromIndex < 0 || toIndex < 0 || toIndex >= fromIndex) 1 else -1
    return AxisSwitch(
        AxisSwitch.Axis.Vertical,
        exitOffset = DEVICE_OUT_SLIDE * -d, exitMillis = DEVICE_OUT_MILLIS,
        enterOffset = DEVICE_IN_SLIDE * d, enterMillis = DEVICE_IN_MILLIS, enterDelayMillis = DEVICE_OUT_MILLIS,
    )
}

/** The [AnimatedContent] transform for this switch. Only offset and alpha animate; the size does not. */
internal fun AxisSwitch.transform(density: Density): ContentTransform {
    val enterPx = with(density) { enterOffset.toPx() }.roundToInt()
    val exitPx = with(density) { exitOffset.toPx() }.roundToInt()
    val enterSlide = tween<IntOffset>(enterMillis, enterDelayMillis, EmphasizedDecelerate)
    val exitSlide = tween<IntOffset>(exitMillis, easing = EmphasizedAccelerate)
    var enter: EnterTransition = fadeIn(tween(enterMillis, enterDelayMillis, EmphasizedDecelerate))
    var exit: ExitTransition = fadeOut(tween(exitMillis, easing = EmphasizedAccelerate))
    if (enterPx != 0) {
        enter += when (axis) {
            AxisSwitch.Axis.Horizontal -> slideInHorizontally(enterSlide) { enterPx }
            AxisSwitch.Axis.Vertical -> slideInVertically(enterSlide) { enterPx }
        }
    }
    if (exitPx != 0) {
        exit += when (axis) {
            AxisSwitch.Axis.Horizontal -> slideOutHorizontally(exitSlide) { exitPx }
            AxisSwitch.Axis.Vertical -> slideOutVertically(exitSlide) { exitPx }
        }
    }
    // Both sides fill the pane, so there is no size to animate; clipping keeps the 36 dp slide inside the pane.
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = true))
}

fun detailTabTransform(from: DetailTab, to: DetailTab, reduced: Boolean, density: Density): ContentTransform =
    detailTabSwitch(from, to, reduced).transform(density)

fun deviceSwitchTransform(fromIndex: Int, toIndex: Int, reduced: Boolean, density: Density): ContentTransform =
    deviceSwitch(fromIndex, toIndex, reduced).transform(density)

/**
 * The detail pane's top row: the back button (single-pane layout) and, while a device is [selected], the
 * Options | Info control. The control opens and closes its row as a device gets selected or none is.
 */
@Composable
fun DetailTabRow(
    selected: Boolean,
    tab: DetailTab,
    onTab: (DetailTab) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduce = remember { systemReducesMotion() }
    Box(modifier.fillMaxWidth()) {
        if (onBack != null) DetailBackButton(onBack, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.Center),
            enter = if (reduce) fadeIn(tween(REDUCED_FADE_MILLIS)) else
                // Size changes use FastOutSlowIn: the emphasized curve jumps half the height on the first frame.
                expandVertically(tween(TAB_ROW_OPEN_MILLIS, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(TAB_ROW_OPEN_MILLIS, easing = EmphasizedDecelerate)),
            exit = if (reduce) fadeOut(tween(REDUCED_FADE_MILLIS)) else
                shrinkVertically(tween(TAB_ROW_CLOSE_MILLIS, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(TAB_ROW_CLOSE_MILLIS, easing = EmphasizedAccelerate)),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(vertical = 8.dp)) {
                DetailTab.entries.forEachIndexed { i, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        shape = styledSegmentShape(i, DetailTab.entries.size),
                        modifier = Modifier.widthIn(min = 120.dp),
                    ) { Text(entry.name) }
                }
            }
        }
    }
}

/**
 * The selected device's detail below the tab row: [options] (the pane as it was before tabs) or the Info pane, with
 * the sideways shared axis between them. On Info, another device swaps the pane along the vertical axis, in the
 * direction of its place in [serials]; [onInfoActive] is true while the Info tab is composed, so polling stops when
 * it is not on screen.
 */
@Composable
fun ColumnScope.DetailTabs(
    tab: DetailTab,
    info: DeviceInfoUiState,
    serials: List<String>,
    onInfoActive: (Boolean) -> Unit,
    onInfoRetry: () -> Unit,
    options: @Composable (Modifier) -> Unit,
) {
    val reduce = remember { systemReducesMotion() }
    val density = LocalDensity.current
    AnimatedContent(
        targetState = tab,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        transitionSpec = { detailTabTransform(initialState, targetState, reduce, density) },
        label = "detail tab",
    ) { shown ->
        when (shown) {
            DetailTab.Options -> options(Modifier.fillMaxSize())
            DetailTab.Info -> InfoTab(info, serials, reduce, onInfoActive, onInfoRetry)
        }
    }
}

@Composable
private fun InfoTab(
    info: DeviceInfoUiState,
    serials: List<String>,
    reduce: Boolean,
    onInfoActive: (Boolean) -> Unit,
    onInfoRetry: () -> Unit,
) {
    val active by rememberUpdatedState(onInfoActive)
    DisposableEffect(Unit) {
        active(true)
        onDispose { active(false) }
    }
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = info,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // The leaving pane keeps its device's last state; only a new serial switches panes.
            contentKey = { it.serial },
            transitionSpec = {
                deviceSwitchTransform(serials.indexOf(initialState.serial), serials.indexOf(targetState.serial), reduce, density)
            },
            label = "info device",
        ) { shown ->
            DeviceInfoPane(
                shown, onInfoRetry,
                Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
                reduceMotion = reduce,
            )
        }
    }
}
