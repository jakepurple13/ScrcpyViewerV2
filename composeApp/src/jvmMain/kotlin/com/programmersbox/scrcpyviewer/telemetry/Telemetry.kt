package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One event the app tried to record. [sent] is false when consent blocked it, true when it was queued to send. */
data class RecentEvent(val name: String, val at: Instant, val sent: Boolean)

/**
 * The app's analytics and crash reporting. Every call returns at once and never throws; only
 * [captureExceptionBlocking] writes on the calling thread (for the crash handler). Consent comes from
 * [Settings.usageStats] (events) and [Settings.crashReports] (exceptions), read at call time.
 */
interface Telemetry {
    /** False in a build without a PostHog key: nothing is recorded, written or sent. */
    val enabled: Boolean

    /** The last [RECENT_LIMIT] events recorded or blocked, newest first. */
    val recent: StateFlow<List<RecentEvent>>

    fun track(event: TelemetryEvent)

    fun captureException(t: Throwable, handled: Boolean)

    /** An unhandled exception, written to the queue before returning, so it survives the process ending. */
    fun captureExceptionBlocking(t: Throwable)

    /** A native crash of an earlier run, sent as `$exception` with type `native_crash`. Its log is not touched. */
    fun captureNativeCrash(c: NativeCrash)

    /** The anonymous install ID sent as `distinct_id`. */
    fun installId(): String

    /** A new install ID, and a new session ID, so nothing sent after it links to what was sent before. */
    fun resetInstallId()

    /** [serial] is replaced by `<device>` in everything sent from now on. */
    fun rememberSerial(serial: String)

    /** Starts sending: queued events right away (leftovers from the last run), then on the sender's schedule. */
    fun start()

    /** Sends what is queued now. */
    suspend fun flush()

    /** Records nothing, writes nothing, sends nothing. */
    object Noop : Telemetry {
        override val enabled: Boolean = false
        override val recent: StateFlow<List<RecentEvent>> = MutableStateFlow(emptyList<RecentEvent>()).asStateFlow()
        override fun track(event: TelemetryEvent) = Unit
        override fun captureException(t: Throwable, handled: Boolean) = Unit
        override fun captureExceptionBlocking(t: Throwable) = Unit
        override fun captureNativeCrash(c: NativeCrash) = Unit
        override fun installId(): String = ""
        override fun resetInstallId() = Unit
        override fun rememberSerial(serial: String) = Unit
        override fun start() = Unit
        override suspend fun flush() = Unit
    }

    companion object {
        const val RECENT_LIMIT = 50
    }
}

/**
 * [Telemetry] backed by a disk queue under `dataDir/telemetry/` and a PostHog [TelemetrySender]. [sender] is created
 * the first time something is sent, so tests that never flush never build a client. Turning a consent switch off
 * deletes the queued events of that kind. Failures go to stderr only.
 */
class PostHogTelemetry(
    private val config: TelemetryConfig,
    dataDir: Path,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    sender: (TelemetryQueue) -> TelemetrySender = { TelemetrySender(config, it) },
) : Telemetry {
    private val dir = dataDir.resolve("telemetry")
    private val queue = TelemetryQueue(dir.resolve("queue.jsonl"))
    private val ids = InstallId(dir)
    private val scrubber = Scrubber()

    /** New on each launch, and on [resetInstallId]: a session spanning the reset would link the old ID to the new. */
    @Volatile private var sessionId = UUID.randomUUID().toString()
    private val batchSender by lazy { sender(queue) }

    /** The sender is not reentrant: the flush loop and the quit flush take turns. */
    private val flushLock = Mutex()

    /** Makes a consent check plus its append atomic against the consent collector's removal. */
    private val writeLock = Any()
    private val started = AtomicBoolean(false)
    private val _recent = MutableStateFlow<List<RecentEvent>>(emptyList())

    override val enabled: Boolean get() = config.enabled
    override val recent: StateFlow<List<RecentEvent>> = _recent.asStateFlow()

    init {
        // A StateFlow replays its value, so leftovers of a kind switched off in the last run are deleted too.
        scope.launch(Dispatchers.IO) {
            settings.settings.map { it.usageStats to it.crashReports }.distinctUntilChanged().collect { (usage, crash) ->
                guard("remove events after a consent change") {
                    synchronized(writeLock) {
                        if (!usage) queue.removeKind(TelemetryKind.Usage)
                        if (!crash) queue.removeKind(TelemetryKind.Crash)
                    }
                }
            }
        }
    }

    override fun track(event: TelemetryEvent) = guard("track ${event.name}") {
        if (!record(event.name, TelemetryKind.Usage)) return@guard
        val now = Instant.now()
        launchWrite {
            guard("queue ${event.name}") {
                write(event.name, TelemetryKind.Usage, now, JsonObject(event.props().mapValues { toJson(it.value) }))
            }
        }
    }

    override fun captureException(t: Throwable, handled: Boolean) = guard("capture exception") {
        if (!record(EXCEPTION, TelemetryKind.Crash)) return@guard
        val now = Instant.now()
        launchWrite {
            guard("queue exception") { write(EXCEPTION, TelemetryKind.Crash, now, exceptionProperties(t, handled, scrubber)) }
        }
    }

    override fun captureExceptionBlocking(t: Throwable) = guard("capture exception") {
        if (!record(EXCEPTION, TelemetryKind.Crash)) return@guard
        write(EXCEPTION, TelemetryKind.Crash, Instant.now(), exceptionProperties(t, handled = false, scrubber))
    }

    override fun captureNativeCrash(c: NativeCrash) = guard("capture native crash") {
        if (!record(EXCEPTION, TelemetryKind.Crash)) return@guard
        val now = Instant.now()
        val props = nativeCrashProperties(c, scrubber)
        launchWrite { guard("queue native crash") { write(EXCEPTION, TelemetryKind.Crash, now, props) } }
    }

    override fun installId(): String = runCatching { ids.get() }.getOrElse { report("read install ID", it); "" }

    override fun resetInstallId() = guard("reset install ID") {
        ids.reset()
        sessionId = UUID.randomUUID().toString()
    }

    override fun rememberSerial(serial: String) = guard("remember serial") { scrubber.rememberSerial(serial) }

    override fun start() = guard("start") {
        if (!started.compareAndSet(false, true)) return@guard
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                flush()
                delay(runCatching { batchSender.nextDelayMs() }.getOrDefault(FALLBACK_DELAY_MS))
            }
        }
    }

    override suspend fun flush() {
        try {
            // Off the caller's thread: the quit flush is called on the UI thread, and the queue reads and rewrites its file.
            withContext(Dispatchers.IO) {
                // An event tracked just before (app_closed at quit) goes out in this flush, not the next launch's.
                pendingWrites.toList().joinAll()
                flushLock.withLock { batchSender.flush() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report("flush", t)
        }
    }

    /** Queue writes still running; [flush] waits for them. */
    private val pendingWrites: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    private fun launchWrite(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(Dispatchers.IO, block = block)
        pendingWrites += job
        // Runs at once when the job already finished, so the set never keeps a done job.
        job.invokeOnCompletion { pendingWrites -= job }
    }

    /** Adds [name] to [recent]; true when consent allows [kind]. */
    private fun record(name: String, kind: TelemetryKind): Boolean {
        val allowed = allowed(kind, settings.settings.value)
        _recent.update { (listOf(RecentEvent(name, Instant.now(), allowed)) + it).take(Telemetry.RECENT_LIMIT) }
        return allowed
    }

    private fun allowed(kind: TelemetryKind, s: Settings): Boolean = when (kind) {
        TelemetryKind.Usage -> s.usageStats
        TelemetryKind.Crash -> s.crashReports
    }

    private fun write(name: String, kind: TelemetryKind, at: Instant, props: JsonObject) {
        val properties = JsonObject(commonProperties() + props)
        val event = QueuedEvent(UUID.randomUUID().toString(), name, kind, at.toString(), properties)
        // Checked again: the switch may have been turned off since the call, and its removal may already have run.
        synchronized(writeLock) {
            if (allowed(kind, settings.settings.value)) queue.append(event)
        }
    }

    private fun commonProperties(): Map<String, JsonElement> = mapOf(
        "distinct_id" to JsonPrimitive(installId()),
        "\$process_person_profile" to JsonPrimitive(false),
        "app_version" to JsonPrimitive(APP_VERSION),
        "os" to JsonPrimitive(
            when {
                Os.isMac -> "mac"
                Os.isWindows -> "windows"
                else -> "linux"
            },
        ),
        "os_version" to JsonPrimitive(System.getProperty("os.version").orEmpty()),
        "arch" to JsonPrimitive(System.getProperty("os.arch").orEmpty()),
        "channel" to JsonPrimitive(config.channel),
        "session_id" to JsonPrimitive(sessionId),
        "\$lib" to JsonPrimitive(LIB),
    )

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(scrubber.scrub(value))
        is List<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(scrubber.scrub(value.toString()))
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report(what, t)
        }
    }

    private fun report(what: String, t: Throwable) {
        System.err.println("Telemetry: $what failed: $t")
    }

    private companion object {
        const val EXCEPTION = "\$exception"
        const val LIB = "scrcpy-vwr"
        const val FALLBACK_DELAY_MS = 30_000L
    }
}
