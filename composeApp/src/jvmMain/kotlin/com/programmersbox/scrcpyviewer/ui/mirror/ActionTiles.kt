package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction

/**
 * The line under a custom tile's name: a toggle's "On" / "Off" (none while unknown), a run-once tile's hint at what
 * a tap does ("Asks for <first variable's label>", else "Asks first"). Never the command.
 */
fun tileStateLine(action: CustomAction, state: Boolean?): String? = when (action) {
    is ToggleAction -> state?.let { if (it) "On" else "Off" }
    is RunOnceAction -> CommandTemplate.variables(action.command).firstOrNull()
        ?.let { "Asks for ${action.variables[it]?.label ?: it}" }
        ?: "Asks first".takeIf { action.confirm }
}

private val TileShape = RoundedCornerShape(16.dp)

/**
 * The Actions tab: the built-in tiles, a "Your actions" divider, the user's tiles and a dashed "+ New action" tile,
 * two per row, plus the variable prompt, confirm and delete dialogs.
 */
@Composable
fun ActionsTabContent(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit) {
    var confirmReboot by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomAction?>(null) }
    // Tiles already there when the tab opened show at once; later ones pop in.
    val initialIds = remember { state.custom.mapTo(HashSet()) { it.id } }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Built in", Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        val builtIn = buildList<@Composable () -> Unit> {
            QuickToggle.entries.forEach { toggle ->
                add {
                    val value = state.toggles[toggle]
                    ActionTile(
                        icon = toggleIcon(toggle, value),
                        title = toggle.label,
                        stateLine = value?.let { if (it) "On" else "Off" },
                        on = value == true,
                        busy = toggle in state.pending,
                        enabled = state.enabled,
                        role = Role.Switch,
                        onClick = { onEvent(QuickActionEvent.Toggle(toggle, value != true)) },
                    )
                }
            }
            add {
                ActionTile(
                    icon = Icons.Filled.DeveloperMode, title = "Developer options", stateLine = "Open",
                    enabled = state.enabled, onClick = { onEvent(QuickActionEvent.DeveloperOptions) },
                )
            }
            add {
                ActionTile(
                    icon = Icons.Filled.RestartAlt, title = "Reboot", stateLine = "Asks first", tint = colors.error,
                    enabled = state.enabled, onClick = { confirmReboot = true },
                )
            }
        }
        TileGrid(builtIn)

        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your actions", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f).padding(start = 8.dp), color = colors.outlineVariant)
        }
        val yours = buildList<@Composable () -> Unit> {
            state.custom.forEach { action ->
                add { PopIn(visible = remember(action.id) { MutableTransitionState(action.id in initialIds).apply { targetState = true } }) {
                    CustomTile(action, state, onEvent, onDelete = { deleting = action })
                } }
            }
            add {
                ActionTile(
                    icon = Icons.Filled.Add, title = "New action", dashed = true,
                    onClick = { onEvent(QuickActionEvent.Manage(null)) },
                )
            }
        }
        TileGrid(yours)
    }

    RunPrompts(state, onEvent)
    deleting?.let { action ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${action.name}?") },
            text = { Text("It is removed from every device.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onEvent(QuickActionEvent.Delete(action.id))
                }) { Text("Delete", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
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

/** [tiles] two per row, each cell half the width. */
@Composable
private fun TileGrid(tiles: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> Box(Modifier.weight(1f)) { tile() } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PopIn(visible: MutableTransitionState<Boolean>, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visibleState = visible,
        enter = scaleIn(tween(300), initialScale = 0.85f) + fadeIn(tween(300)),
    ) { content() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CustomTile(
    action: CustomAction,
    state: QuickActionsUiState,
    onEvent: (QuickActionEvent) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val toggle = action as? ToggleAction
    val value = toggle?.let { state.customState[it.id] }
    Box(
        Modifier.onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isSecondaryPressed) menu = true },
    ) {
        ActionTile(
            icon = ActionIcons.forName(
                action.icon, if (toggle != null) ActionIcons.TOGGLE_DEFAULT else ActionIcons.RUN_ONCE_DEFAULT,
            ),
            title = action.name,
            stateLine = tileStateLine(action, value),
            on = value == true,
            busy = action.id in state.customPending,
            enabled = state.enabled,
            role = if (toggle != null) Role.Switch else Role.Button,
            runMark = toggle == null,
            onClick = {
                onEvent(if (toggle != null) QuickActionEvent.CustomToggle(action.id, value != true) else QuickActionEvent.RunCustom(action.id))
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; onEvent(QuickActionEvent.Manage(action.id)) })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onEvent(QuickActionEvent.Duplicate(action.id)) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

/**
 * One tile: icon (a spinner while [busy]), name, state line. Filled with the accent color while [on]; the ▶ mark
 * shows on run-once tiles; [dashed] is the "New action" tile. A busy or [enabled]-less tile ignores taps.
 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    stateLine: String? = null,
    on: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    role: Role = Role.Button,
    runMark: Boolean = false,
    dashed: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (on) colors.primary else if (dashed) Color.Transparent else colors.surfaceContainerHighest
    val content = tint ?: if (on) colors.onPrimary else colors.onSurface
    val secondary = tint ?: if (on) colors.onPrimary.copy(alpha = 0.8f) else colors.onSurfaceVariant
    val outline = colors.outline
    Column(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(TileShape).background(container)
            .then(
                if (dashed) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            outline, cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                } else Modifier,
            )
            .clickable(enabled = enabled && !busy, role = role, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = if (dashed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Row(
            Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (dashed) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            if (busy) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = secondary)
                }
            } else {
                Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = if (dashed) secondary else content)
            }
            if (runMark) Icon(Icons.Filled.PlayArrow, contentDescription = "Runs once", Modifier.size(14.dp), tint = secondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (dashed) secondary else content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (stateLine != null) {
            Text(stateLine, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
