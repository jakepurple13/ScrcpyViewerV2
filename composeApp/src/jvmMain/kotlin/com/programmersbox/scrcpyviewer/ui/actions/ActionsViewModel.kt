package com.programmersbox.scrcpyviewer.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.actions.ActionCodec
import com.programmersbox.scrcpyviewer.actions.ActionFileException
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.ActionImportItem
import com.programmersbox.scrcpyviewer.actions.ActionImportPlan
import com.programmersbox.scrcpyviewer.actions.ActionSource
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.ui.mirror.DeviceQuickActions
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.common.mapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.KoinViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ActionType { Toggle, RunOnce }

/** The editor's working copy. Holds both types' fields so switching type loses nothing; [id] is null until saved. */
data class ActionDraft(
    val id: String?,
    val type: ActionType,
    val name: String,
    val icon: String,
    val on: String = "",
    val off: String = "",
    val read: String = "",
    val matchMode: MatchMode = MatchMode.Equals,
    val matchValue: String = "",
    val command: String = "",
    /** One entry per distinct `{name}` in [command], in first-appearance order. */
    val variables: Map<String, Variable> = emptyMap(),
    val output: OutputTarget = OutputTarget.Toast,
    val confirm: Boolean = false,
    /** Name of the built-in template this unsaved draft started from, for the editor's banner; null once saved. */
    val fromTemplate: String? = null,
    /** Carried through editing so saving an AI-made action keeps its mark. */
    val source: ActionSource = ActionSource.User,
)

/** Field errors from the last [ActionsViewModel.save]; null means the field is fine. */
data class DraftErrors(val name: String? = null, val on: String? = null, val off: String? = null, val command: String? = null, val match: String? = null) {
    val isEmpty: Boolean get() = name == null && on == null && off == null && command == null && match == null
}

/**
 * A Test run or Test read. [note] explains the result, e.g. a read verdict ("Counts as on") or why it stopped.
 * [shownLines] is set when [output] was trimmed to its last lines.
 */
data class TestResult(
    val running: Boolean,
    val output: String,
    val exitCode: Int?,
    val note: String?,
    val shownLines: Int? = null,
    /** True for a finished Test read. */
    val isRead: Boolean = false,
    /** For a Test read: whether the output counts as on; null when the read failed or this is not a read. */
    val readOn: Boolean? = null,
)

data class ActionsUiState(
    val actions: List<CustomAction> = emptyList(),
    val selectedId: String? = null,
    /** Null shows only the list. */
    val draft: ActionDraft? = null,
    /** The draft differs from what was loaded or last saved. */
    val dirty: Boolean = false,
    /** Changes whenever a different draft is opened; the editor's remembered field state is keyed by it. */
    val editorKey: Int = 0,
    val errors: DraftErrors = DraftErrors(),
    /** Connected device serials for Test run / Test read. */
    val devices: List<String> = emptyList(),
    /** Where tests run; null with no device connected. */
    val testDevice: String? = null,
    val test: TestResult? = null,
    /** Non-null when actions cannot be saved to disk. */
    val persistenceError: String? = null,
    /** Ids ticked for export while the list is in selection mode; null otherwise. Only existing actions. */
    val exportSelection: Set<String>? = null,
)

/** The draft open in the editor, and the [ActionsUiState.editorKey] it was opened with. */
data class OpenDraft(val draft: ActionDraft, val editorKey: Int)

/** The import being reviewed. When [error] is set the file was rejected and [items] is empty. */
data class ActionImportUiState(val items: List<ActionImportItem> = emptyList(), val error: String? = null) {
    /** Actions that confirming would add or change: included, and not a conflict that is set to Skip. */
    val importCount: Int
        get() = items.count { it.include && !(it.conflictsWith != null && it.choice == ConflictChoice.Skip) }
}

@KoinViewModel
class ActionsViewModel(
    private val store: ActionStore,
    private val tracker: DeviceTracker,
    private val adb: AdbClient,
    scope: CoroutineScope = defaultViewModelScope(),
    /** Overall limit for Test run and Test read; the notes always say 30 s, the real limit. */
    private val testTimeoutMs: Long = ShellRunner.BUFFERED_TIMEOUT_MS,
) : ViewModel(scope) {
    private data class Editor(
        val selectedId: String? = null,
        val draft: ActionDraft? = null,
        /** The draft as loaded or last saved; dirty is a difference from it. */
        val baseline: ActionDraft? = null,
        val errors: DraftErrors = DraftErrors(),
        val test: TestResult? = null,
        val testDevice: String? = null,
        /** Bumped each time a draft is opened, so the UI can start the editor fresh (not on save). */
        val generation: Int = 0,
    )

    private val editor = MutableStateFlow(Editor())

    private val exportIds = MutableStateFlow<Set<String>?>(null)

    /** Settings of every variable seen in this draft, so one that disappears while typing comes back unchanged. */
    private val variableMemory = HashMap<String, Variable>()

    private var testJob: Job? = null
    private var packagesJob: Job? = null

    private val devices = tracker.devices.map(::connectedSerials)

    val state: StateFlow<ActionsUiState> = combine(
        store.actions, editor, devices, store.persistenceError, exportIds,
    ) { actions, e, serials, error, export ->
        ActionsUiState(
            actions = actions,
            selectedId = e.selectedId,
            draft = e.draft,
            editorKey = e.generation,
            dirty = e.draft != e.baseline,
            errors = e.errors,
            devices = serials,
            testDevice = testDevice(e.testDevice, serials),
            test = e.test,
            persistenceError = error,
            exportSelection = export?.let { ids -> actions.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id in ids } } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActionsUiState())

    /**
     * The open draft and its [ActionsUiState.editorKey], current as soon as an edit is made. The editor's text fields
     * read the draft from here, not from [state]: `combine` publishes each change in a later EDT event, and a field
     * that recomposes with its old text before then loses the next key (quick or held Backspace deleted the wrong
     * character or nothing).
     */
    val openDraft: StateFlow<OpenDraft?> = editor.mapState { e -> e.draft?.let { OpenDraft(it, e.generation) } }

    private val _pendingDiscard = MutableStateFlow<ActionsRequest?>(null)

    /**
     * A request from [handle] that arrived with unsaved changes. The UI asks to discard, then calls
     * [discardAndSelect] with its [ActionsRequest.actionId] (null for a new action), or [keepEditing].
     */
    val pendingDiscard: StateFlow<ActionsRequest?> get() = _pendingDiscard

    private val _packages = MutableStateFlow<List<String>?>(null)

    /** Installed packages on the test device for an Installed app variable; null until [loadPackages] finishes. */
    val packages: StateFlow<List<String>?> get() = _packages

    private val _importState = MutableStateFlow<ActionImportUiState?>(null)
    val importState: StateFlow<ActionImportUiState?> get() = _importState

    /** One-shot messages for the snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages

    private val dirty: Boolean get() = editor.value.let { it.draft != it.baseline }

    init {
        // The open action deleted elsewhere (a mirror tile's Delete): close it, or Save would bring it back.
        viewModelScope.launch {
            store.actions.collect { actions ->
                if (actions.isEmpty()) cancelExport() // nothing left to tick: leave selection mode
                val e = editor.value
                val id = e.selectedId ?: return@collect
                val stored = actions.firstOrNull { it.id == id }
                if (stored != null) {
                    // Changed elsewhere (the inbox, the sheet's Move to Your actions): show it, or Save would undo it.
                    val storedDraft = stored.toDraft()
                    if (storedDraft == e.baseline) return@collect
                    if (e.draft == e.baseline) {
                        load(id)
                    } else {
                        // Keep the unsaved edits, but never let Save flip where the action lives.
                        editor.update {
                            if (it.selectedId != id) it
                            else it.copy(draft = it.draft?.copy(source = stored.source), baseline = storedDraft)
                        }
                    }
                    return@collect
                }
                cancelTest()
                editor.update { it.copy(selectedId = null, draft = null, baseline = null, errors = DraftErrors(), test = null) }
                _messages.tryEmit("${(e.baseline ?: e.draft)?.name ?: "The action"} was deleted")
            }
        }
    }

    /** A request from a mirror window: open an action, a new one, or (neither) just the list as it is. */
    fun handle(request: ActionsRequest) {
        val e = editor.value
        when {
            !request.new && request.actionId == null -> Unit
            // Already showing what was asked for: keep the unsaved changes without asking.
            request.new && request.template == null && e.draft != null && e.draft.id == null && e.draft.fromTemplate == null -> Unit
            !request.new && request.actionId == e.selectedId && e.draft != null -> Unit
            dirty -> _pendingDiscard.value = request
            request.template != null -> startFromTemplate(request.template)
            request.new -> new()
            else -> select(request.actionId!!)
        }
    }

    /** Opens [id] in the editor. Returns false, changing nothing, when the draft has unsaved changes. */
    fun select(id: String): Boolean {
        if (dirty) return false
        load(id)
        return true
    }

    /** Drops unsaved changes, then opens [id], or a new action when [id] is null. */
    fun discardAndSelect(id: String?) {
        val template = _pendingDiscard.value?.template
        _pendingDiscard.value = null
        when {
            id != null -> load(id)
            template != null -> startFromTemplate(template)
            else -> startNew()
        }
    }

    /** Dismisses [pendingDiscard] and keeps the draft. */
    fun keepEditing() {
        _pendingDiscard.value = null
    }

    /** Starts a blank toggle. The UI asks to discard unsaved changes first (see [select]). */
    fun new() = startNew()

    /** Starts an unsaved draft filled from [template]; like [handle], asks to discard unsaved changes first. */
    fun newFromTemplate(template: ActionTemplate) = handle(ActionsRequest(null, new = true, template = template))

    /** Applies [transform] to the draft, then re-derives the variables from the command. */
    fun edit(transform: (ActionDraft) -> ActionDraft) = updateDraft { withVariables(transform(it)) }

    /** Switches the draft's type, keeping its name; a default icon follows the type. */
    fun setType(type: ActionType) = updateDraft { draft ->
        val icon = if (draft.icon == defaultIcon(draft.type)) defaultIcon(type) else draft.icon
        draft.copy(type = type, icon = icon)
    }

    /** Validates the draft and saves it. Returns false and sets [ActionsUiState.errors] when it is not valid. */
    fun save(): Boolean {
        val draft = editor.value.draft ?: return false
        val errors = validate(draft)
        editor.update { it.copy(errors = errors) }
        if (!errors.isEmpty) return false
        val action = draft.toAction(draft.id ?: UUID.randomUUID().toString())
        store.upsert(action)
        val saved = action.toDraft()
        editor.update { it.copy(selectedId = action.id, draft = saved, baseline = saved) }
        return true
    }

    fun delete(id: String) {
        // Close the editor first, so deleting the open action here is not reported as deleted elsewhere.
        if (editor.value.selectedId == id) {
            cancelTest()
            editor.update { it.copy(selectedId = null, draft = null, baseline = null, errors = DraftErrors(), test = null) }
        }
        store.delete(id)
    }

    /** Copies [id] under a unique name, and opens the copy unless the draft has unsaved changes. */
    fun duplicate(id: String) {
        if (store.actions.value.none { it.id == id }) return
        val copy = store.duplicate(id)
        if (!dirty) load(copy.id)
    }

    /** Moves [id] within its own group; [toGroupIndex] counts only that group's actions. */
    fun move(id: String, toGroupIndex: Int) = store.moveInGroup(id, toGroupIndex)

    /** Moves [id] to "Your actions". An open draft of it follows, so saving it does not mark it AI-made again. */
    fun markAsUser(id: String) {
        store.markAsUser(id)
        editor.update { e ->
            if (e.selectedId != id) e
            else e.copy(
                draft = e.draft?.copy(source = ActionSource.User),
                baseline = e.baseline?.copy(source = ActionSource.User),
            )
        }
    }

    fun setTestDevice(serial: String) = editor.update { it.copy(testDevice = serial) }

    /**
     * Runs the draft on the test device: a run-once command with [values] substituted for its variables, or a
     * toggle's Turn on command (noted "Ran Turn on"). Shell-output actions stream and stop at the time limit; the
     * others run buffered.
     */
    fun testRun(values: Map<String, String>) {
        val draft = editor.value.draft ?: return
        val template = when (draft.type) {
            ActionType.RunOnce -> draft.command
            ActionType.Toggle -> draft.on
        }
        if (template.isBlank()) return endTest(NOTHING_TO_RUN_NOTE)
        val command = if (draft.type == ActionType.RunOnce) CommandTemplate.substitute(template, values) else template
        if (draft.type == ActionType.RunOnce && draft.output == OutputTarget.Shell) {
            startTest { runner -> streamTest(runner, command) }
        } else {
            val note = if (draft.type == ActionType.Toggle) RAN_TURN_ON_NOTE else null
            startTest { runner -> bufferedTest(runner, command) { note } }
        }
    }

    /** Runs a toggle's read command and says whether its output counts as on. */
    fun testRead() {
        val draft = editor.value.draft ?: return
        if (draft.type != ActionType.Toggle || draft.read.isBlank()) return endTest("No read command")
        val onWhen = OnWhen(draft.matchMode, draft.matchValue)
        startTest(isRead = true) { runner ->
            bufferedTest(runner, draft.read, isRead = true, readOn = { output -> onWhen.matches(output) }) { output ->
                if (onWhen.matches(output)) "Counts as on" else "Counts as off"
            }
        }
    }

    /** Lists the test device's packages into [packages]; third-party only unless [includeSystem]. */
    fun loadPackages(includeSystem: Boolean) {
        packagesJob?.cancel()
        _packages.value = null
        val serial = currentTestDevice() ?: run {
            _packages.value = emptyList()
            return
        }
        packagesJob = viewModelScope.launch {
            _packages.value = try {
                DeviceQuickActions(adb, serial).packages(includeSystem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Could not list apps: ${e.message ?: e}")
                emptyList()
            }
        }
    }

    /** The file text for the actions in [ids], in list order. */
    fun exportJson(ids: Collection<String>): String = ActionCodec.encode(store.actions.value.filter { it.id in ids })

    /** Enters selection mode with every action ticked. */
    fun startExport() {
        exportIds.value = store.actions.value.mapTo(HashSet()) { it.id }
    }

    fun toggleExport(id: String) {
        exportIds.update { it?.let { ids -> if (id in ids) ids - id else ids + id } }
    }

    fun selectAllExport() {
        if (exportIds.value != null) startExport()
    }

    fun selectNoExport() {
        if (exportIds.value != null) exportIds.value = emptySet()
    }

    fun cancelExport() {
        exportIds.value = null
    }

    fun beginImport(text: String) {
        _importState.value = try {
            ActionImportUiState(ActionImportPlan.plan(store.actions.value, ActionCodec.decode(text)))
        } catch (e: ActionFileException) {
            ActionImportUiState(error = e.message ?: "Not a Scrcpy Vwr actions file")
        }
    }

    fun setChoice(i: Int, c: ConflictChoice) = updateItem(i) { it.copy(choice = c) }

    fun setInclude(i: Int, b: Boolean) = updateItem(i) { it.copy(include = b) }

    fun confirmImport() {
        val import = _importState.value ?: return
        _importState.value = null
        if (import.error != null) return
        store.replaceAll(ActionImportPlan.apply(store.actions.value, import.items))
        val count = import.importCount
        _messages.tryEmit(if (count == 1) "Imported 1 action" else "Imported $count actions")
        // An import may have replaced the open action; show the new version unless the user is editing it.
        val selected = editor.value.selectedId
        if (selected != null && !dirty) load(selected)
    }

    fun cancelImport() {
        _importState.value = null
    }

    private fun load(id: String) {
        val action = store.actions.value.firstOrNull { it.id == id } ?: return
        open(action.id, action.toDraft())
    }

    private fun startNew() = open(null, ActionDraft(id = null, type = ActionType.Toggle, name = "", icon = ActionIcons.TOGGLE_DEFAULT))

    /** The draft has no baseline, so it counts as unsaved until Save. */
    private fun startFromTemplate(template: ActionTemplate) =
        open(null, template.action.toDraft().copy(id = null, fromTemplate = template.name), baseline = null)

    private fun open(id: String?, draft: ActionDraft, baseline: ActionDraft? = draft) {
        cancelTest()
        variableMemory.clear()
        variableMemory.putAll(draft.variables)
        editor.update { it.copy(selectedId = id, draft = draft, baseline = baseline, errors = DraftErrors(), test = null, generation = it.generation + 1) }
    }

    private fun updateDraft(transform: (ActionDraft) -> ActionDraft) = editor.update { e ->
        val old = e.draft
        val draft = old?.let(transform) ?: return@update e
        // A Test read result describes the read command and rule it ran with; it goes when either changes.
        val readChanged = draft.read != old.read || draft.matchMode != old.matchMode || draft.matchValue != old.matchValue
        val dropTest = readChanged && e.test?.isRead == true
        if (dropTest) cancelTest()
        val test = if (dropTest) null else e.test
        // Errors shown after a failed save clear as the fields are fixed.
        e.copy(draft = draft, test = test, errors = if (e.errors.isEmpty) e.errors else validate(draft))
    }

    private fun withVariables(draft: ActionDraft): ActionDraft {
        variableMemory.putAll(draft.variables)
        val variables = CommandTemplate.variables(draft.command)
            .associateWith { name -> draft.variables[name] ?: variableMemory[name] ?: Variable(label = name) }
        return draft.copy(variables = variables)
    }

    private fun validate(draft: ActionDraft): DraftErrors {
        val name = draft.name.trim()
        return DraftErrors(
            name = when {
                name.isEmpty() -> "Name is required"
                store.isNameTaken(name, exceptId = draft.id) -> "Another action is already named $name"
                else -> null
            },
            on = "Turn on command is required".takeIf { draft.type == ActionType.Toggle && draft.on.isBlank() },
            off = "Turn off command is required".takeIf { draft.type == ActionType.Toggle && draft.off.isBlank() },
            command = "Command is required".takeIf { draft.type == ActionType.RunOnce && draft.command.isBlank() },
            // The rule only counts with a read command (saved without one, it is dropped).
            match = onWhenError(draft.matchMode, draft.matchValue)
                .takeIf { draft.type == ActionType.Toggle && draft.read.isNotBlank() },
        )
    }

    /** The device tests run on, from the editor's choice and the tracker's current list (not the lagging [state]). */
    private fun currentTestDevice(): String? = testDevice(editor.value.testDevice, connectedSerials(tracker.devices.value))

    /** Stops any running test and shows [note] as its result. */
    private fun endTest(note: String) {
        cancelTest()
        setTest(TestResult(running = false, output = "", exitCode = null, note = note))
    }

    private fun startTest(isRead: Boolean = false, block: suspend (ShellRunner) -> Unit) {
        val serial = currentTestDevice() ?: return endTest(NO_DEVICE_NOTE)
        cancelTest()
        setTest(TestResult(running = true, output = "", exitCode = null, note = null, isRead = isRead))
        testJob = viewModelScope.launch {
            try {
                block(ShellRunner(adb, serial))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Still a read: editing the read command or the rule clears it like any read result.
                setTest(
                    TestResult(
                        running = false, output = editor.value.test?.output.orEmpty(), exitCode = null,
                        note = e.message ?: "Failed", isRead = isRead,
                    ),
                )
            }
        }
    }

    private suspend fun bufferedTest(
        runner: ShellRunner,
        command: String,
        isRead: Boolean = false,
        readOn: ((String) -> Boolean)? = null,
        note: (String) -> String?,
    ) {
        val result = runner.run(command, testTimeoutMs)
        val (output, shownLines) = OutputTail.of(result.output)
        val failedRead = isRead && result.exitCode.let { it != null && it != 0 }
        setTest(
            TestResult(
                running = false, output = output, exitCode = result.exitCode,
                note = if (failedRead) "Read failed" else note(result.output),
                shownLines = shownLines, isRead = isRead,
                readOn = if (result.exitCode.let { it == null || it == 0 }) readOn?.invoke(result.output) else null,
            ),
        )
    }

    /**
     * Shows the output's last lines as it arrives, at most every [OUTPUT_REFRESH_MS] so a flood of output
     * cannot swamp the window; stops the command at the time limit.
     */
    private suspend fun streamTest(runner: ShellRunner, command: String) {
        val tail = OutputTail()
        val changed = AtomicBoolean(false)
        var exitCode: Int? = null
        fun show(running: Boolean, note: String? = null) = setTest(
            TestResult(running, tail.toString(), if (running) null else exitCode, note, tail.lineCount.takeIf { tail.trimmed }),
        )
        val finished = coroutineScope {
            val refresh = launch {
                while (true) {
                    delay(OUTPUT_REFRESH_MS)
                    if (changed.getAndSet(false)) show(running = true)
                }
            }
            withTimeoutOrNull(testTimeoutMs) {
                runner.stream(command).collect { event ->
                    when (event) {
                        is ShellEvent.Output -> {
                            tail.append(event.text)
                            changed.set(true)
                        }
                        is ShellEvent.Exit -> exitCode = event.code
                    }
                }
            }.also { refresh.cancel() } != null
        }
        show(running = false, note = if (finished) null else STOPPED_NOTE)
    }

    private fun setTest(result: TestResult) = editor.update { it.copy(test = result) }

    private fun cancelTest() {
        testJob?.cancel()
        testJob = null
    }

    private fun updateItem(index: Int, transform: (ActionImportItem) -> ActionImportItem) {
        val current = _importState.value ?: return
        if (index !in current.items.indices) return
        _importState.value = current.copy(items = current.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
    }

    private companion object {
        const val STOPPED_NOTE = "Stopped after 30 s"
        const val NO_DEVICE_NOTE = "No device connected"
        const val NOTHING_TO_RUN_NOTE = "Nothing to run"
        const val RAN_TURN_ON_NOTE = "Ran Turn on"
        const val OUTPUT_REFRESH_MS = 100L

        fun connectedSerials(devices: List<Device>): List<String> =
            devices.filter { it.state == DeviceState.Device }.map { it.serial }

        /** [chosen] while it is still connected, else the first connected device, else null. */
        fun testDevice(chosen: String?, serials: List<String>): String? = chosen?.takeIf { it in serials } ?: serials.firstOrNull()

        fun defaultIcon(type: ActionType) = when (type) {
            ActionType.Toggle -> ActionIcons.TOGGLE_DEFAULT
            ActionType.RunOnce -> ActionIcons.RUN_ONCE_DEFAULT
        }

        fun CustomAction.toDraft(): ActionDraft = when (this) {
            is ToggleAction -> ActionDraft(
                id = id, type = ActionType.Toggle, name = name, icon = icon,
                on = on, off = off, read = read.orEmpty(),
                matchMode = onWhen?.mode ?: MatchMode.Equals, matchValue = onWhen?.value.orEmpty(), source = source,
            )
            is RunOnceAction -> ActionDraft(
                id = id, type = ActionType.RunOnce, name = name, icon = icon,
                command = command, variables = variables, output = output, confirm = confirm, source = source,
            )
        }

        fun ActionDraft.toAction(id: String): CustomAction = when (type) {
            ActionType.Toggle -> ToggleAction(
                id = id, name = name.trim(), icon = icon, on = on, off = off,
                read = read.takeIf { it.isNotBlank() },
                onWhen = if (read.isNotBlank()) OnWhen(matchMode, matchValue) else null, source = source,
            )
            ActionType.RunOnce -> RunOnceAction(
                id = id, name = name.trim(), icon = icon, command = command,
                output = output, confirm = confirm, variables = variables, source = source,
            )
        }
    }
}
