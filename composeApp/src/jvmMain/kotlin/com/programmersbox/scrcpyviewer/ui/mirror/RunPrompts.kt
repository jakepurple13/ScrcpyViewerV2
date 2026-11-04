package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import kotlinx.coroutines.flow.first
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind

/** Longest app list drawn at once; the search field narrows it. */
private const val MAX_APP_ROWS = 200

/**
 * The dialog for the pending run-once action: the variable form while [PendingRun.needsValues], then the confirm
 * dialog. Both name the action; neither shows its command.
 */
@Composable
fun RunPrompts(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit) {
    val prompt = state.prompt ?: return
    if (prompt.needsValues) VariablesDialog(prompt, state.packages, onEvent) else ConfirmRunDialog(prompt, onEvent)
}

@Composable
private fun ConfirmRunDialog(prompt: PendingRun, onEvent: (QuickActionEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(QuickActionEvent.PromptCancel) },
        title = { Text("Run ${prompt.action.name}?") },
        confirmButton = { TextButton(onClick = { onEvent(QuickActionEvent.PromptConfirm) }) { Text("Run") } },
        dismissButton = { TextButton(onClick = { onEvent(QuickActionEvent.PromptCancel) }) { Text("Cancel") } },
    )
}

@Composable
private fun VariablesDialog(prompt: PendingRun, packages: List<String>?, onEvent: (QuickActionEvent) -> Unit) {
    val action = prompt.action
    val names = remember(action) { CommandTemplate.variables(action.command) }
    VariablesDialog(
        title = action.name,
        variables = names.associateWith { action.variables[it] ?: Variable(label = it) },
        initialValues = prompt.values,
        packages = packages,
        onLoadPackages = { onEvent(QuickActionEvent.LoadPackages(includeSystem = it)) },
        onRun = { onEvent(QuickActionEvent.PromptValues(it)) },
        onCancel = { onEvent(QuickActionEvent.PromptCancel) },
    )
}

/**
 * Asks for every variable in one form, titled [title] (the action's name; never its command). [variables] is in
 * command order. [packages] lists installed apps for an Installed app variable (null while loading);
 * [onLoadPackages] asks for them, with system apps or not.
 */
@Composable
fun VariablesDialog(
    title: String,
    variables: Map<String, Variable>,
    initialValues: Map<String, String>,
    packages: List<String>?,
    onLoadPackages: (includeSystem: Boolean) -> Unit,
    onRun: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val names = variables.keys.toList()
    val values = remember(initialValues) { mutableStateMapOf<String, String>().apply { putAll(initialValues) } }
    var showSystem by remember(initialValues) { mutableStateOf(false) }
    val hasApp = variables.values.any { it.kind == VariableKind.App }
    LaunchedEffect(hasApp) { if (hasApp && packages == null) onLoadPackages(false) }
    // A list or app variable must be chosen; free text may stay empty.
    val complete = names.all { variables.getValue(it).kind == VariableKind.Text || !values[it].isNullOrBlank() || (variables.getValue(it).kind == VariableKind.List && variables.getValue(it).choices.isNotEmpty()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                names.forEach { name ->
                    val variable = variables.getValue(name)
                    val value = values[name].orEmpty()
                    when (variable.kind) {
                        VariableKind.Text -> OutlinedTextField(
                            value, { values[name] = it }, Modifier.fillMaxWidth(), label = { Text(variable.label) }, singleLine = true,
                        )
                        VariableKind.List -> ListField(variable, value) { values[name] = it }
                        VariableKind.App -> AppField(
                            variable.label, value, packages, showSystem,
                            onShowSystem = {
                                showSystem = it
                                onLoadPackages(it)
                            },
                        ) { values[name] = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = complete,
                onClick = {
                    // A list the user never opened runs with its first choice, as shown.
                    val entered = names.associateWith { name ->
                        values[name].orEmpty().ifEmpty { variables.getValue(name).choices.firstOrNull().orEmpty() }
                    }
                    onRun(entered)
                },
            ) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ListField(variable: Variable, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = value.ifEmpty { variable.choices.firstOrNull().orEmpty() }
    Box {
        OutlinedTextField(
            shown, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text(variable.label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        // A read-only text field does not open a menu itself: a transparent hit area over it does.
        Box(Modifier.matchParentSize().clickable(role = Role.DropdownList) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            variable.choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = { open = false; onPick(choice) })
            }
        }
    }
}

@Composable
private fun AppField(
    label: String,
    selected: String,
    packages: List<String>?,
    showSystem: Boolean,
    onShowSystem: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val matches = remember(packages, query) {
        packages.orEmpty().filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.take(MAX_APP_ROWS)
    }
    val scroll = rememberScrollState()
    var rowHeight by remember { mutableIntStateOf(0) }
    // Bring the selected app (e.g. the last one used) into view when the list loads or the search changes,
    // not on every pick, so a click does not move the row under the pointer.
    val selectedIndex = matches.indexOf(selected)
    LaunchedEffect(packages, query) {
        if (selectedIndex < 0) return@LaunchedEffect
        scroll.scrollTo(selectedIndex * snapshotFlow { rowHeight }.first { it > 0 })
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text("Search apps") },
            singleLine = true,
            // The search text is not the value: name the app that will run, so a prefilled one is never a surprise.
            supportingText = { Text(if (selected.isEmpty()) "No app selected" else "Selected: $selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Column(
            Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainer)
                .verticalScroll(scroll),
        ) {
            when {
                packages == null -> Text("Loading apps…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                matches.isEmpty() -> Text("No apps found", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                else -> matches.forEach { pkg ->
                    val picked = pkg == selected
                    Text(
                        pkg,
                        Modifier.fillMaxWidth().onSizeChanged { rowHeight = it.height }
                            .background(if (picked) colors.primaryContainer else colors.surfaceContainer)
                            .clickable { onPick(pkg) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (picked) colors.onPrimaryContainer else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show system apps", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(showSystem, onShowSystem)
        }
    }
}
