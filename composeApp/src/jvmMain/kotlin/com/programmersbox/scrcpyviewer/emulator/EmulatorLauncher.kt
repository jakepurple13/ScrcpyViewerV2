package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorOrigins
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** Starts the emulator process; tests swap in a fake so no emulator ever runs. */
fun interface ProcessStarter {
    /** Starts [command] with [env] added to the environment, stdin empty, stdout and stderr written to [log]. */
    fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess
}

/** A started emulator process. [exit] completes with its exit code. */
interface EmulatorProcess {
    val exit: Deferred<Int>
    fun destroy()
    fun destroyForcibly()
}

enum class BootStage { Starting, Booting, WaitingForAdb }

/** An AVD's launcher state; an AVD missing from [EmulatorLauncher.states] is [Stopped]. */
sealed interface AvdState {
    data object Stopped : AvdState

    /** [startedAt] is the [EmulatorLauncher] clock (wall time, ms) when the process started. */
    data class Booting(val serial: String, val stage: BootStage, val startedAt: Long, val cold: Boolean) : AvdState

    data class Running(val serial: String) : AvdState

    data class Stopping(val serial: String) : AvdState

    /** [cold] repeats on Retry: a failed Cold boot retries cold. */
    data class Failed(val reason: String, val cold: Boolean) : AvdState
}

/** A snackbar from the launcher. [logFile] backs a "Show log" action; [mirrorSerial] is set when a mirror was requested. */
data class EmulatorNotice(val text: String, val logFile: Path? = null, val mirrorSerial: String? = null)

internal const val BOOT_TIMEOUT_MS = 4 * 60_000L
internal const val STOP_WAIT_MS = 20_000L
internal const val POLL_MS = 1_000L
private const val KILL_TIMEOUT_MS = 10_000L
private const val AVD_NAME_TIMEOUT_MS = 10_000L
private const val BOOT_CHECK_TIMEOUT_MS = 5_000L
private const val DESTROY_GRACE_MS = 5_000L
private const val FIRST_PORT = 5554
private const val LAST_PORT = 5682

internal const val BOOT_TIMEOUT_REASON = "Android didn't finish booting in 4 minutes."

private val emulatorSerial = Regex("""emulator-\d+""")

/**
 * Starts AVDs headless, watches their boot, and stops any running emulator (also ones started elsewhere, matched to
 * their AVD with `adb emu avd name`). App-wide: a boot carries on while another screen shows. Quitting the app leaves
 * the emulators running (the process is never destroyed on exit), and the next launch lists them as Running.
 */
class EmulatorLauncher(
    private val adb: AdbClient,
    private val devices: StateFlow<List<Device>>,
    private val settings: AppSettings,
    private val mirrors: MirrorRequests,
    private val logDir: Path,
    private val scope: CoroutineScope,
    private val starter: ProcessStarter = SystemProcessStarter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isPortFree: (Int) -> Boolean = ::loopbackPortFree,
) {
    private class Session(
        val avdId: String,
        val name: String,
        val serial: String,
        val cold: Boolean,
        val startedAt: Long,
        val log: Path,
        val process: EmulatorProcess,
    ) {
        var job: Job? = null
    }

    private sealed interface Outcome {
        data object Ready : Outcome
        data class Exited(val code: Int) : Outcome
    }

    private val lock = Any()
    private val _states = MutableStateFlow<Map<String, AvdState>>(emptyMap())
    private val _bootingSerials = MutableStateFlow<Set<String>>(emptySet())
    private val _notices = MutableSharedFlow<EmulatorNotice>(extraBufferCapacity = 16)

    // All guarded by [lock].
    private val sessions = HashMap<String, Session>()
    private val external = HashMap<String, String>()
    private val asked = HashSet<Pair<String, Long?>>()

    /** By AVD id; Stopped AVDs are absent. */
    val states: StateFlow<Map<String, AvdState>> = _states.asStateFlow()

    /** Serials of emulators this launcher is booting: the connected rows leave them out until they are ready. */
    val bootingSerials: StateFlow<Set<String>> = _bootingSerials.asStateFlow()

    val notices: SharedFlow<EmulatorNotice> = _notices.asSharedFlow()

    init {
        scope.launch { devices.collect(::onDevices) }
    }

    fun stateOf(avdId: String): AvdState = _states.value[avdId] ?: AvdState.Stopped

    /** Starts [avd] unless it is already booting, running or stopping. [cold] adds `-no-snapshot-load`. */
    internal fun start(avd: Avd, sdk: AndroidSdk, cold: Boolean = false): Unit = synchronized(lock) {
        val current = stateOf(avd.id)
        if (current !is AvdState.Stopped && current !is AvdState.Failed) return
        val taken = devices.value.map { it.serial }.toSet() + sessions.values.map { it.serial }
        val port = pickPort(taken, isPortFree) ?: run {
            set(avd.id, AvdState.Failed("Couldn't start the emulator: no free port from $FIRST_PORT to $LAST_PORT", cold))
            return
        }
        val prefs = settings.settings.value.emulatorPrefs[avd.id] ?: EmulatorPrefs()
        val log = logDir.resolve("${avd.id}.log")
        val process = try {
            Files.createDirectories(logDir)
            starter.start(emulatorCommand(sdk, avd, port, prefs.showWindow, cold), emulatorEnv(sdk), log)
        } catch (e: Exception) {
            set(avd.id, AvdState.Failed("Couldn't start the emulator: ${e.message ?: e::class.simpleName}", cold))
            return
        }
        val session = Session(avd.id, avd.name, "emulator-$port", cold, clock(), log, process)
        sessions[avd.id] = session
        set(avd.id, AvdState.Booting(session.serial, BootStage.Starting, session.startedAt, cold))
        session.job = scope.launch(start = CoroutineStart.LAZY) { boot(session) }.also { it.start() }
    }

    /**
     * Stops [avdId]. Booting and not yet listed by adb: our process is destroyed and the row is Stopped at once.
     * Listed by adb: `emu kill`, then Stopped when the serial leaves; still listed after [STOP_WAIT_MS], our own
     * process is destroyed, while one started elsewhere returns to Running with "Couldn't stop <[name]>.".
     */
    fun stop(avdId: String, name: String = avdId): Unit = synchronized(lock) {
        val current = stateOf(avdId)
        val session = sessions[avdId]
        val serial = when (current) {
            is AvdState.Booting -> current.serial
            is AvdState.Running -> current.serial
            else -> return
        }
        session?.job?.cancel()
        if (session != null && devices.value.none { it.serial == serial }) {
            sessions.remove(avdId)
            set(avdId, AvdState.Stopped)
            scope.launch { destroy(session.process) }
            return
        }
        set(avdId, AvdState.Stopping(serial))
        scope.launch { stopListed(avdId, session?.name ?: name, serial, session) }
    }

    private suspend fun boot(session: Session) {
        val outcome = withTimeoutOrNull(BOOT_TIMEOUT_MS) {
            coroutineScope {
                val stages = async { awaitReady(session); Outcome.Ready }
                select<Outcome> {
                    session.process.exit.onAwait { Outcome.Exited(it) }
                    stages.onAwait { it }
                }.also { stages.cancel() }
            }
        }
        when (outcome) {
            Outcome.Ready -> ready(session)
            is Outcome.Exited -> {
                val reason = failureReason(session.log)
                fail(session, "Emulator exited (code ${outcome.code})." + (reason?.let { " $it" } ?: ""))
            }
            null -> {
                synchronized(lock) {
                    if (sessions[session.avdId] !== session) return
                    set(session.avdId, AvdState.Stopping(session.serial))
                }
                val listed = devices.value.any { it.serial == session.serial }
                if (!listed || !killAndWait(session.serial)) destroy(session.process)
                fail(session, BOOT_TIMEOUT_REASON)
            }
        }
    }

    private suspend fun awaitReady(session: Session) {
        val serial = session.serial
        devices.first { list -> list.any { it.serial == serial } }
        setStage(session, BootStage.Booting)
        while (!bootCompleted(serial)) delay(POLL_MS)
        setStage(session, BootStage.WaitingForAdb)
        devices.first { list -> list.any { it.serial == serial && it.state == DeviceState.Device && it.info != null } }
    }

    private suspend fun bootCompleted(serial: String): Boolean = try {
        withTimeoutOrNull(BOOT_CHECK_TIMEOUT_MS) { adb.shell(serial, "getprop sys.boot_completed") }?.trim() == "1"
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun setStage(session: Session, stage: BootStage) {
        synchronized(lock) {
            if (sessions[session.avdId] === session && stateOf(session.avdId) is AvdState.Booting) {
                set(session.avdId, AvdState.Booting(session.serial, stage, session.startedAt, session.cold))
            }
        }
    }

    private fun ready(session: Session) {
        synchronized(lock) {
            if (sessions[session.avdId] !== session) return
            set(session.avdId, AvdState.Running(session.serial))
        }
        val took = clock() - session.startedAt
        settings.update { s ->
            val prefs = s.emulatorPrefs[session.avdId] ?: EmulatorPrefs()
            s.copy(emulatorPrefs = s.emulatorPrefs + (session.avdId to prefs.copy(lastBootMillis = took)))
        }
        if (settings.settings.value.emulatorPrefs[session.avdId]?.mirrorWhenReady == true) {
            // The window grows out of the emulator's row when it is on screen.
            MirrorOrigins.buttonOnScreen(session.serial)?.let { MirrorOrigins.record(it) }
            mirrors.request(session.serial)
            _notices.tryEmit(EmulatorNotice("${session.name} is ready. Opening its mirror window…", mirrorSerial = session.serial))
        } else {
            _notices.tryEmit(EmulatorNotice("${session.name} is ready."))
        }
    }

    private fun fail(session: Session, reason: String) {
        synchronized(lock) {
            if (sessions[session.avdId] !== session) return
            sessions.remove(session.avdId)
            set(session.avdId, AvdState.Failed(reason, session.cold))
        }
        _notices.tryEmit(EmulatorNotice("${session.name} didn't start.", logFile = session.log))
    }

    private suspend fun stopListed(avdId: String, name: String, serial: String, session: Session?) {
        val gone = killAndWait(serial)
        if (!gone && session != null) destroy(session.process)
        val couldNotStop = synchronized(lock) {
            if (session != null && sessions[avdId] === session) sessions.remove(avdId)
            if (stateOf(avdId) != AvdState.Stopping(serial)) return
            if (gone || session != null) {
                set(avdId, AvdState.Stopped)
                false
            } else {
                set(avdId, AvdState.Running(serial))
                true
            }
        }
        if (couldNotStop) _notices.tryEmit(EmulatorNotice("Couldn't stop $name."))
    }

    /** `emu kill`, then true once [serial] leaves the device list, false when it is still there after [STOP_WAIT_MS]. */
    private suspend fun killAndWait(serial: String): Boolean = withTimeoutOrNull(STOP_WAIT_MS) {
        try {
            adb.execWithTimeout(KILL_TIMEOUT_MS, "-s", serial, "emu", "kill")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Still wait: the tracker says whether it went.
        }
        devices.first { list -> list.none { it.serial == serial } }
    } != null

    private suspend fun destroy(process: EmulatorProcess) {
        process.destroy()
        if (withTimeoutOrNull(DESTROY_GRACE_MS) { process.exit.await() } == null) process.destroyForcibly()
    }

    private fun onDevices(list: List<Device>) {
        val toAsk = synchronized(lock) {
            val listed = list.map { it.serial }.toSet()
            val gone = external.filterKeys { it !in listed }
            for ((serial, id) in gone) {
                external.remove(serial)
                if (stateOf(id) == AvdState.Running(serial)) set(id, AvdState.Stopped)
            }
            // Ours that were Running and have gone (closed or crashed): Stopped, following the tracker.
            sessions.values.filter { it.serial !in listed && stateOf(it.avdId) == AvdState.Running(it.serial) }
                .forEach {
                    sessions.remove(it.avdId)
                    set(it.avdId, AvdState.Stopped)
                }
            val keys = list.map { it.serial to it.transportId }.toSet()
            asked.retainAll(keys)
            list.filter { d ->
                emulatorSerial.matches(d.serial) && d.state == DeviceState.Device &&
                    d.serial !in external && sessions.values.none { it.serial == d.serial } &&
                    asked.add(d.serial to d.transportId)
            }
        }
        for (device in toAsk) scope.launch { identify(device) }
    }

    /** Maps an emulator we did not start (or started before a relaunch) to its AVD: it then shows as Running. */
    private suspend fun identify(device: Device) {
        val result = try {
            adb.execWithTimeout(AVD_NAME_TIMEOUT_MS, "-s", device.serial, "emu", "avd", "name")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val id = result?.takeIf { it.exitCode == 0 }?.stdout?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && it != "OK" }
        synchronized(lock) {
            if (id == null) {
                asked.remove(device.serial to device.transportId)
                return
            }
            if (devices.value.none { it.serial == device.serial }) return
            if (sessions.containsKey(id) || sessions.values.any { it.serial == device.serial }) return
            external[device.serial] = id
            val current = stateOf(id)
            if (current is AvdState.Stopped || current is AvdState.Failed) set(id, AvdState.Running(device.serial))
        }
    }

    /** Callers hold [lock]. */
    private fun set(avdId: String, state: AvdState) {
        val next = if (state == AvdState.Stopped) _states.value - avdId else _states.value + (avdId to state)
        _states.value = next
        _bootingSerials.value = next.values.filterIsInstance<AvdState.Booting>().map { it.serial }.toSet()
    }
}

/** `<sdk>/emulator/emulator -avd <id> -port <port> [-no-window] [-no-snapshot-load]`. */
internal fun emulatorCommand(sdk: AndroidSdk, avd: Avd, port: Int, showWindow: Boolean, cold: Boolean): List<String> =
    buildList {
        add(sdk.emulator.toString())
        addAll(listOf("-avd", avd.id, "-port", port.toString()))
        if (!showWindow) add("-no-window")
        if (cold) add("-no-snapshot-load")
    }

/** An app opened from Finder or the Start menu has neither variable; the emulator needs them to find its images. */
internal fun emulatorEnv(sdk: AndroidSdk): Map<String, String> =
    mapOf("ANDROID_SDK_ROOT" to sdk.root.toString(), "ANDROID_HOME" to sdk.root.toString())

/**
 * The lowest even port from 5554 to 5682 with no `emulator-<port>` in [connected] and both it and the next port
 * free; null when none is.
 */
internal fun pickPort(connected: Set<String>, isFree: (Int) -> Boolean): Int? =
    (FIRST_PORT..LAST_PORT step 2).firstOrNull { "emulator-$it" !in connected && isFree(it) && isFree(it + 1) }

private val failureLine = Regex("""(?:ERROR|FATAL)\s*\|\s*(.*)|PANIC:\s*(.*)""")

/** The text after the last `ERROR |`, `FATAL |` or `PANIC:` line of the emulator's [log]; null when there is none. */
internal fun failureReason(log: Path): String? {
    val text = try {
        String(Files.readAllBytes(log), Charsets.UTF_8)
    } catch (_: IOException) {
        return null
    }
    return text.lineSequence().mapNotNull { line ->
        failureLine.find(line)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.trim() }?.takeIf { it.isNotEmpty() }
    }.lastOrNull()
}

/** True when [port] can be bound on 127.0.0.1. */
internal fun loopbackPortFree(port: Int): Boolean = try {
    ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
    true
} catch (_: IOException) {
    false
}

/**
 * Starts the emulator with [ProcessBuilder], output to the log file (a file, not a pipe: see `AdbClient.runAdb`).
 * The process is never destroyed on app exit, so emulators outlive the app.
 */
object SystemProcessStarter : ProcessStarter {
    override fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess {
        val process = ProcessBuilder(command)
            .apply { environment().putAll(env) }
            .redirectInput(ProcessBuilder.Redirect.from(File(if (Os.isWindows) "NUL" else "/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        val exit = CompletableDeferred<Int>()
        process.onExit().whenComplete { p, _ -> exit.complete(p?.exitValue() ?: -1) }
        return object : EmulatorProcess {
            override val exit: Deferred<Int> = exit
            override fun destroy() {
                process.destroy()
            }

            override fun destroyForcibly() {
                process.destroyForcibly()
            }
        }
    }
}
