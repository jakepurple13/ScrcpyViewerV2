package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.adb.ShellReadTimeout
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit

/** `DEFAULT_PUSH_TARGET` in v5.0 `app/src/file_pusher.c`. */
const val DEFAULT_PUSH_TARGET = "/sdcard/Download/"

/** Long enough for a multi-GB push over Wi-Fi; a hung adb still ends eventually. */
private const val SWAP_READ_TIMEOUT_MS = 120_000
private const val KEPT_MARK = "__svw_kept__"
const val TRANSFER_TIMEOUT_MS = 30 * 60 * 1000L

/** How often a pull samples the growing host file, and a push the device file (`stat`). */
private const val PULL_POLL_MS = 250L
private const val PUSH_POLL_MS = 500L

/**
 * [Install] and [Push] take a host file; [Pull] copies a device file or folder to the host; [InstallFromDevice]
 * pulls a device APK to a temp folder and installs it.
 */
enum class TransferKind { Install, Push, Pull, InstallFromDevice }

/**
 * One transfer. [path] is the host file (the source of a Push or Install) or, for a Pull, the host destination
 * folder. [deviceDir] is the push target folder (null: the session's push target) or the folder a Pull /
 * InstallFromDevice reads [deviceName] from; a Push names the device file [deviceName], else after the host file.
 * [opensAfter] pulls into the temp "open" folder instead of [path] (Open on computer) and sets
 * [TransferJob.hostResult]. [size] is the device file's size from the listing (a Pull's progress total; null when
 * unknown) and [isDirectory] says the pulled device entry is a folder. [dragOut] marks a pull into the drag cache for
 * a drag out of the window: it reports nothing when it succeeds (the drop is the result) and the tray does not list it.
 * [replace] (a Push into [deviceDir]) replaces the item named [deviceName] there, file or folder: the push goes to a
 * temp name, which takes the item's place only once it succeeded.
 */
data class TransferRequest(
    val path: Path,
    val kind: TransferKind,
    val deviceDir: String? = null,
    val deviceName: String? = null,
    val opensAfter: Boolean = false,
    val size: Long? = null,
    val isDirectory: Boolean = false,
    val dragOut: Boolean = false,
    val replace: Boolean = false,
) {
    val name: String get() = deviceName ?: path.fileName?.toString() ?: path.toString()
}

sealed interface JobState {
    data object Queued : JobState
    data object Running : JobState
    data object Done : JobState
    data class Failed(val reason: String) : JobState
    data object Cancelled : JobState
}

/**
 * A request in the queue and how far it got: [bytes] of [total] (null: indeterminate, as for folders and installs)
 * at [bytesPerSecond]. [hostResult] is the host file a Pull wrote.
 */
data class TransferJob(
    val id: Long,
    val request: TransferRequest,
    val state: JobState,
    val bytes: Long,
    val total: Long?,
    val bytesPerSecond: Long?,
    val hostResult: Path? = null,
) {
    /** Queued or Running. */
    val active: Boolean get() = state == JobState.Queued || state == JobState.Running
}

/**
 * [dir]/[name], or the first free "name (n).ext" (n >= 1) when it exists: a pull never overwrites. A folder
 * ([isDirectory]) gets " (n)" after its whole name.
 */
fun hostUniquePath(dir: Path, name: String, isDirectory: Boolean = false): Path {
    fun free(n: String) = !Files.exists(dir.resolve(n), LinkOption.NOFOLLOW_LINKS)
    if (free(name)) return dir.resolve(name)
    val dot = if (isDirectory) -1 else name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (!free("$base ($n)$ext")) n++
    return dir.resolve("$base ($n)$ext")
}

private val WINDOWS_BAD_CHARS = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]")
private val WINDOWS_RESERVED = Regex("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?", RegexOption.IGNORE_CASE)

/**
 * The name a device entry [name] gets on the computer. Android allows names Windows does not ([windows]): `:` `?` `*`
 * `"` `<` `>` `|` `\` and control characters become `_`, as do trailing dots and spaces, and a reserved device name
 * (`CON`, `nul.txt`) gets a leading `_`. Elsewhere the name stays as it is.
 */
fun hostFileName(name: String, windows: Boolean = Os.isWindows): String {
    if (!windows) return name
    var n = WINDOWS_BAD_CHARS.replace(name, "_")
    n = n.replace(Regex("[. ]+$")) { "_".repeat(it.value.length) }
    if (WINDOWS_RESERVED.matches(n)) n = "_$n"
    return n.ifEmpty { "_" }
}

/**
 * [serial] as one file name: anything but letters, digits, `.`, `_` and `-` becomes `_`, so a Wi-Fi serial
 * (`192.168.1.5:5555`) is a valid path segment on Windows too.
 */
fun serialPathSegment(serial: String): String =
    serial.replace(Regex("[^A-Za-z0-9._-]"), "_").takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "_"

/**
 * The per-user [transferCacheRoot]: per serial ([serialPathSegment]), `install/` holds device APKs being installed,
 * `open/` opened files, `drag/` files dragged out ([DragCache]).
 */
fun defaultTransferTempRoot(): Path = transferCacheRoot()


/**
 * `.apk` files are installed, everything else is pushed (`is_apk()` in v5.0 `input_manager.c`, which compares the
 * extension case-sensitively; here `.APK` installs too). A directory is always pushed: `adb push` copies it
 * recursively, as v5.0 does for a dropped folder that is not named `*.apk`.
 */
fun classify(path: Path, isDirectory: (Path) -> Boolean = Files::isDirectory): TransferKind =
    if (!isDirectory(path) && path.fileName?.toString()?.endsWith(".apk", ignoreCase = true) == true) TransferKind.Install
    else TransferKind.Push

fun requestsFor(paths: List<Path>, isDirectory: (Path) -> Boolean = Files::isDirectory): List<TransferRequest> =
    paths.map { TransferRequest(it, classify(it, isDirectory)) }

/** The `push-target` option, or [DEFAULT_PUSH_TARGET]. */
fun pushTarget(values: OptionValues): String =
    values.entries["push-target"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PUSH_TARGET

/** `sc_adb_install()` in v5.0 `app/src/adb/adb.c`. */
fun installArgs(serial: String, path: Path): List<String> = listOf("-s", serial, "install", "-r", path.toString())

/** `sc_adb_push()` in v5.0 `app/src/adb/adb.c`. */
fun pushArgs(serial: String, path: Path, target: String): List<String> = listOf("-s", serial, "push", path.toString(), target)

private val FAILURE = Regex("""Failure \[(.+)]""")

/**
 * The line of adb's output that says what went wrong: the `Failure [...]` reason of `adb install`
 * (`INSTALL_FAILED_VERSION_DOWNGRADE: ...`), else the last `error:` line without its `adb: error: ` prefix, else
 * the last line of stderr, then of stdout.
 */
fun adbErrorLine(result: ProcessResult): String {
    val lines = (result.stderr.lines() + result.stdout.lines()).map { it.trim() }.filter { it.isNotEmpty() }
    lines.firstNotNullOfOrNull { FAILURE.find(it)?.groupValues?.get(1) }?.let { return it }
    lines.lastOrNull { "error:" in it }?.let { return it.substringAfter("error:").trim() }
    return result.stderr.lines().lastOrNull { it.isNotBlank() }?.trim()
        ?: result.stdout.lines().lastOrNull { it.isNotBlank() }?.trim()
        ?: "adb exited with code ${result.exitCode}"
}


fun pullArgs(serial: String, source: String, target: Path): List<String> = listOf("-s", serial, "pull", source, target.toString())

/** The item being transferred: [index] of [total] in the current batch (1-based). */
data class TransferProgress(val request: TransferRequest, val index: Int, val total: Int) {
    /** "Installing app.apk…" / "Pushing 2 of 3: photo.jpg…" / "Pulling a.txt…". */
    val label: String
        get() {
            val verb = when (request.kind) {
                TransferKind.Install, TransferKind.InstallFromDevice -> "Installing"
                TransferKind.Push -> "Pushing"
                TransferKind.Pull -> "Pulling"
            }
            return if (total > 1) "$verb $index of $total: ${request.name}…" else "$verb ${request.name}…"
        }
}

sealed interface TransferResult {
    val message: String

    data class Installed(val name: String) : TransferResult {
        override val message get() = "Installed $name"
    }

    data class Pushed(val name: String, val target: String) : TransferResult {
        override val message get() = "Pushed $name to $target"
    }

    /** [name] (as written, after [hostUniquePath]) was pulled into the host folder [dir]. */
    data class Pulled(val name: String, val dir: Path) : TransferResult {
        override val message get() = "Pulled $name to ${dir.fileName}"
    }

    data class Failed(val request: TransferRequest, val reason: String) : TransferResult {
        override val message
            get() = when (request.kind) {
                TransferKind.Install, TransferKind.InstallFromDevice -> "Could not install "
                TransferKind.Push -> "Could not push "
                TransferKind.Pull -> "Could not pull "
            } + "${request.name}: $reason"
    }

    /** Nothing was run: the device is not connected. */
    data class Refused(override val message: String) : TransferResult

    /** The device went away mid-queue: [count] queued transfers were dropped without running adb. */
    data class Cancelled(val count: Int) : TransferResult {
        override val message get() = "$count transfer${if (count == 1) "" else "s"} cancelled: device disconnected"
    }
}

/**
 * One device's transfer queue, as v5.0 `file_pusher.c`: requests run one at a time, in order, on [scope] (the
 * window's scope; cancelling it kills the running adb and drops the rest, like `sc_file_pusher_stop`). After a
 * successful push the device is asked to scan the target folder for media ([scanFile], v5.0 `request_scan_file`).
 *
 * Each request is a [TransferJob] in [jobs], kept after it ends until [dismiss]. [cancel] kills a running job's adb
 * (and removes what it wrote) or drops a queued one. Pulls and device installs use temp folders under
 * [tempRoot]/<serial>; before a pull writes anywhere under [tempRoot], [securePrivateRoot] makes sure it is this
 * user's own (see [ensurePrivateDir]), and the pull fails without running adb when it is not.
 *
 * [canTransfer] is false while the session is disconnected: requests are then refused instead of running adb
 * against a missing device (v5.0 ignores drops once `im->disconnected`).
 */
class FileTransferQueue(
    private val serial: String,
    private val adb: AdbClient,
    private val pushTarget: String,
    private val canTransfer: () -> Boolean,
    private val scanFile: (String) -> Unit,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TRANSFER_TIMEOUT_MS,
    private val tempRoot: Path = defaultTransferTempRoot(),
    private val securePrivateRoot: (Path) -> Unit = { ensurePrivateDir(it) },
    /** Socket read timeout of a Replace's swap: its `rm -rf` of a big folder prints nothing for a long while. */
    private val swapReadTimeoutMs: Int = SWAP_READ_TIMEOUT_MS,
) {
    private val queue = Channel<Long>(Channel.UNLIMITED)

    /** Guards every field below and every write of [_jobs]. */
    private val lock = Any()
    private var nextId = 1L
    private var pending = 0
    private var doneInBatch = 0
    private var runningId: Long? = null
    private var runningProcess: Process? = null
    private var cancelRequested = false

    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<TransferResult>(extraBufferCapacity = 64)
    val results: SharedFlow<TransferResult> = _results.asSharedFlow()

    init {
        scope.launch {
            try {
                for (id in queue) run(id)
            } finally {
                _progress.value = null
                // The window went away: nothing left will run.
                synchronized(lock) {
                    _jobs.value = _jobs.value.map { if (it.active) it.copy(state = JobState.Cancelled) else it }
                }
            }
        }.invokeOnCompletion { queue.cancel() }
    }

    /** Queues [requests] and returns their job ids; empty (and a [TransferResult.Refused]) when the device is not connected. */
    fun enqueue(requests: List<TransferRequest>): List<Long> {
        if (requests.isEmpty()) return emptyList()
        if (!canTransfer()) {
            val verbs = requests.map {
                when (it.kind) {
                    TransferKind.Install, TransferKind.InstallFromDevice -> "install"
                    TransferKind.Push -> "push files"
                    TransferKind.Pull -> "pull files"
                }
            }.distinct()
            val what = when {
                verbs.size == 1 -> verbs.single()
                verbs.toSet() == setOf("install", "push files") -> "install or push files"
                else -> "transfer files"
            }
            _results.tryEmit(TransferResult.Refused("Device not connected: cannot $what"))
            return emptyList()
        }
        return synchronized(lock) {
            requests.mapNotNull { r ->
                val id = nextId++
                if (!queue.trySend(id).isSuccess) return@mapNotNull null
                pending++
                _jobs.value += TransferJob(id, r, JobState.Queued, 0, r.size.takeIf { r.kind == TransferKind.Pull && !r.isDirectory }, null)
                id
            }
        }
    }

    fun enqueuePaths(paths: List<Path>): List<Long> = enqueue(requestsFor(paths))

    /**
     * Stops job [id]: a queued job is removed without running adb; a running one has its adb killed and what it wrote
     * removed (the partial host file of a pull, `rm -f` of the partial device file of a push), then turns
     * [JobState.Cancelled]; nothing is removed when adb had not started yet. No-op once the job ended, and an adb
     * run that already exited successfully still completes the job (a late cancel does not undo it).
     */
    fun cancel(id: Long) {
        val process = synchronized(lock) {
            val job = _jobs.value.find { it.id == id } ?: return
            when (job.state) {
                JobState.Queued -> {
                    _jobs.value = _jobs.value.filterNot { it.id == id }
                    pending--
                    null
                }
                JobState.Running -> if (runningId == id) {
                    cancelRequested = true
                    runningProcess
                } else {
                    null
                }
                else -> null
            }
        }
        process?.destroyForcibly()
    }

    /** Forgets the ended job [id]; queued and running jobs stay. */
    fun dismiss(id: Long) {
        synchronized(lock) { _jobs.value = _jobs.value.filterNot { it.id == id && !it.active } }
    }

    /** Callers hold [lock]. */
    private fun updateJob(id: Long, change: (TransferJob) -> TransferJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) change(it) else it }
    }

    private suspend fun run(id: Long) {
        val connected = canTransfer()
        val dropped: Int
        val request: TransferRequest
        val index: Int
        val total: Int
        synchronized(lock) {
            // Gone when cancelled while queued.
            val job = _jobs.value.find { it.id == id }?.takeIf { it.state == JobState.Queued } ?: return
            if (!connected) {
                // The device went away: drop this one and everything queued behind it, reported once.
                val ids = mutableSetOf(id)
                while (true) ids += queue.tryReceive().getOrNull() ?: break
                var n = 0
                _jobs.value = _jobs.value.map {
                    if (it.id in ids && it.state == JobState.Queued) {
                        n++
                        it.copy(state = JobState.Cancelled)
                    } else {
                        it
                    }
                }
                pending = 0
                doneInBatch = 0
                _progress.value = null
                dropped = n
                request = job.request
                index = 0
                total = 0
            } else {
                dropped = 0
                pending--
                doneInBatch++
                runningId = id
                runningProcess = null
                cancelRequested = false
                updateJob(id) { it.copy(state = JobState.Running) }
                request = job.request
                index = doneInBatch
                total = doneInBatch + pending
            }
        }
        if (!connected) {
            _results.tryEmit(TransferResult.Cancelled(dropped))
            return
        }
        _progress.value = TransferProgress(request, index, total)
        val outcome = try {
            execute(id, request)
        } catch (e: CancellationException) {
            synchronized(lock) { finish(id) { it.copy(state = JobState.Cancelled) } }
            throw e
        } catch (e: UserCancelled) {
            Outcome(null, state = JobState.Cancelled)
        } catch (e: Exception) {
            TransferResult.Failed(request, e.message ?: e.toString()).let { Outcome(it, JobState.Failed(it.reason)) }
        }
        synchronized(lock) {
            finish(id) { job ->
                job.copy(
                    state = outcome.state,
                    bytes = outcome.bytes ?: job.bytes,
                    hostResult = outcome.hostResult ?: job.hostResult,
                    bytesPerSecond = if (outcome.state == JobState.Done) null else job.bytesPerSecond,
                )
            }
        }
        outcome.result?.let { _results.tryEmit(it) }
        synchronized(lock) {
            if (pending == 0) {
                doneInBatch = 0
                _progress.value = null
            }
        }
    }

    /** Ends the running job [id] with [change]. Callers hold [lock]. */
    private fun finish(id: Long, change: (TransferJob) -> TransferJob) {
        updateJob(id, change)
        if (runningId == id) {
            runningId = null
            runningProcess = null
            cancelRequested = false
        }
    }

    /** How a job ended: its new [state], the [result] to report (none for a cancel or an open), the final [bytes]. */
    private data class Outcome(
        val result: TransferResult?,
        val state: JobState,
        val bytes: Long? = null,
        val hostResult: Path? = null,
    )

    /** Thrown when [cancel] stopped the running job. */
    private class UserCancelled : Exception()

    private fun failed(request: TransferRequest, reason: String) =
        TransferResult.Failed(request, reason).let { Outcome(it, JobState.Failed(reason)) }

    private fun timedOut() = "timed out after ${timeoutMs / 60_000} min"

    /** adb's error line, or null when [r] succeeded. Old adb versions print "Failure [...]" and still exit 0. */
    private fun failureOf(r: ProcessResult): String? =
        if (r.exitCode != 0 || FAILURE.containsMatchIn(r.stdout)) adbErrorLine(r) else null

    private suspend fun execute(id: Long, request: TransferRequest): Outcome = when (request.kind) {
        TransferKind.Install -> install(request, request.path)
        TransferKind.Push -> push(id, request)
        TransferKind.Pull -> {
            val dir = if (request.opensAfter) tempRoot.resolve(serialPathSegment(serial)).resolve("open") else request.path
            when (val pulled = pull(id, request, dir)) {
                is Pulled.Failed -> failed(request, pulled.reason)
                // Open on computer and drag-out pulls report nothing: the caller uses [hostResult].
                is Pulled.Ok -> Outcome(
                    if (request.opensAfter || request.dragOut) null else TransferResult.Pulled(pulled.target.fileName.toString(), dir),
                    JobState.Done, pulled.bytes, pulled.target,
                )
            }
        }
        TransferKind.InstallFromDevice -> {
            val dir = tempRoot.resolve(serialPathSegment(serial)).resolve("install")
            when (val pulled = pull(id, request.copy(isDirectory = false), dir)) {
                is Pulled.Failed -> failed(request, pulled.reason)
                is Pulled.Ok -> try {
                    checkCancelled()
                    install(request, pulled.target)
                } finally {
                    Files.deleteIfExists(pulled.target)
                }
            }
        }
    }

    private suspend fun install(request: TransferRequest, apk: Path): Outcome {
        val r = runAdb(installArgs(serial, apk)) ?: return failed(request, timedOut())
        failureOf(r)?.let { return failed(request, it) }
        return Outcome(TransferResult.Installed(request.name), JobState.Done)
    }

    /** Test seam: the random part of a Replace push's temp name. */
    internal var replaceToken: () -> String = { UUID.randomUUID().toString().take(8) }

    private suspend fun push(id: Long, request: TransferRequest): Outcome {
        val dir = request.deviceDir ?: pushTarget
        val name = request.deviceName ?: request.path.fileName?.toString() ?: request.path.toString()
        // A Replace goes to a temp name next to the item, which takes the item's place only once the push succeeded:
        // the original stays untouched by a cancel or a failure, and a folder never lands inside the old one.
        val replace = request.replace && request.deviceDir != null
        val inPlace = devicePath(dir, name)
        val deviceFile = if (replace) devicePath(dir, ".svw-replace-${replaceToken()}-$name") else inPlace
        // Without a folder the target stays the session's push target, as v5.0 passes it to `adb push`.
        val target = if (replace) deviceFile else request.deviceDir?.let { inPlace } ?: pushTarget
        val size = if (Files.isRegularFile(request.path)) runCatching { Files.size(request.path) }.getOrNull() else null
        synchronized(lock) { updateJob(id) { it.copy(total = size) } }
        // A folder push merges into what is there: nothing of it is removed on a cancel. A Replace's temp copy is
        // this push's own, file or folder.
        // Only once adb ran: a cancel before it started must not delete a device file adb never touched.
        var started = false
        val removePartial: suspend () -> Unit = {
            when {
                !started -> Unit
                replace -> runCatching { adb.shell(serial, "rm -rf -- ${shellQuote(deviceFile)}") }
                size != null -> runCatching { adb.shell(serial, "rm -f ${shellQuote(deviceFile)}") }
            }
        }
        val sample: (suspend () -> Long?)? = size?.let {
            { adb.shell(serial, "stat -c %s ${shellQuote(deviceFile)}").trim().toLongOrNull() }
        }
        val r = try {
            withProgress(id, PUSH_POLL_MS, sample) { runAdb(pushArgs(serial, request.path, target)) { started = true } }
        } catch (e: UserCancelled) {
            removePartial()
            throw e
        }
        if (r == null) {
            removePartial()
            return failed(request, timedOut())
        }
        failureOf(r)?.let {
            // A Replace's half-written temp copy is junk; anything else adb failed on stays as it was.
            if (replace) removePartial()
            return failed(request, it)
        }
        if (replace) swapIn(deviceFile, dir, inPlace, name)?.let { return failed(request, it) }
        runCatching { scanFile(dir) }
        return Outcome(TransferResult.Pushed(request.name, dir), JobState.Done, bytes = size)
    }

    /**
     * Puts the pushed [temp] in place of [target] (a file or folder in [dir]): removes [target], behind the same
     * device-side check as a move's Replace (never [dir] or a folder above it), then renames [temp]. Null when done,
     * else the reason; [temp] is then removed only while something still stands at [target].
     */
    private suspend fun swapIn(temp: String, dir: String, target: String, name: String): String? {
        val t = shellQuote(temp)
        val f = shellQuote(target)
        val ok = "__svw_replaced__"
        val out = try {
            withContext(ShellReadTimeout(swapReadTimeoutMs)) {
                adb.shell(
                    serial,
                    "${keepOutsideGuard(dir, target, name, "is the folder being pushed into")} && " +
                        "rm -rf -- $f && mv $t $f && echo $ok",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "could not replace $name"
        }
        if (ok in out) return null
        // Something still at the target: the temp copy is junk. Nothing there (rm ran, mv failed): the temp copy is the
        // only new one, so it stays and the message says where.
        val kept = runCatching {
            adb.shell(serial, "if [ -e $f ] || [ -L $f ]; then rm -rf -- $t; else echo $KEPT_MARK; fi")
        }.getOrDefault("").contains(KEPT_MARK)
        val reason = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "could not replace $name"
        return if (kept) "$reason. The new copy is at $temp" else reason
    }

    private sealed interface Pulled {
        data class Ok(val target: Path, val bytes: Long) : Pulled
        data class Failed(val reason: String) : Pulled
    }

    /**
     * Pulls the device entry of [request] into [hostDir] under a [hostUniquePath] name. Whatever this pull wrote is
     * deleted unless it succeeded (failure, timeout, cancel, or the window closing); nothing that existed before is.
     */
    private suspend fun pull(id: Long, request: TransferRequest, hostDir: Path): Pulled {
        val deviceDir = request.deviceDir ?: return Pulled.Failed("no device folder")
        val name = request.deviceName ?: return Pulled.Failed("no device file")
        // The cache (open/, install/, a drag's folder): only when it is this user's alone.
        val root = tempRoot.toAbsolutePath().normalize()
        if (hostDir.toAbsolutePath().normalize().startsWith(root)) {
            try {
                securePrivateRoot(root)
            } catch (e: java.io.IOException) {
                return Pulled.Failed(e.message ?: "the cache folder can’t be used")
            }
        }
        Files.createDirectories(hostDir)
        val target = hostUniquePath(hostDir, hostFileName(name), request.isDirectory)
        synchronized(lock) { updateJob(id) { it.copy(total = if (request.isDirectory) null else request.size) } }
        var keep = false
        var started = false
        try {
            val r = withProgress(id, PULL_POLL_MS, { hostSize(target) }) {
                runAdb(pullArgs(serial, devicePath(deviceDir, name), target)) { started = true }
            } ?: return Pulled.Failed(timedOut())
            failureOf(r)?.let { return Pulled.Failed(it) }
            keep = true
            return Pulled.Ok(target, hostSize(target))
        } finally {
            // Only what this pull's adb may have written.
            if (!keep && started) runCatching { deleteRecursively(target) }
        }
    }

    /** Bytes under [path]: a file's size, or a folder's files summed. */
    private fun hostSize(path: Path): Long = runCatching {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching Files.size(path)
        Files.walk(path).use { s ->
            s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }.getOrDefault(0L)

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } else {
            Files.deleteIfExists(path)
        }
    }

    private fun checkCancelled() {
        if (synchronized(lock) { cancelRequested }) throw UserCancelled()
    }

    /**
     * Runs [block] while a poller on [scope] samples the job's bytes every [intervalMs] ([sample] null: no progress).
     * The poller is cancelled when [block] ends and never writes after that.
     */
    private suspend fun <T> withProgress(id: Long, intervalMs: Long, sample: (suspend () -> Long?)?, block: suspend () -> T): T {
        val started = System.nanoTime()
        val poller = sample?.let {
            scope.launch {
                while (true) {
                    delay(intervalMs)
                    val bytes = try {
                        it()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    ensureActive()
                    val elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                    synchronized(lock) {
                        if (!isActive || runningId != id) return@synchronized
                        updateJob(id) {
                            if (it.state == JobState.Running) it.copy(bytes = bytes, bytesPerSecond = bytes * 1000 / elapsedMs) else it
                        }
                    }
                }
            }
        }
        try {
            return block()
        } finally {
            poller?.cancel()
        }
    }

    /** Test seam: runs as a job is about to start adb, before the last cancel check. */
    internal var beforeStart: (id: Long) -> Unit = {}

    /**
     * Runs adb with [args] as a process [cancel] can kill; [onStarted] runs once it started. Null when it timed out
     * (the process is then destroyed). A successful exit (0) wins over a cancel that came too late; otherwise a
     * [cancel] makes this throw [UserCancelled]. adb's output goes to a temp file, not a pipe: an adb server it forks
     * would hold a pipe open (see [AdbClient]). stderr comes merged into [ProcessResult.stdout].
     */
    private suspend fun runAdb(args: List<String>, onStarted: () -> Unit = {}): ProcessResult? {
        synchronized(lock) { runningId }?.let(beforeStart)
        val outFile = Files.createTempFile("adb-transfer", ".txt")
        var process: Process? = null
        try {
            val started = synchronized(lock) {
                if (cancelRequested) throw UserCancelled()
                adb.startProcess(outFile, *args.toTypedArray()).also {
                    runningProcess = it
                    process = it
                    onStarted()
                }
            }
            val exit = withTimeoutOrNull(timeoutMs) { runInterruptible(Dispatchers.IO) { started.waitFor() } }
            if (exit == null) return null
            if (exit != 0) checkCancelled()
            return ProcessResult(exit, runCatching { Files.readString(outFile) }.getOrDefault(""), "")
        } finally {
            process?.let { p ->
                if (p.isAlive) {
                    p.destroyForcibly()
                    // Let it die before the caller removes what it wrote.
                    runCatching { p.waitFor(1, TimeUnit.SECONDS) }
                }
                synchronized(lock) { if (runningProcess === p) runningProcess = null }
            }
            runCatching { Files.deleteIfExists(outFile) }
        }
    }
}
