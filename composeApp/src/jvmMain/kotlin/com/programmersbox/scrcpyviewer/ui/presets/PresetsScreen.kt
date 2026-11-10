package com.programmersbox.scrcpyviewer.ui.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.listRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import com.programmersbox.scrcpyviewer.ui.theme.AlertDialog
import androidx.compose.material3.AssistChip
import com.programmersbox.scrcpyviewer.ui.theme.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.ResizableListDetail
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.OptionRow
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import androidx.compose.runtime.key
import com.programmersbox.scrcpyviewer.ui.devices.initialValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files

private val ToolbarPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** Where Apply is unavailable, this explains why. */
const val APPLY_DISABLED_HINT = "Select a device on the Devices page"

@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel,
    files: PresetFileDialogs,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val import by viewModel.importState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar?.showSnackbar(it) } }
    var pasting by rememberSaveable { mutableStateOf(false) }

    /** Runs [block]; shows the failure in the snackbar instead of crashing the UI on a file error. */
    suspend fun reportErrors(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            snackbar?.showSnackbar("File error: ${e.message ?: e::class.simpleName}")
        }
    }

    val scope = rememberCoroutineScope()
    fun export(ids: List<String>, suggested: String) {
        val path = files.chooseExportFile(suggested) ?: return
        scope.launchIo { reportErrors { Files.writeString(path, viewModel.exportJson(ids)) } }
    }

    PresetsContent(
        state = state,
        onSelect = viewModel::select,
        onNew = viewModel::new,
        onEdit = viewModel::edit,
        onDuplicate = viewModel::duplicate,
        onDelete = viewModel::delete,
        onSetDefault = viewModel::setDefault,
        onApply = { viewModel.applyToSelectedDevice(it) },
        onExport = { p -> export(listOf(p.id), exportFileName(p.name)) },
        onExportAll = { export(state.presets.map { it.id }, exportFileName(null)) },
        onImportFile = {
            val path = files.chooseImportFile()
            if (path != null) scope.launchIo { reportErrors { viewModel.beginImport(Files.readString(path)) } }
        },
        onImportPaste = { pasting = true },
        modifier = modifier,
        panes = panes,
    )
    if (pasting) PasteJsonDialog(onDismiss = { pasting = false }, onImport = { pasting = false; viewModel.beginImport(it) })
    import?.let {
        ImportDialog(it, viewModel::setChoice, viewModel::setInclude, viewModel::confirmImport, viewModel::cancelImport)
    }
}

private fun CoroutineScope.launchIo(block: suspend () -> Unit) {
    launch(Dispatchers.IO) { block() }
}

/** Stateless Presets screen (Concept C: preset list, detail with option rows and exported file). */
@Composable
fun PresetsContent(
    state: PresetsUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Preset,
    onEdit: (String, String, String, OptionValues) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onApply: (String) -> Unit,
    onExport: (Preset) -> Unit,
    onExportAll: () -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let { ErrorBanner("Presets can't be saved: $it") }
        ResizableListDetail(
            panes = panes,
            listMinWidth = PRESET_LIST_MIN_WIDTH,
            listPreferredWidth = PresetListPreferredWidth,
            detailMinWidth = PRESET_DETAIL_MIN_WIDTH,
            list = {
                PresetList(
                    state, onSelect = { editingId = null; onSelect(it); panes.navigation.openDetail() },
                    onNew = { editingId = onNew().id; panes.navigation.openDetail() }, onExportAll = onExportAll,
                    onImportFile = onImportFile, onImportPaste = onImportPaste,
                )
            },
            detail = { onBack ->
                val selected = state.selected
                Box(Modifier.fillMaxSize()) {
                    when {
                        selected == null -> {
                            if (onBack != null) DetailBackButton(onBack, Modifier.padding(start = 6.dp, top = 6.dp))
                            Text(
                                "No presets yet. Create one with + New preset, or import a file.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        editingId == selected.id -> PresetEditor(
                            selected,
                            isNameTaken = { n -> state.presets.any { it.id != selected.id && it.name.equals(n.trim(), ignoreCase = true) } },
                            onSave = { name, description, values -> onEdit(selected.id, name, description, values); editingId = null },
                            onCancel = { editingId = null },
                            onBack = onBack,
                        )
                        else -> PresetDetail(
                            selected, state, onEdit = { editingId = selected.id },
                            onDuplicate = onDuplicate, onDelete = onDelete, onSetDefault = onSetDefault,
                            onApply = onApply, onExport = onExport, onBack = onBack,
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** Narrowest preset list that still shows a preset row (the toolbar wraps below its width). */
val PRESET_LIST_MIN_WIDTH = 280.dp
private val PresetListPreferredWidth = 390.dp

/** Narrowest detail that still fits the action row and the two-column flag grid. */
val PRESET_DETAIL_MIN_WIDTH = 600.dp

/** A full-width error strip above a screen's content, e.g. when its store can't be saved. */
@Composable
internal fun ErrorBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetList(
    state: PresetsUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onExportAll: () -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var importMenu by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(onNew, contentPadding = ToolbarPadding) { Text("+ New preset", maxLines = 1) }
            Box {
                OutlinedButton({ importMenu = true }, contentPadding = ToolbarPadding) { Text("Import") }
                DropdownMenu(importMenu, { importMenu = false }) {
                    DropdownMenuItem(text = { Text("From file…") }, onClick = { importMenu = false; onImportFile() })
                    DropdownMenuItem(text = { Text("Paste JSON…") }, onClick = { importMenu = false; onImportPaste() })
                }
            }
            OutlinedButton(onExportAll, enabled = state.presets.isNotEmpty(), contentPadding = ToolbarPadding) { Text("Export all", maxLines = 1) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.presets.forEachIndexed { i, preset ->
                // Keyed, so a row's entrance stays with its preset when rows are added or removed above it.
                key(preset.id) {
                    PresetRow(
                        preset, selected = preset.id == state.selected?.id, isDefault = preset.id == state.defaultId,
                        // The rows fill in one after another when the list appears.
                        modifier = Modifier.staggeredEntrance(i, trigger = Unit),
                    ) { onSelect(preset.id) }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, selected: Boolean, isDefault: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = styledCorner(12.dp)
    val n = preset.options.entries.size
    Row(
        modifier
            .fillMaxWidth()
            .listRow(selected, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (isDefault) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(preset.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$n ${if (n == 1) "option" else "options"}" + if (isDefault) " · default for new devices" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PresetDetail(
    preset: Preset,
    state: PresetsUiState,
    onEdit: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onApply: (String) -> Unit,
    onExport: (Preset) -> Unit,
    onBack: (() -> Unit)?,
) {
    var confirmDelete by rememberSaveable(preset.id) { mutableStateOf(false) }
    val isDefault = preset.id == state.defaultId
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onBack != null) DetailBackButton(onBack, Modifier.offset(x = (-8).dp))
            // Name and chip take the space the actions leave; the name ellipsizes first.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(preset.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.width(10.dp))
                if (isDefault) FilterChip(true, { onSetDefault(null) }, label = { Text("Default") })
                else AssistChip({ onSetDefault(preset.id) }, label = { Text("Set as default") })
            }
            TextButton(onEdit) { Text("Edit") }
            TextButton({ onDuplicate(preset.id) }) { Text("Duplicate") }
            TextButton({ onExport(preset) }) { Text("Export") }
            TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button({ onApply(preset.id) }, enabled = state.canApply) { Text("Apply") }
        }
        if (!state.canApply) {
            Text(APPLY_DISABLED_HINT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
        }
        if (preset.description.isNotBlank()) {
            Text(preset.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlagGrid(preset.options)
        Text("EXPORTED FILE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        JsonPanel(state.selectedJson)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${preset.name}?") },
            text = { Text("Devices that started from this preset keep their current options.") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete(preset.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FlagGrid(values: OptionValues) {
    if (values.entries.isEmpty()) {
        Text("No options. Use Edit to add some.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val order = OptionCatalog.all.withIndex().associate { it.value.flag to it.index }
    val rows = values.entries.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (flag, value) ->
                    val label = OptionCatalog.byFlag(flag)?.label ?: flag
                    Row(
                        Modifier.weight(1f).clip(styledCorner(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (value == null) "--$flag" else "--$flag=$value",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun JsonPanel(json: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = styledCorner(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box {
            SelectionContainer {
                Text(
                    json,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            CopyButton(text = { json }, label = "Copy JSON", modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
private fun PresetEditor(
    preset: Preset,
    isNameTaken: (String) -> Boolean,
    onSave: (String, String, OptionValues) -> Unit,
    onCancel: () -> Unit,
    onBack: (() -> Unit)?,
) {
    // Saveable so an unsaved draft survives switching rail tabs.
    var name by rememberSaveable(preset.id) { mutableStateOf(preset.name) }
    var description by rememberSaveable(preset.id) { mutableStateOf(preset.description) }
    var values by rememberSaveable(preset.id) { mutableStateOf(preset.options) }
    val errors = values.validate()
    val nameTaken = isNameTaken(name)
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) DetailBackButton(onBack, Modifier.offset(x = (-8).dp))
            Text("Edit preset", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel") }
            Button({ onSave(name, description, values) }, enabled = name.isNotBlank() && !nameTaken && errors.isEmpty()) { Text("Save") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, isError = name.isBlank() || nameTaken, supportingText = if (nameTaken) ({ Text("A preset with this name already exists") }) else null, modifier = Modifier.weight(1f))
            OutlinedTextField(description, { description = it }, singleLine = true, label = { Text("Description") }, modifier = Modifier.weight(2f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OptionGroup.entries.forEach { group ->
                val specs = OptionCatalog.all.filter { it.group == group }
                if (specs.isEmpty()) return@forEach
                Text(group.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                specs.forEach { spec ->
                    OptionRow(
                        spec = spec,
                        fieldKey = preset.id,
                        checked = spec.flag in values.entries,
                        value = values.entries[spec.flag],
                        error = errors[spec.flag],
                        onCheckedChange = { on -> values = if (on) values.with(spec.flag, initialValue(spec)) else values.without(spec.flag) },
                        onValueChange = { v -> if (spec.flag in values.entries) values = values.with(spec.flag, v) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteJsonDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste JSON") },
        text = {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("Paste the contents of a .scrcpypresets.json file") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        },
        confirmButton = { TextButton({ onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
