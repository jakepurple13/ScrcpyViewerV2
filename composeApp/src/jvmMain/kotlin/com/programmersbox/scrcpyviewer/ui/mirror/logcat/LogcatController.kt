package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.logcat.EntryInfo
import com.programmersbox.scrcpyviewer.logcat.ExportFormat
import com.programmersbox.scrcpyviewer.logcat.ExportRow
import com.programmersbox.scrcpyviewer.logcat.FoldResult
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatEntry
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatQuery
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.logcat.SuggestionContext
import com.programmersbox.scrcpyviewer.logcat.TraceFolder
import com.programmersbox.scrcpyviewer.logcat.exportFileName
import com.programmersbox.scrcpyviewer.logcat.packageOf
import com.programmersbox.scrcpyviewer.logcat.parseExportDevice
import com.programmersbox.scrcpyviewer.logcat.withTerm
import com.programmersbox.scrcpyviewer.logcat.writeStudioLogcat
import com.programmersbox.scrcpyviewer.logcat.writeThreadtime
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lines kept per window; older ones drop off. */
const val LOGCAT_MAX_ENTRIES = 20_000

/** How often buffered lines are moved into the list, at most. */
private const val DRAIN_MILLIS = 50L

/** Lines arriving this soon after Play or a resume are taken as the read-back backlog. */
internal const val BACKLOG_MILLIS = 1_500L

/** At most one `ps` per this long. */
private const val LOOKUP_MILLIS = 1_000L

/** A pid `ps` didn't list is asked about again after this long, in case Android reused it. */
private const val MISSED_RETRY_MILLIS = 10_000L

/** An export stays shown as running at least this long, so a tiny one doesn't flash. */
internal const val EXPORT_MIN_RUN_MILLIS = 400L

/** How long the finished export is shown before going back to idle. */
internal const val EXPORT_DONE_MILLIS = 3_600L

/** How long the canceled export is shown before going back to idle. */
internal const val EXPORT_CANCELED_MILLIS = 2_800L

enum class LogcatRun { NotStarted, Running, Paused, StoppedByLeaving }

/** Where an export of the list to a file stands. */
sealed interface LogcatExport {
    data object Idle : LogcatExport

    /** Writing [done] of [total] lines. */
    data class Running(val format: ExportFormat, val done: Int, val total: Int) : LogcatExport

    /** Finished: [lines] lines are in the file at [path]. */
    data class Done(val path: Path, val lines: Int) : LogcatExport

    data object Canceled : LogcatExport
}

/**
 * What a failed export's toast says went wrong. A file system error's message is just its file (the `.part`), so
 * its reason is used, or a plain phrase for its kind.
 */
internal fun exportFailureReason(e: Exception): String = when (e) {
    is FileSystemException -> e.reason?.takeIf { it.isNotBlank() } ?: when (e) {
        is AccessDeniedException -> "Permission denied"
        is NoSuchFileException -> "Folder not found"
        else -> "Could not write file"
    }
    else -> e.message ?: e.toString()
}

/** A row of the Logcat list. */
sealed interface LogcatItem {
    val key: Long

    /**
     * A log entry; [process] is its process name from `ps` and [pkg] its app package, null while (or when) the pid
     * is unknown. Kept on the line, so a line keeps them after its process exits and `ps` no longer lists the pid.
     */
    data class Line(val entry: LogcatEntry, val pkg: String?, val process: String? = null) : LogcatItem {
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
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    var run by mutableStateOf(LogcatRun.NotStarted)
        private set

    /**
     * False while the device's session is disconnected (the sheet sets it): [play] does nothing and Play shows
     * disabled, in the sheet and in the own window. Pause still works.
     */
    var connected by mutableStateOf(true)
        internal set

    /** Play can start or resume the stream now. */
    val canPlay: Boolean get() = source != null && connected

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
            refilter()
        }

    /** The picked app's package (`package:mine`). */
    var mine by mutableStateOf<String?>(null)
        private set

    var wrap by mutableStateOf(false)
    var compact by mutableStateOf(false)

    /** True while the panel is in its own window (see [popOut]); the sheet's tab then shows a card. */
    var popped by mutableStateOf(false)
        private set

    /** The own window stays above other windows. Off on every [popOut]. */
    var alwaysOnTop by mutableStateOf(false)
        private set

    /** Increments to ask the own window to come to the front. */
    var raiseRequests by mutableIntStateOf(0)
        private set

    /** Runs after every [dock]; the sheet sets it to show the Logcat tab again. */
    internal var onDock: () -> Unit = {}

    /**
     * Entries whose stack-trace fold the user flipped from the default (crashes unfolded, others folded), by entry
     * key. Here, not in the list, so folds survive the panel moving between the sheet and its own window.
     */
    internal val traceFlips = mutableStateMapOf<Long, Boolean>()

    /** Flips the fold of the trace on the entry [key]. */
    fun flipTrace(key: Long) {
        traceFlips[key] = traceFlips[key] != true
    }

    /** Moves the panel into its own window: lines, filter and run state stay as they are; [alwaysOnTop] starts off. */
    fun popOut() {
        alwaysOnTop = false
        popped = true
        moved = true
    }

    /** Moves the panel back into the sheet ([onDock] shows its tab). Never changes [run]. */
    fun dock() {
        if (!popped) return
        popped = false
        moved = true
        onDock()
    }

    /**
     * True from a [popOut] or [dock] until the panel's pop-out button next shows: that one swaps its icon in from the
     * other place's, the ones shown later just show theirs. Not state.
     */
    internal var moved = false

    /** Asks the own window to come to the front. */
    fun raise() {
        raiseRequests++
    }

    fun toggleAlwaysOnTop() {
        alwaysOnTop = !alwaysOnTop
        toast(if (alwaysOnTop) "Logcat window stays on top" else "Logcat window no longer stays on top")
    }

    /** Increments to ask the UI to pulse Play. */
    var nudge by mutableIntStateOf(0)
        private set

    /**
     * The [nudge] the Play button last pulsed for. The tab writes it, so each bump pulses once, also when the bump
     * came while the tab wasn't shown (returning to a stopped tab). Not state.
     */
    internal var nudgeShown = 0

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

    /** The last `ps` table, pid to process name. Lines copy their names from it; not state. */
    private var processes: Map<Int, String> = emptyMap()

    /*
     * The filter result is kept up to date as lines come and go, not recomputed from all of [items] per batch: a
     * batch checks only its new lines (and the entry its trace lines folded into), trimmed lines leave the front,
     * and only a new query, a new picked app or new process names filter everything again. `age:` is re-checked by
     * [tickAge]. [visible] holds the same instances as [items], so trimming can match them by identity.
     */
    private var visibleState by mutableStateOf<List<LogcatItem>>(emptyList())

    /** [items] that pass the filter; gaps are always kept. */
    val visible: List<LogcatItem> get() = visibleState

    private var totalState by mutableIntStateOf(0)

    /** Lines in [items]. */
    val totalLines: Int get() = totalState

    private var levelState by mutableStateOf<Map<LogLevel, Int>>(emptyMap())

    /** Lines in [visible] per level. */
    val levelCounts: Map<LogLevel, Int> get() = levelState

    /** Lines in [visible] per level, by ordinal; [levelCounts] is published from it. Not state. */
    private val visibleLevels = IntArray(LogLevel.entries.size)

    /** Lines in [items] per tag, package and process, for [suggestionContext]. Not state. */
    private val tagCounts = LinkedHashMap<String, Int>()
    private val packageCounts = HashMap<String, Int>()
    private val processCounts = HashMap<String, Int>()

    /** The clock the filter last judged every `age:` term at. */
    private var filteredAt = Long.MIN_VALUE

    private fun passes(item: LogcatItem, q: LogcatQuery, m: String?, now: Long): Boolean =
        item !is LogcatItem.Line || q.matches(item.entry, EntryInfo(item.pkg, item.process), m, now)

    private fun countLevel(item: LogcatItem, by: Int) {
        if (item is LogcatItem.Line) visibleLevels[item.entry.line.level.ordinal] += by
    }

    private fun publishVisible(vis: List<LogcatItem>) {
        visibleState = vis
        levelState = buildMap {
            for (l in LogLevel.entries) if (visibleLevels[l.ordinal] > 0) put(l, visibleLevels[l.ordinal])
        }
    }

    /** Filters all of [items] again: the query, the picked app or a line's process changed. */
    private fun refilter() {
        val q = parsed
        val m = mine
        val now = clock()
        visibleLevels.fill(0)
        val vis = ArrayList<LogcatItem>()
        for (item in itemsState) {
            if (passes(item, q, m, now)) {
                vis += item
                countLevel(item, 1)
            }
        }
        filteredAt = now
        publishVisible(vis)
    }

    /**
     * Judges `age:` terms against the clock again. The list calls it about once a second while the query has one,
     * so lines age out (or in, for `-age:`) on a quiet or paused stream too. Only lines whose age limit passed since
     * the last check are matched again; the rest keep their result.
     */
    internal fun tickAge() {
        val q = parsed
        val limits = q.ageLimits
        if (limits.isEmpty()) return
        val now = clock()
        val from = filteredAt
        if (now == from) return
        if (now < from) return refilter()
        val m = mine
        val old = visibleState
        val vis = ArrayList<LogcatItem>(old.size)
        var j = 0
        var changed = false
        for (item in itemsState) {
            val was = j < old.size && old[j] === item
            if (was) j++
            val crossed = item is LogcatItem.Line && limits.any { item.entry.receivedAtMillis + it in from..now }
            val keep = if (crossed) passes(item, q, m, now) else was
            if (keep != was) {
                changed = true
                countLevel(item, if (keep) 1 else -1)
            }
            if (keep) vis += item
        }
        filteredAt = now
        if (changed) publishVisible(vis)
    }

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

    private var exportState by mutableStateOf<LogcatExport>(LogcatExport.Idle)

    /** The export in progress or just finished; [LogcatExport.Idle] otherwise. */
    var export: LogcatExport
        get() = exportState
        private set(value) {
            exportState = value
        }

    private var exportJob: Job? = null
    private var exportPart: Path? = null

    /** Called on [io] for each export progress report; the cancel test hooks it. */
    internal var onExportProgress: (Int) -> Unit = {}

    /** Called on [io] just before the finished `.part` is moved over the target; the cancel test hooks it. */
    internal var onExportMove: () -> Unit = {}

    /** True while the finished file is moved over the target; a cancel then does nothing. */
    private var exportMoving = false

    /** Shows Done or Canceled for a while, then goes back to idle; its own job, so a click can cut it short. */
    private var exportResultJob: Job? = null

    /**
     * Exports every line in [items] (the filter never limits it) as [format]. The lines are those at this call;
     * later ones aren't in it. [pick] asks for the target file (null = canceled, nothing happens). The file is
     * written beside the target as `.part` and moved over it when complete. Does nothing unless idle with lines
     * and no other export is asking for its file.
     */
    fun export(format: ExportFormat, pick: suspend (suggestedName: String, extension: String) -> Path?) {
        if (export !is LogcatExport.Idle || exportJob?.isActive == true || totalLines == 0) return
        val rows = items.filterIsInstance<LogcatItem.Line>().map { ExportRow(it.entry, it.process) }
        val filter = query
        val projectIds = listOfNotNull(mine)
        exportJob = scope.launch {
            val device = source?.exportDevice() ?: parseExportDevice("", "", ZonedDateTime.now())
            val ext = format.extension
            val target = pick(exportFileName(device, now(), ext), ext) ?: return@launch
            val part = target.resolveSibling(target.fileName.toString() + ".part")
            exportPart = part
            val startedAt = clock()
            export = LogcatExport.Running(format, 0, rows.size)
            val onProgress = { n: Int ->
                onExportProgress(n)
                scope.launch { (export as? LogcatExport.Running)?.let { export = it.copy(done = n) } }
                Unit
            }
            try {
                withContext(io) {
                    Files.newBufferedWriter(part, StandardCharsets.UTF_8).use { out ->
                        when (format) {
                            ExportFormat.Studio -> writeStudioLogcat(out, rows, device, filter, projectIds, onProgress)
                            ExportFormat.Text -> writeThreadtime(out, rows, onProgress)
                        }
                    }
                }
                val wait = EXPORT_MIN_RUN_MILLIS - (clock() - startedAt)
                if (wait > 0) delay(wait)
                // Past here the target may already be replaced, so the move always finishes and cancel is ignored.
                exportMoving = true
                withContext(NonCancellable + io) {
                    onExportMove()
                    try {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } catch (e: CancellationException) {
                runCatching { Files.deleteIfExists(part) }
                throw e
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(part) }
                exportPart = null
                toast("Could not export: ${exportFailureReason(e)}")
                export = LogcatExport.Idle
                return@launch
            } finally {
                exportMoving = false
            }
            exportPart = null
            showExportResult(LogcatExport.Done(target, rows.size), EXPORT_DONE_MILLIS)
        }
    }

    /**
     * Stops a running export, removes its `.part` file and leaves any existing target as it was. Does nothing once
     * the finished file is being moved over the target.
     */
    fun cancelExport() {
        if (export !is LogcatExport.Running || exportMoving) return
        exportJob?.cancel()
        exportJob = null
        exportPart?.let { runCatching { Files.deleteIfExists(it) } }
        exportPart = null
        showExportResult(LogcatExport.Canceled, EXPORT_CANCELED_MILLIS)
    }

    /** Goes back to idle at once from Done or Canceled, so a new export can start. */
    fun dismissExportResult() {
        if (export !is LogcatExport.Done && export !is LogcatExport.Canceled) return
        exportResultJob?.cancel()
        exportResultJob = null
        export = LogcatExport.Idle
    }

    private fun showExportResult(result: LogcatExport, millis: Long) {
        exportResultJob?.cancel()
        export = result
        exportResultJob = scope.launch {
            delay(millis)
            if (export == result) export = LogcatExport.Idle
        }
    }

    private val unknownPids = HashSet<Int>()
    /** Pids `ps` didn't list, with when that was. */
    private val missedPids = HashMap<Int, Long>()
    private var lookupJob: Job? = null
    private var lastLookupAt = Long.MIN_VALUE

    /** Starts the stream, or resumes it from the newest line received. Does nothing while not [connected]. */
    fun play() {
        val src = source ?: return
        if (run == LogcatRun.Running || !connected) return
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
        backlogUntil = clock() + BACKLOG_MILLIS
        val buffer = Channel<LogcatLine>(Channel.UNLIMITED)
        streamJob = scope.launch {
            launch(io) {
                // Always closed, so the drain loop ends even on a cancellation that isn't ours.
                try {
                    src.lines(since).collect { buffer.send(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    buffer.close(e)
                } finally {
                    buffer.close()
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

    /** Pauses a running stream, else plays (Play/Pause and Space). */
    fun toggle() = if (run == LogcatRun.Running) pause() else play()

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
        if (error != null) toast("Logcat stopped: ${error.message ?: error}")
    }

    /** Empties the list; the run state and query stay. */
    fun clear() {
        setItems(emptyList())
        totalState = 0
        tagCounts.clear()
        packageCounts.clear()
        processCounts.clear()
        visibleLevels.fill(0)
        publishVisible(emptyList())
        atNewest.clear()
        replays.clear()
        replayTs = null
        pendingGap = null
        folder = TraceFolder()
        traceFlips.clear()
        toast("Cleared. Older lines stay on the device buffer.")
    }

    /** Makes [pkg] the picked app and adds `package:mine` to the query if missing. */
    fun pick(pkg: String) {
        mine = pkg
        query = withTerm(query, "package:mine", true)
    }

    fun requestNudge() {
        nudge++
    }

    /** Host time until which arriving lines count as read-back: the `-T` backlog after Play or a resume. */
    private var backlogUntil = Long.MIN_VALUE

    /**
     * True for an entry that arrived within [BACKLOG_MILLIS] of the last [play]: most likely the device's buffered
     * lines read back, not a live one. The list doesn't grow or flash these.
     */
    internal fun isBacklog(entry: LogcatEntry): Boolean = entry.receivedAtMillis < backlogUntil

    /** Shows [message] as a toast: in the own window while it is up ([windowToast]), else through `notify`. */
    internal fun toast(message: String) = (windowToast ?: notify)(message)

    /** The own window's snackbar while that window is up; it shows the panel's toasts there. */
    internal var windowToast: ((String) -> Unit)? = null

    /** Tags (with line counts), packages and processes seen in the list, for autocomplete. */
    fun suggestionContext(): SuggestionContext = SuggestionContext(
        // Counted as lines come and go, so this costs per tag, not per line, on every keystroke.
        tags = LinkedHashMap(tagCounts),
        packages = HashSet(packageCounts.keys),
        processes = HashSet(processCounts.keys),
        mine = mine,
    )

    private fun <K> MutableMap<K, Int>.bump(key: K?, by: Int) {
        if (key == null) return
        val n = (this[key] ?: 0) + by
        if (n > 0) this[key] = n else remove(key)
    }

    private fun countLine(line: LogcatItem.Line, by: Int) {
        tagCounts.bump(line.entry.line.tag, by)
        packageCounts.bump(line.pkg, by)
        processCounts.bump(line.process, by)
    }

    private fun append(batch: List<LogcatLine>) {
        val now = clock()
        val out = ArrayList(items)
        val vis = ArrayList(visible)
        val firstNew = out.size
        // Rows from here on are matched against the filter after the loop: the new ones, and an older entry that
        // trace lines folded into, since `message:` and `is:` look at its trace.
        var recheck = firstNew
        val names = processes
        for (line in batch) {
            if (dropAsReplay(line)) continue
            remember(line)
            when (val f = folder.add(line, seq++, now)) {
                is FoldResult.New -> {
                    // Only before a new row: a continuation folds into a row above the Gap.
                    pendingGap?.let { (p, r) ->
                        out += LogcatItem.Gap(--gapKey, p, r)
                        pendingGap = null
                    }
                    val pid = f.entry.line.pid
                    val process = names[pid]
                    val missedAt = missedPids[pid]
                    if (process == null && (missedAt == null || now - missedAt >= MISSED_RETRY_MILLIS)) {
                        missedPids.remove(pid)
                        unknownPids += pid
                    }
                    out += LogcatItem.Line(f.entry, process?.let(::packageOf), process)
                }
                is FoldResult.Appended -> {
                    val i = out.indexOfLast { it is LogcatItem.Line && it.entry.seq == f.entry.seq }
                    if (i >= 0) {
                        out[i] = (out[i] as LogcatItem.Line).copy(entry = f.entry)
                        if (i < recheck) recheck = i
                    }
                }
            }
        }
        if (recheck < firstNew) {
            // The rows being rechecked end the old list, so whichever of them showed end [vis].
            val keys = (recheck until firstNew).mapTo(HashSet()) { out[it].key }
            while (vis.isNotEmpty() && vis.last().key in keys) countLevel(vis.removeAt(vis.lastIndex), -1)
        }
        val q = parsed
        val m = mine
        var lines = totalState
        for (i in recheck until out.size) {
            val item = out[i]
            if (i >= firstNew && item is LogcatItem.Line) {
                lines++
                countLine(item, 1)
            }
            if (passes(item, q, m, now)) {
                vis += item
                countLevel(item, 1)
            }
        }
        totalState = trim(out, vis, lines)
        setItems(out)
        publishVisible(vis)
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

    /**
     * Drops the oldest rows of [out] past [maxEntries] lines, and the same rows from [vis]. [count] is the lines in
     * [out]; returns the lines left.
     */
    private fun trim(out: MutableList<LogcatItem>, vis: MutableList<LogcatItem>, count: Int): Int {
        var lines = count
        if (lines <= maxEntries) return lines
        var cut = 0
        while (lines > maxEntries) {
            if (out[cut] is LogcatItem.Line) lines--
            cut++
        }
        while (cut < out.size && out[cut] is LogcatItem.Gap) cut++
        // [vis] keeps [out]'s order and instances, so the cut rows that show are its first ones.
        var shown = 0
        for (i in 0 until cut) {
            val item = out[i]
            if (item is LogcatItem.Line) countLine(item, -1)
            if (shown < vis.size && vis[shown] === item) {
                countLevel(item, -1)
                shown++
            }
        }
        vis.subList(0, shown).clear()
        out.subList(0, cut).clear()
        return lines
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
            // A `ps` answer is the whole table: it replaces the map, so a reused pid gets its new process. A failed
            // read keeps the old one.
            val names = try {
                src.processes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                processes
            }
            val answeredAt = clock()
            for (pid in asked) if (pid !in names) missedPids[pid] = answeredAt
            unknownPids.removeAll(names.keys)
            if (names != processes) {
                processes = names
                fillPackages(names)
            }
            lookupJob = null
            if (unknownPids.isNotEmpty()) requestLookup()
        }
    }

    /**
     * Gives each line the process (and package) [names] has for its pid when that differs from the one it shows:
     * unknown ones get theirs, and a reused pid's lines follow its new process. A pid [names] doesn't list (its
     * process exited) keeps what it shows.
     */
    private fun fillPackages(names: Map<Int, String>) {
        var changed = false
        val out = items.map { item ->
            if (item is LogcatItem.Line) {
                val process = names[item.entry.line.pid]
                if (process != null && process != item.process) {
                    changed = true
                    item.copy(pkg = packageOf(process), process = process)
                } else {
                    item
                }
            } else {
                item
            }
        }
        if (changed) {
            setItems(out)
            packageCounts.clear()
            processCounts.clear()
            for (item in out) {
                if (item is LogcatItem.Line) {
                    packageCounts.bump(item.pkg, 1)
                    processCounts.bump(item.process, 1)
                }
            }
            // Package and process terms may judge the changed lines differently now.
            refilter()
        }
    }

    private fun hostTime(ms: Long): String = hostTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
}
