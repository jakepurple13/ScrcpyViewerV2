package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import com.programmersbox.scrcpyviewer.ui.common.LabelTooltip
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveRow
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveSegmentedButtons
import com.programmersbox.scrcpyviewer.ui.common.BackArrowRow
import com.programmersbox.scrcpyviewer.ui.common.BarItem
import com.programmersbox.scrcpyviewer.ui.common.BarSpacer
import com.programmersbox.scrcpyviewer.ui.common.ControlSize
import com.programmersbox.scrcpyviewer.ui.common.LocalBarProbe
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.PriorityBar
import com.programmersbox.scrcpyviewer.ui.common.SegmentOption
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.rowFit
import com.programmersbox.scrcpyviewer.ui.common.expandFadeIn
import com.programmersbox.scrcpyviewer.ui.common.shrinkFadeOut
import com.programmersbox.scrcpyviewer.ui.mirror.VariablesDialog
import kotlin.math.roundToInt

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
    /** Back to the list, while the editor is pushed over it: the arrow starts the header row. */
    onBack: (() -> Unit)?,
) {
    val edit = { transform: (ActionDraft) -> ActionDraft -> onEvent(ActionsEvent.Edit(transform)) }
    val errors = state.errors
    var confirmDelete by remember(draft.id) { mutableStateOf(false) }
    var askValues by remember(draft.id) { mutableStateOf(false) }
    val monospace = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp)) {
            // The title (down to 90 dp) never folds; "Unsaved changes" folds into More when there is no room.
            val title = if (draft.id == null) "New action" else "Edit action"
            BackArrowRow(onBack) { barModifier ->
                PriorityBar(
                    listOfNotNull(
                        BarItem("title", priority = 0, menuLabel = title, onMenuClick = null, minWidth = 90.dp, grow = true) {
                            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        if (state.dirty) {
                            BarItem("unsaved", priority = 1, menuLabel = "Unsaved changes", onMenuClick = null) {
                                Text("Unsaved changes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        } else {
                            null
                        },
                    ),
                    barModifier,
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 4.dp),
        ) {
          // The test output sits outside the spaced column, so while collapsed it adds no gap.
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            draft.fromTemplate?.takeIf { draft.templateBanner }?.let { TemplateBanner(it) }
            if (draft.source == ActionSource.Ai && draft.id != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Made by AI", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ onEvent(ActionsEvent.MarkAsUser(draft.id)) }) { Text("Move to Your actions") }
                }
            }
            FitSegments(typeLabels, draft.type) { onEvent(ActionsEvent.SetType(it)) }
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
        EditorBottomBar(
            state,
            canDelete = draft.id != null,
            onRun = {
                val variables = draft.type == ActionType.RunOnce && draft.variables.isNotEmpty()
                if (variables) askValues = true else onEvent(ActionsEvent.TestRun(emptyMap()))
            },
            onDevice = { onEvent(ActionsEvent.SetTestDevice(it)) },
            onDelete = { confirmDelete = true },
            onSave = { onEvent(ActionsEvent.Save) },
        )
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
        // Segments share the row's width equally; give "matches regex" room (420 dp, less when the editor is narrower).
        FitSegments(matchLabels, draft.matchMode, fillWidth = true, maxWidth = 420.dp) { mode -> edit { it.copy(matchMode = mode) } }
        val matchError = onWhenError(draft.matchMode, draft.matchValue)
        // Test read moves under Value when Value would get less than 160 dp beside it.
        val canTestRead = state.testDevice != null && state.test?.running != true
        AdaptiveRow(
            text = {
                OutlinedTextField(
                    draft.matchValue, { v -> edit { it.copy(matchValue = v) } }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Value") }, singleLine = true, textStyle = monospace, isError = matchError != null,
                )
            },
            control = { OutlinedButton(onTestRead, enabled = canTestRead) { Text("Test read", maxLines = 1) } },
            textMin = 160.dp,
            gap = 10.dp,
        )
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
    FitSegments(outputLabels, draft.output) { output -> edit { it.copy(output = output) } }
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
            val reduceMotion = LocalReduceMotion.current
            LookaheadScope {
                // The three glide between one row and stacked.
                val glide = if (reduceMotion) Modifier else Modifier.animateBounds(this, boundsTransform = { _, _ -> layoutSpring() })
                VariableFields(
                    name = { Text("{$name}", glide, style = monospace, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    label = {
                        OutlinedTextField(
                            variable.label, { onChange(variable.copy(label = it)) }, glide,
                            label = { Text("Label") }, singleLine = true,
                        )
                    },
                    kind = { KindMenu(variable.kind, glide) { onChange(variable.copy(kind = it)) } },
                )
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

/** The variable card's fields in one row ({name} 120 dp, Label, Asks with 170 dp) from this card width, else stacked. */
private val VariableRowMinWidth = 440.dp
private val VariableNameWidth = 120.dp
private val VariableKindWidth = 170.dp
private val VariableGap = 10.dp

/** Lays out a variable's {name}, Label and Asks with in one row, or stacked full width under [VariableRowMinWidth]. */
@Composable
private fun VariableFields(name: @Composable () -> Unit, label: @Composable () -> Unit, kind: @Composable () -> Unit) {
    Layout(contents = listOf(name, label, kind)) { (n, l, k), constraints ->
        val width = constraints.maxWidth
        val gap = VariableGap.roundToPx()
        if (width >= VariableRowMinWidth.roundToPx()) {
            val nameWidth = VariableNameWidth.roundToPx()
            val kindWidth = VariableKindWidth.roundToPx()
            val labelWidth = (width - nameWidth - kindWidth - 2 * gap).coerceAtLeast(0)
            val np = n.first().measure(Constraints.fixedWidth(nameWidth))
            val lp = l.first().measure(Constraints.fixedWidth(labelWidth))
            val kp = k.first().measure(Constraints.fixedWidth(kindWidth))
            val height = maxOf(np.height, lp.height, kp.height)
            layout(width, height) {
                np.place(0, (height - np.height) / 2)
                lp.place(nameWidth + gap, (height - lp.height) / 2)
                kp.place(width - kindWidth, (height - kp.height) / 2)
            }
        } else {
            val placeables = listOf(n, l, k).map { it.first().measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { p ->
                    p.place(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}

@Composable
private fun KindMenu(kind: VariableKind, modifier: Modifier, onPick: (VariableKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
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

/**
 * Test run [ on <device> ▾ ] … Delete, Save. Out of room the device picker folds into More first (its label ellipsizes
 * before that, from 260 dp), then Delete; Test run and Save never fold. The device menu is outside the bar (the bar
 * composes its items twice) and opens at the control clicked: the picker, or More when the picker is folded.
 *
 * Under [CompactBarWidth] (a phone-width window) Test run and Save would not fit beside More: Test run's label slides
 * shut, leaving its play icon, and both buttons' side padding shrinks from 24 to 16 dp.
 */
@Composable
private fun EditorBottomBar(
    state: ActionsUiState,
    canDelete: Boolean,
    onRun: () -> Unit,
    onDevice: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    val device = state.testDevice
    val draft = state.draft
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var picker by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Where the device menu opens, relative to the bar's box; null while closed.
    var menuAt by remember { mutableStateOf<Rect?>(null) }
    // A disconnect closes the menu, so it does not reopen by itself when a device comes back.
    LaunchedEffect(device == null) { if (device == null) menuAt = null }
    val moreWidth = with(LocalDensity.current) { 48.dp.toPx() }
    fun openFromPicker() {
        val box = container?.takeIf { it.isAttached } ?: return
        val from = picker?.takeIf { it.isAttached }
        menuAt = if (from != null) box.localBoundingBoxOf(from) else Rect(box.size.width - moreWidth, 0f, box.size.width.toFloat(), box.size.height.toFloat())
    }
    fun openFromMore() {
        val box = container?.takeIf { it.isAttached } ?: return
        // More sits at the bar's end. 48 dp is PriorityBar's overflow button (an IconButton at its minimum touch
        // size); PriorityBar does not expose its bounds, so a change to that button must be mirrored here.
        menuAt = Rect(box.size.width - moreWidth, 0f, box.size.width.toFloat(), box.size.height.toFloat())
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).onGloballyPositioned { container = it }) {
        val compact = maxWidth < CompactBarWidth
        val reduceMotion = LocalReduceMotion.current
        val sidePadding by animateDpAsState(
            if (compact) 16.dp else ButtonSidePadding,
            if (reduceMotion) snap() else layoutSpring(),
            label = "barButtonPadding",
        )
        /** The measuring copies use the target padding; only the displayed buttons animate. */
        @Composable
        fun padding(): PaddingValues {
            val side = if (LocalBarProbe.current) (if (compact) 16.dp else ButtonSidePadding) else sidePadding
            return if (side == ButtonSidePadding) ButtonDefaults.ContentPadding else PaddingValues(horizontal = side, vertical = ButtonVerticalPadding)
        }
        val items = listOfNotNull(
            BarItem("run", priority = 0, menuLabel = "Test run", onMenuClick = null) {
                Button(onRun, enabled = device != null && state.test?.running != true, contentPadding = padding()) {
                    TestRunLabel(compact, reduceMotion)
                }
            },
            if (device == null) {
                BarItem("device", priority = 3, menuLabel = "No device connected", onMenuClick = null) {
                    Text("No device connected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            } else {
                BarItem("device", priority = 3, menuLabel = "Test on $device", onMenuClick = ::openFromMore) {
                    val probe = LocalBarProbe.current
                    TextButton(
                        ::openFromPicker,
                        Modifier.widthIn(max = 260.dp).then(if (probe) Modifier else Modifier.onGloballyPositioned { picker = it }),
                    ) {
                        Text("on $device", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose test device")
                    }
                }
            },
            BarSpacer,
            if (canDelete) {
                BarItem("delete", priority = 2, menuLabel = "Delete", onMenuClick = onDelete) {
                    TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error, maxLines = 1) }
                }
            } else {
                null
            },
            BarItem("save", priority = 0, menuLabel = "Save", onMenuClick = null) {
                Button(onSave, enabled = state.dirty || draft?.id == null, contentPadding = padding()) { Text("Save", maxLines = 1) }
            },
        )
        PriorityBar(items, Modifier.fillMaxWidth())
        val at = menuAt
        if (at != null && device != null) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset(at.left.roundToInt(), at.top.roundToInt()) }
                    .size(with(density) { at.width.toDp() }, with(density) { at.height.toDp() }),
            ) {
                DropdownMenu(true, { menuAt = null }) {
                    state.devices.forEach { serial ->
                        DropdownMenuItem(text = { Text(serial) }, onClick = { menuAt = null; onDevice(serial) })
                    }
                }
            }
        }
    }
}

/** Below this bar width Test run shows only its icon (Test run, Save and More need 248 dp at full size). */
private val CompactBarWidth = 250.dp
private val ButtonSidePadding = 24.dp
private val ButtonVerticalPadding = 8.dp

/**
 * "Test run", or its play icon alone when [compact] (named for screen readers and in a tooltip). The label slides shut
 * and the icon grows in; the bar's measuring copy shows the target form at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TestRunLabel(compact: Boolean, reduceMotion: Boolean) {
    val icon = @Composable { Icon(Icons.Filled.PlayArrow, contentDescription = "Test run", Modifier.size(20.dp)) }
    val label = @Composable { Text("Test run", maxLines = 1) }
    if (LocalBarProbe.current) {
        if (compact) icon() else label()
        return
    }
    val enter = if (reduceMotion) fadeIn(tween(120)) else expandHorizontally(layoutSpring()) + fadeIn(tween(150))
    val exit = if (reduceMotion) fadeOut(tween(120)) else shrinkHorizontally(layoutSpring()) + fadeOut(tween(150))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(compact, enter = enter, exit = exit) {
            TooltipArea(tooltip = { LabelTooltip("Test run") }) { icon() }
        }
        AnimatedVisibility(!compact, enter = enter, exit = exit) { label() }
    }
}

/** The latest Test run / Test read: output as it came, the exit code, and the note. */
@Composable
private fun TestOutput(test: TestResult, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The label and exit code never shrink. The note takes the room left and ellipsizes away first; "Showing last
        // N lines" keeps its width while there is room, then ellipsizes too. Neither folds, so no More button takes
        // room at phone width.
        PriorityBar(
            listOfNotNull(
                BarItem("label", priority = 0, menuLabel = "Test output", onMenuClick = null) { SectionLabel("Test output") },
                if (test.running) {
                    BarItem("running", priority = 0, menuLabel = "Running", onMenuClick = null) {
                        // A plain box while measured: the spinner's animation runs only in the displayed copy.
                        if (LocalBarProbe.current) Box(Modifier.size(14.dp)) else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                } else {
                    null
                },
                test.exitCode?.let { code ->
                    BarItem("exit", priority = 0, menuLabel = "exit $code", onMenuClick = null) {
                        Text("exit $code", style = MaterialTheme.typography.labelMedium, color = if (code == 0) colors.onSurfaceVariant else colors.error, maxLines = 1)
                    }
                },
                test.note?.let { note ->
                    BarItem("note", priority = 0, menuLabel = note, onMenuClick = null, minWidth = 0.dp, grow = true) {
                        Text(note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                test.shownLines?.let { n ->
                    BarItem(
                        "shown", priority = 1, menuLabel = "Showing last $n lines", onMenuClick = null,
                        minWidth = 0.dp, grow = true, growToNatural = true,
                    ) {
                        Text("Showing last $n lines", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            ),
            Modifier.fillMaxWidth(),
        )
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

/**
 * An [AdaptiveSegmentedButtons] at the size [rowFit] picks for the width it gets (no text beside it): the segmented
 * row when its full width fits, else the menu. The full width is read from a hidden probe.
 */
@Composable
private fun <T> FitSegments(
    options: List<Pair<T, String>>,
    selected: T,
    fillWidth: Boolean = false,
    maxWidth: Dp = Dp.Unspecified,
    onSelect: (T) -> Unit,
) {
    val segments = remember(options) { options.map { (value, label) -> SegmentOption(value, label) } }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    SubcomposeLayout { constraints ->
        val size = if (!constraints.hasBoundedWidth) {
            ControlSize.Full
        } else {
            val full = subcompose(FitProbe) {
                AdaptiveSegmentedButtons(segments, currentSelected, {}, ControlSize.Full, fillWidth = fillWidth, maxWidth = maxWidth)
            }.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
            rowFit(constraints.maxWidth, textMin = 0, gap = 0, full = full).size
        }
        val placeables = subcompose(FitContent) {
            AdaptiveSegmentedButtons(segments, currentSelected, { currentOnSelect(it) }, size, fillWidth = fillWidth, maxWidth = maxWidth)
        }.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

private object FitProbe
private object FitContent
