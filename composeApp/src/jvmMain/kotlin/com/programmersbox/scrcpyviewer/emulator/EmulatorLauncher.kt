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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

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
    /**
     * Where "Mirror when ready" finds the row on screen and requests the mirror: the UI thread, since the row's
     * bounds come from Compose layout coordinates. Tests pass their own.
     */
    private val uiContext: CoroutineContext = Dispatchers.Main,
    /** Deletes an AVD folder's data for [wipe]: [wipeAvdData] off the main thread. Tests can hold a wipe midway. */
    private val wipeData: suspend (Path) -> Unit = { withContext(Dispatchers.IO) { wipeAvdData(it) } },
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
    // Changed under [lock].
    private val _wiping = MutableStateFlow<Set<String>>(emptySet())

    // All guarded by [lock].
    private val sessions = HashMap<String, Session>()
    private val external = HashMap<String, String>()
    private val asked = HashSet<Pair<String, Long?>>()
    // Processes we started that have not exited yet, serial to AVD id: their ports stay taken even after the row is
    // Stopped, and their AVD cannot be wiped (the emulator may still be saving its snapshot).
    private val live = HashMap<String, String>()
    private val _alive = MutableStateFlow<Set<String>>(emptySet())

    /** By AVD id; Stopped AVDs are absent. */
    val states: StateFlow<Map<String, AvdState>> = _states.asStateFlow()

    /** Serials of emulators this launcher is booting: the connected rows leave them out until they are ready. */
    val bootingSerials: StateFlow<Set<String>> = _bootingSerials.asStateFlow()

    val notices: SharedFlow<EmulatorNotice> = _notices.asSharedFlow()

    /** AVD ids whose data is being wiped: they cannot start until it finishes. */
    val wiping: StateFlow<Set<String>> = _wiping.asStateFlow()

    /**
     * AVD ids with an emulator process we started that has not exited yet. Its row can read Stopped or Failed before
     * then (a Stop before adb listed it, a Stop while it saves its snapshot, a boot timeout): it cannot be wiped yet.
     */
    val processAlive: StateFlow<Set<String>> = _alive.asStateFlow()

    init {
        scope.launch { devices.collect(::onDevices) }
    }

    fun stateOf(avdId: String): AvdState = _states.value[avdId] ?: AvdState.Stopped

    /**
     * Starts [avd] unless it is already booting, running or stopping. [cold] adds `-no-snapshot-load`. Refused, with a
     * notice, while its data is being wiped: it would boot on half-deleted images.
     */
    internal fun start(avd: Avd, sdk: AndroidSdk, cold: Boolean = false): Unit = synchronized(lock) {
        val current = stateOf(avd.id)
        if (current !is AvdState.Stopped && current !is AvdState.Failed) return
        if (avd.id in _wiping.value) {
            _notices.tryEmit(EmulatorNotice("${avd.name} is still being wiped. Start it once that finishes."))
            return
        }
        val taken = devices.value.map { it.serial }.toSet() + sessions.values.map { it.serial } + live.keys
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
        live[session.serial] = avd.id
        _alive.value = live.values.toSet()
        set(avd.id, AvdState.Booting(session.serial, BootStage.Starting, session.startedAt, cold))
        session.job = scope.launch { boot(session) }
        scope.launch {
            process.exit.await()
            synchronized(lock) {
                live -= session.serial
                _alive.value = live.values.toSet()
            }
        }
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
            rematch()
            return
        }
        set(avdId, AvdState.Stopping(serial))
        scope.launch { stopListed(avdId, session?.name ?: name, serial, session) }
    }

    /**
     * Deletes [avd]'s user data, cache and snapshots ([WIPE_ENTRIES], with [wipeAvdData]), so its next start is a cold
     * boot on fresh data, and says how it went in a notice. Refused while the AVD is Booting, Running or Stopping, or
     * runs on any emulator adb lists: ours, one started elsewhere, or one not matched to an AVD yet (asked with `adb emu
     * avd name`; one that cannot say refuses the wipe too). A Start of the AVD is refused until the wipe ends.
     */
    internal suspend fun wipe(avd: Avd): Result<Unit> {
        val result = runWipe(avd)
        val text = result.fold(
            onSuccess = { "Wiped ${avd.name}. Next start is a cold boot." },
            onFailure = { "Couldn't wipe ${avd.name}: ${wipeFailure(it)}" },
        )
        _notices.tryEmit(EmulatorNotice(text))
        return result
    }

    /**
     * [wipe] in the launcher's app-wide scope: the wipe and its snackbar finish even when the screen that asked for it
     * goes away.
     */
    internal fun launchWipe(avd: Avd): Deferred<Result<Unit>> = scope.async { wipe(avd) }

    private suspend fun runWipe(avd: Avd): Result<Unit> {
        val asked = synchronized(lock) {
            if (avd.id in _wiping.value) return Result.failure(WipeRefused("it's already being wiped"))
            busyReason(avd.id)?.let { return Result.failure(WipeRefused(it)) }
            _wiping.value += avd.id
            unmatchedEmulators()
        }
        try {
            for (serial in asked) {
                val id = avdNameOf(serial) ?: return Result.failure(WipeRefused("couldn't tell which AVD $serial runs"))
                if (id == avd.id) return Result.failure(WipeRefused("it's running"))
            }
            // Checked again: an emulator may have started (or been matched to it) while the others answered.
            synchronized(lock) {
                busyReason(avd.id)?.let { return Result.failure(WipeRefused(it)) }
                if ((unmatchedEmulators() - asked).isNotEmpty()) return Result.failure(WipeRefused("an emulator is starting. Try again"))
            }
            wipeData(avd.dir)
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            synchronized(lock) { _wiping.value -= avd.id }
        }
    }

    /** Why [avdId] cannot be wiped now, or null when it can. Callers hold [lock]. */
    private fun busyReason(avdId: String): String? {
        val state = stateOf(avdId)
        return when {
            state is AvdState.Stopping -> "it's still stopping"
            state !is AvdState.Stopped && state !is AvdState.Failed -> "it's running"
            sessions.containsKey(avdId) || avdId in external.values -> "it's running"
            avdId in live.values -> "it's still stopping"
            else -> null
        }
    }

    /** Emulators adb lists that are neither ours nor matched to an AVD: any of them could be running one. */
    private fun unmatchedEmulators(): Set<String> {
        val ours = sessions.values.map { it.serial }.toSet()
        return devices.value.map { it.serial }.filter { emulatorSerial.matches(it) && it !in external && it !in ours }.toSet()
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
            is Outcome.Exited -> fail(session, exitReason(session, outcome.code))
            null -> {
                synchronized(lock) {
                    if (sessions[session.avdId] !== session) return
                    set(session.avdId, AvdState.Stopping(session.serial))
                }
                val listed = devices.value.any { it.serial == session.serial }
                if (!listed || !killAndWait(session.serial)) destroy(session.process)
                fail(session, BOOT_TIMEOUT_REASON, timedOut = true)
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

    /** Test seam for the ready/stop race: what the boot does once [avdId]'s emulator is ready. */
    internal fun onReady(avdId: String) {
        synchronized(lock) { sessions[avdId] }?.let(::ready)
    }

    /** Test seam for the exit/stop race: what the boot does when [avdId]'s process exits with [code]. */
    internal fun onExited(avdId: String, code: Int) {
        synchronized(lock) { sessions[avdId] }?.let { fail(it, exitReason(it, code)) }
    }

    private fun exitReason(session: Session, code: Int): String {
        val reason = failureReason(session.log)
        return "Emulator exited (code $code)." + (reason?.let { " $it" } ?: "")
    }

    /** Only a session still booting becomes Running: a Stop that got in first wins. */
    private fun ready(session: Session) {
        synchronized(lock) {
            if (sessions[session.avdId] !== session || stateOf(session.avdId) !is AvdState.Booting) return
            set(session.avdId, AvdState.Running(session.serial))
        }
        // Only warm boots feed the progress estimate; a cold boot's time would overstate the next warm one.
        if (!session.cold) {
            val took = clock() - session.startedAt
            settings.update { s ->
                val prefs = s.emulatorPrefs[session.avdId] ?: EmulatorPrefs()
                s.copy(emulatorPrefs = s.emulatorPrefs + (session.avdId to prefs.copy(lastBootMillis = took)))
            }
        }
        if (settings.settings.value.emulatorPrefs[session.avdId]?.mirrorWhenReady == true) {
            // The window grows out of the emulator's row when it is on screen. The row's bounds are read on the UI
            // thread, where Compose lays it out; the row registers them while Booting, so they are there already.
            scope.launch(uiContext) {
                MirrorOrigins.buttonOnScreen(session.serial)?.let { MirrorOrigins.record(it) }
                mirrors.request(session.serial)
            }
            _notices.tryEmit(EmulatorNotice("${session.name} is ready. Opening its mirror window…", mirrorSerial = session.serial))
        } else {
            _notices.tryEmit(EmulatorNotice("${session.name} is ready."))
        }
    }

    /**
     * Only a session still booting fails, so a user Stop never ends as Failed. [timedOut]: the boot timeout set
     * Stopping itself before stopping the emulator, so that Stopping is accepted too.
     */
    private fun fail(session: Session, reason: String, timedOut: Boolean = false) {
        synchronized(lock) {
            if (sessions[session.avdId] !== session) return
            val state = stateOf(session.avdId)
            if (state !is AvdState.Booting && !(timedOut && state == AvdState.Stopping(session.serial))) return
            sessions.remove(session.avdId)
            set(session.avdId, AvdState.Failed(reason, session.cold))
            rematch()
        }
        _notices.tryEmit(EmulatorNotice("${session.name} didn't start.", logFile = session.log))
    }

    private suspend fun stopListed(avdId: String, name: String, serial: String, session: Session?) {
        val gone = killAndWait(serial)
        if (!gone && session != null) destroy(session.process)
        val couldNotStop = synchronized(lock) {
            if (session != null && sessions[avdId] === session) {
                sessions.remove(avdId)
                rematch()
            }
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
        val id = avdNameOf(device.serial)
        synchronized(lock) {
            if (id == null) {
                asked.remove(device.serial to device.transportId)
                return
            }
            if (devices.value.none { it.serial == device.serial }) return
            if (sessions.containsKey(id) || sessions.values.any { it.serial == device.serial }) {
                // Ours is booting this AVD (or owns the serial): ask again once that session ends.
                asked.remove(device.serial to device.transportId)
                return
            }
            external[device.serial] = id
            val current = stateOf(id)
            if (current is AvdState.Stopped || current is AvdState.Failed) set(id, AvdState.Running(device.serial))
        }
    }

    /** The AVD id the emulator on [serial] runs (`adb emu avd name`), or null when it cannot say. */
    private suspend fun avdNameOf(serial: String): String? {
        val result = try {
            adb.execWithTimeout(AVD_NAME_TIMEOUT_MS, "-s", serial, "emu", "avd", "name")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return result?.takeIf { it.exitCode == 0 }?.stdout?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && it != "OK" }
    }

    /** After a session ends: emulators of its AVD it was hiding are matched again. The lock is reentrant. */
    private fun rematch() = onDevices(devices.value)

    /** Callers hold [lock]. */
    private fun set(avdId: String, state: AvdState) {
        val next = if (state == AvdState.Stopped) _states.value - avdId else _states.value + (avdId to state)
        _states.value = next
        _bootingSerials.value = next.values.filterIsInstance<AvdState.Booting>().map { it.serial }.toSet()
    }
}

/**
 * What a wipe deletes inside an AVD folder: the snapshots (first, so a wipe stopped by an error never leaves a
 * snapshot to load over fresh data), then the user data and cache images (raw and qcow2 overlay).
 */
internal val WIPE_ENTRIES = listOf("snapshots", "userdata-qemu.img", "userdata-qemu.img.qcow2", "cache.img", "cache.img.qcow2")

/**
 * A running emulator's lock files in its AVD folder (also one started elsewhere that adb does not list yet). Both stay
 * behind after it exits (`multiinstance.lock` in every AVD that ever booted, `hardware-qemu.ini.lock` after a crash),
 * so only a held one counts: see [heldAvdLock].
 */
internal const val PID_LOCK = "hardware-qemu.ini.lock"
internal const val INSTANCE_LOCK = "multiinstance.lock"

private val pidText = Regex("""\d+""")

/** True while a process with [pid] runs. */
private fun pidAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

/**
 * The name of a lock in [root] that a running emulator holds, or null when there is none (or only stale ones).
 * [PID_LOCK] holds the emulator's pid (a file, or on Windows a folder with a `pid` file): held while that process is
 * alive; unreadable or not a number counts as stale. [INSTANCE_LOCK] is held while another process has a file lock on
 * it: a lock we get is released at once; when the platform cannot say, it does not refuse.
 */
internal fun heldAvdLock(root: Path, alive: (Long) -> Boolean = ::pidAlive): String? {
    val pidLock = root.resolve(PID_LOCK)
    val pidFile = if (Files.isDirectory(pidLock, LinkOption.NOFOLLOW_LINKS)) pidLock.resolve("pid") else pidLock
    val pid = runCatching {
        if (!Files.isRegularFile(pidFile, LinkOption.NOFOLLOW_LINKS)) null
        else pidText.find(Files.readString(pidFile))?.value?.toLongOrNull()
    }.getOrNull()
    if (pid != null && alive(pid)) return PID_LOCK

    val instance = root.resolve(INSTANCE_LOCK)
    if (Files.isRegularFile(instance, LinkOption.NOFOLLOW_LINKS)) {
        val held = try {
            FileChannel.open(instance, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val lock = channel.tryLock()
                lock?.release()
                lock == null
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: Exception) {
            // No way to tell (no write access, locks unsupported): not counted as held.
            false
        }
        if (held) return INSTANCE_LOCK
    }
    return null
}

/** A wipe refused before anything was deleted; the message completes "Couldn't wipe <name>: …". */
internal class WipeRefused(message: String) : Exception(message)

/** The end of "Couldn't wipe <name>: …" for [e], with a full stop. */
private fun wipeFailure(e: Throwable): String {
    val text = when (e) {
        is WipeRefused -> e.message.orEmpty()
        is FileSystemException -> listOfNotNull(e.file?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() ?: it }, e.reason)
            .joinToString(": ")
        else -> e.message.orEmpty()
    }
    return text.ifBlank { e::class.simpleName.orEmpty() }.removeSuffix(".") + "."
}

/**
 * Deletes [WIPE_ENTRIES] inside [dir] (missing ones are fine) and nothing else. Every entry must resolve inside the
 * folder's real path, or nothing is deleted and this throws [WipeRefused]. A symbolic link (or Windows junction)
 * among them is deleted itself, never what it points at; `snapshots` is deleted file by file without following links.
 */
internal fun wipeAvdData(dir: Path) {
    // A symlinked AVD folder is fine: where it points is the AVD.
    val root = dir.toRealPath()
    // The catalog's `path=` can name any folder: only one with an AVD's config.ini is wiped.
    if (!Files.isRegularFile(root.resolve("config.ini"), LinkOption.NOFOLLOW_LINKS)) {
        throw WipeRefused("its folder has no config.ini. Nothing was deleted")
    }
    heldAvdLock(root)?.let { throw WipeRefused("it's running ($it is held)") }
    val entries = WIPE_ENTRIES.map { root.resolve(it) }.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    // All checked before any is deleted: a wipe either refuses or deletes.
    for (entry in entries) {
        val real = try {
            entry.toRealPath()
        } catch (_: IOException) {
            null
        }
        if (entry.parent != root || real == null || real == root || !real.startsWith(root)) {
            throw WipeRefused("${entry.fileName} points outside its AVD folder. Nothing was deleted")
        }
    }
    for (entry in entries) {
        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isDirectory && !attrs.isOther && !attrs.isSymbolicLink) deleteTree(entry) else Files.delete(entry)
    }
}

/** Deletes [start] and everything in it. Links are never followed: a link (or junction) inside is deleted itself. */
private fun deleteTree(start: Path) {
    // No FOLLOW_LINKS: a symbolic link is visited as a file, so deleting it removes only the link.
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // A Windows junction can read as a directory: delete the junction itself, never walk into it.
            if (dir != start && (attrs.isOther || attrs.isSymbolicLink)) {
                Files.delete(dir)
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
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
        return SystemEmulatorProcess(process)
    }
}

/**
 * Destroys the process tree, not just the launcher: on Windows `emulator.exe` runs qemu as a child, which
 * `Process.destroy` alone leaves running. Children are listed before the parent dies (then they are re-parented and
 * no longer its descendants) and kept, so [destroyForcibly] still reaches them after the grace period.
 */
internal class SystemEmulatorProcess(private val process: Process) : EmulatorProcess {
    private val children = java.util.concurrent.ConcurrentHashMap.newKeySet<ProcessHandle>()

    override val exit: Deferred<Int> = CompletableDeferred<Int>().also { exit ->
        process.onExit().whenComplete { p, _ -> exit.complete(p?.exitValue() ?: -1) }
    }

    override fun destroy() {
        process.descendants().forEach { children += it }
        children.forEach { it.destroy() }
        process.destroy()
    }

    override fun destroyForcibly() {
        process.descendants().forEach { children += it }
        children.forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
