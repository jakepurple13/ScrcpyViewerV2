package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

/** Selecting a device or none: the old content fades out, then the new one fades in, with the tab control. */
internal const val SELECT_OUT_MILLIS = 100
internal const val SELECT_IN_DELAY_MILLIS = 60
internal const val SELECT_FADE_MILLIS = 200

/** The tab row's height. It is kept with no device selected too, so the content below never moves. */
internal val DETAIL_TAB_ROW_HEIGHT = 56.dp

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
 * No device ↔ a device: a fade-through. The old content fades out over [SELECT_OUT_MILLIS] while the new one fades
 * in over [SELECT_FADE_MILLIS] after [SELECT_IN_DELAY_MILLIS]. The fade-in uses FastOutSlowIn: the emphasized curve
 * puts a dense pane at about two-thirds opacity on its first frame, which reads as a pop.
 */
internal fun selectionFade(reduced: Boolean): ContentTransform {
    if (reduced) {
        return ContentTransform(
            fadeIn(tween(REDUCED_FADE_MILLIS)), fadeOut(tween(REDUCED_FADE_MILLIS)), sizeTransform = SizeTransform(clip = true),
        )
    }
    return ContentTransform(
        fadeIn(selectInSpec()),
        fadeOut(tween(SELECT_OUT_MILLIS, easing = EmphasizedAccelerate)),
        sizeTransform = SizeTransform(clip = true),
    )
}

private fun <T> selectInSpec() = tween<T>(SELECT_FADE_MILLIS, SELECT_IN_DELAY_MILLIS, FastOutSlowInEasing)

/** What the segmented control calls each tab. */
internal fun DetailTab.label(): String = when (this) {
    DetailTab.Options -> "Options"
    DetailTab.Info -> "Info"
}

/**
 * The detail pane's top row: the back button (single-pane layout) and, while a device is [selected], the
 * Options | Info control, which fades and scales in and out. The row keeps [DETAIL_TAB_ROW_HEIGHT] either way, so
 * nothing below it moves.
 */
@Composable
fun DetailTabRow(
    selected: Boolean,
    tab: DetailTab,
    onTab: (DetailTab) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = remember { systemReducesMotion() },
) {
    Box(modifier.fillMaxWidth().height(DETAIL_TAB_ROW_HEIGHT)) {
        if (onBack != null) DetailBackButton(onBack, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.Center),
            // Arrives and leaves with the content below (see selectionFade).
            enter = if (reduceMotion) fadeIn(tween(REDUCED_FADE_MILLIS)) else
                fadeIn(selectInSpec()) + scaleIn(selectInSpec(), initialScale = 0.92f),
            exit = if (reduceMotion) fadeOut(tween(REDUCED_FADE_MILLIS)) else
                fadeOut(tween(SELECT_OUT_MILLIS, easing = EmphasizedAccelerate)) +
                    scaleOut(tween(SELECT_OUT_MILLIS, easing = EmphasizedAccelerate), targetScale = 0.92f),
        ) {
            SingleChoiceSegmentedButtonRow {
                DetailTab.entries.forEachIndexed { i, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        shape = styledSegmentShape(i, DetailTab.entries.size),
                        modifier = Modifier.widthIn(min = 120.dp),
                    ) { Text(entry.label()) }
                }
            }
        }
    }
}

/** What one device's detail shows; a leaving device keeps its last one while it animates out. */
private data class Shown<T>(val page: T, val info: DeviceInfoUiState)

/**
 * The detail pane below the tab row. With no device ([serialOf] gives null) it shows [empty]; selecting a device or
 * none cross-fades. Another device swaps the content along the vertical axis, in the direction of its place in
 * [serials], on either tab. Within one device, [options] (the pane as it was before tabs) and the Info pane switch
 * along the sideways shared axis, and each tab keeps its saveable state (scroll and the like) per device.
 *
 * [onInfoActive] is true while a device is selected with the Info tab chosen, so polling stops when Info is not on
 * screen. Updates for the same device (a new [page], Info's polls) recompose the shown content without motion.
 */
@Composable
fun <T> DetailPane(
    page: T,
    serialOf: (T) -> String?,
    serials: List<String>,
    tab: DetailTab,
    info: DeviceInfoUiState,
    onInfoActive: (Boolean) -> Unit,
    onInfoRetry: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = remember { systemReducesMotion() },
    empty: @Composable (Modifier) -> Unit,
    options: @Composable (page: T, modifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val infoShown = serialOf(page) != null && tab == DetailTab.Info
    val active by rememberUpdatedState(onInfoActive)
    DisposableEffect(infoShown) {
        if (infoShown) active(true)
        onDispose { if (infoShown) active(false) }
    }
    val saved = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = Shown(page, info),
        modifier = modifier,
        contentKey = { serialOf(it.page) },
        transitionSpec = {
            val from = serialOf(initialState.page)
            val to = serialOf(targetState.page)
            if (from == null || to == null) selectionFade(reduceMotion)
            else deviceSwitchTransform(serials.indexOf(from), serials.indexOf(to), reduceMotion, density)
        },
        label = "detail device",
    ) { shown ->
        val serial = serialOf(shown.page)
        if (serial == null) {
            empty(Modifier.fillMaxSize())
            return@AnimatedContent
        }
        // Info can still describe the previous device for a moment after the selection changes.
        val deviceInfo = if (shown.info.serial == serial) shown.info else DeviceInfoUiState(serial = serial)
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { detailTabTransform(initialState, targetState, reduceMotion, density) },
            label = "detail tab",
        ) { shownTab ->
            saved.SaveableStateProvider("$serial/${shownTab.name}") {
                when (shownTab) {
                    DetailTab.Options -> options(shown.page, Modifier.fillMaxSize())
                    DetailTab.Info -> DeviceInfoPane(
                        deviceInfo, onInfoRetry,
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
                        reduceMotion = reduceMotion,
                    )
                }
            }
        }
    }
}
