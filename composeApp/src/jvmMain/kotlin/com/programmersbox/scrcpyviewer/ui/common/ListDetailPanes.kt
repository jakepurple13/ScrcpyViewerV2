package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionState
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * Which pane of a list-detail screen is the current destination. It only matters when the
 * layout has room for one pane: then the list shows until an item is opened, and back returns to it.
 */
@Stable
class ListDetailNavigation {
    var detailOpen by mutableStateOf(false)
        private set

    fun openDetail() {
        detailOpen = true
    }

    fun backToList() {
        detailOpen = false
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun scaffoldValue(maxHorizontalPartitions: Int): ThreePaneScaffoldValue =
        calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(
                if (detailOpen) ListDetailPaneScaffoldRole.Detail else ListDetailPaneScaffoldRole.List,
            ),
        )

    /** The detail pane needs its own way back when the list is hidden. */
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun showsBackButton(maxHorizontalPartitions: Int): Boolean =
        scaffoldValue(maxHorizontalPartitions)[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
}

/**
 * Keeps the draggable divider inside [minFirst]..[maxFirst] (list pane width, px). The scaffold
 * calls [consume] with each raw drag delta and applies what it returns; [onFirstPaneMeasured]
 * resyncs with the laid-out list width.
 */
class PaneWidthLimits {
    private var minFirst = 0f
    private var maxFirst = Float.MAX_VALUE
    private var firstWidth = Float.NaN

    fun update(minFirst: Float, maxFirst: Float) {
        this.minFirst = minFirst
        this.maxFirst = maxFirst
    }

    fun onFirstPaneMeasured(width: Int) {
        firstWidth = width.toFloat()
    }

    /** Forgets the laid-out width (the list is not a side pane in the single-pane layout). */
    fun reset() {
        firstWidth = Float.NaN
    }

    /** The laid-out list width in px, or null before the two-pane layout has been measured. */
    val laidOutFirstWidth: Int? get() = if (firstWidth.isNaN()) null else firstWidth.toInt()

    /** The list width after a keyboard step of [deltaPx], clamped to the limits; null before layout. */
    fun stepped(deltaPx: Float): Int? {
        if (firstWidth.isNaN()) return null
        return (firstWidth + deltaPx).coerceAtMost(maxFirst).coerceAtLeast(minFirst).toInt()
    }

    fun consume(delta: Float): Float {
        if (firstWidth.isNaN()) return delta
        val target = (firstWidth + delta).coerceAtMost(maxFirst).coerceAtLeast(minFirst)
        val applied = target - firstWidth
        firstWidth = target
        return applied
    }

    /** The list width to restore when the laid-out width is out of range (e.g. after a window resize), else null. */
    fun correction(): Int? {
        if (firstWidth.isNaN()) return null
        val clamped = firstWidth.coerceAtMost(maxFirst).coerceAtLeast(minFirst)
        return if (clamped == firstWidth) null else clamped.toInt()
    }
}

/** State of a resizable list-detail screen; hoist it above the navigation so it outlives tab switches. */
@Stable
class ListDetailPanes internal constructor(
    val expansion: PaneExpansionState,
    val navigation: ListDetailNavigation,
    internal val limits: PaneWidthLimits,
) {
    /** The list pane's laid-out width in px in the two-pane layout, else null. */
    val listWidthPx: Int? get() = limits.laidOutFirstWidth

    /**
     * The list width the user dragged the divider to in [AdaptiveListDetail]'s two-pane layout, or null
     * for the screen's preferred width. Replaces [expansion] once the last [ResizableListDetail] is gone.
     */
    var listWidth: Dp? by mutableStateOf(null)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberListDetailPanes(): ListDetailPanes {
    val limits = remember { PaneWidthLimits() }
    val navigation = remember { ListDetailNavigation() }
    val expansion = rememberPaneExpansionState(consumeDragDelta = limits::consume)
    return remember(expansion) { ListDetailPanes(expansion, navigation, limits) }
}

/** Space between the panes; the drag handle sits in it. */
internal val DividerWidth = 16.dp

/** How far Left/Right move the focused divider. */
internal val KeyboardStep = 24.dp

/**
 * List-detail layout with a draggable divider. Both panes show side by side when the width fits
 * [listMinWidth] + [detailMinWidth]; otherwise one pane shows at a time and [detail] gets an
 * `onBack` to return to the list (null while the list is visible).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ResizableListDetail(
    panes: ListDetailPanes,
    listMinWidth: Dp,
    listPreferredWidth: Dp,
    detailMinWidth: Dp,
    list: @Composable () -> Unit,
    detail: @Composable (onBack: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val partitions = if (maxWidth >= listMinWidth + DividerWidth + detailMinWidth) 2 else 1
        val density = LocalDensity.current
        val boxWidth = maxWidth
        SideEffect {
            with(density) {
                panes.limits.update(minFirst = listMinWidth.toPx(), maxFirst = (boxWidth - DividerWidth - detailMinWidth).toPx())
            }
        }
        val directive = remember(partitions, listPreferredWidth) {
            PaneScaffoldDirective(
                maxHorizontalPartitions = partitions,
                horizontalPartitionSpacerSize = DividerWidth,
                maxVerticalPartitions = 1,
                verticalPartitionSpacerSize = 0.dp,
                defaultPanePreferredWidth = listPreferredWidth,
                defaultPanePreferredHeight = 0.dp,
                excludedBounds = emptyList(),
                // Opening a detail must not pull focus out of the field the user is in.
                shouldAutoFocusCurrentDestination = false,
            )
        }
        val value = panes.navigation.scaffoldValue(partitions)
        val onBack: (() -> Unit)? =
            if (panes.navigation.showsBackButton(partitions)) panes.navigation::backToList else null
        // A window resize can leave a dragged divider outside the limits; pull it back.
        LaunchedEffect(partitions, maxWidth) {
            if (partitions == 2) panes.limits.correction()?.let(panes.expansion::setFirstPaneWidth)
            else panes.limits.reset()
        }
        ListDetailPaneScaffold(
            directive = directive,
            value = value,
            listPane = {
                AnimatedPane(
                    Modifier.preferredWidth(listPreferredWidth)
                        .onSizeChanged { if (partitions == 2) panes.limits.onFirstPaneMeasured(it.width) },
                ) { list() }
            },
            detailPane = { AnimatedPane { detail(onBack) } },
            paneExpansionState = panes.expansion,
            paneExpansionDragHandle = if (partitions == 2) {
                { state ->
                    val interaction = remember { MutableInteractionSource() }
                    val step = with(density) { KeyboardStep.toPx() }
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                        // Hairline so the divider reads in the light theme too.
                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                        VerticalDragHandle(
                            modifier = Modifier
                                .paneExpansionDraggable(state, LocalMinimumInteractiveComponentSize.current, interaction)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                .onKeyEvent { event ->
                                    val delta = when {
                                        event.type != KeyEventType.KeyDown -> return@onKeyEvent false
                                        event.key == Key.DirectionLeft -> -step
                                        event.key == Key.DirectionRight -> step
                                        else -> return@onKeyEvent false
                                    }
                                    panes.limits.stepped(delta)?.let(state::setFirstPaneWidth)
                                    true
                                }
                                .focusable(interactionSource = interaction),
                            interactionSource = interaction,
                        )
                    }
                }
            } else {
                null
            },
        )
    }
}

/** Back affordance for a detail pane header in the single-pane layout. */
@Composable
fun DetailBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list")
    }
}
