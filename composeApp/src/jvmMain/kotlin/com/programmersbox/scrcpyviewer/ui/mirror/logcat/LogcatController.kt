package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.logcat.EntryInfo
import com.programmersbox.scrcpyviewer.logcat.FoldResult
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatEntry
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatQuery
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.logcat.SuggestionContext
import com.programmersbox.scrcpyviewer.logcat.TraceFolder
import com.programmersbox.scrcpyviewer.logcat.packageOf
import com.programmersbox.scrcpyviewer.logcat.withTerm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lines kept per window; older ones drop off. */
const val LOGCAT_MAX_ENTRIES = 20_000

/** How often buffered lines are moved into the list, at most. */
private const val DRAIN_MILLIS = 50L

/** At most one `ps` per this long. */
private const val LOOKUP_MILLIS = 1_000L

enum class LogcatRun { NotStarted, Running, Paused, StoppedByLeaving }

/** A row of the Logcat list. */
sealed interface LogcatItem {
    val key: Long

    /** A log entry; [pkg] is its app package, null while (or when) the pid is unknown. */
    data class Line(val entry: LogcatEntry, val pkg: String?) : LogcatItem {
        override val key: Long get() = entry.seq
    }

    /** The divider before lines read back on resume. Times are host `HH:MM:SS`. */
    data class Gap(override val key: Long, val pausedAt: String, val resumedAt: String) : LogcatItem
}

private val hostTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Identity of a line for dropping replays on resume. */
private data class LineKey(val timestamp: String, val pid: Int, val tid: Int, val tag: String, val message: String)

private fun LogcatLine.key() = LineKey(timestamp, pid, tid, tag, message)

/**
 * The Logcat tab of one mirror window. Nothing runs until [play]. The stream is read on [io] into a buffer that a
 * loop on [scope] moves into [items] at most every 50 ms, folding stack traces and keeping the newest [maxEntries]
 * lines. [pause] and [stopForLeaving] stop the device command; [play] then resumes from the newest line received,
 * drops lines already shown and puts a [LogcatItem.Gap] before the read-back lines. Package names come from `ps`,
 * read at most once a second when a line arrives from an unknown pid. Snapshot state is written only on [scope].
 */
@Stable
class LogcatController(
    private val source: LogcatSource?,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = LOGCAT_MAX_ENTRIES,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    var run by mutableStateOf(LogcatRun.NotStarted)
        private set

    /** Host `HH:MM:SS` when the stream last stopped; null while running or before the first play. */
    var pausedAt by mutableStateOf<String?>(null)
        private set

    var parsed by mutableStateOf(LogcatQuery.parse(""))
        private set

    private var queryText by mutableStateOf("")

    /** The filter text; setting it reparses [parsed]. */
    var query: String
        get() = queryText
        set(value) {
            queryText = value
            parsed = LogcatQuery.parse(value)
        }

    /** The picked app's package (`package:mine`). */
    var mine by mutableStateOf<String?>(null)
        private set

    var wrap by mutableStateOf(false)
    var compact by mutableStateOf(false)

    /** Increments to ask the UI to pulse Play. */
    var nudge by mutableIntStateOf(0)
        private set

    private var itemsState by mutableStateOf<List<LogcatItem>>(emptyList())

    /** Writes to [items] so far; the batching test reads it. */
    internal var itemWrites = 0
        private set

    /** Every row, oldest first. */
    val items: List<LogcatItem> get() = itemsState

    private fun setItems(value: List<LogcatItem>) {
        itemsState = value
        itemWrites++
    }

    private var processes by mutableStateOf<Map<Int, String>>(emptyMap())

    private val visibleState = derivedStateOf {
        val q = parsed
        val m = mine
        val names = processes
        val now = clock()
        itemsState.filter { item ->
            item !is LogcatItem.Line || q.matches(item.entry, EntryInfo(item.pkg, names[item.entry.line.pid]), m, now)
        }
    }

    /** [items] that pass the filter; gaps are always kept. */
    val visible: List<LogcatItem> by visibleState

    private val totalState = derivedStateOf { itemsState.count { it is LogcatItem.Line } }

    /** Lines in [items]. */
    val totalLines: Int by totalState

    private val levelState = derivedStateOf {
        visible.filterIsInstance<LogcatItem.Line>().groupingBy { it.entry.line.level }.eachCount()
    }

    /** Lines in [visible] per level. */
    val levelCounts: Map<LogLevel, Int> by levelState

    private var streamJob: Job? = null
    private var folder = TraceFolder()
    private var seq = 0L
    private var gapKey = 0L

    /** Newest timestamp received, the `since` of the next resume. */
    private var newestTs: String? = null

    /** Lines received at [newestTs]. */
    private val atNewest = mutableListOf<LineKey>()

    /** While resuming: lines at the resume timestamp still to drop when replayed. */
    private var replayTs: String? = null
    private val replays = mutableListOf<LineKey>()

    /** The Gap to put before the first line that survives dedup after a resume. */
    private var pendingGap: Pair<String, String>? = null

    private val unknownPids = HashSet<Int>()
    private val missedPids = HashSet<Int>()
    private var lookupJob: Job? = null
    private var lastLookupAt = Long.MIN_VALUE

    /** Starts the stream, or resumes it from the newest line received. */
    fun play() {
        val src = source ?: return
        if (run == LogcatRun.Running) return
        val since = if (run == LogcatRun.NotStarted) null else newestTs
        if (run != LogcatRun.NotStarted) {
            val now = hostTime(clock())
            pendingGap = (pendingGap?.first ?: pausedAt ?: now) to now
            replayTs = newestTs
            replays.clear()
            replays += atNewest
        }
        run = LogcatRun.Running
        pausedAt = null
        val buffer = Channel<LogcatLine>(Channel.UNLIMITED)
        streamJob = scope.launch {
            launch(io) {
                try {
                    src.lines(since).collect { buffer.send(it) }
                    buffer.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    buffer.close(e)
                }
            }
            while (true) {
                val first = buffer.receiveCatching()
                val batch = ArrayList<LogcatLine>()
                first.getOrNull()?.let { batch += it }
                var end = if (first.isClosed) first else null
                while (end == null) {
                    val r = buffer.tryReceive()
                    if (r.isSuccess) batch += r.getOrThrow() else if (r.isClosed) end = r else break
                }
                if (batch.isNotEmpty()) append(batch)
                if (end != null) {
                    stopped(end.exceptionOrNull())
                    break
                }
                delay(DRAIN_MILLIS)
            }
        }
    }

    /** Stops the device command; [play] resumes. */
    fun pause() = stop(LogcatRun.Paused)

    /** Stops the device command because the tab is no longer shown. Only a running stream changes. */
    fun stopForLeaving() = stop(LogcatRun.StoppedByLeaving)

    private fun stop(to: LogcatRun) {
        if (run != LogcatRun.Running) return
        streamJob?.cancel()
        streamJob = null
        run = to
        pausedAt = hostTime(clock())
    }

    /** The stream ended on its own: pause, telling why if it failed. */
    private fun stopped(error: Throwable?) {
        streamJob = null
        run = LogcatRun.Paused
        pausedAt = hostTime(clock())
        if (error != null) notify("Logcat stopped: ${error.message ?: error}")
    }

    /** Empties the list; the run state and query stay. */
    fun clear() {
        setItems(emptyList())
        atNewest.clear()
        replays.clear()
        replayTs = null
        pendingGap = null
        folder = TraceFolder()
        notify("Cleared. Older lines stay on the device buffer.")
    }

    /** Makes [pkg] the picked app and adds `package:mine` to the query if missing. */
    fun pick(pkg: String) {
        mine = pkg
        query = withTerm(query, "package:mine", true)
    }

    fun requestNudge() {
        nudge++
    }

    /** Tags (with line counts), packages and processes seen in the list, for autocomplete. */
    fun suggestionContext(): SuggestionContext {
        val lines = items.filterIsInstance<LogcatItem.Line>()
        val names = processes
        return SuggestionContext(
            tags = lines.groupingBy { it.entry.line.tag }.eachCount(),
            packages = lines.mapNotNullTo(HashSet()) { it.pkg },
            processes = lines.mapNotNullTo(HashSet()) { names[it.entry.line.pid] },
            mine = mine,
        )
    }

    private fun append(batch: List<LogcatLine>) {
        val now = clock()
        val out = ArrayList(items)
        val names = processes
        for (line in batch) {
            if (dropAsReplay(line)) continue
            remember(line)
            pendingGap?.let { (p, r) ->
                out += LogcatItem.Gap(--gapKey, p, r)
                pendingGap = null
            }
            when (val f = folder.add(line, seq++, now)) {
                is FoldResult.New -> {
                    val pid = f.entry.line.pid
                    val process = names[pid]
                    if (process == null && pid !in missedPids) unknownPids += pid
                    out += LogcatItem.Line(f.entry, process?.let(::packageOf))
                }
                is FoldResult.Appended -> {
                    val i = out.indexOfLast { it is LogcatItem.Line && it.entry.seq == f.entry.seq }
                    if (i >= 0) out[i] = (out[i] as LogcatItem.Line).copy(entry = f.entry)
                }
            }
        }
        trim(out)
        setItems(out)
        if (unknownPids.isNotEmpty()) requestLookup()
    }

    private fun dropAsReplay(line: LogcatLine): Boolean {
        val ts = replayTs ?: return false
        if (line.timestamp > ts) {
            replayTs = null
            replays.clear()
            return false
        }
        return line.timestamp == ts && replays.remove(line.key())
    }

    private fun remember(line: LogcatLine) {
        val ts = line.timestamp
        val newest = newestTs
        if (newest == null || ts > newest) {
            newestTs = ts
            atNewest.clear()
            atNewest += line.key()
        } else if (ts == newest) {
            atNewest += line.key()
        }
    }

    private fun trim(out: MutableList<LogcatItem>) {
        var lines = out.count { it is LogcatItem.Line }
        if (lines <= maxEntries) return
        var cut = 0
        while (lines > maxEntries) {
            if (out[cut] is LogcatItem.Line) lines--
            cut++
        }
        while (cut < out.size && out[cut] is LogcatItem.Gap) cut++
        out.subList(0, cut).clear()
    }

    /** Reads `ps` for [unknownPids], waiting until a second after the last read. */
    private fun requestLookup() {
        val src = source ?: return
        if (lookupJob?.isActive == true) return
        lookupJob = scope.launch {
            val wait = if (lastLookupAt == Long.MIN_VALUE) 0 else lastLookupAt + LOOKUP_MILLIS - clock()
            if (wait > 0) delay(wait)
            lastLookupAt = clock()
            val asked = HashSet(unknownPids)
            unknownPids.clear()
            val found = try {
                src.processes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            val names = processes + found
            processes = names
            missedPids += asked.filter { it !in names }
            unknownPids.removeAll(names.keys)
            fillPackages(names)
            lookupJob = null
            if (unknownPids.isNotEmpty()) requestLookup()
        }
    }

    private fun fillPackages(names: Map<Int, String>) {
        var changed = false
        val out = items.map { item ->
            if (item is LogcatItem.Line && item.pkg == null) {
                names[item.entry.line.pid]?.let { changed = true; item.copy(pkg = packageOf(it)) } ?: item
            } else {
                item
            }
        }
        if (changed) setItems(out)
    }

    private fun hostTime(ms: Long): String = hostTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
}
