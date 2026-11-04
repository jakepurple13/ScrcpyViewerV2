package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.mirror.VariablesDialog

/** Shown under the Output field: Toast and None runs stop at 30 s. */
const val OUTPUT_HINT = "Long or streaming commands need Shell tab"

private val typeLabels = listOf(ActionType.Toggle to "Toggle", ActionType.RunOnce to "Run once")
private val matchLabels = listOf(MatchMode.Equals to "equals", MatchMode.Contains to "contains", MatchMode.Regex to "matches regex")
private val outputLabels = listOf(OutputTarget.Toast to "Toast", OutputTarget.Shell to "Shell tab", OutputTarget.None to "None")
private val kindLabels = listOf(VariableKind.Text to "Text", VariableKind.List to "List", VariableKind.App to "Installed app")

/**
 * The editor for [draft]: fields in the spec's order, the test result under them, and Test run / Delete / Save at
 * the bottom. Nothing is saved until Save.
 */
@Composable
fun ActionEditor(
    state: ActionsUiState,
    draft: ActionDraft,
    packages: List<String>?,
    onEvent: (ActionsEvent) -> Unit,
    onBack: (() -> Unit)?,
) {
    val edit = { transform: (ActionDraft) -> ActionDraft -> onEvent(ActionsEvent.Edit(transform)) }
    val errors = state.errors
    var confirmDelete by remember(draft.id) { mutableStateOf(false) }
    var askValues by remember(draft.id) { mutableStateOf(false) }
    val monospace = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) DetailBackButton(onBack, Modifier.offset(x = (-8).dp))
            Text(if (draft.id == null) "New action" else "Edit action", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.dirty) Text("Unsaved changes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Segments(typeLabels, draft.type) { onEvent(ActionsEvent.SetType(it)) }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButtonWithPicker(draft) { icon -> edit { it.copy(icon = icon) } }
                OutlinedTextField(
                    draft.name, { v -> edit { it.copy(name = v) } }, Modifier.weight(1f),
                    label = { Text("Name") }, singleLine = true, isError = errors.name != null,
                    supportingText = errors.name?.let { { Text(it) } },
                )
            }
            when (draft.type) {
                ActionType.Toggle -> ToggleFields(draft, state, monospace, edit, onTestRead = { onEvent(ActionsEvent.TestRead) })
                ActionType.RunOnce -> RunOnceFields(draft, errors, monospace, edit)
            }
            state.test?.let { TestOutput(it) }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TestRunControl(
                state,
                onRun = {
                    val variables = draft.type == ActionType.RunOnce && draft.variables.isNotEmpty()
                    if (variables) askValues = true else onEvent(ActionsEvent.TestRun(emptyMap()))
                },
                onDevice = { onEvent(ActionsEvent.SetTestDevice(it)) },
            )
            Box(Modifier.weight(1f))
            if (draft.id != null) TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button({ onEvent(ActionsEvent.Save) }, enabled = state.dirty || draft.id == null) { Text("Save") }
        }
    }

    if (askValues) {
        // Like a real run: one form for every variable, prefilled with its default; it never shows the command.
        VariablesDialog(
            title = draft.name.ifBlank { "Test run" },
            variables = draft.variables,
            initialValues = remember(draft.variables) { draft.variables.mapValues { it.value.default }.filterValues { it.isNotEmpty() } },
            packages = packages,
            onLoadPackages = { onEvent(ActionsEvent.LoadPackages(it)) },
            onRun = { values -> askValues = false; onEvent(ActionsEvent.TestRun(values)) },
            onCancel = { askValues = false },
        )
    }
    if (confirmDelete && draft.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.name.ifBlank { "this action" }}?") },
            text = { Text("Its tile leaves every mirror window.") },
            confirmButton = { TextButton({ confirmDelete = false; onEvent(ActionsEvent.Delete(draft.id)) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IconButtonWithPicker(draft: ActionDraft, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val default = if (draft.type == ActionType.Toggle) ActionIcons.TOGGLE_DEFAULT else ActionIcons.RUN_ONCE_DEFAULT
    Box(Modifier.padding(top = 4.dp)) {
        FilledTonalIconButton({ open = !open }, Modifier.size(56.dp)) {
            Icon(ActionIcons.forName(draft.icon, default), contentDescription = "Icon: ${draft.icon}. Change icon")
        }
        if (open) IconPickerPopup(draft.icon, onPick = { onPick(it); open = false }, onDismiss = { open = false })
    }
}

@Composable
private fun ToggleFields(
    draft: ActionDraft,
    state: ActionsUiState,
    monospace: TextStyle,
    edit: ((ActionDraft) -> ActionDraft) -> Unit,
    onTestRead: () -> Unit,
) {
    val errors = state.errors
    CommandField("Turn on", draft.on, errors.on, monospace) { v -> edit { it.copy(on = v) } }
    CommandField("Turn off", draft.off, errors.off, monospace) { v -> edit { it.copy(off = v) } }
    CommandField(
        "Read state (optional)", draft.read, null, monospace,
        hint = "Runs each time the quick actions sheet opens; keep it quick",
    ) { v -> edit { it.copy(read = v) } }
    if (draft.read.isNotBlank()) {
        SectionLabel("On when output")
        // Segments share the row's width equally; give "matches regex" room.
        Segments(matchLabels, draft.matchMode, Modifier.width(420.dp)) { mode -> edit { it.copy(matchMode = mode) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                draft.matchValue, { v -> edit { it.copy(matchValue = v) } }, Modifier.weight(1f),
                placeholder = { Text("Value") }, singleLine = true, textStyle = monospace,
            )
            OutlinedButton(onTestRead, enabled = state.testDevice != null && state.test?.running != true) { Text("Test read") }
        }
        Hint("Output is trimmed before matching.")
    }
}

@Composable
private fun RunOnceFields(draft: ActionDraft, errors: DraftErrors, monospace: TextStyle, edit: ((ActionDraft) -> ActionDraft) -> Unit) {
    CommandField("Command", draft.command, errors.command, monospace) { v -> edit { it.copy(command = v) } }
    SectionLabel("Variables found in the command")
    if (draft.variables.isEmpty()) {
        Hint("None. Write {name} in the command to ask for a value each time it runs.")
    } else {
        draft.variables.forEach { (name, variable) ->
            VariableRow(name, variable, monospace) { v -> edit { it.copy(variables = it.variables + (name to v)) } }
        }
    }
    SectionLabel("Output")
    Segments(outputLabels, draft.output) { output -> edit { it.copy(output = output) } }
    Hint(OUTPUT_HINT)
    Row(
        Modifier.clickable(role = Role.Checkbox) { edit { it.copy(confirm = !it.confirm) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(draft.confirm, null)
        Text("Confirm before running", Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun VariableRow(name: String, variable: Variable, monospace: TextStyle, onChange: (Variable) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("{$name}", style = monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    variable.label, { onChange(variable.copy(label = it)) }, Modifier.weight(1f),
                    label = { Text("Label") }, singleLine = true,
                )
                KindMenu(variable.kind) { onChange(variable.copy(kind = it)) }
            }
            when (variable.kind) {
                VariableKind.Text -> OutlinedTextField(
                    variable.default, { onChange(variable.copy(default = it)) }, Modifier.fillMaxWidth(),
                    label = { Text("Default (optional)") }, singleLine = true,
                )
                VariableKind.List -> ChoicesField(name, variable.choices) { onChange(variable.copy(choices = it)) }
                VariableKind.App -> Hint("Picks from the device's apps; third-party only unless Show system apps is on.")
            }
        }
    }
}

/** Comma-separated choices; the text is kept as typed so a trailing comma survives until the next choice. */
@Composable
private fun ChoicesField(name: String, choices: List<String>, onChange: (List<String>) -> Unit) {
    var text by rememberSaveable(name) { mutableStateOf(choices.joinToString(", ")) }
    OutlinedTextField(
        text,
        { v ->
            text = v
            onChange(v.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        },
        Modifier.fillMaxWidth(),
        label = { Text("Choices, comma-separated") },
        singleLine = true,
    )
}

@Composable
private fun KindMenu(kind: VariableKind, onPick: (VariableKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(170.dp)) {
        OutlinedTextField(
            kindLabels.first { it.first == kind }.second, {}, Modifier.fillMaxWidth(), readOnly = true,
            label = { Text("Asks with") }, singleLine = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        // A read-only text field does not open a menu itself: a transparent hit area over it does.
        Box(Modifier.matchParentSize().clickable(role = Role.DropdownList) { open = true })
        DropdownMenu(open, { open = false }) {
            kindLabels.forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
private fun CommandField(label: String, value: String, error: String?, style: TextStyle, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) }, textStyle = style, isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
    )
}

/** Test run [ on <device> ▾ ]: disabled with no device connected. */
@Composable
private fun TestRunControl(state: ActionsUiState, onRun: () -> Unit, onDevice: (String) -> Unit) {
    val device = state.testDevice
    Button(onRun, enabled = device != null && state.test?.running != true) { Text("Test run") }
    if (device == null) {
        Text("No device connected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }) {
            Text("on $device", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose test device")
        }
        DropdownMenu(open, { open = false }) {
            state.devices.forEach { serial ->
                DropdownMenuItem(text = { Text(serial) }, onClick = { open = false; onDevice(serial) })
            }
        }
    }
}

/** The latest Test run / Test read: output as it came, the exit code, and the note. */
@Composable
private fun TestOutput(test: TestResult) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Test output")
            if (test.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            test.exitCode?.let { code ->
                Text("exit $code", style = MaterialTheme.typography.labelMedium, color = if (code == 0) colors.onSurfaceVariant else colors.error)
            }
            test.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            test.shownLines?.let { Text("Showing last $it lines", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
        }
        if (test.output.isNotEmpty() || test.running) {
            Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                SelectionContainer(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        test.output.ifEmpty { "…" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}
