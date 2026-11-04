package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.ActionImportItem
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.ui.theme.warnColor

/** The import preview's warning, shown above the incoming actions. */
const val IMPORT_WARNING = "These run shell commands on your devices. Check each one."

private val choiceLabels = mapOf(
    ConflictChoice.Replace to "Replace",
    ConflictChoice.KeepBoth to "Keep both",
    ConflictChoice.Skip to "Skip",
)

/** Preview of an actions file before anything changes; nothing runs. */
@Composable
fun ActionImportDialog(
    state: ActionImportUiState,
    onChoice: (Int, ConflictChoice) -> Unit,
    onInclude: (Int, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val failed = state.error != null
    AlertDialog(
        onDismissRequest = onCancel,
        // Wider than the default so commands stay readable.
        modifier = Modifier.widthIn(max = 680.dp).padding(24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (failed) "Can't import" else "Import ${state.items.size} ${if (state.items.size == 1) "action" else "actions"}") },
        text = { ActionImportDialogContent(state, onChoice, onInclude) },
        confirmButton = {
            if (failed) TextButton(onCancel) { Text("Close") }
            else TextButton(onConfirm, enabled = state.importCount > 0) { Text("Import ${state.importCount}") }
        },
        dismissButton = if (failed) null else ({ TextButton(onCancel) { Text("Cancel") } }),
    )
}

/**
 * The body of [ActionImportDialog]: the warning, then one row per incoming action with its include checkbox, name
 * and commands (the one place besides the editor that shows them), and Replace / Keep both / Skip on a name clash.
 */
@Composable
fun ActionImportDialogContent(
    state: ActionImportUiState,
    onChoice: (Int, ConflictChoice) -> Unit,
    onInclude: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.error != null) {
        Text(state.error, color = MaterialTheme.colorScheme.error, modifier = modifier)
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = warnColor(), modifier = Modifier.size(20.dp))
            Text(IMPORT_WARNING, color = warnColor(), fontWeight = FontWeight.Medium)
        }
        state.items.forEachIndexed { index, item -> ImportRow(item, { onChoice(index, it) }, { onInclude(index, it) }) }
    }
}

@Composable
private fun ImportRow(item: ActionImportItem, onChoice: (ConflictChoice) -> Unit, onInclude: (Boolean) -> Unit) {
    val action = item.action
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(item.include, onInclude)
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(ActionIcons.forName(action.icon, action.defaultIcon), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(action.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(actionTypeLine(action), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Commands(action)
            if (item.conflictsWith != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name already exists", style = MaterialTheme.typography.labelMedium, color = warnColor(), modifier = Modifier.weight(1f))
                    ConflictChoices(item.choice, enabled = item.include, onChoice)
                }
            }
        }
    }
}

/** Every command the action would run, labelled for a toggle. */
@Composable
private fun Commands(action: CustomAction) {
    val lines = when (action) {
        is ToggleAction -> listOfNotNull("on" to action.on, "off" to action.off, action.read?.let { "read" to it })
        is RunOnceAction -> listOf(null to action.command)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { (label, command) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (label != null) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 28.dp))
                    }
                    Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictChoices(selected: ConflictChoice, enabled: Boolean, onSelect: (ConflictChoice) -> Unit) {
    val entries = ConflictChoice.entries
    SingleChoiceSegmentedButtonRow {
        entries.forEachIndexed { i, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, entries.size),
            ) { Text(choiceLabels.getValue(choice), maxLines = 1) }
        }
    }
}
