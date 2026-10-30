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
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Device settings the quick actions sheet switches on and off over `adb shell`. */
enum class QuickToggle(val label: String, val readCommand: String) {
    DarkMode("Dark mode", "cmd uimode night"),
    WiFi("Wi-Fi", "settings get global wifi_on"),
    AirplaneMode("Airplane mode", "settings get global airplane_mode_on"),
    ShowTaps("Show taps", "settings get system show_touches");

    fun setCommand(on: Boolean): String = when (this) {
        DarkMode -> "cmd uimode night ${if (on) "yes" else "no"}"
        WiFi -> "svc wifi ${if (on) "enable" else "disable"}"
        AirplaneMode -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        ShowTaps -> "settings put system show_touches ${if (on) 1 else 0}"
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
        }
    }
}

/** `adb shell` commands of the quick actions sheet for one device. */
open class DeviceQuickActions(private val adb: AdbClient, private val serial: String) {
    /** Every [QuickToggle]'s state, read in one shell call; null for a setting that could not be read. */
    open suspend fun read(): Map<QuickToggle, Boolean?> {
        val cmd = QuickToggle.entries.joinToString("; echo $SEPARATOR; ") { it.readCommand }
        val parts = adb.shell(serial, cmd).split(SEPARATOR)
        return QuickToggle.entries.withIndex().associate { (i, t) -> t to parts.getOrNull(i)?.let(t::parse) }
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

/** What the sheet shows. */
data class QuickActionsUiState(
    val open: Boolean = false,
    /** adb is available and the device is connected. */
    val enabled: Boolean = false,
    /** Each toggle's state on the device; missing or null while unknown. */
    val toggles: Map<QuickToggle, Boolean?> = emptyMap(),
    /** Toggles whose command is running. */
    val pending: Set<QuickToggle> = emptySet(),
)

/** A tap in the quick actions sheet. */
sealed interface QuickActionEvent {
    data class Toggle(val toggle: QuickToggle, val on: Boolean) : QuickActionEvent
    data object DeveloperOptions : QuickActionEvent
    data object Reboot : QuickActionEvent
    data object Close : QuickActionEvent
}

/**
 * The mirror window's quick actions: whether the sheet is open and the toggles' states. Toggles flip at once and
 * flip back (with a message) when the command fails; the states are read again each time the sheet opens.
 * Snapshot state is written on the UI thread (the window's coroutine scope).
 */
@Stable
class QuickActions internal constructor(
    private val device: DeviceQuickActions?,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    var open by mutableStateOf(false)
        private set

    var connected by mutableStateOf(true)
        internal set

    private var toggles by mutableStateOf<Map<QuickToggle, Boolean?>>(emptyMap())
    private var pending by mutableStateOf<Set<QuickToggle>>(emptySet())

    val enabled: Boolean get() = device != null && connected

    val uiState: QuickActionsUiState get() = QuickActionsUiState(open, enabled, toggles, pending)

    fun toggleSheet() {
        if (open) close() else if (enabled) {
            open = true
            refresh()
        }
    }

    fun close() {
        open = false
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
        }
    }

    private fun refresh() {
        val d = device ?: return
        scope.launch {
            val read = runCatching { d.read() }.getOrNull() ?: return@launch
            // A toggle being switched keeps its optimistic state until its command answers.
            toggles = read + toggles.filterKeys { it in pending }
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
}

/** The quick actions of [serial]: disabled without adb or while the session is disconnected. */
@Composable
fun rememberQuickActions(serial: String, session: ScrcpySession, adb: AdbClient?, notify: (String) -> Unit): QuickActions {
    val currentNotify by rememberUpdatedState(notify)
    val scope = rememberCoroutineScope()
    val actions = remember(serial, adb) {
        QuickActions(adb?.let { DeviceQuickActions(it, serial) }, scope) { currentNotify(it) }
    }
    LaunchedEffect(actions, session) {
        session.status.collect {
            actions.connected = it != SessionStatus.Disconnected && it != SessionStatus.Failed
            if (!actions.connected) actions.close()
        }
    }
    return actions
}
