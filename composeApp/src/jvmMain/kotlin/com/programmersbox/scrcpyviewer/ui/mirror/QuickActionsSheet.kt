package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import kotlin.math.roundToInt

/** Default and minimum width of the open sheet on the Actions tab, its drag bar included. */
val QUICK_ACTIONS_WIDTH = 280.dp

/** Default and minimum width of the open sheet on the Shell tab, its drag bar included. */
val QUICK_ACTIONS_SHELL_WIDTH = 400.dp

/** Default and minimum width of the open sheet on the Apps tab (three app cards a row), its drag bar included. */
val QUICK_ACTIONS_APPS_WIDTH = 480.dp

/** How far tab content slides, how long the incoming tab waits before fading in, how fast the outgoing one fades. */
private val SLIDE_OFFSET = 40.dp
private const val FADE_IN_DELAY_MILLIS = 60
private const val FADE_OUT_MILLIS = 160

/** Growing (opening, or to the Shell tab): 260 ms emphasized-decelerate; shrinking: 200 ms emphasized-accelerate. */
private fun <T> growSpec(grow: Boolean): TweenSpec<T> =
    if (grow) tween(OPEN_MILLIS, easing = EmphasizedDecelerate) else tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)

/** The sheet's open or close animation, for the window growing or shrinking with it. */
fun quickActionsSpec(open: Boolean): TweenSpec<Float> = growSpec(open)

/** How wide the sheet is: nothing while closed, else its tab's width in [widths]. */
fun sheetWidth(open: Boolean, tab: SheetTab, widths: SheetWidths = SheetWidths()): Dp = if (open) widths[tab] else 0.dp

/** The sheet's and window's width change from [from] to [to]: the open timing when growing, the close one otherwise. */
fun sheetWidthSpec(from: Dp, to: Dp): TweenSpec<Float> = growSpec(to > from)

/**
 * The "Slide" tab switch: the outgoing tab moves 40 dp toward the side it leaves from while fading out; the incoming
 * one enters from the other side, fading in slightly later. To Shell uses the open timing, back to Actions the close
 * one.
 */
fun slideTransform(toShell: Boolean, density: Density): ContentTransform {
    val offset = with(density) { SLIDE_OFFSET.roundToPx() }
    val sign = if (toShell) 1 else -1
    val millis = if (toShell) OPEN_MILLIS else CLOSE_MILLIS
    val enter = slideInHorizontally(growSpec(toShell)) { sign * offset } +
        fadeIn(tween(millis - FADE_IN_DELAY_MILLIS, delayMillis = FADE_IN_DELAY_MILLIS, easing = LinearEasing))
    val exit = slideOutHorizontally(growSpec(toShell)) { -sign * offset } +
        fadeOut(tween(FADE_OUT_MILLIS, easing = LinearEasing))
    return enter togetherWith exit
}

private val toggleIcons = mapOf(
    QuickToggle.DarkMode to Icons.Filled.DarkMode,
    QuickToggle.WiFi to Icons.Filled.Wifi,
    QuickToggle.AirplaneMode to Icons.Filled.AirplanemodeActive,
    QuickToggle.ShowTaps to Icons.Filled.TouchApp,
    QuickToggle.AutoRotate to Icons.Filled.ScreenRotation,
)

/** [toggle]'s icon; auto-rotate shows a locked rotation while off, like Android's quick settings tile. */
internal fun toggleIcon(toggle: QuickToggle, value: Boolean?): ImageVector =
    if (toggle == QuickToggle.AutoRotate && value == false) Icons.Filled.ScreenLockRotation else toggleIcons.getValue(toggle)

/**
 * The quick actions sheet docked right of the side toolbar, animating its own width (taken from the stage); [panel]
 * draws the sheet. The mirror window instead grows by the sheet's width and shows the panel in the new space.
 */
@Composable
fun QuickActionsSheet(open: Boolean, panel: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate), expandFrom = Alignment.Start) +
            slideInHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { -it / 12 } +
            fadeIn(tween(OPEN_MILLIS - 40, delayMillis = 40)),
        exit = shrinkHorizontally(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Start) +
            slideOutHorizontally(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { -it / 12 } +
            fadeOut(tween(CLOSE_MILLIS)),
    ) {
        panel()
    }
}

/**
 * [QuickActionsPanel] as movable content. The window moves it between its pinned slot (while the window grows or
 * shrinks) and [QuickActionsSheet] around every open, close and tab switch; the tab transition, width animation and
 * scroll positions survive the move.
 */
@Composable
fun rememberQuickActionsPanel(
    state: QuickActionsUiState,
    onEvent: (QuickActionEvent) -> Unit,
    console: ShellConsole?,
    rowWidth: Dp?,
): @Composable (Modifier) -> Unit {
    val currentState by rememberUpdatedState(state)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentConsole by rememberUpdatedState(console)
    val currentRowWidth by rememberUpdatedState(rowWidth)
    return remember {
        movableContentOf { modifier: Modifier ->
            QuickActionsPanel(currentState, currentOnEvent, currentConsole, currentRowWidth, modifier)
        }
    }
}

/**
 * The sheet itself, at its tab's width from [QuickActionsUiState.widths], with the drag bar on its left edge.
 * [rowWidth] is the content row it shares with the stage and the side toolbar: the sheet leaves the stage
 * [STAGE_MIN_WIDTH] of it. Null (while the window grows or shrinks for the sheet, or unmeasured): only each tab's
 * minimum applies. A tab switch or reset animates the width with the tab switch's timing; a drag or a window resize
 * follows at once.
 */
@Composable
fun QuickActionsPanel(
    state: QuickActionsUiState,
    onEvent: (QuickActionEvent) -> Unit,
    console: ShellConsole?,
    rowWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    fun fit(width: Dp, tab: SheetTab) = if (rowWidth == null) {
        width.coerceAtLeast(SheetWidths.default(tab))
    } else {
        clampSheetWidth(width, tab, rowWidth, SIDE_TOOLBAR_WIDTH)
    }
    val tab = state.tab
    val shown = fit(state.widths[tab], tab)
    val animated = remember { Animatable(shown, Dp.VectorConverter) }
    // Plain holders, read in effects and layout only: the tab the width last settled on, and whether the next width
    // change animates (a tab switch or reset) rather than following at once (a drag, a key press, a window resize).
    val settledTab = remember { arrayOf(tab) }
    val animating = remember { BooleanArray(1) }
    if (settledTab[0] != tab) {
        settledTab[0] = tab
        animating[0] = true
    }
    val currentShown by rememberUpdatedState(shown)
    LaunchedEffect(tab, shown) {
        if (animating[0]) {
            animated.animateTo(shown, growSpec(shown > animated.value)) // the timing of sheetWidthSpec
            animating[0] = false
        } else {
            animated.snapTo(shown)
        }
    }
    Row(
        modifier.layout { measurable, constraints ->
            // Read here, in layout, so the animation's frames re-measure without recomposing the sheet (a drag does
            // recompose it: each step is a new width in the UI state). Never narrower than the pinned slot.
            val width = constraints.constrainWidth((if (animating[0]) animated.value else currentShown).roundToPx())
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }.fillMaxHeight()
            .reportsSheetFocus()
            // An Esc nothing in the sheet used (a field clears its filter first) closes the sheet.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onEvent(QuickActionEvent.Close)
                    true
                } else {
                    false
                }
            },
    ) {
        SheetDragBar(
            width = shown,
            fit = { fit(it, tab) },
            onResize = {
                animating[0] = false // a drag that cuts a tab switch short follows the pointer from here
                onEvent(QuickActionEvent.Resize(it))
            },
            onResizeDone = { onEvent(QuickActionEvent.ResizeDone(tab)) }, // the tab resized, whatever shows by then
            saveKey = tab,
            onReset = {
                if (fit(SheetWidths.default(tab), tab) != shown) animating[0] = true
                onEvent(QuickActionEvent.ResetWidth)
            },
        )
        SheetContent(state, onEvent, console, columns = tileColumns(fit(state.widths.actions, SheetTab.Actions)))
    }
}

@Composable
private fun RowScope.SheetContent(
    state: QuickActionsUiState,
    onEvent: (QuickActionEvent) -> Unit,
    console: ShellConsole?,
    columns: Int,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var overflow by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).fillMaxHeight().background(colors.surfaceContainerHigh).padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                Text("Runs adb shell on this device", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }, Modifier.reportsSheetFocus()) {
                    DropdownMenuItem(
                        text = { Text("Manage actions…") },
                        onClick = {
                            overflow = false
                            onEvent(QuickActionEvent.Manage(null))
                        },
                    )
                }
            }
            IconButton(onClick = { onEvent(QuickActionEvent.Close) }) {
                Icon(Icons.Filled.Close, contentDescription = "Close quick actions")
            }
        }
        SheetTabs(
            state.tab, state.consoleRunning,
            onSelect = { if (it != state.tab) onEvent(QuickActionEvent.SelectTab(it)) },
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        // The command field takes focus on every switch to Shell: composed afresh, or, when a switch away is reversed
        // mid-transition, kept and selected again.
        AnimatedContent(
            targetState = state.tab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { slideTransform(targetState.ordinal > initialState.ordinal, density) },
            contentAlignment = Alignment.TopStart,
        ) { tab ->
            when (tab) {
                SheetTab.Actions -> ActionsTabContent(state, onEvent, columns)
                SheetTab.Apps -> AppsTabContent(state.apps, state.enabled) { onEvent(QuickActionEvent.Apps(it)) }
                SheetTab.Shell -> if (console != null) {
                    ShellTabContent(
                        console, state.custom.filterIsInstance<RunOnceAction>(), selected = state.tab == SheetTab.Shell, onEvent,
                    )
                }
            }
        }
    }
    // At the sheet level, not in a tab: a Shell tab chip opens the same prompts as a tile.
    RunPrompts(state, onEvent)
}

private val TrackShape = RoundedCornerShape(10.dp)
private val SegmentShape = RoundedCornerShape(8.dp)

/**
 * The Actions / Apps / Shell segmented control. The highlight glides to the selected segment with the tab switch's
 * timing; the Shell segment shows a red dot while a console command runs.
 */
@Composable
private fun SheetTabs(tab: SheetTab, consoleRunning: Boolean, onSelect: (SheetTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val segments = SheetTab.entries.size
    val target = tab.ordinal.toFloat()
    // Forward (to the right) uses the open timing, backward the close one, like the content's slide.
    val lastTarget = remember { floatArrayOf(target) }
    val forward = target >= lastTarget[0]
    lastTarget[0] = target
    val position by animateFloatAsState(target, growSpec(forward))
    Box(modifier.fillMaxWidth().height(36.dp).clip(TrackShape).background(colors.surfaceContainerHighest).padding(3.dp)) {
        Box(
            Modifier.fillMaxHeight()
                .layout { measurable, constraints ->
                    // One segment wide, offset by the animated position (read here, in layout, not in composition).
                    val segment = constraints.maxWidth / segments
                    val placeable = measurable.measure(constraints.copy(minWidth = segment, maxWidth = segment))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative((position * segment).roundToInt(), 0)
                    }
                }
                .clip(SegmentShape)
                .background(colors.surface),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            SheetTab.entries.forEach { segment ->
                val selected = segment == tab
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(SegmentShape)
                        .selectable(selected, role = Role.Tab) { onSelect(segment) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        segment.name, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (segment == SheetTab.Shell && consoleRunning) {
                        Box(
                            Modifier.padding(start = 6.dp).size(6.dp).background(colors.error, CircleShape)
                                .semantics { contentDescription = "Command running" },
                        )
                    }
                }
            }
        }
    }
}
