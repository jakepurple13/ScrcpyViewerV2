package com.programmersbox.scrcpyviewer.ui.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.programmersbox.scrcpyviewer.ui.theme.AlertDialog
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.ui.theme.warnColor

private val choiceLabels = mapOf(
    ConflictChoice.Replace to "Replace",
    ConflictChoice.KeepBoth to "Keep both",
    ConflictChoice.Skip to "Skip",
)

@Composable
fun ImportDialog(
    state: ImportUiState,
    onChoice: (Int, ConflictChoice) -> Unit,
    onInclude: (Int, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val failed = state.error != null
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (failed) "Can't import" else "Import ${state.items.size} ${if (state.items.size == 1) "preset" else "presets"}") },
        text = { ImportDialogContent(state, onChoice, onInclude) },
        confirmButton = {
            if (failed) TextButton(onCancel) { Text("Close") }
            else TextButton(onConfirm, enabled = state.importCount > 0) { Text("Import ${state.importCount}") }
        },
        dismissButton = if (failed) null else ({ TextButton(onCancel) { Text("Cancel") } }),
    )
}

/** The body of [ImportDialog]: one row per incoming preset, or the error. */
@Composable
fun ImportDialogContent(
    state: ImportUiState,
    onChoice: (Int, ConflictChoice) -> Unit,
    onInclude: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.error != null) {
        Text(state.error, color = MaterialTheme.colorScheme.error, modifier = modifier)
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.items.forEachIndexed { index, item ->
            val unknown = state.unknownOptions[item.preset.name].orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.preset.name, style = MaterialTheme.typography.bodyLarge)
                    if (item.conflictsWith != null) {
                        Text("Name already exists", style = MaterialTheme.typography.labelMedium, color = warnColor())
                    } else {
                        val n = item.preset.options.entries.size
                        Text("New · $n ${if (n == 1) "option" else "options"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (unknown.isNotEmpty()) {
                        val flags = unknown.joinToString(", ") { "--$it" }
                        Text(
                            "${unknown.size} unknown ${if (unknown.size == 1) "option is" else "options are"} ignored: $flags",
                            style = MaterialTheme.typography.labelMedium,
                            color = warnColor(),
                        )
                    }
                }
                if (item.conflictsWith != null) {
                    ConflictChoices(item.choice) { onChoice(index, it) }
                } else {
                    Checkbox(item.include, { onInclude(index, it) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictChoices(selected: ConflictChoice, onSelect: (ConflictChoice) -> Unit) {
    val entries = ConflictChoice.entries
    SingleChoiceSegmentedButtonRow(Modifier.padding(start = 4.dp)) {
        entries.forEachIndexed { i, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                shape = styledSegmentShape(i, entries.size),
            ) { Text(choiceLabels.getValue(choice), maxLines = 1) }
        }
    }
}
