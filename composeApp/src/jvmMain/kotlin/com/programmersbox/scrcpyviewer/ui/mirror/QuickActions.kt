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
import androidx.compose.ui.unit.Dp
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.apps.AppIconLoader
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.apps.DeviceApps
import com.programmersbox.scrcpyviewer.di.DataDir
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.mirror.files.FilesLocation
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatRun
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
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
open class DeviceQuickActions(
    private val adb: AdbClient,
    private val serial: String,
    private val readTimeoutMs: Long = ShellRunner.BUFFERED_TIMEOUT_MS,
) {
    /** Every [QuickToggle]'s state, read in one shell call; null for a setting that could not be read. */
    open suspend fun read(): Map<QuickToggle, Boolean?> = parse(adb.shell(serial, batch(emptyList())), emptyList()).builtIn

    /**
     * The built-ins plus every [custom] toggle that has a read command, in the same shell call. A custom toggle is on
     * when its [ToggleAction.onWhen] matches its output; null without a match rule or when its part is missing.
     * A user's read command may never end (`logcat`), so this call runs on the cancellable stream with the buffered
     * 30 s overall limit and throws [IOException] when it is reached: the read fails and the device command stops.
     */
    open suspend fun read(custom: List<ToggleAction>): ToggleStates {
        val readable = custom.filter { it.read != null }
        if (readable.isEmpty()) return ToggleStates(read(), emptyMap())
        val out = StringBuilder()
        try {
            withTimeout(readTimeoutMs) { adb.shellStream(serial, batch(readable)).collect { out.append(it) } }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Reading the toggles timed out")
        }
        return parse(out.toString(), readable)
    }

    // Newlines, not `;`: a trailing `#comment` in a custom read command cannot swallow the commands after it.
    private fun batch(readable: List<ToggleAction>): String =
        (QuickToggle.entries.map { it.readCommand } + readable.map { it.read!! }).joinToString("\necho $SEPARATOR\n")

    private fun parse(output: String, readable: List<ToggleAction>): ToggleStates {
        val parts = output.split(SEPARATOR)
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

    /** Installed package names, sorted; third-party only unless [includeSystem]. */
    open suspend fun packages(includeSystem: Boolean): List<String> =
        adb.shell(serial, if (includeSystem) "pm list packages" else "pm list packages -3").lineSequence()
            .map { it.trim().removePrefix("package:") }.filter { it.isNotEmpty() }.distinct().sorted().toList()

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

/** The sheet's tabs, in the order the segmented control shows them. */
enum class SheetTab { Actions, Apps, Shell, Files, Logcat }

/**
 * A run-once action waiting for the user: the variable prompt while [needsValues], then the confirm dialog while
 * [needsConfirm]. [values] prefill the prompt and, once entered, are what runs.
 */
data class PendingRun(val action: RunOnceAction, val needsValues: Boolean, val needsConfirm: Boolean, val values: Map<String, String>)

/**
 * What the quick actions sheet remembers for the life of the app (not persisted), shared by every mirror window:
 * custom toggles without a read command (per device, per action), the last variable values (per device, action and
 * variable), the Files tab's folder (per device) and the last tab. Used on the UI thread.
 */
class QuickActionsMemory {
    private val toggles = HashMap<Pair<String, String>, Boolean>()
    private val values = HashMap<Triple<String, String, String>, String>()
    private val filesLocations = HashMap<String, FilesLocation>()
    var tab: SheetTab = SheetTab.Actions

    fun filesLocation(serial: String): FilesLocation? = filesLocations[serial]

    fun setFilesLocation(serial: String, location: FilesLocation) {
        filesLocations[serial] = location
    }

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
    /** Files transfers in progress (the Files tab shows a count badge). */
    val filesTransfersRunning: Int = 0,
    /** Installed apps for the prompt's "Installed app" variables; null until loaded. */
    val packages: List<String>? = null,
    /** Each tab's sheet width, as the user dragged it. */
    val widths: SheetWidths = SheetWidths(),
    /** Logcat is in its own window (the Logcat segment shows a window icon). */
    val logcatPopped: Boolean = false,
    /** Logcat is in its own window and running (the segment adds a blinking green dot). */
    val logcatLive: Boolean = false,
    /** The Apps tab. */
    val apps: AppsUiState = AppsUiState(),
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

    /** Opens the Actions editor in the main window: on [actionId], on a blank new action when [new], else just the screen. */
    data class Manage(val actionId: String?, val new: Boolean = false) : QuickActionEvent
    data class Duplicate(val id: String) : QuickActionEvent

    /** Moves an AI-made action to "Your actions". */
    data class MoveToYours(val id: String) : QuickActionEvent

    /** Deletes at once; the sheet asks for confirmation before sending it. */
    data class Delete(val id: String) : QuickActionEvent

    /** The variable prompt wants the device's installed apps, with or without system apps. */
    data class LoadPackages(val includeSystem: Boolean) : QuickActionEvent

    /** The drag bar moved: the current tab's sheet is now [width] wide (already clamped). Not saved yet. */
    data class Resize(val width: Dp) : QuickActionEvent

    /** A drag or a run of key presses on [tab]'s bar ended: save that tab's width (the sheet may show another now). */
    data class ResizeDone(val tab: SheetTab) : QuickActionEvent

    /** Double-click or Home on the drag bar: the current tab goes back to its default width, saved. */
    data object ResetWidth : QuickActionEvent

    /** Something done on the Apps tab. */
    data class Apps(val event: AppsEvent) : QuickActionEvent
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
    private val onManage: (actionId: String?, new: Boolean) -> Unit = { _, _ -> },
    /** Each tab's sheet width when the window opens; [saveWidth] persists one tab's width after a drag. */
    widths: SheetWidths = SheetWidths(),
    private val saveWidth: (SheetTab, Dp) -> Unit = { _, _ -> },
    /** The Apps tab; it lists the apps the first time it shows. */
    private val apps: AppExplorer? = null,
    /** The Logcat tab; null without adb. Its stream stops when the tab stops showing, unless it is in its own window. */
    val logcat: LogcatController? = null,
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
    private var packages by mutableStateOf<List<String>?>(null)
    private var packagesJob: Job? = null
    private var widths by mutableStateOf(widths)

    val enabled: Boolean get() = device != null && connected

    init {
        logcat?.onDock = ::showLogcatTab
    }

    val uiState: QuickActionsUiState
        get() = QuickActionsUiState(
            open, enabled, toggles, pending, tab, custom, customState, customPending, prompt, console?.running == true, packages = packages,
            widths = widths, apps = (apps?.uiState ?: AppsUiState()).copy(logcatPackage = logcat?.mine),
            logcatPopped = logcat?.popped == true,
            logcatLive = logcat?.let { it.popped && it.run == LogcatRun.Running } == true,
        )

    fun toggleSheet() {
        if (open) close() else if (enabled) {
            tab = memory.tab
            open = true
            refresh()
            if (tab == SheetTab.Apps) apps?.ensureLoaded()
            if (tab == SheetTab.Logcat) nudgeIfStopped()
        }
    }

    /**
     * Closes the sheet and any prompt and stops the console's command and logcat; Toast and None runs carry on.
     * Logcat in its own window keeps running.
     */
    fun close() {
        open = false
        prompt = null
        console?.stop()
        logcat?.takeUnless { it.popped }?.stopForLeaving()
    }

    /**
     * Follows the session: a disconnected or failed session disables the sheet and closes it. Logcat in its own window
     * pauses there.
     */
    internal fun onSessionStatus(status: SessionStatus) {
        connected = status != SessionStatus.Disconnected && status != SessionStatus.Failed
        if (!connected) {
            logcat?.takeIf { it.popped }?.pause()
            close()
        }
    }

    /**
     * Shows [to]: leaving Logcat stops its stream, and coming back to a stopped one pulses Play. Not while Logcat is in
     * its own window: it keeps running there, and its Play is there.
     */
    private fun select(to: SheetTab) {
        val from = tab
        if (from == SheetTab.Logcat && to != SheetTab.Logcat) logcat?.takeUnless { it.popped }?.stopForLeaving()
        tab = to
        memory.tab = to
        if (to == SheetTab.Apps) apps?.ensureLoaded()
        if (to == SheetTab.Logcat && from != to) nudgeIfStopped()
    }

    private fun nudgeIfStopped() {
        val l = logcat?.takeUnless { it.popped } ?: return
        if (l.run == LogcatRun.Paused || l.run == LogcatRun.StoppedByLeaving) l.requestNudge()
    }

    /**
     * Logcat came back from its own window ([LogcatController.dock]): shows its tab as it is, opening the sheet when it
     * can. Nothing stops and Play doesn't pulse.
     */
    private fun showLogcatTab() {
        tab = SheetTab.Logcat
        memory.tab = SheetTab.Logcat
        if (!open && enabled) {
            open = true
            refresh()
        }
    }

    /**
     * Picks [app] in Logcat (adding `package:mine`) and shows the tab or, while Logcat is in its own window, brings
     * that window forward and leaves the tab as it is. It never starts the stream: Play pulses.
     */
    private fun showInLogcat(app: DeviceApp) {
        val l = logcat
        l?.pick(app.packageName)
        if (l?.popped == true) l.raise() else select(SheetTab.Logcat)
        l?.let { if (it.run == LogcatRun.NotStarted) it.requestNudge() }
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
            is QuickActionEvent.SelectTab -> select(event.tab)
            is QuickActionEvent.CustomToggle -> setCustom(event.id, event.on)
            is QuickActionEvent.RunCustom -> startRun(event.id, event.fromChip)
            is QuickActionEvent.PromptValues -> prompt?.takeIf { it.needsValues }?.let { p ->
                val entered = p.copy(needsValues = false, values = event.values)
                if (entered.needsConfirm) prompt = entered else execute(entered)
            }
            QuickActionEvent.PromptConfirm -> prompt?.takeIf { !it.needsValues }?.let(::execute)
            QuickActionEvent.PromptCancel -> prompt = null
            is QuickActionEvent.Manage -> onManage(event.actionId, event.new)
            is QuickActionEvent.Duplicate -> editStore("Could not duplicate the action") { duplicate(event.id) }
            is QuickActionEvent.MoveToYours -> editStore("Could not move the action") { markAsUser(event.id) }
            is QuickActionEvent.Delete -> editStore("Could not delete the action") { delete(event.id) }
            is QuickActionEvent.LoadPackages -> loadPackages(event.includeSystem)
            is QuickActionEvent.Resize -> widths = widths.with(tab, event.width)
            is QuickActionEvent.ResizeDone -> saveWidth(event.tab, widths[event.tab])
            QuickActionEvent.ResetWidth -> {
                widths = widths.with(tab, SheetWidths.default(tab))
                saveWidth(tab, widths[tab])
            }
            is QuickActionEvent.Apps -> when (val e = event.event) {
                is AppsEvent.ShowInLogcat -> showInLogcat(e.app)
                else -> apps?.onEvent(e)
            }
        }
    }

    /** Store edits from the sheet; the action may already be gone (deleted in the main window), which is reported. */
    private fun editStore(failure: String, block: ActionStore.() -> Unit) {
        val s = store ?: return
        try {
            s.block()
        } catch (e: Exception) {
            notify("$failure: ${e.message ?: e}")
        }
    }

    /** A newer request replaces an older one still running. */
    private fun loadPackages(includeSystem: Boolean) {
        val d = device ?: return
        packagesJob?.cancel()
        packages = null
        packagesJob = scope.launch {
            packages = try {
                d.packages(includeSystem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("Could not list apps: ${e.message ?: e}")
                emptyList()
            }
        }
    }

    private fun refresh() {
        val d = device ?: return
        val toggleActions = custom.filterIsInstance<ToggleAction>()
        scope.launch {
            val read = runCatching { d.read(toggleActions) }.getOrNull() ?: return@launch
            // A toggle being switched keeps its optimistic state until its command answers.
            toggles = read.builtIn + toggles.filterKeys { it in pending }
            // Only toggles that still exist: one deleted during the read must not get its state back.
            val ids = custom.mapTo(HashSet()) { it.id }
            customState = customState + read.custom.filterKeys { it in ids && it !in customPending }
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
        if (pendingRun.needsValues) {
            // Each prompt asks the device again: apps change, and an earlier load may have failed.
            packagesJob?.cancel()
            packages = null
        }
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
            // The console may have become busy while the prompt was open: refuse before switching tabs.
            if (c.running) {
                notify(ShellConsole.BUSY_MESSAGE)
                return
            }
            select(SheetTab.Shell)
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
        /**
         * Runs [cmd] buffered (30 s limit) and returns its output; throws with the device's last line when it fails.
         * No exit code (a command that ended the shell, like `reboot` or `exit`) counts as success.
         */
        suspend fun ShellRunner.runChecked(cmd: String): String {
            val result = run(cmd)
            val code = result.exitCode
            if (code != null && code != 0) {
                throw IOException(result.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "exit code $code")
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
 * Actions editor in the main window (on an action, a blank new one, or just the screen).
 */
@Composable
fun rememberQuickActions(
    serial: String,
    session: ScrcpySession,
    adb: AdbClient?,
    notify: (String) -> Unit,
    onManage: (actionId: String?, new: Boolean) -> Unit = { _, _ -> },
    store: ActionStore = koinInject(),
    memory: QuickActionsMemory = koinInject(),
    history: ShellHistory = koinInject(),
    settings: AppSettings = koinInject(),
    tools: BundledTools = koinInject(),
    dataDir: DataDir = koinInject(),
): QuickActions {
    val currentNotify by rememberUpdatedState(notify)
    val currentOnManage by rememberUpdatedState(onManage)
    val scope = rememberCoroutineScope()
    val actions = remember(serial, adb) {
        val runner = adb?.let { ShellRunner(it, serial) }
        val apps = AppExplorer(
            device = adb?.let { DeviceApps(it, serial, { tools.serverJarOrNull }, tools.serverVersion) },
            iconLoader = adb?.let { AppIconLoader(it, serial, dataDir.path.resolve("app-icons")) },
            scope = scope,
        ) { currentNotify(it) }
        QuickActions(
            device = adb?.let { DeviceQuickActions(it, serial) },
            scope = scope,
            serial = serial,
            store = store,
            runner = runner,
            console = ShellConsole(runner, history, scope, { currentNotify(it) }),
            memory = memory,
            onManage = { id, new -> currentOnManage(id, new) },
            widths = settings.settings.value.let {
                SheetWidths.fromSettings(it.quickActionsWidth, it.shellWidth, it.appsWidth, it.filesWidth, it.logcatWidth)
            },
            // Only the tab that changed: another mirror window may have saved the others since this one opened.
            saveWidth = { tab, width ->
                val dp = width.value.roundToInt()
                settings.update {
                    when (tab) {
                        SheetTab.Actions -> it.copy(quickActionsWidth = dp)
                        SheetTab.Apps -> it.copy(appsWidth = dp)
                        SheetTab.Shell -> it.copy(shellWidth = dp)
                        SheetTab.Files -> it.copy(filesWidth = dp)
                        SheetTab.Logcat -> it.copy(logcatWidth = dp)
                    }
                }
            },
            apps = apps,
            logcat = adb?.let { LogcatController(LogcatSource(it, serial), scope, { msg -> currentNotify(msg) }) },
        ) { currentNotify(it) }
    }
    LaunchedEffect(actions) { actions.watchActions() }
    LaunchedEffect(actions, session) {
        session.status.collect { actions.onSessionStatus(it) }
    }
    return actions
}
