package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
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

    /** The list alone at full width (nothing pushed). */
    ListView,

    /** The pushed item's detail at full width over the list, which waits shifted and dimmed beneath it. */
    ItemView,
}

/**
 * Two panes when [contentWidth] fits the list's minimum, the divider and [detailMinWidth]; the dragged
 * width does not count, so dragging never flips modes. Below that, [pushed] picks the view.
 */
fun paneMode(
    contentWidth: Dp,
    listMinWidth: Dp,
    pushed: Boolean,
    detailMinWidth: Dp = DetailMinWidth,
    divider: Dp = DividerWidth,
): PaneMode = when {
    contentWidth >= listMinWidth + divider + detailMinWidth -> PaneMode.TwoPane
    pushed -> PaneMode.ItemView
    else -> PaneMode.ListView
}

/** Esc, or ⌘[ on macOS and Ctrl+[ elsewhere: back from a pushed detail to the list. */
internal fun isBackShortcut(key: Key, meta: Boolean, ctrl: Boolean, mac: Boolean = Os.isMac): Boolean = when (key) {
    Key.Escape -> true
    Key.LeftBracket -> if (mac) meta && !ctrl else ctrl && !meta
    else -> false
}

/** The detail never gets less than this in two panes. */
private val DetailMinWidth = 420.dp

/** Distance the detail slides in from the right when two panes open beside the list view. */
private val DetailEnterOffset = 48.dp

/** How far the covered list shifts toward the start, as a share of the width, while a detail is pushed over it. */
private const val CoveredListShift = 0.22f

/** Scrim colour alpha over the covered list. */
private const val ScrimAlpha = 0.32f

/** Longest fade allowed under reduced motion. */
private const val ReducedFadeMillis = 150

/**
 * What the push motion last saw: the mode, the push target, whether the detail is shown pushed (full width), and where
 * the detail's left edge was laid out last (px).
 */
private class PushMotion(var mode: PaneMode, var target: Float, var fullWidth: Boolean) {
    var detailLeftPx = 0

    /** Where a push from two panes starts, until its animation has snapped there. */
    var pendingPush: Float? = null

    /** The push the covered list starts shifting and dimming from: 0, or where a push from two panes started. */
    var coverFrom = 0f

    /** How covered the list is (0..1) at push [slid]: it shifts and dims over the rest of the push from [coverFrom]. */
    fun cover(slid: Float): Float = if (coverFrom >= 1f) 1f else ((slid - coverFrom) / (1f - coverFrom)).coerceIn(0f, 1f)
}

/**
 * List-detail layout in three modes (see [PaneMode]). In two panes the list takes the dragged (or
 * [listPreferredWidth]) width beside the detail, split by the divider. In a narrower window the list comes first, at
 * full width; once [ListDetailPanes.pushed] (and [hasSelection]) the detail slides in from the end edge over it, full
 * width, while the list shifts [CoveredListShift] of the width toward the start under a scrim, and Back reverses it.
 * Every change of mode moves with [layoutSpring]; reduced motion swaps the push for a [ReducedFadeMillis] cross-fade.
 *
 * [detail] is composed in two panes and while pushed; its `onBack` is non-null while it is shown pushed: screens put a
 * [DetailBackButton] for it at the start of their header. Esc (unless a text field in the detail has focus) and ⌘[
 * (Ctrl+[ elsewhere) go back too, and Back hands keyboard focus to the row the item was opened from. Losing the
 * selection clears the push. The covered list takes no pointer input or focus and leaves the semantics tree; the
 * detail leaving after Back takes none either.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AdaptiveListDetail(
    panes: ListDetailPanes,
    listMinWidth: Dp,
    listPreferredWidth: Dp,
    hasSelection: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable (onBack: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
    resizable: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = maxWidth
        val mode = paneMode(contentWidth, listMinWidth, panes.pushed && hasSelection)
        val density = LocalDensity.current
        val reduceMotion = LocalReduceMotion.current
        val maxListWidth = maxOf(listMinWidth, contentWidth - DividerWidth - DetailMinWidth)
        SideEffect {
            with(density) { panes.limits.update(minFirst = listMinWidth.toPx(), maxFirst = maxListWidth.toPx()) }
        }

        // A window resize can leave a dragged width outside the limits; use it clamped, keep it for wider windows.
        val twoPaneListWidth = (panes.listWidth ?: listPreferredWidth).coerceIn(listMinWidth, maxListWidth)
        var dragging by remember { mutableStateOf(false) }
        // A mode change can remove the divider mid-drag; its drag-stopped callback then never comes.
        LaunchedEffect(mode) { if (mode != PaneMode.TwoPane) dragging = false }
        val itemView = mode == PaneMode.ItemView
        val pushTarget = if (itemView) 1f else 0f
        val last = remember { PushMotion(mode, pushTarget, fullWidth = itemView) }
        // Between two panes and a pushed detail the detail's edge moves, not the list's: the list, under the detail
        // either way, takes its new width at once.
        val crossing = (last.mode == PaneMode.TwoPane && itemView) || (last.mode == PaneMode.ItemView && mode == PaneMode.TwoPane)
        val listWidth = listWidthMotion(
            // Pushed or not, a narrow list keeps the full width: the detail slides over it.
            target = if (mode == PaneMode.TwoPane) twoPaneListWidth else contentWidth,
            mode = mode,
            userWidth = panes.listWidth to listPreferredWidth,
            // A mode change during a pointer drag still glides.
            followDirectly = (dragging && mode == PaneMode.TwoPane) || reduceMotion || crossing,
            // The two-pane target is clamped to the detail's min; a widening glide must not overshoot it.
            noOvershootPastTarget = mode == PaneMode.TwoPane,
        )
        val gapWidth by animateDpAsState(
            targetValue = if (mode == PaneMode.TwoPane) DividerWidth else 0.dp,
            animationSpec = if (reduceMotion) snap() else layoutSpring(),
            label = "gapWidth",
        )

        // The push: 0 with the list in view, 1 with the detail over it. Back (or widening) reverses it from wherever
        // it is, with its velocity.
        val push = remember { Animatable(pushTarget) }
        val pushChanged = pushTarget != last.target
        // Read now: relaunching the effect cancels the running push, which zeroes its velocity.
        val pushVelocity = if (pushChanged) push.velocity else 0f
        // Into or out of two panes the detail is on screen either way: reduced motion changes it at once.
        val snapPush = reduceMotion && (last.mode == PaneMode.TwoPane || mode == PaneMode.TwoPane)
        // From two panes the push starts where the detail stood beside the list. Set here, before the effect below can
        // start: the layout uses it until the effect has snapped the push there.
        val widthPx = with(density) { contentWidth.roundToPx() }.coerceAtLeast(1)
        val pushFrom = if (pushChanged && itemView && last.mode == PaneMode.TwoPane && !reduceMotion) {
            (1f - last.detailLeftPx.toFloat() / widthPx).coerceIn(0f, 1f)
        } else {
            null
        }
        if (pushFrom != null) {
            last.pendingPush = pushFrom
            last.coverFrom = pushFrom
        } else if (pushChanged && itemView) {
            last.coverFrom = 0f
        }
        LaunchedEffect(pushTarget) {
            if (pushFrom != null) push.snapTo(pushFrom)
            last.pendingPush = null
            when {
                snapPush -> push.snapTo(pushTarget)
                reduceMotion -> push.animateTo(pushTarget, tween(ReducedFadeMillis, easing = LinearEasing))
                else -> push.animateTo(pushTarget, layoutSpring(), initialVelocity = if (pushFrom != null) 0f else pushVelocity)
            }
        }
        // The detail is shown pushed (full width, sliding) from a push until two panes take it back; it keeps that
        // through the list view while it leaves after Back.
        val fullWidthDetail = when (mode) {
            PaneMode.ItemView -> true
            PaneMode.TwoPane -> false
            PaneMode.ListView -> last.fullWidth
        }
        SideEffect {
            last.mode = mode
            last.target = pushTarget
            last.fullWidth = fullWidthDetail
        }
        val pushShowing by remember { derivedStateOf { push.value > 0f || push.isRunning } }
        // How far the detail has slid in (0..1). Reduced motion does not slide: the detail fades in place.
        fun slid(): Float = if (reduceMotion) {
            if (itemView || push.value > 0f) 1f else 0f
        } else {
            push.value.coerceIn(0f, 1f)
        }
        // The list is under the detail, or still partly under it while a push reverses (Back, or widening into two
        // panes): no pointer input reaches it and it leaves the semantics tree. Keyboard focus stays out only while
        // it is pushed over, so Back can hand focus straight back.
        val listCovered = itemView || pushShowing
        val detailLeaving = mode == PaneMode.ListView
        // The detail is opaque while it is or was pushed over the list, until it is back beside it.
        val detailOpaque = fullWidthDetail || pushShowing

        // Lost the selection (deleted, unplugged, closed): nothing is left to push, so the list shows.
        LaunchedEffect(hasSelection) { if (!hasSelection) panes.back() }

        // Keyboard focus follows the push: into the detail as it comes in; on Back, to the list row it was opened
        // from (with nothing focused there before, the focus just leaves the detail).
        val detailFocus = remember { FocusRequester() }
        val listFocus = remember { FocusRequester() }
        var detailHasFocus by remember { mutableStateOf(false) }
        val listRowSaved = remember { booleanArrayOf(false) }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(mode) {
            when {
                mode == PaneMode.ItemView && !detailHasFocus -> {
                    listRowSaved[0] = listFocus.saveFocusedChild()
                    runCatching { detailFocus.requestFocus() }
                }
                mode == PaneMode.ListView && (listRowSaved[0] || detailHasFocus) -> {
                    val restored = listRowSaved[0] && listFocus.restoreFocusedChild()
                    if (!restored && detailHasFocus) focusManager.clearFocus(force = true)
                    listRowSaved[0] = false
                }
                // Widened to two panes: the list is beside the detail; nothing to hand back later.
                mode == PaneMode.TwoPane -> listRowSaved[0] = false
            }
        }
        // Text fields in the detail keep Esc (it can clear or leave the field): count their input sessions.
        val textInputs = remember { intArrayOf(0) }

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
        val scrim = MaterialTheme.colorScheme.scrim
        val pushedBackground = MaterialTheme.colorScheme.surface

        val listSlot = @Composable {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged {
                        // Mid-drag the limits track the target width, which the snap lags by a frame.
                        if (mode != PaneMode.TwoPane) panes.limits.reset() else if (!dragging) panes.limits.onFirstPaneMeasured(it.width)
                    }
                    .then(if (listCovered) Modifier.blockPointer().clearAndSetSemantics { } else Modifier)
                    // One focus group for the list: Back restores its row, and nothing in it takes focus while covered.
                    .focusProperties { if (itemView) onEnter = { cancelFocusChange() } }
                    .focusRequester(listFocus)
                    .focusGroup()
                    .drawWithContent {
                        drawContent()
                        val alpha = if (reduceMotion) 0f else ScrimAlpha * last.cover(push.value)
                        if (alpha > 0f) drawRect(scrim, alpha = alpha)
                    },
            ) {
                list()
            }
        }
        val gapSlot = @Composable {
            if (mode == PaneMode.TwoPane || gapWidth > 0.dp) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        }
        val detailSlot = @Composable {
            AnimatedVisibility(
                visible = mode != PaneMode.ListView || pushShowing,
                modifier = Modifier.fillMaxSize(),
                enter = when {
                    // The push itself moves (or fades) the detail in.
                    itemView -> EnterTransition.None
                    reduceMotion -> fadeIn(tween(ReducedFadeMillis))
                    else -> slideInHorizontally(layoutSpring()) { with(density) { DetailEnterOffset.roundToPx() } } + fadeIn(tween(220))
                },
                exit = when {
                    // The push has already moved (or faded) it out.
                    fullWidthDetail -> ExitTransition.None
                    reduceMotion -> fadeOut(tween(ReducedFadeMillis))
                    else -> slideOutHorizontally(layoutSpring()) { with(density) { DetailEnterOffset.roundToPx() } } + fadeOut(tween(120))
                },
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .keepWidthWhile(detailLeaving)
                        // Under reduced motion the pushed detail cross-fades with the list.
                        .then(if (fullWidthDetail && reduceMotion) Modifier.graphicsLayer { alpha = push.value.coerceIn(0f, 1f) } else Modifier)
                        // Opaque over the list.
                        .then(if (detailOpaque) Modifier.background(pushedBackground) else Modifier)
                        .then(if (detailLeaving) Modifier.blockInput() else Modifier)
                        .onKeyEvent { event ->
                            val back = itemView && event.type == KeyEventType.KeyDown &&
                                isBackShortcut(event.key, meta = event.isMetaPressed, ctrl = event.isCtrlPressed) &&
                                // A text field keeps Esc; ⌘[ (Ctrl+[) still goes back from it.
                                !(event.key == Key.Escape && textInputs[0] > 0)
                            if (back) panes.back()
                            back
                        }
                        .focusRequester(detailFocus)
                        .onFocusChanged { detailHasFocus = it.hasFocus }
                        .focusProperties { canFocus = itemView }
                        .focusTarget(),
                ) {
                    InterceptPlatformTextInput(
                        interceptor = { request, next ->
                            textInputs[0]++
                            try {
                                next.startInputMethod(request)
                            } finally {
                                textInputs[0]--
                            }
                        },
                    ) {
                        detail(if (fullWidthDetail && mode != PaneMode.TwoPane) panes::back else null)
                    }
                }
            }
        }

        Layout(
            contents = listOf(listSlot, gapSlot, detailSlot),
            // The pushed detail starts past the end edge; keep it (and the shifted list) inside the panes.
            modifier = Modifier.fillMaxSize().then(if (mode != PaneMode.TwoPane || pushShowing) Modifier.clipToBounds() else Modifier),
        ) { (listMeasurables, gapMeasurables, detailMeasurables), constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val listPx = listWidth.roundToPx().coerceIn(0, width)
            val gapPx = gapWidth.roundToPx().coerceIn(0, width - listPx)
            // Read the push on every pass, so its first frame relayouts this even while a pending start stands in.
            val current = slid()
            val slid = last.pendingPush ?: current
            val pushedOver = fullWidthDetail && mode != PaneMode.TwoPane
            // Pushed, the detail slides from the end edge to 0 over the list. Side by side it starts after the list
            // and divider; back from a push it slides there from 0.
            val slidLeft = (width * (1f - slid)).roundToInt()
            val detailLeft = if (pushedOver) slidLeft else minOf(listPx + gapPx, slidLeft)
            val detailWidth = if (pushedOver) width else (width - detailLeft).coerceAtLeast(0)
            val listPlaceable = listMeasurables.firstOrNull()?.measure(Constraints.fixed(listPx, height))
            val gapPlaceable = gapMeasurables.firstOrNull()?.measure(Constraints.fixed(gapPx, height))
            val detailPlaceable = detailMeasurables.firstOrNull()?.measure(Constraints.fixed(detailWidth, height))
            val listShift = if (reduceMotion) 0 else (width * CoveredListShift * last.cover(slid)).roundToInt()
            last.detailLeftPx = detailLeft
            layout(width, height) {
                listPlaceable?.place(-listShift, 0)
                gapPlaceable?.place(listPx, 0)
                detailPlaceable?.place(detailLeft, 0)
            }
        }
    }
}

/**
 * While [keep] (the detail leaving as the list widens over it), lays the content out at the width it had before
 * and clips it to the shrinking slot, so it slides away whole instead of being squeezed to nothing (a squeeze
 * would also drive its springy bounds animations below zero).
 */
private fun Modifier.keepWidthWhile(keep: Boolean): Modifier = composed {
    val lastWidth = remember { intArrayOf(0) }
    clipToBounds().layout { measurable, constraints ->
        if (!keep) lastWidth[0] = constraints.maxWidth
        val width = if (keep) maxOf(lastWidth[0], constraints.maxWidth) else constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

/** Swallows pointer input before the content sees it, and keeps focus out of the content (a group nothing enters). */
private fun Modifier.blockInput(): Modifier =
    focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup()
        .blockPointer()

/** Swallows pointer input before the content sees it. */
private fun Modifier.blockPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
