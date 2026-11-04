package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Device settings the quick actions sheet switches on and off over `adb shell`. */
enum class QuickToggle(val label: String, val readCommand: String) {
    DarkMode("Dark mode", "cmd uimode night"),
    WiFi("Wi-Fi", "settings get global wifi_on"),
    AirplaneMode("Airplane mode", "settings get global airplane_mode_on"),
    ShowTaps("Show taps", "settings get system show_touches"),
    AutoRotate("Auto-rotate", "settings get system accelerometer_rotation");

    fun setCommand(on: Boolean): String = when (this) {
        DarkMode -> "cmd uimode night ${if (on) "yes" else "no"}"
        WiFi -> "svc wifi ${if (on) "enable" else "disable"}"
        AirplaneMode -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        ShowTaps -> "settings put system show_touches ${if (on) 1 else 0}"
        AutoRotate -> "settings put system accelerometer_rotation ${if (on) 1 else 0}"
    }

    /** The setting's state from the output of [readCommand]; null when the output is not understood. */
    fun parse(output: String): Boolean? {
        val text = output.trim()
        return when (this) {
            // "Night mode: yes" / "no" / "auto" / "custom": only "yes" is forced dark.
            DarkMode -> text.substringAfter("Night mode:", "").trim().takeIf { it.isNotEmpty() }?.let { it == "yes" }
            // 0 off, 1 on, 2 on while in airplane mode, 3 off in airplane mode (remembered as on).
            WiFi -> when (text) {
                "1", "2" -> true
                "0", "3" -> false
                else -> null
            }
            // An unset setting reads "null": the default, off.
            AirplaneMode, ShowTaps -> when (text) {
                "1" -> true
                "0", "null" -> false
                else -> null
            }
            // Unset reads "null", and its default differs between devices: unknown.
            AutoRotate -> when (text) {
                "1" -> true
                "0" -> false
                else -> null
            }
        }
    }
}

/** The built-in toggles' states and the custom toggles' states by action id, from one batched read. */
data class ToggleStates(val builtIn: Map<QuickToggle, Boolean?>, val custom: Map<String, Boolean?>)

/** `adb shell` commands of the quick actions sheet for one device. */
open class DeviceQuickActions(private val adb: AdbClient, private val serial: String) {
    /** Every [QuickToggle]'s state, read in one shell call; null for a setting that could not be read. */
    open suspend fun read(): Map<QuickToggle, Boolean?> = readBatch(emptyList()).builtIn

    /**
     * The built-ins plus every [custom] toggle that has a read command, in the same shell call. A custom toggle is on
     * when its [ToggleAction.onWhen] matches its output; null without a match rule or when its part is missing.
     */
    open suspend fun read(custom: List<ToggleAction>): ToggleStates =
        if (custom.none { it.read != null }) ToggleStates(read(), emptyMap()) else readBatch(custom)

    private suspend fun readBatch(custom: List<ToggleAction>): ToggleStates {
        val readable = custom.filter { it.read != null }
        // Newlines, not `;`: a trailing `#comment` in a custom read command cannot swallow the commands after it.
        val cmd = (QuickToggle.entries.map { it.readCommand } + readable.map { it.read!! }).joinToString("\necho $SEPARATOR\n")
        val parts = adb.shell(serial, cmd).split(SEPARATOR)
        val builtIn = QuickToggle.entries.withIndex().associate { (i, t) -> t to parts.getOrNull(i)?.let(t::parse) }
        val states = readable.withIndex().associate { (i, a) ->
            a.id to parts.getOrNull(QuickToggle.entries.size + i)?.let { out -> a.onWhen?.matches(out) }
        }
        return ToggleStates(builtIn, states)
    }

    /** Turns [toggle] on or off; throws [IOException] with the device's output when the command fails. */
    open suspend fun set(toggle: QuickToggle, on: Boolean) {
        runChecked(toggle.setCommand(on))
    }

    /** Opens Settings › Developer options on the device. */
    open suspend fun openDeveloperOptions() {
        val out = runChecked("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        // Older `am` reports an unresolved intent on stdout with exit code 0.
        if ("Error" in out) throw IOException(out.lineSequence().first { "Error" in it }.trim())
    }

    open suspend fun reboot() {
        val result = adb.execWithTimeout(REBOOT_TIMEOUT_MS, "-s", serial, "reboot")
            ?: throw IOException("adb reboot timed out")
        if (result.exitCode != 0) throw IOException((result.stderr + result.stdout).trim().ifEmpty { "adb reboot failed" })
    }

    /** Runs [cmd] and returns its output; the legacy shell service drops the exit code, so it is echoed after it. */
    private suspend fun runChecked(cmd: String): String {
        val out = adb.shell(serial, "$cmd; echo $EXIT_MARKER$?")
        val text = out.substringBeforeLast(EXIT_MARKER).trim()
        val code = out.substringAfterLast(EXIT_MARKER, "").trim().toIntOrNull()
        if (code != 0) throw IOException(text.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit code ${code ?: "unknown"}")
        return text
    }

    companion object {
        private const val SEPARATOR = "__scrcpyviewer_sep__"
        private const val EXIT_MARKER = "__scrcpyviewer_exit="
        private const val REBOOT_TIMEOUT_MS = 15_000L
    }
}

enum class SheetTab { Actions, Shell }

/**
 * A run-once action waiting for the user: the variable prompt while [needsValues], then the confirm dialog while
 * [needsConfirm]. [values] prefill the prompt and, once entered, are what runs.
 */
data class PendingRun(val action: RunOnceAction, val needsValues: Boolean, val needsConfirm: Boolean, val values: Map<String, String>)

/**
 * What the quick actions sheet remembers for the life of the app (not persisted), shared by every mirror window:
 * custom toggles without a read command (per device, per action), the last variable values (per device, action and
 * variable) and the last tab. Used on the UI thread.
 */
class QuickActionsMemory {
    private val toggles = HashMap<Pair<String, String>, Boolean>()
    private val values = HashMap<Triple<String, String, String>, String>()
    var tab: SheetTab = SheetTab.Actions

    fun toggle(serial: String, id: String): Boolean? = toggles[serial to id]

    fun setToggle(serial: String, id: String, on: Boolean) {
        toggles[serial to id] = on
    }

    fun value(serial: String, id: String, variable: String): String? = values[Triple(serial, id, variable)]

    fun setValues(serial: String, id: String, entered: Map<String, String>) {
        entered.forEach { (variable, value) -> values[Triple(serial, id, variable)] = value }
    }
}

/** What the sheet shows. */
data class QuickActionsUiState(
    val open: Boolean = false,
    /** adb is available and the device is connected. */
    val enabled: Boolean = false,
    /** Each toggle's state on the device; missing or null while unknown. */
    val toggles: Map<QuickToggle, Boolean?> = emptyMap(),
    /** Toggles whose command is running. */
    val pending: Set<QuickToggle> = emptySet(),
    val tab: SheetTab = SheetTab.Actions,
    /** The user's actions, in their order. */
    val custom: List<CustomAction> = emptyList(),
    /** Custom toggles' states by action id; missing or null while unknown. */
    val customState: Map<String, Boolean?> = emptyMap(),
    /** Custom actions whose buffered command is running (toggles, Toast and None runs). */
    val customPending: Set<String> = emptySet(),
    /** The variable prompt or confirm dialog that is open, if any. */
    val prompt: PendingRun? = null,
    /** A console command is running (the Shell tab shows a red dot). */
    val consoleRunning: Boolean = false,
)

/** A tap in the quick actions sheet. */
sealed interface QuickActionEvent {
    data class Toggle(val toggle: QuickToggle, val on: Boolean) : QuickActionEvent
    data object DeveloperOptions : QuickActionEvent
    data object Reboot : QuickActionEvent
    data object Close : QuickActionEvent
    data class SelectTab(val tab: SheetTab) : QuickActionEvent
    data class CustomToggle(val id: String, val on: Boolean) : QuickActionEvent

    /** A run-once tile, or ([fromChip]) a Shell tab chip, which sends the output to the console. */
    data class RunCustom(val id: String, val fromChip: Boolean = false) : QuickActionEvent
    data class PromptValues(val values: Map<String, String>) : QuickActionEvent
    data object PromptConfirm : QuickActionEvent
    data object PromptCancel : QuickActionEvent

    /** Opens the Actions editor in the main window: on [actionId], or blank / no selection when null. */
    data class Manage(val actionId: String?) : QuickActionEvent
    data class Duplicate(val id: String) : QuickActionEvent

    /** Deletes at once; the sheet asks for confirmation before sending it. */
    data class Delete(val id: String) : QuickActionEvent
}

/**
 * The mirror window's quick actions: whether the sheet is open, its tab, and the toggles' states. Toggles flip at once
 * and flip back (with a message) when the command fails; the states are read again each time the sheet opens.
 * Custom actions come from [store]; their buffered commands run through [runner], Shell-tab output through [console].
 * Snapshot state is written on the UI thread (the window's coroutine scope); [watchActions] must run in it.
 */
@Stable
class QuickActions internal constructor(
    private val device: DeviceQuickActions?,
    private val scope: CoroutineScope,
    private val serial: String = "",
    private val store: ActionStore? = null,
    private val runner: ShellRunner? = null,
    /** The window's shell console; it outlives tabs and closing the sheet, but its command stops when the sheet closes. */
    val console: ShellConsole? = null,
    private val memory: QuickActionsMemory = QuickActionsMemory(),
    private val onManage: (actionId: String?) -> Unit = {},
    private val notify: (String) -> Unit,
) {
    var open by mutableStateOf(false)
        private set

    var connected by mutableStateOf(true)
        internal set

    private var toggles by mutableStateOf<Map<QuickToggle, Boolean?>>(emptyMap())
    private var pending by mutableStateOf<Set<QuickToggle>>(emptySet())
    private var tab by mutableStateOf(memory.tab)
    private var custom by mutableStateOf(store?.actions?.value.orEmpty())
    private var customState by mutableStateOf(rememberedToggles(custom))
    private var customPending by mutableStateOf<Set<String>>(emptySet())
    private var prompt by mutableStateOf<PendingRun?>(null)
    private var promptToConsole = false

    val enabled: Boolean get() = device != null && connected

    val uiState: QuickActionsUiState
        get() = QuickActionsUiState(
            open, enabled, toggles, pending, tab, custom, customState, customPending, prompt, console?.running == true,
        )

    fun toggleSheet() {
        if (open) close() else if (enabled) {
            tab = memory.tab
            open = true
            refresh()
        }
    }

    /** Closes the sheet and any prompt and stops the console's command; Toast and None runs carry on. */
    fun close() {
        open = false
        prompt = null
        console?.stop()
    }

    /** Follows the store: new and changed actions show at once; a prompt for a deleted or changed action closes. */
    suspend fun watchActions() {
        val store = store ?: return
        store.actions.collect { list ->
            custom = list
            val ids = list.mapTo(HashSet()) { it.id }
            customState = rememberedToggles(list) + customState.filterKeys { it in ids }
            prompt?.let { p -> if (list.none { it == p.action }) prompt = null }
        }
    }

    fun onEvent(event: QuickActionEvent) {
        when (event) {
            is QuickActionEvent.Toggle -> set(event.toggle, event.on)
            QuickActionEvent.DeveloperOptions -> launchAction("Could not open Developer options") { openDeveloperOptions() }
            QuickActionEvent.Reboot -> {
                close()
                launchAction("Could not reboot") {
                    reboot()
                    notify("Rebooting…")
                }
            }
            QuickActionEvent.Close -> close()
            is QuickActionEvent.SelectTab -> {
                tab = event.tab
                memory.tab = event.tab
            }
            is QuickActionEvent.CustomToggle -> setCustom(event.id, event.on)
            is QuickActionEvent.RunCustom -> startRun(event.id, event.fromChip)
            is QuickActionEvent.PromptValues -> prompt?.takeIf { it.needsValues }?.let { p ->
                val entered = p.copy(needsValues = false, values = event.values)
                if (entered.needsConfirm) prompt = entered else execute(entered)
            }
            QuickActionEvent.PromptConfirm -> prompt?.takeIf { !it.needsValues }?.let(::execute)
            QuickActionEvent.PromptCancel -> prompt = null
            is QuickActionEvent.Manage -> onManage(event.actionId)
            is QuickActionEvent.Duplicate -> store?.let { s -> runCatching { s.duplicate(event.id) } }
            is QuickActionEvent.Delete -> store?.delete(event.id)
        }
    }

    private fun refresh() {
        val d = device ?: return
        val toggleActions = custom.filterIsInstance<ToggleAction>()
        scope.launch {
            val read = runCatching { d.read(toggleActions) }.getOrNull() ?: return@launch
            // A toggle being switched keeps its optimistic state until its command answers.
            toggles = read.builtIn + toggles.filterKeys { it in pending }
            customState = customState + read.custom.filterKeys { it !in customPending }
        }
    }

    private fun set(toggle: QuickToggle, on: Boolean) {
        val d = device ?: return
        if (toggle in pending) return
        val before = toggles[toggle]
        pending = pending + toggle
        toggles = toggles + (toggle to on)
        scope.launch {
            try {
                d.set(toggle, on)
            } catch (e: Exception) {
                toggles = toggles + (toggle to before)
                notify("Could not turn ${toggle.label} ${if (on) "on" else "off"}: ${e.message ?: e}")
            } finally {
                pending = pending - toggle
            }
        }
    }

    /** Flips at once, runs the on or off command as written (braces stay literal), flips back when it fails. */
    private fun setCustom(id: String, on: Boolean) {
        val action = custom.firstOrNull { it.id == id } as? ToggleAction ?: return
        val r = runner ?: return
        if (id in customPending) return
        val before = customState[id]
        customPending = customPending + id
        customState = customState + (id to on)
        scope.launch {
            try {
                r.runChecked(if (on) action.on else action.off)
                if (action.read == null) memory.setToggle(serial, id, on)
            } catch (e: Exception) {
                customState = customState + (id to before)
                notify(failure("Could not turn ${action.name} ${if (on) "on" else "off"}", e))
            } finally {
                customPending = customPending - id
            }
        }
    }

    /** A run-once tap: the variable prompt, then the confirm dialog, then the run; the steps it doesn't need are skipped. */
    private fun startRun(id: String, fromChip: Boolean) {
        val action = custom.firstOrNull { it.id == id } as? RunOnceAction ?: return
        val toConsole = fromChip || action.output == OutputTarget.Shell
        if (toConsole && console?.running == true) {
            notify(ShellConsole.BUSY_MESSAGE)
            return
        }
        if (!toConsole && id in customPending) return
        val names = CommandTemplate.variables(action.command)
        val values = names.associateWith { name ->
            memory.value(serial, id, name) ?: action.variables[name]?.default.orEmpty()
        }
        val pendingRun = PendingRun(action, needsValues = names.isNotEmpty(), needsConfirm = action.confirm, values = values)
        promptToConsole = toConsole
        if (pendingRun.needsValues || pendingRun.needsConfirm) prompt = pendingRun else execute(pendingRun)
    }

    private fun execute(pending: PendingRun) {
        prompt = null
        val action = pending.action
        if (custom.none { it == action }) return
        memory.setValues(serial, action.id, pending.values)
        val cmd = CommandTemplate.substitute(action.command, pending.values)
        if (promptToConsole) {
            val c = console ?: return
            tab = SheetTab.Shell
            memory.tab = SheetTab.Shell
            c.runAction(action.name, cmd)
            return
        }
        val r = runner ?: return
        if (action.id in customPending) return
        customPending = customPending + action.id
        scope.launch {
            try {
                val out = r.runChecked(cmd)
                if (action.output == OutputTarget.Toast) notify(out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Done")
            } catch (e: Exception) {
                notify(failure("Could not run ${action.name}", e))
            } finally {
                customPending = customPending - action.id
            }
        }
    }

    /** Custom toggles without a read command, at the state they were last set to on this device. */
    private fun rememberedToggles(actions: List<CustomAction>): Map<String, Boolean?> =
        actions.filterIsInstance<ToggleAction>().filter { it.read == null }.associate { it.id to memory.toggle(serial, it.id) }

    private fun launchAction(failure: String, block: suspend DeviceQuickActions.() -> Unit) {
        val d = device ?: return
        scope.launch {
            try {
                d.block()
            } catch (e: Exception) {
                notify("$failure: ${e.message ?: e}")
            }
        }
    }

    private companion object {
        /** Runs [cmd] buffered (30 s limit) and returns its output; throws with the device's last line when it fails. */
        suspend fun ShellRunner.runChecked(cmd: String): String {
            val result = run(cmd)
            if (result.exitCode != 0) {
                throw IOException(
                    result.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                        ?: "exit code ${result.exitCode ?: "unknown"}",
                )
            }
            return result.output
        }

        /** The timeout explains itself; other failures read "[what]: message", as the built-ins do. */
        fun failure(what: String, e: Exception): String =
            if (e is ShellTimeoutException) e.message!! else "$what: ${e.message ?: e}"
    }
}

/**
 * The quick actions of [serial]: disabled without adb or while the session is disconnected. [onManage] opens the
 * Actions editor in the main window (on an action, or none for null).
 */
@Composable
fun rememberQuickActions(
    serial: String,
    session: ScrcpySession,
    adb: AdbClient?,
    notify: (String) -> Unit,
    onManage: (actionId: String?) -> Unit = {},
    store: ActionStore = koinInject(),
    memory: QuickActionsMemory = koinInject(),
    history: ShellHistory = koinInject(),
): QuickActions {
    val currentNotify by rememberUpdatedState(notify)
    val currentOnManage by rememberUpdatedState(onManage)
    val scope = rememberCoroutineScope()
    val actions = remember(serial, adb) {
        val runner = adb?.let { ShellRunner(it, serial) }
        QuickActions(
            device = adb?.let { DeviceQuickActions(it, serial) },
            scope = scope,
            serial = serial,
            store = store,
            runner = runner,
            console = ShellConsole(runner, history, scope, { currentNotify(it) }),
            memory = memory,
            onManage = { currentOnManage(it) },
        ) { currentNotify(it) }
    }
    LaunchedEffect(actions) { actions.watchActions() }
    LaunchedEffect(actions, session) {
        session.status.collect {
            actions.connected = it != SessionStatus.Disconnected && it != SessionStatus.Failed
            if (!actions.connected) actions.close()
        }
    }
    return actions
}
