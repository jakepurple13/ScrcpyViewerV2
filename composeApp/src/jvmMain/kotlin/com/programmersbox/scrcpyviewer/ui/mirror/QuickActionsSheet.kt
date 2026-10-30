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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
private fun toggleIcon(toggle: QuickToggle, value: Boolean?): ImageVector =
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
    var confirmReboot by remember { mutableStateOf(false) }
    Column(
        Modifier.weight(1f).fillMaxHeight().background(colors.surfaceContainerHigh)
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                Text("Runs adb shell on this device", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = { onEvent(QuickActionEvent.Close) }) {
                Icon(Icons.Filled.Close, contentDescription = "Close quick actions")
            }
        }
        SectionLabel("Toggles")
        QuickToggle.entries.forEach { toggle ->
            val value = state.toggles[toggle]
            val enabled = state.enabled && toggle !in state.pending
            ActionRow(
                icon = toggleIcon(toggle, value),
                title = toggle.label,
                command = toggle.setCommand(value != true),
                modifier = Modifier.toggleable(
                    value = value == true,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onEvent(QuickActionEvent.Toggle(toggle, it)) },
                ),
            ) {
                // The row handles the tap; the switch only shows the state.
                Switch(checked = value == true, onCheckedChange = null, enabled = enabled)
            }
        }
        SectionLabel("Actions")
        ActionRow(
            icon = Icons.Filled.DeveloperMode,
            title = "Developer options",
            command = "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            modifier = Modifier.clickable(enabled = state.enabled) { onEvent(QuickActionEvent.DeveloperOptions) },
        ) { Chevron() }
        ActionRow(
            icon = Icons.Filled.RestartAlt,
            title = "Reboot",
            command = "adb reboot",
            tint = colors.error,
            modifier = Modifier.clickable(enabled = state.enabled) { confirmReboot = true },
        ) { Chevron() }
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Reboot device?") },
            text = { Text("Mirroring stops until the device is back and you reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = false
                    onEvent(QuickActionEvent.Reboot)
                }) { Text("Reboot", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text.uppercase(),
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    command: String,
    modifier: Modifier,
    tint: Color? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(modifier).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = tint ?: colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tint ?: colors.onSurface)
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
