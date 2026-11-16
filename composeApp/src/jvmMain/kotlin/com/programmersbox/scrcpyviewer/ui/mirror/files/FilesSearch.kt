package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.files.SearchHit
import com.programmersbox.scrcpyviewer.files.kindOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Typing pauses this long before a search starts. */
const val SEARCH_DEBOUNCE_MILLIS = 250L

/** A search stops once it has found this many matches. */
const val MAX_SEARCH_HITS = 1_000

/** The type chips, in order, with their labels. */
val SEARCH_KINDS: List<Pair<FileKind, String>> = listOf(
    FileKind.Folder to "Folders",
    FileKind.Image to "Images",
    FileKind.Video to "Videos",
    FileKind.Audio to "Audio",
    FileKind.Document to "Documents",
    FileKind.Apk to "APKs",
)

/** One folder's matches, as the results list groups them. */
data class SearchGroup(val dir: String, val hits: List<SearchHit>)

/**
 * The Files tab's device-wide search: open or not, the query, the matches found so far, the type chips and the
 * keyboard cursor. [run] streams matches for a query (see [com.programmersbox.scrcpyviewer.files.DeviceFiles.search]);
 * [includeHidden] follows the folder view's Show hidden files. Searches run in [scope]; a newer query, [stop] or
 * [close] cancels the one running.
 */
@Stable
class FilesSearch(
    private val scope: CoroutineScope,
    private val run: (query: String, includeHidden: Boolean) -> Flow<List<SearchHit>>,
    private val includeHidden: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var active by mutableStateOf(false)
        private set

    // Backing field: `setQuery` would clash with a generated private setter.
    private var queryState by mutableStateOf("")
    val query: String get() = queryState

    /** The query the current [hits] belong to (trimmed); empty before any search. */
    var searched by mutableStateOf("")
        private set

    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** A search is waiting out the debounce or still streaming. */
    var running by mutableStateOf(false)
        private set

    /** The last search was stopped (by [stop]) before it finished. */
    var stopped by mutableStateOf(false)
        private set

    /** The last search hit [MAX_SEARCH_HITS] and stopped there. */
    var truncated by mutableStateOf(false)
        private set

    /** How long the last finished search took; null while one runs or before any. */
    var elapsedMillis by mutableStateOf<Long?>(null)
        private set

    /** The search failed with this message. */
    var error by mutableStateOf<String?>(null)
        private set

    var kinds by mutableStateOf<Set<FileKind>>(emptySet())
        private set

    /** The keyboard cursor: a match's device path. */
    var cursor by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    /** Matches per type, over everything found (the chips' counts). */
    val counts: Map<FileKind, Int> by derivedStateOf { hits.groupingBy { kindOf(it.entry) }.eachCount() }

    /** Matches of the chosen types (all with no chip on), grouped by folder, folders and names in order. */
    val groups: List<SearchGroup> by derivedStateOf {
        hits.asSequence()
            .filter { kinds.isEmpty() || kindOf(it.entry) in kinds }
            .groupBy { it.dir }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (dir, list) -> SearchGroup(dir, list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name })) }
    }

    /** [groups] flattened, in the order shown. */
    val shown: List<SearchHit> by derivedStateOf { groups.flatMap { it.hits } }

    fun open() {
        active = true
    }

    /** Leaves search: the running one stops; the query and matches stay for the next [open]. */
    fun close() {
        if (!active) return
        active = false
        if (running) stop()
    }

    fun setQuery(text: String) {
        if (text == queryState) return
        queryState = text
        start(debounce = true)
    }

    /** Runs the query again now (Enter in the field, or the result of a hidden-files change). */
    fun rerun() = start(debounce = false)

    private fun start(debounce: Boolean) {
        job?.cancel()
        val q = queryState.trim()
        stopped = false
        truncated = false
        error = null
        elapsedMillis = null
        if (q.isEmpty()) {
            running = false
            searched = ""
            hits = emptyList()
            cursor = null
            return
        }
        running = true
        val hidden = includeHidden()
        job = scope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            val started = now()
            searched = q
            hits = emptyList()
            cursor = null
            var found = emptyList<SearchHit>()
            try {
                run(q, hidden).collect { batch ->
                    found = found + batch.take(MAX_SEARCH_HITS - found.size)
                    hits = found
                    if (found.size >= MAX_SEARCH_HITS) {
                        truncated = true
                        throw Truncated()
                    }
                }
            } catch (_: Truncated) {
                // Enough found: the device's search stops with the collection.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            elapsedMillis = now() - started
            running = false
        }
    }

    private class Truncated : CancellationException("enough matches")

    /** Stops the running search; what it found stays. */
    fun stop() {
        if (!running) return
        job?.cancel()
        running = false
        stopped = searched == queryState.trim() && searched.isNotEmpty()
    }

    fun toggleKind(kind: FileKind) {
        kinds = if (kind in kinds) kinds - kind else kinds + kind
        if (cursor != null && shown.none { it.path == cursor }) cursor = null
    }

    /** Moves the cursor [delta] matches down (up when negative), starting at the first or last. */
    fun moveCursor(delta: Int) {
        val list = shown
        if (list.isEmpty()) return
        val at = list.indexOfFirst { it.path == cursor }
        val next = if (at < 0) (if (delta > 0) 0 else list.lastIndex) else (at + delta).coerceIn(0, list.lastIndex)
        cursor = list[next].path
    }

    fun pointAt(path: String?) {
        cursor = path
    }

    /** The match under the cursor, if it is shown. */
    val current: SearchHit? get() = cursor?.let { c -> shown.firstOrNull { it.path == c } }
}
