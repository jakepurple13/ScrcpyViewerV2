package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.ui.mirror.isConnected
import org.koin.compose.koinInject
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.files.DEVICE_ROOT
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.files.devicePath
import com.programmersbox.scrcpyviewer.files.isInside
import com.programmersbox.scrcpyviewer.files.kindOf
import com.programmersbox.scrcpyviewer.files.nameProblem
import com.programmersbox.scrcpyviewer.files.normalizeDir
import com.programmersbox.scrcpyviewer.files.parentOf
import com.programmersbox.scrcpyviewer.files.uniqueName
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.nio.file.Paths

/** How long an added row's highlight fades, after the row has grown in ([OPEN_MILLIS]). */
const val ARRIVING_FADE_MILLIS = 1_400L

/** How long a row added since the last listing counts as arriving: its grow, then its highlight's fade. */
const val ARRIVING_MILLIS = OPEN_MILLIS + ARRIVING_FADE_MILLIS

/** Extra delay per removed row, so rows collapse one after another. */
const val LEAVING_STAGGER_MILLIS = 30L

private const val NEW_FOLDER = "New folder"

enum class SortKey(val setting: String) {
    Name("name"), Size("size"), Modified("mtime");

    companion object {
        fun fromSetting(value: String): SortKey = entries.firstOrNull { it.setting == value } ?: Name
    }
}

/** Folders first, then [key] ([descending] or not), then name. A folder's size is its item count. */
fun sortEntries(entries: List<DeviceEntry>, key: SortKey, descending: Boolean): List<DeviceEntry> {
    val byName = compareBy<DeviceEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
    val byKey: Comparator<DeviceEntry> = when (key) {
        SortKey.Name -> byName
        SortKey.Size -> compareBy { if (it.isDir) (it.itemCount ?: -1).toLong() else it.size }
        SortKey.Modified -> compareBy { it.mtimeMillis }
    }
    val ordered = if (descending) byKey.reversed() else byKey
    return entries.sortedWith(compareBy<DeviceEntry> { !it.isDir }.then(ordered).then(byName))
}

enum class ClashChoice { Skip, KeepBoth, Replace }

sealed interface FilesDialog {
    data class Delete(val names: List<String>, val hasFolder: Boolean) : FilesDialog

    /** Items named in [clashing] already exist in [toDir]; [onChoice] settles the whole batch. */
    data class Clash(val toDir: String, val clashing: List<String>, val onChoice: (ClashChoice) -> Unit) : FilesDialog
}

data class Snack(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** "Internal storage" for the storage root, else the folder's own name. */
fun folderLabel(dir: String): String {
    val d = normalizeDir(dir)
    return if (d == DEVICE_ROOT) "Internal storage" else d.substringAfterLast('/')
}

/**
 * State of one mirror window's Files tab: the open folder, its listing, selection, sort and filter, and the
 * operations on it. Device commands go through [files]; pulls, pushes and installs through [enqueue] (the window's
 * transfer queue). Every operation is a no-op while [connected] is false. Results go to [snack] for the tab's
 * snackbar; [notify] also hears each one (tests record them there; the app passes nothing).
 */
@Stable
class FilesBrowser(
    val serial: String,
    private val files: DeviceFiles,
    private val transfers: FileTransfers,
    private val memory: QuickActionsMemory,
    private val settings: AppSettings,
    startDir: String,
    private val scope: CoroutineScope,
    private val notify: (Snack) -> Unit = {},
    private val enqueue: (List<TransferRequest>) -> List<Long> = { transfers.queue?.enqueue(it) ?: emptyList() },
    jobs: Flow<List<TransferJob>> = transfers.queue?.jobs ?: emptyFlow(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Opens a pulled file with the computer's default app; false when that failed. */
    private val openOnComputer: (Path) -> Boolean = { false },
) {
    private val saved = memory.filesLocation(serial)

    var path by mutableStateOf(normalizeDir(saved?.path ?: startDir))
        private set

    /** Folders visited before [path], oldest first. */
    private var history by mutableStateOf(saved?.history.orEmpty())

    val canGoBack: Boolean get() = history.isNotEmpty()
    val canGoUp: Boolean get() = parentOf(path) != null

    /** The last listing; it belongs to [listingPath], which lags [path] while a new folder loads. */
    var listing by mutableStateOf<Listing?>(null)
        private set

    /** The folder [listing] belongs to; null before the first listing. */
    var listingPath by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set
    var free by mutableStateOf<DeviceSpace?>(null)
        private set
    var connected by mutableStateOf(transfers.connected)
        private set
    var selection by mutableStateOf(Selection())
        private set
    // Backing fields: `setFilter` / `setSort` would clash with generated private setters.
    private var filterState by mutableStateOf("")
    val filter: String get() = filterState
    private var sortState by mutableStateOf(SortKey.fromSetting(settings.settings.value.filesSort))
    val sort: SortKey get() = sortState
    var descending by mutableStateOf(settings.settings.value.filesSortDescending)
        private set
    var showHidden by mutableStateOf(settings.settings.value.filesShowHidden)
        private set

    /** Names added since the previous listing of this folder, for [ARRIVING_MILLIS]. */
    var arriving by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Names collapsing out after a delete or move. */
    var leaving by mutableStateOf<Set<String>>(emptySet())
        private set
    var renaming by mutableStateOf<String?>(null)
        private set
    var renameError by mutableStateOf<String?>(null)
        private set

    /** A folder row that exists only here until its name is committed. */
    var pendingNewFolder by mutableStateOf<String?>(null)
        private set
    private var pendingMtime by mutableStateOf(0L)

    var dialog by mutableStateOf<FilesDialog?>(null)
        private set

    /** The result to show in the tab's snackbar; the newest replaces an older one. */
    var snack by mutableStateOf<Snack?>(null)
        private set

    /** +1 forward, -1 back (up, back, an ancestor crumb), 0 refresh. */
    var navDirection by mutableStateOf(0)
        private set

    private var loadJob: Job? = null
    private var generation = 0

    /** The rows to show: filtered, hidden-aware, sorted, with the pending new folder. */
    val visible: List<DeviceEntry> by derivedStateOf {
        val entries = (listing as? Listing.Entries)?.entries.orEmpty()
        val query = filter.trim()
        val shown = entries.filter {
            (showHidden || !it.hidden) && (query.isEmpty() || it.name.contains(query, ignoreCase = true))
        }
        // Once the device has the name (mkdir done, or made elsewhere), its own row stands in for the pending one.
        val pending = pendingNewFolder?.takeIf { p -> entries.none { it.name == p } }
            ?.let { DeviceEntry(it, isDir = true, size = 0, mtimeMillis = pendingMtime) }
        sortEntries(if (pending != null) shown + pending else shown, sort, descending)
    }

    private val order: List<String> get() = visible.map { it.name }

    /** Every name in the open folder (hidden and filtered ones too), when its listing is current. */
    private val currentNames: Set<String>?
        get() = (listing as? Listing.Entries)?.takeIf { listingPath == path }?.entries?.mapTo(HashSet()) { it.name }

    init {
        saveLocation()
        if (connected) load()
        scope.launch {
            val seen = HashSet<Long>()
            val opened = HashSet<Long>()
            var first = true
            jobs.collect { list ->
                // Pulls that finished before this browser started are not opened now.
                if (first) {
                    for (job in list) if (job.request.opensAfter && job.state == JobState.Done) opened.add(job.id)
                }
                var refresh = false
                for (job in list) {
                    openWhenDone(job, opened)
                    if (job.state != JobState.Done || !seen.add(job.id) || first) continue
                    val r = job.request
                    if (r.kind == TransferKind.Push && normalizeDir(r.deviceDir ?: transfers.pushTarget) == path) refresh = true
                }
                first = false
                if (refresh && connected) refresh()
            }
        }
    }

    /** An Open on computer pull that finished: opens its temp file, once. */
    private fun openWhenDone(job: TransferJob, opened: MutableSet<Long>) {
        val file = job.hostResult
        if (!job.request.opensAfter || job.state != JobState.Done || file == null || !opened.add(job.id)) return
        val name = job.request.name
        post(Snack(if (openOnComputer(file)) "Opened $name on your computer" else "Could not open $name"))
    }

    /** The window's transfers, for the tray: newest last. */
    val transferJobs: List<TransferJob> get() = transfers.jobs

    /** The push target a push without a folder goes to. */
    val pushTarget: String get() = transfers.pushTarget

    fun cancelTransfer(id: Long) {
        transfers.queue?.cancel(id)
    }

    fun dismissTransfer(id: Long) {
        transfers.queue?.dismiss(id)
    }

    private fun post(s: Snack) {
        snack = s
        notify(s)
    }

    /** The snackbar showed [shown] for its time (or its action ran); a newer snack stays. */
    fun dismissSnack(shown: Snack) {
        if (snack === shown) snack = null
    }

    // Navigation

    fun open(dir: String) {
        if (!connected) return
        val d = normalizeDir(dir)
        if (d == path) return refresh()
        navigate(d, history + path, if (isInside(path, d)) -1 else 1)
    }

    fun up() {
        if (!connected) return
        val parent = parentOf(path) ?: return
        navigate(parent, history + path, -1)
    }

    fun back() {
        if (!connected) return
        val previous = history.lastOrNull() ?: return
        navigate(previous, history.dropLast(1), -1)
    }

    fun refresh() {
        if (!connected) return
        navDirection = 0
        load()
    }

    private fun navigate(dir: String, newHistory: List<String>, direction: Int) {
        path = dir
        history = newHistory
        navDirection = direction
        filterState = ""
        selection = Selection()
        renaming = null
        renameError = null
        pendingNewFolder = null
        arriving = emptySet()
        showDialog(null)
        saveLocation()
        load()
    }

    private fun saveLocation() = memory.setFilesLocation(serial, FilesLocation(path, history))

    /** Lists [path]; a newer load (or navigation) cancels this one and its result is never applied. */
    private fun load() {
        loadJob?.cancel()
        val gen = ++generation
        val dir = path
        loading = true
        loadJob = scope.launch {
            val result = try {
                files.list(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Listing.Failed(e.message ?: "Could not list $dir")
            }
            if (gen != generation) return@launch
            val before = (listing as? Listing.Entries)?.takeIf { listingPath == dir }?.entries?.map { it.name }?.toSet()
            listing = result
            listingPath = dir
            loading = false
            selection = selection.retain(order)
            if (before != null && result is Listing.Entries) {
                val added = result.entries.map { it.name }.toSet() - before
                if (added.isNotEmpty()) markArriving(added)
            }
            if (result !is Listing.Entries) return@launch
            val folders = result.entries.filter { it.isDir }.map { it.name }
            val counts = runCatching { files.counts(dir, folders) }.getOrElse {
                if (it is CancellationException) throw it
                emptyMap()
            }
            if (gen != generation) return@launch
            if (counts.isNotEmpty()) {
                listing = Listing.Entries(result.entries.map { e -> counts[e.name]?.let { e.copy(itemCount = it) } ?: e })
            }
            val space = runCatching { files.freeSpace() }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            if (gen == generation) free = space
        }
    }

    private fun markArriving(names: Set<String>) {
        arriving = arriving + names
        scope.launch {
            delay(ARRIVING_MILLIS)
            arriving = arriving - names
        }
    }

    /** Refreshes when the user is still in [dir] (an operation started there has finished). */
    private fun refreshIfIn(dir: String) {
        if (path == dir) refresh()
    }

    /** Folder → open; `.apk` → install from the device; other file → pull to a temp folder and open on the computer. */
    fun openEntry(name: String) {
        if (!connected) return
        val entry = entry(name) ?: return
        when {
            entry.isDir -> open(devicePath(path, name))
            kindOf(entry) == FileKind.Apk -> install(name)
            else -> openOnComputer(name)
        }
    }

    /** Pulls file [name] to a temp folder, then opens it with the computer's default app. */
    fun openOnComputer(name: String) {
        if (!connected) return
        val entry = entry(name)?.takeIf { !it.isDir } ?: return
        enqueue(listOf(TransferRequest(Paths.get(""), TransferKind.Pull, path, name, opensAfter = true, size = entry.size)))
    }

    /** Installs the device-side `.apk` [name] (`adb install -r`, pulled to temp first). */
    fun install(name: String) {
        if (!connected) return
        val entry = entry(name)?.takeIf { kindOf(it) == FileKind.Apk } ?: return
        enqueue(listOf(TransferRequest(Paths.get(""), TransferKind.InstallFromDevice, path, name, size = entry.size)))
    }

    private fun entry(name: String): DeviceEntry? =
        (listing as? Listing.Entries)?.takeIf { listingPath == path }?.entries?.firstOrNull { it.name == name }

    // View options

    fun setFilter(text: String) {
        filterState = text
        selection = selection.retain(order)
    }

    /** The same key reverses; a new key starts ascending for Name and descending for Size and Modified. */
    fun setSort(key: SortKey) {
        if (key == sort) {
            descending = !descending
        } else {
            sortState = key
            descending = key != SortKey.Name
        }
        val k = sort
        val d = descending
        settings.update { it.copy(filesSort = k.setting, filesSortDescending = d) }
    }

    fun toggleHidden() {
        showHidden = !showHidden
        val h = showHidden
        settings.update { it.copy(filesShowHidden = h) }
        selection = selection.retain(order)
    }

    // Selection

    fun click(name: String) { selection = selection.click(order, name) }
    fun shiftClick(name: String) { selection = selection.shiftClick(order, name) }
    fun toggle(name: String) { selection = selection.toggle(name) }
    fun selectAll() { selection = selection.all(order) }
    fun moveSelection(delta: Int, extend: Boolean) { selection = selection.move(order, delta, extend) }
    fun clearSelection() { selection = selection.clear() }

    // Rename and new folder

    fun startRename(name: String) {
        if (!connected) return
        renaming = name
        renameError = null
    }

    fun cancelRename() {
        if (renaming != null && renaming == pendingNewFolder) {
            pendingNewFolder = null
            selection = selection.retain(order)
        }
        renaming = null
        renameError = null
    }

    /** Refuses per [nameProblem] (the field stays open with [renameError]); otherwise runs `mkdir` or `mv`. */
    fun commitRename(text: String) {
        val from = renaming ?: return
        if (!connected) return
        val dir = path
        val taken = (currentNames ?: emptySet()) - from
        if (text == from && from != pendingNewFolder) {
            cancelRename()
            return
        }
        nameProblem(text, taken)?.let {
            renameError = it
            return
        }
        renaming = null
        renameError = null
        if (from == pendingNewFolder) {
            pendingNewFolder = text
            selection = Selection(setOf(text), text, text)
            scope.launch {
                try {
                    files.mkdir(dir, text)
                    // In place before the pending row goes, so the row never disappears while the refresh runs.
                    val current = listing
                    if (path == dir && listingPath == dir && current is Listing.Entries && current.entries.none { it.name == text }) {
                        listing = Listing.Entries(current.entries + DeviceEntry(text, isDir = true, size = 0, mtimeMillis = pendingMtime))
                        markArriving(setOf(text))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    post(Snack("Could not create folder: ${e.message}"))
                    if (path == dir) selection = selection.retain(order - text)
                } finally {
                    if (pendingNewFolder == text) pendingNewFolder = null
                    refreshIfIn(dir)
                }
            }
            return
        }
        scope.launch {
            try {
                files.rename(dir, from, text)
                // In place, so the refresh does not take the new name for an arrival.
                val current = listing
                if (path == dir && listingPath == dir && current is Listing.Entries) {
                    listing = Listing.Entries(current.entries.map { if (it.name == from) it.copy(name = text) else it })
                }
                if (path == dir && from in selection.names) {
                    selection = Selection(
                        selection.names - from + text,
                        selection.anchor.takeUnless { it == from } ?: text,
                        selection.cursor.takeUnless { it == from } ?: text,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Snack("Could not rename: ${e.message}"))
            } finally {
                refreshIfIn(dir)
            }
        }
    }

    /** Adds a "New folder" row in rename; `mkdir` runs only when the name is committed. */
    fun newFolder() {
        if (!connected || pendingNewFolder != null) return
        val taken = currentNames ?: return
        val name = uniqueName(taken, NEW_FOLDER)
        pendingMtime = now()
        pendingNewFolder = name
        selection = Selection(setOf(name), name, name)
        startRename(name)
    }

    // Delete

    fun askDelete() {
        if (!connected) return
        val names = order.filter { it in selection.names && it != pendingNewFolder }
        if (names.isEmpty()) return
        showDialog(FilesDialog.Delete(names, names.any { entry(it)?.isDir == true }))
    }

    fun confirmDelete() {
        val d = dialog as? FilesDialog.Delete ?: return
        dialog = null
        if (!connected) return
        val dir = path
        scope.launch {
            try {
                files.delete(dir, d.names)
                collapse(dir, d.names)
                post(Snack(if (d.names.size == 1) "Deleted ${d.names.single()}" else "Deleted ${d.names.size} items"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Snack("Could not delete: ${e.message}"))
            } finally {
                refreshIfIn(dir)
            }
        }
    }

    /** Closes the dialog; a clash dialog closed without an answer skips the clashing items. */
    fun dismissDialog() {
        showDialog(null)
    }

    /** Shows [next] (or nothing); a clash dialog it replaces unanswered reports what it skipped. */
    private fun showDialog(next: FilesDialog?) {
        val old = dialog
        dialog = next
        if (old is FilesDialog.Clash && old !== next) {
            val n = old.clashing.size
            val where = folderLabel(old.toDir)
            post(Snack(if (n == 1) "Skipped ${old.clashing.single()}, already in $where" else "Skipped $n items already in $where"))
        }
    }

    /** Collapses [names] out of [dir]'s rows: [leaving] for the close motion, then gone. */
    private suspend fun collapse(dir: String, names: List<String>) {
        if (path != dir) return
        leaving = leaving + names
        try {
            delay(CLOSE_MILLIS + LEAVING_STAGGER_MILLIS * (names.size - 1))
        } finally {
            leaving = leaving - names.toSet()
        }
        val current = listing
        if (path == dir && listingPath == dir && current is Listing.Entries) {
            listing = Listing.Entries(current.entries.filter { it.name !in names })
            selection = selection.retain(order)
        }
    }

    // Move

    /**
     * Moves [names] from the open folder into [toDir]. Folders that would land inside themselves are left out; moving
     * into the open folder does nothing. Names already in [toDir] wait for one [FilesDialog.Clash]; the rest move now.
     */
    fun move(names: List<String>, toDir: String) {
        if (!connected) return
        val dir = path
        val target = normalizeDir(toDir)
        // /sdcard ignores case: another spelling of the open folder is the open folder.
        if (target.equals(dir, ignoreCase = true)) return
        val items = names.distinct().filter { !isInside(target, devicePath(dir, it)) }
        if (items.isEmpty()) return
        scope.launch {
            val taken = namesIn(target) ?: return@launch
            val clashing = items.filter { it in taken }
            val ok = items - clashing.toSet()
            if (clashing.isNotEmpty()) {
                showDialog(FilesDialog.Clash(target, clashing) { choice ->
                    dialog = null
                    if (!connected) return@Clash
                    when (choice) {
                        ClashChoice.Skip -> Unit
                        ClashChoice.Replace -> scope.launch { runMove(dir, clashing, target, replace = clashing.toSet()) }
                        ClashChoice.KeepBoth -> {
                            val used = HashSet(taken + ok)
                            val renames = LinkedHashMap<String, String>()
                            for (n in clashing) renames[n] = uniqueName(used, n).also { used += it }
                            scope.launch { runMove(dir, clashing, target, renames = renames) }
                        }
                    }
                })
            }
            if (ok.isNotEmpty()) runMove(dir, ok, target)
        }
    }

    private suspend fun runMove(
        dir: String,
        names: List<String>,
        target: String,
        renames: Map<String, String> = emptyMap(),
        replace: Set<String> = emptySet(),
    ) {
        try {
            files.move(dir, names, target, renames, replace)
            collapse(dir, names)
            val what = if (names.size == 1) names.single() else "${names.size} items"
            post(Snack("Moved $what to ${folderLabel(target)}", "Open") { open(target) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            post(Snack("Could not move: ${e.message}"))
        } finally {
            refreshIfIn(dir)
        }
    }

    /**
     * Every name in [dir]: the current listing when it is open, else a fresh listing. Null (with a snackbar) when
     * Android blocks the folder; empty when it cannot be listed, since the device-side commands refuse to overwrite.
     */
    private suspend fun namesIn(dir: String): Set<String>? {
        currentNames?.takeIf { dir == path }?.let { return it }
        val result = try {
            files.list(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when (result) {
            is Listing.Entries -> result.entries.mapTo(HashSet()) { it.name }
            Listing.Blocked -> {
                post(Snack("Android blocks this folder"))
                null
            }
            else -> emptySet()
        }
    }

    // Transfers

    /**
     * Pushes [paths] into [toDir]. False when nothing can go: disconnected, or [toDir] is the open folder and Android
     * blocks it. Into the open folder, clashes are known at once; into another folder it is listed first.
     */
    fun push(paths: List<Path>, toDir: String): Boolean {
        if (!connected || paths.isEmpty()) return false
        val target = normalizeDir(toDir)
        if (target == path && listingPath == path && listing == Listing.Blocked) return false
        val known = currentNames?.takeIf { target == path }
        if (known != null) return pushInto(paths, target, known)
        scope.launch {
            val taken = namesIn(target) ?: return@launch
            pushInto(paths, target, taken)
        }
        return true
    }

    private fun pushInto(paths: List<Path>, target: String, taken: Set<String>): Boolean {
        fun request(p: Path, name: String) = TransferRequest(p, TransferKind.Push, target, name)
        val named = paths.map { it to (it.fileName?.toString() ?: it.toString()) }
        val clashing = named.filter { it.second in taken }
        val ok = named - clashing.toSet()
        var queued = false
        if (ok.isNotEmpty()) queued = enqueue(ok.map { request(it.first, it.second) }).isNotEmpty()
        if (clashing.isEmpty()) return queued
        showDialog(FilesDialog.Clash(target, clashing.map { it.second }) { choice ->
            dialog = null
            if (!connected) return@Clash
            when (choice) {
                ClashChoice.Skip -> Unit
                ClashChoice.Replace -> enqueue(clashing.map { request(it.first, it.second) })
                ClashChoice.KeepBoth -> {
                    val used = HashSet(taken + ok.map { it.second })
                    enqueue(clashing.map { (p, n) -> request(p, uniqueName(used, n).also { used += it }) })
                }
            }
        })
        return true
    }

    /** Pulls [names] from the open folder into the computer folder [toDir]. */
    fun pull(names: List<String>, toDir: Path) {
        if (!connected) return
        val requests = names.mapNotNull { name ->
            val e = entry(name) ?: return@mapNotNull null
            TransferRequest(toDir, TransferKind.Pull, path, name, size = e.size, isDirectory = e.isDir)
        }
        if (requests.isNotEmpty()) enqueue(requests)
    }

    // Connection

    /** The session dropped or came back: while down every operation is off and the list stays; back up refreshes. */
    fun onConnectionChanged(connected: Boolean) {
        if (connected == this.connected) return
        this.connected = connected
        if (connected) {
            refresh()
        } else {
            loadJob?.cancel()
            generation++
            loading = false
            showDialog(null)
            cancelRename()
        }
    }
}

/**
 * The Files tab state for one mirror window, or null without adb. It opens at [transfers]' push target the first
 * time for [serial] in this app run (afterwards where [memory] left it) and follows the session's connection. Its
 * results show in the tab's snackbar ([FilesBrowser.snack]).
 */
@Composable
fun rememberFilesBrowser(
    serial: String,
    session: ScrcpySession,
    adb: AdbClient?,
    transfers: FileTransfers,
    memory: QuickActionsMemory = koinInject(),
    settings: AppSettings = koinInject(),
): FilesBrowser? {
    val uriHandler = LocalUriHandler.current
    val parent = rememberCoroutineScope()
    // One scope per browser: a Reconnect (new session, new transfers) cancels the old browser's loads and collectors.
    val scope = remember(serial, session, adb, transfers) { childScope(parent) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val browser = remember(scope) {
        adb?.let {
            FilesBrowser(
                serial,
                DeviceFiles(ShellRunner(it, serial)) { path -> session.send(ControlMessage.ScanFile(path)) },
                transfers, memory, settings, transfers.pushTarget, scope,
                openOnComputer = { path -> uriHandler.openFileSafely(path) },
            )
        }
    } ?: return null
    LaunchedEffect(browser, session) {
        session.status.collect { browser.onConnectionChanged(it.isConnected()) }
    }
    return browser
}

/** A scope under [parent] that can be cancelled on its own; [parent]'s cancellation still reaches it. */
internal fun childScope(parent: CoroutineScope): CoroutineScope =
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
