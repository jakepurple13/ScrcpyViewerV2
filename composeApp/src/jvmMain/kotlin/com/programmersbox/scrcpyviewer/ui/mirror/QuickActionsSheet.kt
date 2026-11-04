package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DarkMode
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import kotlin.math.roundToInt

/** Width of the open sheet on the Actions tab, its divider included. */
val QUICK_ACTIONS_WIDTH = 280.dp

/** Width of the open sheet on the Shell tab, its divider included. */
val QUICK_ACTIONS_SHELL_WIDTH = 400.dp

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val OPEN_MILLIS = 260
private const val CLOSE_MILLIS = 200

/** How far tab content slides, how long the incoming tab waits before fading in, how fast the outgoing one fades. */
private val SLIDE_OFFSET = 40.dp
private const val FADE_IN_DELAY_MILLIS = 60
private const val FADE_OUT_MILLIS = 160

/** Growing (opening, or to the Shell tab): 260 ms emphasized-decelerate; shrinking: 200 ms emphasized-accelerate. */
private fun <T> growSpec(grow: Boolean): TweenSpec<T> =
    if (grow) tween(OPEN_MILLIS, easing = EmphasizedDecelerate) else tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)

/** The sheet's open or close animation, for the window growing or shrinking with it. */
fun quickActionsSpec(open: Boolean): TweenSpec<Float> = growSpec(open)

/** How wide the sheet is: nothing while closed, else its tab's width. */
fun sheetWidth(open: Boolean, tab: SheetTab): Dp = when {
    !open -> 0.dp
    tab == SheetTab.Shell -> QUICK_ACTIONS_SHELL_WIDTH
    else -> QUICK_ACTIONS_WIDTH
}

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
): @Composable (Modifier) -> Unit {
    val currentState by rememberUpdatedState(state)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentConsole by rememberUpdatedState(console)
    return remember {
        movableContentOf { modifier: Modifier -> QuickActionsPanel(currentState, currentOnEvent, currentConsole, modifier) }
    }
}

/** The sheet itself: [QUICK_ACTIONS_WIDTH] wide on the Actions tab, [QUICK_ACTIONS_SHELL_WIDTH] on the Shell tab. */
@Composable
fun QuickActionsPanel(
    state: QuickActionsUiState,
    onEvent: (QuickActionEvent) -> Unit,
    console: ShellConsole?,
    modifier: Modifier = Modifier,
) {
    val toShell = state.tab == SheetTab.Shell
    val width by animateDpAsState(sheetWidth(true, state.tab), growSpec(toShell))
    Row(modifier.width(width).fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SheetContent(state, onEvent, console)
    }
}

@Composable
private fun RowScope.SheetContent(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit, console: ShellConsole?) {
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
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
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
            transitionSpec = { slideTransform(targetState == SheetTab.Shell, density) },
            contentAlignment = Alignment.TopStart,
        ) { tab ->
            when (tab) {
                SheetTab.Actions -> ActionsTabContent(state, onEvent)
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
 * The two-segment Actions / Shell control. The highlight glides to the selected segment with the tab switch's timing;
 * the Shell segment shows a red dot while a console command runs.
 */
@Composable
private fun SheetTabs(tab: SheetTab, consoleRunning: Boolean, onSelect: (SheetTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val toShell = tab == SheetTab.Shell
    val position by animateFloatAsState(if (toShell) 1f else 0f, growSpec(toShell))
    Box(modifier.fillMaxWidth().height(36.dp).clip(TrackShape).background(colors.surfaceContainerHighest).padding(3.dp)) {
        Box(
            Modifier.fillMaxHeight()
                .layout { measurable, constraints ->
                    // Half the track, offset by the animated position (read here, in layout, not in composition).
                    val half = constraints.maxWidth / 2
                    val placeable = measurable.measure(constraints.copy(minWidth = half, maxWidth = half))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative((position * (constraints.maxWidth - half)).roundToInt(), 0)
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
