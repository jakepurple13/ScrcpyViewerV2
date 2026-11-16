package com.programmersbox.scrcpyviewer.ui.presets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import com.programmersbox.scrcpyviewer.ui.theme.AlertDialog
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.AssistChip
import com.programmersbox.scrcpyviewer.ui.theme.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveListDetail
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveRow
import com.programmersbox.scrcpyviewer.ui.common.BarItem
import com.programmersbox.scrcpyviewer.ui.common.BarSpacer
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.ListPresentation
import com.programmersbox.scrcpyviewer.ui.common.ListStrip
import com.programmersbox.scrcpyviewer.ui.common.ListSwitcherChip
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.PriorityBar
import com.programmersbox.scrcpyviewer.ui.common.RowHighlight
import com.programmersbox.scrcpyviewer.ui.common.StripItem
import com.programmersbox.scrcpyviewer.ui.common.closeOverlay
import com.programmersbox.scrcpyviewer.ui.common.highlightContainer
import com.programmersbox.scrcpyviewer.ui.common.RowOrigin
import com.programmersbox.scrcpyviewer.ui.common.highlightPart
import com.programmersbox.scrcpyviewer.ui.common.rowOrigin
import com.programmersbox.scrcpyviewer.ui.common.itemSwitchTransform
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberRowHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlightTarget
import com.programmersbox.scrcpyviewer.ui.common.switchDirection
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.OptionRow
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import androidx.compose.runtime.key
import com.programmersbox.scrcpyviewer.ui.devices.initialValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import kotlin.math.roundToInt

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
    // One picker at a time: off macOS the UI stays live while it is open.
    val picker = rememberPickerGuard()
    fun export(ids: List<String>, suggested: String) {
        picker.launch(scope) {
            val path = files.chooseExportFile(suggested) ?: return@launch
            launchIo { reportErrors { Files.writeString(path, viewModel.exportJson(ids)) } }
        }
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
            picker.launch(scope) {
                val path = files.chooseImportFile()
                if (path != null) launchIo { reportErrors { viewModel.beginImport(Files.readString(path)) } }
            }
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
    val reduceMotion = LocalReduceMotion.current
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let { ErrorBanner("Presets can't be saved: $it") }
        AdaptiveListDetail(
            panes = panes,
            listMinWidth = PRESET_LIST_MIN_WIDTH,
            listPreferredWidth = PresetListPreferredWidth,
            hasSelection = state.selected != null,
            list = { presentation ->
                // Rows and avatars cross-fade while the list's width glides between them.
                Crossfade(presentation, animationSpec = tween(if (reduceMotion) 150 else 220), label = "presetPresentation") { shown ->
                    when (shown) {
                        ListPresentation.Full -> PresetList(
                            state,
                            onSelect = {
                                editingId = null
                                onSelect(it)
                                panes.navigation.openDetail()
                                panes.closeOverlay()
                            },
                            onNew = {
                                editingId = onNew().id
                                panes.navigation.openDetail()
                                panes.closeOverlay()
                            },
                            onExportAll = onExportAll, onImportFile = onImportFile, onImportPaste = onImportPaste,
                        )
                        ListPresentation.Strip -> PresetStrip(
                            state,
                            onSelect = { editingId = null; onSelect(it) },
                            onShowList = panes.navigation::backToList,
                            onNew = { editingId = onNew().id },
                        )
                    }
                }
            },
            detail = { openSwitcher ->
                // Item view only: the open preset leaving the list (deleted) leaves item view too.
                if (openSwitcher != null) BackToListWhenGone(state, panes.navigation::backToList)
                val switcher: (@Composable () -> Unit)? = openSwitcher?.let { open ->
                    { ListSwitcherChip(state.selected?.name ?: "Presets", open, Modifier.padding(bottom = 4.dp)) }
                }
                val ids = state.presets.map { it.id }
                // Keyed by preset, so editing one updates it in place; switching presets slides between them.
                AnimatedContent(
                    targetState = state.selected,
                    contentKey = { it?.id },
                    transitionSpec = {
                        itemSwitchTransform(switchDirection(ids.indexOf(initialState?.id), ids.indexOf(targetState?.id)), reduceMotion)
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "presetSwitch",
                ) { selected ->
                    Box(Modifier.fillMaxSize()) {
                        when {
                            selected == null -> {
                                switcher?.let { Box(Modifier.padding(start = 22.dp, top = 16.dp)) { it() } }
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
                                switcher = switcher,
                            )
                            else -> PresetDetail(
                                selected, state, onEdit = { editingId = selected.id },
                                onDuplicate = onDuplicate, onDelete = onDelete, onSetDefault = onSetDefault,
                                onApply = onApply, onExport = onExport, switcher = switcher,
                            )
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Calls [backToList] when the preset that was open leaves the list (deleted): item view would otherwise jump to
 * whichever preset is selected next.
 */
@Composable
private fun BackToListWhenGone(state: PresetsUiState, backToList: () -> Unit) {
    val open = remember { arrayOf(state.selected?.id) }
    val ids = state.presets.map { it.id }
    LaunchedEffect(state.selected?.id, ids) {
        val was = open[0]
        open[0] = state.selected?.id
        if (was != null && was !in ids) backToList()
    }
}

/** A preset's strip avatar: the first letters of its first two words, else its first two letters, uppercased. */
internal fun presetInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        else -> name.trim().take(2)
    }.uppercase()
}

/**
 * The preset list beside an open preset: an initials avatar per preset (the default one badged), and a New preset
 * button under them. Import and Export stay in the full list.
 */
@Composable
private fun PresetStrip(state: PresetsUiState, onSelect: (String) -> Unit, onShowList: () -> Unit, onNew: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val items = state.presets.map { preset ->
        StripItem(
            key = preset.id,
            label = preset.name,
            avatar = { Text(presetInitials(preset.name), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1) },
            badgeColor = primary.takeIf { preset.id == state.defaultId },
        )
    }
    ListStrip(
        items, state.selected?.id,
        onSelect = { onSelect(it as String) },
        onShowList = onShowList,
        modifier = Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        FilledTonalIconButton(onClick = onNew, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).size(44.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New preset", Modifier.size(20.dp))
        }
    }
}

/** Narrowest preset list that still shows a preset row (the toolbar wraps below its width). */
val PRESET_LIST_MIN_WIDTH = 280.dp
private val PresetListPreferredWidth = 390.dp

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
        // Material: the selected row's highlight slides between rows (see RowHighlight). The other styles mark the
        // selected row themselves (listRow).
        val highlight = rememberRowHighlight()
        Column(
            Modifier.verticalScroll(rememberScrollState()).then(
                if (currentStyle == ThemeStyle.Material) Modifier.highlightContainer(highlight) else Modifier,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.presets.forEachIndexed { i, preset ->
                // Keyed, so a row's entrance stays with its preset when rows are added or removed above it.
                key(preset.id) {
                    PresetRow(
                        preset, selected = preset.id == state.selected?.id, isDefault = preset.id == state.defaultId, highlight = highlight,
                        // The rows fill in one after another when the list appears.
                        entrance = Modifier.staggeredEntrance(i, trigger = Unit),
                    ) { onSelect(preset.id) }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: Preset,
    selected: Boolean,
    isDefault: Boolean,
    highlight: RowHighlight,
    /** The row's entrance; it moves the row in a layer, so the highlight's bounds are read outside it. */
    entrance: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = styledCorner(12.dp)
    val n = preset.options.entries.size
    val material = currentStyle == ThemeStyle.Material
    val origin = remember { RowOrigin() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (material) Modifier.selectionHighlightTarget(highlight.state, selected).rowOrigin(highlight, origin) else Modifier)
            .then(entrance)
            .then(
                // Material: the row's surface with the sliding highlight over it. The other styles keep listRow.
                if (material) {
                    Modifier.clip(shape).background(MaterialTheme.colorScheme.surface).highlightPart(highlight, shape, origin)
                } else {
                    Modifier.listRow(selected, shape)
                },
            )
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
    /** Item view's switcher chip, on its own line above the header. */
    switcher: (@Composable () -> Unit)?,
) {
    var confirmDelete by rememberSaveable(preset.id) { mutableStateOf(false) }
    val isDefault = preset.id == state.defaultId
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        switcher?.invoke()
        // Out of room, Duplicate folds into More first, then Export, Delete, Edit and the default chip; the name
        // (down to 110 dp) and Apply never fold. The name ellipsizes first; the chip stays right after it.
        val items = listOf(
            BarItem("name", priority = 0, menuLabel = preset.name, onMenuClick = null, minWidth = 110.dp, grow = true, growToNatural = true) {
                Text(preset.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            BarItem(
                "default", priority = 1,
                menuLabel = if (isDefault) "Remove as default" else "Set as default",
                onMenuClick = { onSetDefault(if (isDefault) null else preset.id) },
            ) {
                // With the bar's 4 dp gap, 10 dp after the name.
                Box(Modifier.padding(start = 6.dp)) {
                    if (isDefault) FilterChip(true, { onSetDefault(null) }, label = { Text("Default") })
                    else AssistChip({ onSetDefault(preset.id) }, label = { Text("Set as default") })
                }
            },
            BarSpacer,
            BarItem("edit", priority = 2, menuLabel = "Edit", onMenuClick = onEdit) { TextButton(onEdit) { Text("Edit") } },
            BarItem("duplicate", priority = 5, menuLabel = "Duplicate", onMenuClick = { onDuplicate(preset.id) }) {
                TextButton({ onDuplicate(preset.id) }) { Text("Duplicate") }
            },
            BarItem("export", priority = 4, menuLabel = "Export", onMenuClick = { onExport(preset) }) {
                TextButton({ onExport(preset) }) { Text("Export") }
            },
            BarItem("delete", priority = 3, menuLabel = "Delete", onMenuClick = { confirmDelete = true }) {
                TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            BarItem("apply", priority = 0, menuLabel = "Apply", onMenuClick = { onApply(preset.id) }.takeIf { state.canApply }) {
                Button({ onApply(preset.id) }, enabled = state.canApply) { Text("Apply") }
            },
        )
        PriorityBar(items, Modifier.fillMaxWidth(), gap = 4.dp)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FlagGrid(values: OptionValues) {
    if (values.entries.isEmpty()) {
        Text("No options. Use Edit to add some.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val order = OptionCatalog.all.withIndex().associate { it.value.flag to it.index }
    val rows = values.entries.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
    val reduceMotion = LocalReduceMotion.current
    LookaheadScope {
        // Every cell glides to its place when the grid changes between one and two columns.
        val glide = if (reduceMotion) Modifier else Modifier.animateBounds(this, boundsTransform = { _, _ -> layoutSpring() })
        FlagCells {
            rows.forEach { (flag, value) ->
                key(flag) { FlagCell(flag, value, glide) }
            }
        }
    }
}

/** Two cells per row when the grid is at least this wide, else one. */
private val TwoFlagColumnsMinWidth = 520.dp
private val FlagCellGap = 6.dp

/** Lays [cells] out in rows of two (one under [TwoFlagColumnsMinWidth]), 6 dp apart; a row is as tall as its tallest cell. */
@Composable
private fun FlagCells(cells: @Composable () -> Unit) {
    Layout(cells) { measurables, constraints ->
        val gap = FlagCellGap.roundToPx()
        val width = constraints.maxWidth
        val columns = if (width >= TwoFlagColumnsMinWidth.roundToPx()) 2 else 1
        // Like two weight(1f) cells in a Row: the first takes the odd pixel.
        val second = (width - gap * (columns - 1)) / columns
        val first = width - gap * (columns - 1) - second * (columns - 1)
        val placeables = measurables.mapIndexed { i, m ->
            val w = if (i % columns == 0) first else second
            m.measure(Constraints(minWidth = w, maxWidth = w))
        }
        val rowHeights = placeables.chunked(columns).map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            placeables.chunked(columns).forEachIndexed { r, row ->
                row.forEachIndexed { c, p -> p.place(if (c == 0) 0 else first + gap, y) }
                y += rowHeights[r] + gap
            }
        }
    }
}

/** One flag of the grid: its label, then the flag as written on the command line (under the label when narrow). */
@Composable
private fun FlagCell(flag: String, value: String?, modifier: Modifier) {
    val label = OptionCatalog.byFlag(flag)?.label ?: flag
    AdaptiveRow(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        control = {
            Text(
                if (value == null) "--$flag" else "--$flag=$value",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.clip(styledCorner(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        textMin = 90.dp,
        gap = 10.dp,
    )
}

@Composable
private fun JsonPanel(json: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = styledCorner(12.dp), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            // Narrow, the JSON wraps under Copy JSON: the text starts below the button instead (0 beside it, 1 under).
            val under by animateFloatAsState(
                if (maxWidth < NarrowJsonPanelWidth) 1f else 0f,
                if (LocalReduceMotion.current) snap() else layoutSpring(),
                label = "jsonUnderCopy",
            )
            Layout(
                contents = listOf(
                    {
                        SelectionContainer {
                            Text(
                                json,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            )
                        }
                    },
                    { CopyButton(text = { json }, label = "Copy JSON", modifier = Modifier.padding(4.dp)) },
                ),
            ) { (textMeasurables, copyMeasurables), constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val copy = copyMeasurables.first().measure(loose)
                val text = textMeasurables.first().measure(loose)
                val besideTop = 12.dp.roundToPx()
                val top = besideTop + ((copy.height - besideTop) * under).roundToInt()
                val width = constraints.maxWidth
                layout(width, maxOf(top + text.height, copy.height)) {
                    text.place(0, top)
                    copy.place(width - copy.width, 0)
                }
            }
        }
    }
}

/** Below this panel width the JSON text starts under the Copy JSON button rather than beside it. */
private val NarrowJsonPanelWidth = 520.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PresetEditor(
    preset: Preset,
    isNameTaken: (String) -> Boolean,
    onSave: (String, String, OptionValues) -> Unit,
    onCancel: () -> Unit,
    /** Item view's switcher chip, on its own line above the header. */
    switcher: (@Composable () -> Unit)?,
) {
    // Saveable so an unsaved draft survives switching rail tabs.
    var name by rememberSaveable(preset.id) { mutableStateOf(preset.name) }
    var description by rememberSaveable(preset.id) { mutableStateOf(preset.description) }
    var values by rememberSaveable(preset.id) { mutableStateOf(preset.options) }
    val errors = values.validate()
    val nameTaken = isNameTaken(name)
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        switcher?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Edit preset", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel") }
            Button({ onSave(name, description, values) }, enabled = name.isNotBlank() && !nameTaken && errors.isEmpty()) { Text("Save") }
        }
        val reduceMotion = LocalReduceMotion.current
        LookaheadScope {
            // The two fields glide between side by side and stacked.
            val glide = if (reduceMotion) Modifier else Modifier.animateBounds(this, boundsTransform = { _, _ -> layoutSpring() })
            NameAndDescription(
                name = {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, isError = name.isBlank() || nameTaken, supportingText = if (nameTaken) ({ Text("A preset with this name already exists") }) else null, modifier = glide)
                },
                description = {
                    OutlinedTextField(description, { description = it }, singleLine = true, label = { Text("Description") }, modifier = glide)
                },
            )
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

/** Name and description side by side (1 : 2) when the editor is at least this wide, else stacked. */
private val SideBySideFieldsMinWidth = 480.dp
private val FieldGap = 10.dp

@Composable
private fun NameAndDescription(name: @Composable () -> Unit, description: @Composable () -> Unit) {
    Layout(contents = listOf(name, description)) { (nameMeasurables, descriptionMeasurables), constraints ->
        val gap = FieldGap.roundToPx()
        val width = constraints.maxWidth
        if (width >= SideBySideFieldsMinWidth.roundToPx()) {
            // Like weight(1f) and weight(2f) in a Row.
            val nameWidth = ((width - gap) / 3f).roundToInt()
            val descriptionWidth = width - gap - nameWidth
            val n = nameMeasurables.first().measure(Constraints.fixedWidth(nameWidth))
            val d = descriptionMeasurables.first().measure(Constraints.fixedWidth(descriptionWidth))
            layout(width, maxOf(n.height, d.height)) {
                n.place(0, 0)
                d.place(nameWidth + gap, 0)
            }
        } else {
            val n = nameMeasurables.first().measure(Constraints.fixedWidth(width))
            val d = descriptionMeasurables.first().measure(Constraints.fixedWidth(width))
            layout(width, n.height + gap + d.height) {
                n.place(0, 0)
                d.place(0, n.height + gap)
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
