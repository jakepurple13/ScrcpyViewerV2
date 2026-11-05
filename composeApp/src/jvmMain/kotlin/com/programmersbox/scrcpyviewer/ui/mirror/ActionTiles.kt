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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.ActionGroups
import com.programmersbox.scrcpyviewer.actions.ActionSource
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
 * [columns] per row, plus the reboot and delete confirmations. The variable prompt and run confirm live at the sheet level.
 */
@Composable
fun ActionsTabContent(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit, columns: Int = 2) {
    var confirmReboot by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomAction?>(null) }
    // Tiles already there when the tab opened show at once; later ones pop in.
    val initialIds = remember { state.custom.mapTo(HashSet()) { it.id } }
    val colors = MaterialTheme.colorScheme
    val groups = ActionGroups.of(state.custom)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Built in", Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        val builtIn = buildList {
            QuickToggle.entries.forEach { toggle ->
                add(GridTile(toggle) {
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
                })
            }
            add(GridTile("developer-options") {
                ActionTile(
                    icon = Icons.Filled.DeveloperMode, title = "Developer options", stateLine = "Open",
                    enabled = state.enabled, onClick = { onEvent(QuickActionEvent.DeveloperOptions) },
                )
            })
            add(GridTile("reboot") {
                ActionTile(
                    icon = Icons.Filled.RestartAlt, title = "Reboot", stateLine = "Asks first", tint = colors.error,
                    enabled = state.enabled, onClick = { confirmReboot = true },
                )
            })
        }
        TileGrid(builtIn, columns)

        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your actions", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f).padding(start = 8.dp), color = colors.outlineVariant)
        }
        val yours = buildList {
            groups.yours.forEach { action ->
                add(GridTile(action.id) {
                    PopIn(visible = remember { MutableTransitionState(action.id in initialIds).apply { targetState = true } }) {
                        CustomTile(action, state, onEvent, onDelete = { deleting = action })
                    }
                })
            }
            add(GridTile(NewTileKey) {
                ActionTile(
                    icon = Icons.Filled.Add, title = "New action", dashed = true,
                    onClick = { onEvent(QuickActionEvent.Manage(null, new = true)) },
                )
            })
        }
        TileGrid(yours, columns)

        if (groups.showAiSection) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Made by AI", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f).padding(start = 8.dp), color = colors.outlineVariant)
            }
            TileGrid(
                groups.madeByAi.map { action ->
                    GridTile(action.id) {
                        PopIn(visible = remember { MutableTransitionState(action.id in initialIds).apply { targetState = true } }) {
                            CustomTile(action, state, onEvent, onDelete = { deleting = action })
                        }
                    }
                },
                columns,
            )
        }
    }

    deleting?.let { action ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            modifier = Modifier.reportsSheetFocus(),
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
            modifier = Modifier.reportsSheetFocus(),
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

/** A grid cell: [key] keeps the tile's state (its pop-in) with it when tiles before it come or go. */
private class GridTile(val key: Any, val content: @Composable () -> Unit)

/** The "New action" tile's key, never equal to an action id. */
private object NewTileKey

/**
 * [tiles] [columns] per row, each cell an equal share of the width (spacers fill a short last row). One flow, not a
 * Row per line, so a key survives a change of row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileGrid(tiles: List<GridTile>, columns: Int) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = columns,
    ) {
        tiles.forEach { tile -> key(tile.key) { Box(Modifier.weight(1f)) { tile.content() } } }
        repeat((columns - tiles.size % columns) % columns) { Spacer(Modifier.weight(1f)) }
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
            aiMark = action.source == ActionSource.Ai,
            onClick = {
                onEvent(if (toggle != null) QuickActionEvent.CustomToggle(action.id, value != true) else QuickActionEvent.RunCustom(action.id))
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, Modifier.reportsSheetFocus()) {
            DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; onEvent(QuickActionEvent.Manage(action.id)) })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onEvent(QuickActionEvent.Duplicate(action.id)) })
            if (action.source == ActionSource.Ai) {
                DropdownMenuItem(text = { Text("Move to Your actions") }, onClick = { menu = false; onEvent(QuickActionEvent.MoveToYours(action.id)) })
            }
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
    aiMark: Boolean = false,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (runMark) Icon(Icons.Filled.PlayArrow, contentDescription = "Runs once", Modifier.size(14.dp), tint = secondary)
                if (aiMark) Text("✦", Modifier.semantics { contentDescription = "Made by AI" }, style = MaterialTheme.typography.labelMedium, color = secondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (dashed) secondary else content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (stateLine != null) {
            Text(stateLine, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
