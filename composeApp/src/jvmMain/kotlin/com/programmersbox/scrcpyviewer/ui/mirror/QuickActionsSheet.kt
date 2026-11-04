package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Width of the open sheet, its divider included. */
val QUICK_ACTIONS_WIDTH = 280.dp

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val OPEN_MILLIS = 260
private const val CLOSE_MILLIS = 200

/** The sheet's open or close animation, for the window growing or shrinking with it. */
fun quickActionsSpec(open: Boolean): TweenSpec<Float> =
    if (open) tween(OPEN_MILLIS, easing = EmphasizedDecelerate) else tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)

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
 * The quick actions sheet docked right of the side toolbar, animating its own width (taken from the stage). The
 * mirror window instead grows by [QUICK_ACTIONS_WIDTH] and shows [QuickActionsPanel] in the new space.
 */
@Composable
fun QuickActionsSheet(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit) {
    AnimatedVisibility(
        visible = state.open,
        enter = expandHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate), expandFrom = Alignment.Start) +
            slideInHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { -it / 12 } +
            fadeIn(tween(OPEN_MILLIS - 40, delayMillis = 40)),
        exit = shrinkHorizontally(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Start) +
            slideOutHorizontally(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { -it / 12 } +
            fadeOut(tween(CLOSE_MILLIS)),
    ) {
        QuickActionsPanel(state, onEvent)
    }
}

/** The sheet itself, [QUICK_ACTIONS_WIDTH] wide. */
@Composable
fun QuickActionsPanel(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.width(QUICK_ACTIONS_WIDTH).fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SheetContent(state, onEvent)
    }
}

@Composable
private fun RowScope.SheetContent(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
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
        ActionsTabContent(state, onEvent)
    }
}
