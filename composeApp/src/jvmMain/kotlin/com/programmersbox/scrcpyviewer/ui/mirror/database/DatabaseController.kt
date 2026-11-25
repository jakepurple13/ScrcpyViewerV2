package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.AppDataAccess
import com.programmersbox.scrcpyviewer.database.DeviceDatabase
import com.programmersbox.scrcpyviewer.database.DeviceDatabases
import com.programmersbox.scrcpyviewer.database.LocalDatabase
import com.programmersbox.scrcpyviewer.database.Page
import com.programmersbox.scrcpyviewer.database.RootProbe
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.database.RowKey
import com.programmersbox.scrcpyviewer.database.SqlOutcome
import com.programmersbox.scrcpyviewer.database.SqlValue
import com.programmersbox.scrcpyviewer.database.SuStyle
import com.programmersbox.scrcpyviewer.database.TableInfo
import com.programmersbox.scrcpyviewer.database.accessFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalTime
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** The Data tab's sub-views. */
enum class DbView { Browse, Query, Structure }

/** What the pull/write line in the Data tab's bar says. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Pulling(val via: String) : SyncStatus
    data class Pulled(val at: LocalTime) : SyncStatus
    data object Stopping : SyncStatus
    data object Writing : SyncStatus
    data class Written(val at: LocalTime) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** The write-back dialog: the first confirm, or the warning that the app changed the database since it was pulled. */
sealed interface WriteDialog {
    data class Confirm(val appLabel: String, val running: Boolean, val count: Int) : WriteDialog
    data class Conflict(val appLabel: String) : WriteDialog
}

/** A table's schema for the Structure view. */
data class TableStructure(val ddl: String, val indexes: List<String>, val path: String, val journalMode: String, val via: String)

/**
 * The Data tab of one mirror window, shared by the sheet and the own window. Pulls an app's database to a local copy
 * under [tempRoot], keeps every edit there (staged) until [confirmWrite] copies it back. Device and SQLite work runs
 * on [io], one step at a time; snapshot state is written only on [scope].
 */
@Stable
class DatabaseController(
    private val devices: DeviceDatabases,
    private val probe: RootProbe,
    /** The device's apps for the app picker; null until the Apps tab has listed them. */
    val apps: () -> List<DeviceApp>?,
    private val tempRoot: Path,
    parentScope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val launch: (DeviceApp) -> Unit,
    private val clock: () -> LocalTime = LocalTime::now,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /**
     * A child of the window's scope that one failure can't cancel: an unexpected error fails the sync line instead
     * of taking down the Apps, Logcat and Shell work that shares the window's scope.
     */
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e ->
                local?.reopen()
                sync = SyncStatus.Failed(e.message ?: e.toString())
            },
    )

    init {
        // A previous run that quit without closing left its copies of apps' private data here.
        sweep()
    }

    var root by mutableStateOf(RootState.Unknown)
        private set
    private var su: SuStyle? = null

    var app by mutableStateOf<DeviceApp?>(null)
        private set
    var access by mutableStateOf<AppDataAccess?>(null)
        private set
    var databases by mutableStateOf<List<DeviceDatabase>>(emptyList())
        private set
    var db by mutableStateOf<String?>(null)
        private set
    var tables by mutableStateOf<List<TableInfo>>(emptyList())
        private set
    var table by mutableStateOf<String?>(null)
        private set
    var view by mutableStateOf(DbView.Browse)
        private set
    var sync by mutableStateOf<SyncStatus>(SyncStatus.Idle)
        private set
    var staged by mutableIntStateOf(0)
        private set
    var dialog by mutableStateOf<WriteDialog?>(null)
        private set

    /** The Browse page of [table]; null before a database is open. */
    var page by mutableStateOf<Page?>(null)
        private set

    /** SQLite's error for the current WHERE filter, shown in place of the rows. */
    var pageError by mutableStateOf<String?>(null)
        private set
    var where by mutableStateOf("")
        private set

    /** Cells changed since the pull, by row key and column name, for the edited tint. */
    var edited by mutableStateOf<Set<Pair<RowKey, String>>>(emptySet())
        private set

    /** The Query view's SQL; kept here so it survives moving between the sheet and the own window. */
    var queryText by mutableStateOf("")

    var queryOutcome by mutableStateOf<SqlOutcome?>(null)
        private set
    var structure by mutableStateOf<TableStructure?>(null)
        private set

    /** The staged SQL, for Review SQL. */
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    /** True while the panel is in its own window; the sheet's tab then shows a card. */
    var popped by mutableStateOf(false)
        private set

    /** Increments to ask the own window to come to the front. */
    var raiseRequests by mutableIntStateOf(0)
        private set

    /** Runs after every [dock]; the sheet sets it to show the Data tab again. */
    internal var onDock: () -> Unit = {}

    val busy: Boolean get() = sync is SyncStatus.Pulling || sync == SyncStatus.Stopping || sync == SyncStatus.Writing

    private var local: LocalDatabase? = null
    private var pulledFingerprint: String? = null
    private var relaunchAfter = false
    private var appRunning = false

    /** Edits launched but not yet run: they count as staged for the guard, so a quick switch can't strand one. */
    private var pendingEdits = 0
    private val lock = Mutex()

    /** Detects root once, silently. */
    fun ensureRoot() {
        if (root != RootState.Unknown) return
        scope.launch { detectRoot() }
    }

    /** Runs `su` (the phone may prompt). On success, reopens the current app through root if it was locked. */
    fun askRoot() {
        if (root != RootState.Available && root != RootState.Denied) return
        scope.launch {
            askRootNow()
            val current = app
            if (current != null && access == null && root == RootState.Rooted) open(current)
        }
    }

    /** Opens [target]'s databases: lists them through its access route and pulls the first. */
    fun pick(target: DeviceApp) {
        if (target.packageName == app?.packageName && db != null) return
        if (blockedByStaged()) return
        scope.launch { open(target) }
    }

    fun selectDb(name: String) {
        if (name == db || blockedByStaged()) return
        scope.launch { pullDb(name) }
    }

    fun selectTable(name: String) {
        if (name == table) return
        if (blockedByStaged()) return
        table = name
        where = ""
        queryOutcome = null
        scope.launch { reloadTable() }
    }

    fun selectView(v: DbView) {
        view = v
    }

    fun applyWhere(text: String) {
        where = text
        scope.launch { reloadTable() }
    }

    /** Pulls the current database again; refused while changes are staged. */
    fun refresh() {
        val name = db ?: return
        if (blockedByStaged()) return
        scope.launch { pullDb(name) }
    }

    /** Sets one Browse cell, by row and column index in [page]. */
    fun setCell(row: Int, column: Int, value: SqlValue) {
        val p = page ?: return
        val key = p.rows.getOrNull(row)?.key ?: return
        val name = p.columns[column].name
        val t = table ?: return
        edit({ db -> db.setCell(t, key, name, value) }) { edited = edited + (key to name) }
    }

    fun insertRow() {
        val t = table ?: return
        edit({ db -> db.insertRow(t) })
    }

    fun deleteRow(row: Int) {
        val key = page?.rows?.getOrNull(row)?.key ?: return
        val t = table ?: return
        edit({ db -> db.deleteRow(t, key) })
    }

    fun runQuery(sql: String) {
        if (busy) return
        scope.launch {
            val db = local ?: return@launch
            val outcome = withDb { db.execute(sql) }
            queryOutcome = outcome
            afterChange()
        }
    }

    fun discard() {
        if (busy) return
        scope.launch {
            val db = local ?: return@launch
            withDb { db.discard() }
            edited = emptySet()
            afterChange()
        }
    }

    /** Opens the write dialog, which says whether the app is running and will be stopped. */
    fun requestWrite() {
        val target = app ?: return
        if (staged == 0 || busy) return
        scope.launch {
            appRunning = try {
                withContext(io) { devices.isRunning(target.packageName) }
            } catch (_: IOException) {
                false
            }
            dialog = WriteDialog.Confirm(target.label, appRunning, staged)
        }
    }

    /** Stops the app when it's running, checks the database hasn't changed on the device, then writes. */
    fun confirmWrite(relaunch: Boolean) {
        val target = app ?: return
        val route = access ?: return
        val name = db ?: return
        dialog = null
        relaunchAfter = relaunch && appRunning
        scope.launch {
            try {
                if (appRunning) {
                    sync = SyncStatus.Stopping
                    withContext(io) { devices.forceStop(target.packageName) }
                }
                val now = withContext(io) { devices.fingerprint(route, name) }
                if (now != pulledFingerprint) {
                    sync = SyncStatus.Idle
                    dialog = WriteDialog.Conflict(target.label)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sync = SyncStatus.Failed(e.message ?: "Couldn't reach the device")
                return@launch
            }
            write(target, route, name, stopped = appRunning)
        }
    }

    /** Writes over the device's newer copy. */
    fun writeAnyway() {
        val target = app ?: return
        val route = access ?: return
        val name = db ?: return
        dialog = null
        scope.launch { write(target, route, name, stopped = false) }
    }

    /** Drops the staged changes and pulls the device's copy again. */
    fun pullAgain() {
        val name = db ?: return
        dialog = null
        scope.launch { pullDb(name) }
    }

    fun dismissDialog() {
        dialog = null
    }

    fun popOut() {
        popped = true
    }

    fun dock() {
        if (!popped) return
        popped = false
        onDock()
    }

    fun raise() {
        raiseRequests++
    }

    /**
     * Closes the copy and deletes this window's temp files at once: called while the window's scope is being
     * cancelled, so nothing launched here would run.
     */
    fun close() {
        val db = local
        local = null
        runCatching { db?.close() }
        sweep()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun sweep() {
        runCatching { tempRoot.deleteRecursively() }
    }

    private fun blockedByStaged(): Boolean {
        if (staged == 0 && pendingEdits == 0) return false
        notify(STAGED_GUARD)
        return true
    }

    private suspend fun detectRoot() {
        root = try {
            withContext(io) { probe.detect() }
        } catch (_: IOException) {
            RootState.None
        }
    }

    private suspend fun askRootNow() {
        root = RootState.Asking
        val (state, style) = try {
            withContext(io) { probe.ask() }
        } catch (_: IOException) {
            RootState.Denied to null
        }
        root = state
        su = style
    }

    private suspend fun open(target: DeviceApp) {
        closeLocal()
        app = target
        databases = emptyList()
        db = null
        tables = emptyList()
        table = null
        page = null
        if (root == RootState.Unknown) detectRoot()
        if (!target.debuggable && root == RootState.Available) askRootNow()
        val route = accessFor(target, root, su)
        access = route
        if (route == null) {
            sync = SyncStatus.Idle
            return
        }
        sync = SyncStatus.Pulling(route.label)
        try {
            databases = withContext(io) { devices.list(route) }
        } catch (e: IOException) {
            sync = SyncStatus.Failed(e.message ?: "Couldn't list the databases")
            return
        }
        val first = databases.firstOrNull()
        if (first == null) sync = SyncStatus.Idle else pullDb(first.name)
    }

    private suspend fun pullDb(name: String) {
        val target = app ?: return
        val route = access ?: return
        val entry = databases.firstOrNull { it.name == name } ?: return
        closeLocal()
        db = name
        sync = SyncStatus.Pulling(route.label)
        try {
            val dir = tempRoot.resolve(target.packageName).resolve(name)
            // The app may write while the files stream: a copy is kept only when the database looked the same before
            // and after it. Otherwise the copy may be torn, and writing it back would corrupt the app's data.
            var pulled: Path? = null
            for (attempt in 1..PULL_ATTEMPTS) {
                val before = withContext(io) { devices.fingerprint(route, name) }
                val file = withContext(io) { devices.pull(route, entry, dir.resolve("pulled")) }
                val after = withContext(io) { devices.fingerprint(route, name) }
                if (before == after) {
                    pulledFingerprint = after
                    pulled = file
                    break
                }
            }
            if (pulled == null) {
                sync = SyncStatus.Failed("${target.label} is writing to $name. Stop the app, then pull again.")
                return
            }
            val opened = withContext(io) { LocalDatabase.open(pulled, dir.resolve("work")) }
            local = opened
            val list = withDb { opened.tables() }
            tables = list
            table = list.firstOrNull { !it.system }?.name ?: list.firstOrNull()?.name
            queryText = table?.let { "SELECT *\nFROM $it\nLIMIT 100;" }.orEmpty()
            where = ""
            queryOutcome = null
            edited = emptySet()
            afterChange()
            sync = SyncStatus.Pulled(clock())
        } catch (e: IOException) {
            sync = SyncStatus.Failed(e.message ?: "Couldn't pull $name")
        } catch (e: SQLException) {
            sync = SyncStatus.Failed(e.message ?: "$name isn't a readable SQLite database")
        }
    }

    /** Writes the copy back. Unless the app was [stopped] just now, it is checked again: it may have started since. */
    private suspend fun write(target: DeviceApp, route: AppDataAccess, name: String, stopped: Boolean) {
        val db = local ?: return
        sync = SyncStatus.Writing
        try {
            if (!stopped && withContext(io) { devices.isRunning(target.packageName) }) {
                withContext(io) { devices.forceStop(target.packageName) }
            }
            val file = withDb { db.prepareForWrite() }
            withContext(io) { devices.write(route, name, file) }
            withDb { db.markWritten() }
            pulledFingerprint = withContext(io) { devices.fingerprint(route, name) }
            edited = emptySet()
            afterChange()
            sync = SyncStatus.Written(clock())
            if (relaunchAfter) launch(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The device copy may be partly written; the local copy and its staged changes are kept for another try.
            withDb { db.reopen() }
            sync = SyncStatus.Failed(e.message ?: "Couldn't write $name")
        }
    }

    /** Runs [change] on the copy (not while pulling or writing), then [after] on [scope] when it worked. */
    private fun edit(change: (LocalDatabase) -> Unit, after: () -> Unit = {}) {
        if (busy) return
        val db = local ?: return
        pendingEdits++
        scope.launch {
            try {
                withDb { change(db) }
                after()
            } catch (e: SQLException) {
                notify(e.message ?: "SQLite refused the change")
            } catch (e: IllegalArgumentException) {
                notify(e.message ?: "That cell can't be edited")
            } finally {
                pendingEdits--
            }
            afterChange()
        }
    }

    /** Re-reads the staged count, table list, page and structure after anything that may have changed them. */
    private suspend fun afterChange() {
        val db = local ?: return
        try {
            val list = withDb { db.tables() }
            tables = list
            log = withDb { db.log }
            staged = log.size
        } catch (e: SQLException) {
            notify(e.message ?: "Couldn't read the database")
            return
        }
        reloadTable()
    }

    private suspend fun reloadTable() {
        val db = local ?: return
        val name = table ?: return
        try {
            page = withDb { db.page(name, where.ifBlank { null }) }
            pageError = null
        } catch (e: SQLException) {
            pageError = e.message ?: "Bad filter"
        }
        val route = access
        structure = try {
            withDb {
                TableStructure(db.ddl(name), db.indexes(name), "/data/data/${app?.packageName}/databases/${this.db}", db.journalMode(), route?.label.orEmpty())
            }
        } catch (_: SQLException) {
            null
        }
    }

    private suspend fun closeLocal() {
        val old = local ?: return
        local = null
        withDb { old.close() }
    }

    private suspend fun <T> withDb(block: suspend () -> T): T = withContext(io) { lock.withLock { block() } }

    companion object {
        const val STAGED_GUARD = "Write or discard staged changes first"
        private const val PULL_ATTEMPTS = 3
    }
}
