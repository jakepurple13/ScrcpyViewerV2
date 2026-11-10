package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import com.programmersbox.scrcpyviewer.ui.theme.AlertDialog
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionSource
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.common.expandFadeIn
import com.programmersbox.scrcpyviewer.ui.common.shrinkFadeOut
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
        ) {
          // The test output sits outside the spaced column, so while collapsed it adds no gap.
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            draft.fromTemplate?.let { TemplateBanner(it) }
            if (draft.source == ActionSource.Ai && draft.id != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Made by AI", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ onEvent(ActionsEvent.MarkAsUser(draft.id)) }) { Text("Move to Your actions") }
                }
            }
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
          }
            AnimatedPresence(state.test) { TestOutput(it, Modifier.padding(top = 12.dp)) }
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

/** Says the unsaved draft came from a built-in template. */
@Composable
private fun TemplateBanner(name: String) {
    Surface(Modifier.fillMaxWidth(), shape = styledCorner(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.AutoAwesomeMotion, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Text(
                "From template \"$name\". Change anything, then Save.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
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
        val matchError = onWhenError(draft.matchMode, draft.matchValue)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                draft.matchValue, { v -> edit { it.copy(matchValue = v) } }, Modifier.weight(1f),
                placeholder = { Text("Value") }, singleLine = true, textStyle = monospace, isError = matchError != null,
            )
            OutlinedButton(onTestRead, enabled = state.testDevice != null && state.test?.running != true) { Text("Test read") }
        }
        // A pattern that does not compile says so in place of the sentence (Save reports it too).
        if (matchError != null) {
            Text(matchError, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Hint(onWhenSentence(draft.matchMode, draft.matchValue))
        }
        state.test?.takeIf { it.isRead && !it.running }?.let { test ->
            Text(
                buildAnnotatedString {
                    if (test.readOn == null) {
                        // No exit code: the read never finished (an adb error or the time limit; the note says which).
                        append(test.exitCode?.let { "Test read failed (exit $it)" } ?: "Test read failed")
                    } else {
                        append("Test read: device printed ")
                        val printed = printedLine(test.output)
                        if (printed.isEmpty()) append("(nothing)")
                        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(printed) }
                        append(" → counts as ${if (test.readOn) "On" else "Off"}")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunOnceFields(draft: ActionDraft, errors: DraftErrors, monospace: TextStyle, edit: ((ActionDraft) -> ActionDraft) -> Unit) {
    CommandField("Command", draft.command, errors.command, monospace) { v -> edit { it.copy(command = v) } }
    // One child of the field column, so the collapsed parts add no extra spacing between fields.
    Column {
        SectionLabel("Variables found in the command")
        AnimatedVisibility(draft.variables.isEmpty(), enter = expandFadeIn(), exit = shrinkFadeOut()) {
            Hint("None. Write {name} in the command to ask for a value each time it runs.", Modifier.padding(top = 12.dp))
        }
        AnimatedVisibility(draft.variables.isNotEmpty(), enter = expandFadeIn(), exit = shrinkFadeOut()) {
            VariableRows(draft.variables, monospace) { name, v -> edit { it.copy(variables = it.variables + (name to v)) } }
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

/**
 * One row per variable. A row grows in when its variable appears and shrinks away when it goes, and is kept
 * meanwhile with the settings it last had. Rows present when this first shows appear without animating.
 */
@Composable
private fun VariableRows(variables: Map<String, Variable>, monospace: TextStyle, onChange: (String, Variable) -> Unit) {
    // Plain collections: written during composition and read only by it, so typing does not trigger extra recomposition.
    val lastSeen = remember { HashMap<String, Variable>() }
    val order = remember { ArrayList<String>() }
    var settled by remember { mutableStateOf(false) }
    var finished by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { settled = true }
    lastSeen.putAll(variables)
    // Current variables in command order, with a leaving row staying at the index it had.
    val rows = variables.keys.toMutableList()
    order.forEachIndexed { index, name -> if (name !in variables && name in lastSeen) rows.add(index.coerceAtMost(rows.size), name) }
    order.clear()
    order.addAll(rows)
    Column {
        // Reading `finished` makes the list recompute once a row has finished leaving.
        key(finished) {}
        rows.forEach { name ->
            key(name) {
                VariableRowItem(name, variables[name], lastSeen[name], settled, monospace, onChange) {
                    lastSeen.remove(name)
                    finished++
                }
            }
        }
    }
}

@Composable
private fun VariableRowItem(
    name: String,
    live: Variable?,
    lastSeen: Variable?,
    settled: Boolean,
    monospace: TextStyle,
    onChange: (String, Variable) -> Unit,
    onGone: () -> Unit,
) {
    val variable = live ?: lastSeen
    val visible = remember { MutableTransitionState(!settled) }
    visible.targetState = live != null
    if (variable != null) {
        AnimatedVisibility(visible, enter = expandFadeIn(), exit = shrinkFadeOut()) {
            VariableRow(name, variable, monospace, Modifier.padding(top = 12.dp)) { onChange(name, it) }
        }
    }
    if (live == null && visible.isIdle && !visible.currentState) LaunchedEffect(Unit) { onGone() }
}

@Composable
private fun VariableRow(name: String, variable: Variable, monospace: TextStyle, modifier: Modifier, onChange: (Variable) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = styledCorner(12.dp), modifier = modifier.fillMaxWidth()) {
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
private fun TestOutput(test: TestResult, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Surface(color = colors.surfaceContainerHighest, shape = styledCorner(12.dp), modifier = Modifier.fillMaxWidth()) {
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

/** Shows [value] with its content growing in, and shrinking away when [value] becomes null, keeping the last value meanwhile. */
@Composable
private fun <T : Any> AnimatedPresence(value: T?, content: @Composable (T) -> Unit) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    val shown = last[0] as T?
    AnimatedVisibility(value != null, enter = expandFadeIn(), exit = shrinkFadeOut()) {
        if (shown != null) content(shown)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = styledSegmentShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}
