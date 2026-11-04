package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import com.programmersbox.scrcpyviewer.actions.ActionTemplates
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.ResizableListDetail
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.presets.ErrorBanner
import com.programmersbox.scrcpyviewer.ui.presets.PresetFileDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.nio.file.Files
import kotlin.math.roundToInt

/** Suggested export file name. */
const val ACTIONS_EXPORT_FILE = "scrcpy-actions.json"

private val ToolbarPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** What the Actions screen asks of its view model. */
sealed interface ActionsEvent {
    /** Opens [id], asking to discard unsaved changes first. */
    data class Select(val id: String) : ActionsEvent
    data object New : ActionsEvent
    /** Starts an unsaved draft from [template], asking to discard unsaved changes first. */
    data class NewFromTemplate(val template: ActionTemplate) : ActionsEvent
    data object Import : ActionsEvent
    /** Enters selection mode: tick the actions to export. */
    data object Export : ActionsEvent
    /** Asks for a file and writes the ticked actions to it. */
    data object ExportSelected : ActionsEvent
    data class ToggleExport(val id: String) : ActionsEvent
    data object SelectAllExport : ActionsEvent
    data object SelectNoExport : ActionsEvent
    data object CancelExport : ActionsEvent
    data object ShowIntro : ActionsEvent
    data class Move(val id: String, val toIndex: Int) : ActionsEvent
    data class Edit(val transform: (ActionDraft) -> ActionDraft) : ActionsEvent
    data class SetType(val type: ActionType) : ActionsEvent
    data object Save : ActionsEvent
    data class Delete(val id: String) : ActionsEvent
    data class TestRun(val values: Map<String, String>) : ActionsEvent
    data object TestRead : ActionsEvent
    data class SetTestDevice(val serial: String) : ActionsEvent
    data class LoadPackages(val includeSystem: Boolean) : ActionsEvent
}

@Composable
fun ActionsScreen(
    viewModel: ActionsViewModel,
    files: PresetFileDialogs,
    introSeen: Boolean,
    onIntroSeen: () -> Unit,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
) {
    val state by viewModel.state.collectAsState()
    val import by viewModel.importState.collectAsState()
    val pending by viewModel.pendingDiscard.collectAsState()
    val packages by viewModel.packages.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar?.showSnackbar(it) } }
    val scope = rememberCoroutineScope()
    var introRequested by remember { mutableStateOf(false) }

    /** Runs [block] on the IO dispatcher; shows a file error in the snackbar instead of crashing the UI. */
    fun io(block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                snackbar?.showSnackbar("File error: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    ActionsContent(
        state = state,
        packages = packages,
        onEvent = { event ->
            when (event) {
                is ActionsEvent.Select -> viewModel.handle(ActionsRequest(event.id, new = false))
                ActionsEvent.New -> viewModel.handle(ActionsRequest(null, new = true))
                is ActionsEvent.NewFromTemplate -> viewModel.newFromTemplate(event.template)
                ActionsEvent.Import -> files.chooseImportFile()?.let { path -> io { viewModel.beginImport(Files.readString(path)) } }
                ActionsEvent.Export -> viewModel.startExport()
                ActionsEvent.ExportSelected -> {
                    // Cancelling the file picker keeps the selection, so the user can pick again.
                    files.chooseExportFile(ACTIONS_EXPORT_FILE)?.let { path ->
                        val json = viewModel.exportJson(state.exportSelection.orEmpty())
                        viewModel.cancelExport()
                        io { Files.writeString(path, json) }
                    }
                }
                is ActionsEvent.ToggleExport -> viewModel.toggleExport(event.id)
                ActionsEvent.SelectAllExport -> viewModel.selectAllExport()
                ActionsEvent.SelectNoExport -> viewModel.selectNoExport()
                ActionsEvent.CancelExport -> viewModel.cancelExport()
                ActionsEvent.ShowIntro -> introRequested = true
                is ActionsEvent.Move -> viewModel.move(event.id, event.toIndex)
                is ActionsEvent.Edit -> viewModel.edit(event.transform)
                is ActionsEvent.SetType -> viewModel.setType(event.type)
                ActionsEvent.Save -> viewModel.save()
                is ActionsEvent.Delete -> viewModel.delete(event.id)
                is ActionsEvent.TestRun -> viewModel.testRun(event.values)
                ActionsEvent.TestRead -> viewModel.testRead()
                is ActionsEvent.SetTestDevice -> viewModel.setTestDevice(event.serial)
                is ActionsEvent.LoadPackages -> viewModel.loadPackages(event.includeSystem)
            }
        },
        modifier = modifier,
        panes = panes,
    )
    pending?.let { request ->
        DiscardDialog(
            name = state.draft?.name.orEmpty(),
            onDiscard = { viewModel.discardAndSelect(request.actionId) },
            onKeepEditing = viewModel::keepEditing,
        )
    }
    if (!introSeen || introRequested) {
        // Leaves composition on close, so reopening starts at the first page.
        var page by remember { mutableIntStateOf(0) }
        ActionsIntroDialog(
            page = page,
            onPage = { page = it },
            onFinish = { introRequested = false; onIntroSeen() },
        )
    }
    import?.let {
        ActionImportDialog(it, viewModel::setChoice, viewModel::setInclude, viewModel::confirmImport, viewModel::cancelImport)
    }
}

/** Stateless Actions screen: the action list, and the editor for the open action. */
@Composable
fun ActionsContent(
    state: ActionsUiState,
    onEvent: (ActionsEvent) -> Unit,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
    packages: List<String>? = null,
) {
    var picker by remember { mutableStateOf(false) }
    // The picker is this screen's own state; everything else goes to the caller.
    val send: (ActionsEvent) -> Unit = { event ->
        when (event) {
            is ActionsEvent.NewFromTemplate -> {
                picker = false
                onEvent(event)
                panes.navigation.openDetail()
            }
            else -> onEvent(event)
        }
    }
    if (picker) TemplatePickerDialog(onPick = { send(ActionsEvent.NewFromTemplate(it)) }, onDismiss = { picker = false })
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let { ErrorBanner("Actions can't be saved: $it") }
        ResizableListDetail(
            panes = panes,
            listMinWidth = ACTION_LIST_MIN_WIDTH,
            listPreferredWidth = ActionListPreferredWidth,
            detailMinWidth = ACTION_DETAIL_MIN_WIDTH,
            list = {
                ActionList(
                    state,
                    onBrowseTemplates = { picker = true },
                    onSelect = { onEvent(ActionsEvent.Select(it)); panes.navigation.openDetail() },
                    onEvent = { event ->
                        send(event)
                        if (event == ActionsEvent.New) panes.navigation.openDetail()
                    },
                )
            },
            detail = { onBack ->
                val draft = state.draft
                Box(Modifier.fillMaxSize()) {
                    when {
                        // Keyed by the open draft so remembered field text (e.g. a List variable's choices) never carries over.
                        draft != null -> key(state.editorKey) { ActionEditor(state, draft, packages, onEvent, onBack) }
                        else -> {
                            if (onBack != null) DetailBackButton(onBack, Modifier.padding(start = 6.dp, top = 6.dp))
                            if (state.actions.isEmpty()) EmptyState(
                                onBrowseTemplates = { picker = true },
                                onEvent = { event ->
                                    send(event)
                                    if (event == ActionsEvent.New) panes.navigation.openDetail()
                                },
                                modifier = Modifier.align(Alignment.Center))
                            else Text(
                                "Select an action to edit it, or create one with + New.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** Narrowest action list that still shows a row and the toolbar under it. */
val ACTION_LIST_MIN_WIDTH = 260.dp
private val ActionListPreferredWidth = 320.dp

/** Narrowest editor that still fits the bottom button row. */
val ACTION_DETAIL_MIN_WIDTH = 560.dp

@Composable
private fun EmptyState(onBrowseTemplates: () -> Unit, onEvent: (ActionsEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 440.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("No actions yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Actions are adb shell commands you run from a mirror window's quick actions sheet: toggles that turn " +
                "something on and off, or commands you run once.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onEvent(ActionsEvent.New) }) { Text("+ New") }
            OutlinedButton(onBrowseTemplates) { Text("Browse templates") }
            OutlinedButton({ onEvent(ActionsEvent.Import) }) { Text("Import") }
        }
        Text("Popular templates, one click to start", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            QUICK_START_TEMPLATES.forEach { name ->
                ActionTemplates.all.firstOrNull { it.name == name }?.let { template ->
                    AssistChip(
                        { onEvent(ActionsEvent.NewFromTemplate(template)) },
                        label = { Text(template.name) },
                        leadingIcon = { Icon(ActionIcons.forName(template.action.icon, template.action.defaultIcon), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

/** The empty page's one-click chips. */
private val QUICK_START_TEMPLATES = listOf("Grant permission", "Start activity", "Send broadcast", "Stay awake")

/** "+ New" with a drop-down arrow: the arrow offers a blank action or the template picker. */
@Composable
private fun SplitNewButton(onNew: () -> Unit, onBrowseTemplates: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(
            onNew,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
            contentPadding = ToolbarPadding,
        ) { Text("+ New", maxLines = 1) }
        Box {
            Button(
                { menu = true },
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "More ways to create an action") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text("New blank action") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { menu = false; onNew() },
                )
                DropdownMenuItem(
                    text = { Text("From template…") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesomeMotion, contentDescription = null) },
                    onClick = { menu = false; onBrowseTemplates() },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionList(state: ActionsUiState, onBrowseTemplates: () -> Unit, onSelect: (String) -> Unit, onEvent: (ActionsEvent) -> Unit) {
    val selection = state.exportSelection
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
        ListHeader(state.actions.size, selection, onEvent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.actions.isEmpty()) {
                Text(
                    "No actions yet. Create one with + New, or import a file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                )
            } else {
                ReorderableActions(
                    state.actions, state.selectedId, selection,
                    onSelect = { if (selection != null) onEvent(ActionsEvent.ToggleExport(it)) else onSelect(it) },
                ) { id, to -> onEvent(ActionsEvent.Move(id, to)) }
            }
        }
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (selection == null) {
                SplitNewButton(onNew = { onEvent(ActionsEvent.New) }, onBrowseTemplates = onBrowseTemplates)
                OutlinedButton({ onEvent(ActionsEvent.Import) }, contentPadding = ToolbarPadding) { Text("Import") }
                OutlinedButton({ onEvent(ActionsEvent.Export) }, enabled = state.actions.isNotEmpty(), contentPadding = ToolbarPadding) { Text("Export") }
            } else {
                Button({ onEvent(ActionsEvent.ExportSelected) }, enabled = selection.isNotEmpty(), contentPadding = ToolbarPadding) {
                    Text("Export ${selection.size}", maxLines = 1)
                }
                OutlinedButton({ onEvent(ActionsEvent.CancelExport) }, contentPadding = ToolbarPadding) { Text("Cancel") }
            }
        }
    }
}

/** "Actions" and the help button, or in selection mode the count with Select all and None. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListHeader(total: Int, selection: Set<String>?, onEvent: (ActionsEvent) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selection == null) {
            Text("Actions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TooltipArea(
                tooltip = {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                        Text(
                            "How actions work",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                },
            ) {
                IconButton({ onEvent(ActionsEvent.ShowIntro) }, Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "How actions work", modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Text("${selection.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ onEvent(ActionsEvent.SelectAllExport) }, enabled = selection.size < total, contentPadding = ToolbarPadding) { Text("Select all") }
            TextButton({ onEvent(ActionsEvent.SelectNoExport) }, enabled = selection.isNotEmpty(), contentPadding = ToolbarPadding) { Text("None") }
        }
    }
}

private val RowSpacing = 6.dp

/** The rows in order; dragging a row's handle moves it, and the others make room as it passes. */
@Composable
private fun ReorderableActions(
    actions: List<CustomAction>,
    selectedId: String?,
    exportSelection: Set<String>?,
    onSelect: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    val step = rowHeight + with(LocalDensity.current) { RowSpacing.toPx() }
    // The drag gesture outlives recompositions; it reads the latest list and callback.
    val currentActions by rememberUpdatedState(actions)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentStep by rememberUpdatedState(step)

    /** Where the dragged row would land, from its index and how far it has moved. */
    fun targetOf(from: Int, list: List<CustomAction>, step: Float): Int =
        if (from < 0 || step <= 0f) from else (from + (dragOffset / step).roundToInt()).coerceIn(list.indices)

    val from = actions.indexOfFirst { it.id == dragged }
    val target = targetOf(from, actions, step)

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RowSpacing)) {
        actions.forEachIndexed { index, action ->
            // Rows between the dragged row's start and its target shift one step to make room.
            val shift = when {
                from < 0 || index == from -> 0f
                index in (from + 1)..target -> -step
                index in target until from -> step
                else -> 0f
            }
            val isDragged = index == from
            ActionRow(
                action,
                selected = exportSelection == null && action.id == selectedId,
                checked = exportSelection?.let { action.id in it },
                onClick = { onSelect(action.id) },
                handle = Modifier.pointerInput(action.id, exportSelection == null) {
                    // No reordering while ticking actions to export.
                    if (exportSelection != null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { dragged = action.id; dragOffset = 0f },
                        onDragEnd = {
                            val id = dragged
                            val f = currentActions.indexOfFirst { it.id == id }
                            val t = targetOf(f, currentActions, currentStep)
                            if (id != null && t != f) currentOnMove(id, t)
                            dragged = null
                            dragOffset = 0f
                        },
                        onDragCancel = { dragged = null; dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                    }
                },
                modifier = Modifier
                    .onSizeChanged { rowHeight = it.height }
                    .graphicsLayer {
                        translationY = if (isDragged) dragOffset else shift
                        shadowElevation = if (isDragged) 8f else 0f
                    },
            )
        }
    }
}

@Composable
private fun ActionRow(action: CustomAction, selected: Boolean, checked: Boolean?, onClick: () -> Unit, handle: Modifier, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .then(
                if (checked == null) Modifier.clickable(onClick = onClick)
                else Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() }),
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (checked != null) Checkbox(checked, null, Modifier.size(20.dp))
        Icon(ActionIcons.forName(action.icon, action.defaultIcon), contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(action.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(actionTypeLine(action), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked == null) {
            Icon(
                Icons.Filled.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = colors.onSurfaceVariant,
                modifier = handle.pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR))).padding(4.dp).size(20.dp),
            )
        } else {
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun DiscardDialog(name: String, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard unsaved changes?") },
        text = { Text(if (name.isBlank()) "Your changes to this action are not saved." else "Your changes to $name are not saved.") },
        confirmButton = { TextButton(onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onKeepEditing) { Text("Keep editing") } },
    )
}

/** The type default, used when an icon name is unknown. */
val CustomAction.defaultIcon: String
    get() = when (this) {
        is ToggleAction -> ActionIcons.TOGGLE_DEFAULT
        is RunOnceAction -> ActionIcons.RUN_ONCE_DEFAULT
    }

/** "Toggle", "Run once", "Run once · 1 variable", "Run once · to Shell". */
fun actionTypeLine(action: CustomAction): String = when (action) {
    is ToggleAction -> "Toggle"
    is RunOnceAction -> buildList {
        add("Run once")
        CommandTemplate.variables(action.command).size.takeIf { it > 0 }?.let { add(if (it == 1) "1 variable" else "$it variables") }
        if (action.output == OutputTarget.Shell) add("to Shell")
    }.joinToString(" · ")
}
