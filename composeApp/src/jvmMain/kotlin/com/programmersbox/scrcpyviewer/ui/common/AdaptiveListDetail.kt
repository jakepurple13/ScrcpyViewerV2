package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import kotlin.math.roundToInt

/** What the list width motion last saw, so a recomposition can tell a mode or user change from a window resize. */
private class ListWidthMotion(var mode: PaneMode, var userWidth: Any?, var shown: Dp) {
    /** The current glide widens the list (it started narrower than its target). */
    var growing = false
}

/**
 * The list width to lay out now: [target] plus an offset that springs to 0 (with [layoutSpring]).
 *
 * When [mode] or [userWidth] (the dragged and preferred widths) changes, the offset starts at the width shown
 * before minus the new target, so the list glides from where it was, keeping the velocity of a glide it
 * interrupts. A window resize only moves [target], so the list follows the window edge in the same frame, even
 * mid-glide. [followDirectly] (pointer drag, reduced motion) drops the offset.
 *
 * [noOvershootPastTarget] (two panes, where [target] is already clamped to the detail's minimum) stops a widening
 * glide from overshooting past [target]. A narrowing glide starts wider than the limit on purpose: it shrinks in
 * from where the list was while the detail grows in beside it.
 */
@Composable
private fun listWidthMotion(
    target: Dp,
    mode: PaneMode,
    userWidth: Any?,
    followDirectly: Boolean,
    noOvershootPastTarget: Boolean,
): Dp {
    val offset = remember { Animatable(0.dp, Dp.VectorConverter) }
    val last = remember { ListWidthMotion(mode, userWidth, target) }
    val changed = mode != last.mode || userWidth != last.userWidth
    // Until the effect below starts the glide, show the old width.
    val startOffset = if (changed && !followDirectly) last.shown - target else null
    // Read now: relaunching the effect cancels the running glide, which zeroes its velocity.
    val startVelocity = if (startOffset != null) offset.velocity else 0.dp
    LaunchedEffect(mode, userWidth, followDirectly) {
        last.mode = mode
        last.userWidth = userWidth
        if (startOffset == null) {
            offset.snapTo(0.dp)
        } else {
            last.growing = startOffset < 0.dp
            offset.snapTo(startOffset)
            offset.animateTo(0.dp, layoutSpring(), initialVelocity = startVelocity)
        }
    }
    // Read the offset on every pass, so the glide recomposes this even when it starts from startOffset.
    val currentOffset = offset.value
    val shown = when {
        followDirectly -> target
        startOffset != null -> target + startOffset
        noOvershootPastTarget && last.growing -> target + currentOffset.coerceAtMost(0.dp)
        else -> target + currentOffset
    }
    SideEffect { last.shown = shown }
    return shown
}

/** How a list-detail screen lays out at the current width. */
enum class PaneMode {
    /** List and detail side by side, split by the draggable divider. */
    TwoPane,

    /** The list alone at full width (nothing open). */
    ListView,

    /** The open item, with the list shrunk to the [StripWidth] icon strip on its left. */
    ItemView,
}

/**
 * Two panes when [contentWidth] fits the list's minimum, the divider and [detailMinWidth]; the dragged
 * width does not count, so dragging never flips modes. Below that, [detailOpen] picks the view.
 */
fun paneMode(
    contentWidth: Dp,
    listMinWidth: Dp,
    detailOpen: Boolean,
    detailMinWidth: Dp = DetailMinWidth,
    divider: Dp = DividerWidth,
): PaneMode = when {
    contentWidth >= listMinWidth + divider + detailMinWidth -> PaneMode.TwoPane
    detailOpen -> PaneMode.ItemView
    else -> PaneMode.ListView
}

/** How the list pane draws itself: full rows, or the icon strip beside an open item. */
enum class ListPresentation { Full, Strip }

/** Width of the list's icon strip in item view. */
val StripWidth = 76.dp

/** The detail never gets less than this in two panes. */
private val DetailMinWidth = 420.dp

/** Gap (with a hairline) between the strip and the detail in item view. */
private val StripGap = 8.dp

/** Distance the detail slides in from the right when an item opens from the list view. */
private val DetailEnterOffset = 48.dp

/** Longest fade allowed under reduced motion. */
private const val ReducedFadeMillis = 150

/**
 * List-detail layout in three modes (see [PaneMode]). Every change of mode animates the list width with
 * [layoutSpring]: to the dragged (or [listPreferredWidth]) width in two panes, to [StripWidth] in item view,
 * and to the full width in list view. [list] gets [ListPresentation.Strip] only in item view. [detail] is
 * composed in two panes and item view; its `openSwitcher` is non-null only in item view.
 *
 * In two panes, [hasSelection] is the destination: a selected item counts as open, so narrowing the window
 * keeps it on screen in item view.
 */
@Composable
fun AdaptiveListDetail(
    panes: ListDetailPanes,
    listMinWidth: Dp,
    listPreferredWidth: Dp,
    hasSelection: Boolean,
    list: @Composable (ListPresentation) -> Unit,
    detail: @Composable (openSwitcher: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
    resizable: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = maxWidth
        val mode = paneMode(contentWidth, listMinWidth, panes.navigation.detailOpen)
        val density = LocalDensity.current
        val reduceMotion = LocalReduceMotion.current
        val maxListWidth = maxOf(listMinWidth, contentWidth - DividerWidth - DetailMinWidth)
        SideEffect {
            with(density) { panes.limits.update(minFirst = listMinWidth.toPx(), maxFirst = maxListWidth.toPx()) }
        }
        LaunchedEffect(mode, hasSelection) {
            if (mode == PaneMode.TwoPane) {
                if (hasSelection) panes.navigation.openDetail() else panes.navigation.backToList()
            }
        }

        // A window resize can leave a dragged width outside the limits; use it clamped, keep it for wider windows.
        val twoPaneListWidth = (panes.listWidth ?: listPreferredWidth).coerceIn(listMinWidth, maxListWidth)
        var dragging by remember { mutableStateOf(false) }
        // A mode change can remove the divider mid-drag; its drag-stopped callback then never comes.
        LaunchedEffect(mode) { if (mode != PaneMode.TwoPane) dragging = false }
        val targetListWidth = when (mode) {
            PaneMode.TwoPane -> twoPaneListWidth
            PaneMode.ItemView -> StripWidth
            PaneMode.ListView -> contentWidth
        }
        val listWidth = listWidthMotion(
            target = targetListWidth,
            mode = mode,
            userWidth = panes.listWidth to listPreferredWidth,
            // A mode change during a pointer drag still glides.
            followDirectly = (dragging && mode == PaneMode.TwoPane) || reduceMotion,
            // The two-pane target is clamped to the detail's min; a widening glide must not overshoot it.
            noOvershootPastTarget = mode == PaneMode.TwoPane,
        )
        val gapWidth by animateDpAsState(
            targetValue = when (mode) {
                PaneMode.TwoPane -> DividerWidth
                PaneMode.ItemView -> StripGap
                PaneMode.ListView -> 0.dp
            },
            animationSpec = if (reduceMotion) snap() else layoutSpring(),
            label = "gapWidth",
        )

        // Drags and arrow keys start from the width on screen now, clamped to the limits.
        val currentTwoPaneWidth by rememberUpdatedState(twoPaneListWidth)
        val currentMin by rememberUpdatedState(listMinWidth)
        val currentMax by rememberUpdatedState(maxListWidth)
        val currentDensity by rememberUpdatedState(density)
        fun seedLimits() = panes.limits.onFirstPaneMeasured(with(density) { currentTwoPaneWidth.toPx() }.roundToInt())
        val dragState = rememberDraggableState { delta ->
            // Several deltas can land before a recomposition, so build on the stored width, not the composed one.
            val start = panes.listWidth?.coerceIn(currentMin, currentMax) ?: currentTwoPaneWidth
            panes.listWidth = start + with(currentDensity) { panes.limits.consume(delta).toDp() }
        }
        // Task 6 replaces this with the overlay that shows the full list over the detail.
        val openSwitcher: () -> Unit = remember { {} }

        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(listWidth).fillMaxHeight().onSizeChanged {
                    // Mid-drag the limits track the target width, which the snap lags by a frame.
                    if (mode != PaneMode.TwoPane) panes.limits.reset() else if (!dragging) panes.limits.onFirstPaneMeasured(it.width)
                },
            ) {
                list(if (mode == PaneMode.ItemView) ListPresentation.Strip else ListPresentation.Full)
            }
            if (mode != PaneMode.ListView || gapWidth > 0.dp) {
                Box(Modifier.width(gapWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    // Hairline so the divider reads in the light theme too.
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    if (mode == PaneMode.TwoPane && resizable) {
                        val interaction = remember { MutableInteractionSource() }
                        val step = with(density) { KeyboardStep.toPx() }
                        // The whole divider is the drag target; the handle marks it and takes keyboard focus.
                        Box(
                            Modifier.fillMaxSize()
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                .draggable(
                                    state = dragState,
                                    orientation = Orientation.Horizontal,
                                    interactionSource = interaction,
                                    onDragStarted = {
                                        seedLimits()
                                        dragging = true
                                    },
                                    onDragStopped = { dragging = false },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            VerticalDragHandle(
                                modifier = Modifier
                                    .onKeyEvent { event ->
                                        val delta = when {
                                            event.type != KeyEventType.KeyDown -> return@onKeyEvent false
                                            event.key == Key.DirectionLeft -> -step
                                            event.key == Key.DirectionRight -> step
                                            else -> return@onKeyEvent false
                                        }
                                        seedLimits()
                                        panes.limits.stepped(delta)?.let { panes.listWidth = with(density) { it.toDp() } }
                                        true
                                    }
                                    .focusable(interactionSource = interaction),
                                interactionSource = interaction,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = mode != PaneMode.ListView,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enter = if (reduceMotion) {
                    fadeIn(tween(ReducedFadeMillis))
                } else {
                    slideInHorizontally(layoutSpring()) { with(density) { DetailEnterOffset.roundToPx() } } + fadeIn(tween(220))
                },
                exit = if (reduceMotion) {
                    fadeOut(tween(ReducedFadeMillis))
                } else {
                    slideOutHorizontally(layoutSpring()) { with(density) { DetailEnterOffset.roundToPx() } } + fadeOut(tween(120))
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    detail(if (mode == PaneMode.ItemView) openSwitcher else null)
                }
            }
        }
    }
}
