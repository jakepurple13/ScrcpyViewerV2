# Database Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Data" tab to the mirror window's quick actions sheet. It browses, edits, queries and writes back an app's SQLite databases: through `run-as` for debuggable apps, and through `su` / adb root on rooted phones. It can pop out into its own window. The Apps tab gains a Debuggable filter, a DEBUG badge, a marquee "Data access" box and a Databases button.

**Architecture:**
- A device layer (`database/`) wraps commands for the access route (run-as / su / adb root). It lists, pulls, fingerprints and writes database files with the existing `AdbClient` / `ShellRunner`.
- A local layer opens the pulled copy with sqlite-jdbc. Every edit runs on `work.db` and is logged. Discard restores `pristine.db`.
- A per-window `DatabaseController`, built in `remember` like `LogcatController`, holds the UI state. The sheet tab and the pop-out window both render it.

**Tech Stack:** Kotlin 2.3, Compose Multiplatform 1.12 desktop, Material 3, sqlite-jdbc 3.47.1.0, kotlinx-coroutines-test, `ImageComposeScene` tests.

**Spec:** `docs/superpowers/specs/2026-11-25-database-viewer-design.md` (with the mockup linked in it).

Paths: `M` = `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/`, `T` = `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/`. Test command: `./gradlew :composeApp:jvmTest --tests '<FQN>'`.

## Global Constraints

- **Repo rules:** follow `CLAUDE.md`.
  - Check every new UI at 360, 520, 700 and 1000 dp.
  - Size and position changes use `layoutSpring()`.
  - Read reduce motion from `LocalReduceMotion.current`.
  - Clamp animated sizes with `coerceAtLeast(0)`.
  - No `Cursor.MOVE_CURSOR`.
  - Tests pass `motion` or `LocalReduceMotion` explicitly.
- **Tab order:** `enum class SheetTab { Actions, Apps, Files, Database, Shell, Links, Logcat }`. The segment label for `Database` is "Data"; every other label stays `name`.
- **Wrapping and quoting:** every device command for app data goes through `AppDataAccess.wrap(inner)`. Every file name goes through `shellQuote` (`M/files/DevicePath.kt:15`).
- **Root detection must not run `su`.** Only `askRoot()` runs it.
- **Default sheet width:** `QUICK_ACTIONS_DATABASE_WIDTH = 620.dp`, saved as `AppSettings.databaseWidth: Int = 620`.
- **Window sizes:** `DATABASE_WINDOW_SIZE = DpSize(900.dp, 640.dp)`, `DatabaseWindowMinSize = DpSize(360.dp, 360.dp)`. Add "database 360×360" to the CLAUDE.md min-size line.
- **Browse page size:** 500 rows.
- **Copy (exact strings):**
  - Staged-change guard toast: "Write or discard staged changes first".
  - Locked title: "<Label>'s databases are private".
  - Popped-out card: "Databases is open in its own window".

## Review Focus

1. **Odd database names:** a name with spaces, quotes or unicode (`my "notes" 's.db`) must list, pull and write. Pinned in Task 3.
2. **App writes during an edit:** if the app writes to its database between pull and write, the user must see the conflict dialog, not silently lose the app's data. Pinned in Task 5.
3. **Big tables:** a table with 120 000 rows must load the first 500 and say "500 of 120,000", not freeze. Pinned in Task 1.
4. **WITHOUT ROWID tables, views and BLOBs:** edits must key by primary key. Views and BLOB cells must be read-only, not throw. Pinned in Task 1.
5. **Disconnect mid-write:** if the device disconnects mid-write, staged edits and `work.db` must survive and the sync status must show the failure. Pinned in Task 5.

---

### Task 1: Local SQLite copy (`LocalDatabase`)

**Files:**
- Modify: `gradle/libs.versions.toml`: add version `sqlite-jdbc = "3.47.1.0"` and library `sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite-jdbc" }`.
- Modify: `composeApp/build.gradle.kts:133-160`: add `implementation(libs.sqlite.jdbc)`. At `:212`, add `"java.sql"` to `modules(...)`.
- Create: `M/database/LocalDatabase.kt`, `M/database/SqlValue.kt`
- Test: `T/database/LocalDatabaseTest.kt`

**Interfaces (produces):**
```kotlin
sealed interface SqlValue { data object Null; data class Integer(val v: Long); data class Real(val v: Double); data class Text(val v: String); data class Blob(val size: Int) }
fun sqlLiteral(v: SqlValue): String              // NULL, 42, 1.5, 'it''s'; Blob -> error
sealed interface RowKey { data class Rowid(val id: Long); data class Pk(val values: Map<String, SqlValue>) }
data class TableInfo(val name: String, val rows: Long, val system: Boolean, val view: Boolean, val withoutRowid: Boolean)
data class ColumnInfo(val name: String, val type: String, val pk: Int, val notNull: Boolean)
data class Row(val key: RowKey?, val values: List<SqlValue>)        // key null for views
data class Page(val columns: List<ColumnInfo>, val rows: List<Row>, val total: Long, val editable: Boolean)
sealed interface SqlOutcome { data class Rows(val columns: List<String>, val rows: List<List<SqlValue>>, val millis: Long); data class Changed(val count: Int, val millis: Long); data class Failed(val message: String) }
class LocalDatabase private constructor(...) : AutoCloseable {
  companion object { fun open(pulled: Path, workDir: Path): LocalDatabase }  // checkpoints pulled, copies to workDir/work.db + pristine.db
  fun tables(): List<TableInfo>          // system = sqlite_% | android_metadata | room_master_table
  fun page(table: String, where: String? = null, limit: Int = 500, offset: Long = 0): Page
  fun setCell(table: String, key: RowKey, column: String, value: SqlValue)
  fun insertRow(table: String): RowKey   // INSERT DEFAULT VALUES
  fun deleteRow(table: String, key: RowKey)
  fun execute(sql: String): SqlOutcome   // non-query statements are logged
  fun ddl(table: String): String; fun indexes(table: String): List<String>; fun journalMode(): String
  val log: List<String>                  // staged SQL in run order, literals inlined
  fun discard()                          // pristine -> work, log cleared, connection reopened
  fun prepareForWrite(): Path            // wal_checkpoint(TRUNCATE), close; returns work.db
  fun markWritten()                      // work -> pristine, log cleared, reopen
}
```

- [ ] **Step 1: Write failing tests.** Build fixtures in a `@TempDir` with plain JDBC.
  - `opensWalCopyAndSeesUncheckpointedRows`: create a WAL database, insert a row while a second connection holds the WAL open, copy `x.db`, `x.db-wal` and `x.db-shm`, then open. `tables()` shows the table with `rows == 1`.
  - `pageLimitsAndCountsTotal`: insert 120 000 rows. Then `page("t").rows.size == 500` and `.total == 120_000L`.
  - `setCellLogsUpdateAndDiscardRestores`:
    - `setCell("notes", Rowid(3), "folder_id", Integer(1))`.
    - Then `log == listOf("UPDATE \"notes\" SET \"folder_id\" = 1 WHERE rowid = 3;")`.
    - Then `discard()`; the row reads its old value and `log` is empty.
  - `withoutRowidKeysByPrimaryKey`: a `WITHOUT ROWID` table's page rows have `RowKey.Pk`. `setCell` writes `WHERE "a" = 1 AND "b" = 'x'`.
  - `viewsAndBlobsAreReadOnly`:
    - `page("v").editable == false`.
    - `setCell` on a Blob column throws `IllegalArgumentException("BLOB cells can't be edited")`.
  - `executeSelectReturnsRowsAndUpdateIsLogged`: a SELECT gives `Rows` and leaves `log` empty. An `UPDATE … WHERE pinned = 1` affecting 3 rows gives `Changed(3, _)`, and `log.last()` is the statement text.
  - `executeErrorIsFailedNotThrown`: `execute("SELECT * FROM nope")` returns `Failed` with a message containing "no such table: nope".
  - `prepareForWriteLeavesNoWal`: after `prepareForWrite()`, `work.db-wal` doesn't exist or has size 0. The header's journal mode is still WAL (`journalMode() == "wal"` after reopen).
  - `quotesOddIdentifiers`: a table named `my "t"` round-trips through `page` and `setCell`.
- [ ] **Step 2: Run them.** `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.database.LocalDatabaseTest'`. Expected: FAIL (unresolved `LocalDatabase`).
- [ ] **Step 3: Add the dependency and `java.sql`, then implement `LocalDatabase`.**
  - Use one `java.sql.Connection` (`jdbc:sqlite:<work.db>`).
  - Quote identifiers by doubling `"`.
  - Run `execute` through `Statement.execute`: if it returns true it's `Rows` (cap at 1 000 rows); otherwise `Changed(updateCount)` and log the trimmed statement with a trailing `;`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): local SQLite copy with staged edit log"`

### Task 2: Access routes and root detection

**Files:**
- Create: `M/database/AppDataAccess.kt`, `M/database/RootProbe.kt`
- Test: `T/database/AppDataAccessTest.kt`, `T/database/RootProbeTest.kt`

**Interfaces:**
- Consumes: `ShellRunner.run(cmd, timeoutMs): ShellResult` (`M/adb/ShellRunner.kt:33`), `shellQuote`.
- Produces:
```kotlin
enum class RootState { Unknown, None, Available, Asking, Rooted, AdbRoot, Denied }
enum class SuStyle { DashC, Zero }
sealed interface AppDataAccess { val pkg: String; val label: String; fun wrap(inner: String): String
  data class RunAs(override val pkg: String) /* label "run-as" */
  data class Su(override val pkg: String, val style: SuStyle) /* label "root · su" */
  data class AdbRoot(override val pkg: String) /* label "adb root" */ }
fun accessFor(app: DeviceApp, root: RootState, su: SuStyle?): AppDataAccess?   // debuggable -> RunAs; AdbRoot -> AdbRoot; Rooted+su -> Su; else null
open class RootProbe(private val runner: ShellRunner) {
  open suspend fun detect(): RootState            // id -u == 0 -> AdbRoot; `command -v su` non-empty -> Available; else None. Never runs su.
  open suspend fun ask(): Pair<RootState, SuStyle?> // su -c 'id -u' then su 0 sh -c 'id -u'; "0" -> Rooted+style; else Denied+null. Timeout 20_000 ms.
}
```
- [ ] **Step 1: Write failing tests.**
  - `wrapRunAs`: `RunAs("com.x").wrap("cat databases/'a b.db'") == "run-as com.x sh -c 'cat databases/'\\''a b.db'\\'''"`. The expected string is whatever `shellQuote` gives for the inner command; assert against `"run-as com.x sh -c " + shellQuote(inner)`.
  - `wrapSuDashC == "su -c " + shellQuote("cd /data/data/com.x && " + inner)`.
  - `wrapSuZero == "su 0 sh -c " + shellQuote("cd /data/data/com.x && " + inner)`.
  - `wrapAdbRoot == "cd /data/data/com.x && " + inner`.
  - `accessForPicksRunAsBeforeRoot`: a debuggable app on a rooted phone gets `RunAs`. A release app with `RootState.None` gets `null`.
  - `detectNeverRunsSu`: a fake runner answers `id -u` → `2000` and `command -v su` → `/system/bin/su`. Then `detect() == Available`, and none of the recorded commands starts with `su`.
  - `detectAdbRoot`: `id -u` → `0` gives `AdbRoot`.
  - `askFallsBackToZeroStyle`: the first command fails (exit 1); `su 0 sh -c 'id -u'` → `0`. Then `ask() == Rooted to SuStyle.Zero`.
  - `askDenied`: both fail, so `Denied to null`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** The fake runner is a `ShellRunner` subclass overriding `run` (pattern: `MatrixRunner`, `T/ui/NarrowWidthsTest.kt:212`).
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): run-as/su/adb-root access and root probe"`

### Task 3: Device database files (`DeviceDatabases`)

**Files:**
- Create: `M/database/DeviceDatabases.kt`
- Test: `T/database/DeviceDatabasesTest.kt`

**Interfaces:**
- Consumes: Task 2 `AppDataAccess`, `AdbClient.openExecStream` (`M/adb/AdbClient.kt:103`), `AdbClient.exec` (`:176`), `ShellRunner.run`.
- Produces:
```kotlin
data class DeviceDatabase(val name: String, val size: Long, val walSize: Long?, val hasShm: Boolean)
open class DeviceDatabases(private val adb: AdbClient, private val runner: ShellRunner, private val serial: String) {
  open suspend fun list(access: AppDataAccess): List<DeviceDatabase>        // stat -c '%n|%s|%F' databases/* 2>/dev/null; regular files only; companions folded
  open suspend fun pull(access: AppDataAccess, db: DeviceDatabase, into: Path): Path   // writes into/<name>[,-wal,-shm]; returns into/<name>; IO dispatcher
  open suspend fun fingerprint(access: AppDataAccess, db: String): String   // stat -c '%s:%Y' on db and -wal, joined; missing files skipped
  open suspend fun isRunning(pkg: String): Boolean                          // `pidof <pkg>` output non-blank
  open suspend fun forceStop(pkg: String)
  open suspend fun write(access: AppDataAccess, db: String, local: Path)    // push to /data/local/tmp/scrcpyvwr-<uuid>.db, cat > target, rm -f -wal -shm, rm tmp (rm tmp in finally)
}
```
- [ ] **Step 1: Write failing tests.** Use a fake `AdbClient` subclass recording `openExecStream` commands (returning `ByteArrayInputStream`s keyed by command) and `exec` args. Use a fake `ShellRunner` with scripted answers.
  - `listFoldsCompanions`:
    - Input: `"databases/notes.db|151552|regular file\ndatabases/notes.db-wal|32768|regular file\ndatabases/notes.db-shm|32768|regular file\ndatabases/x.db-journal|0|regular file\ndatabases/x.db|4096|regular file\ndatabases/sub|4096|directory"`.
    - Expected: `[DeviceDatabase("notes.db",151552,32768,true), DeviceDatabase("x.db",4096,null,false)]`.
  - `listEmptyWhenNoFolder`: blank output gives an empty list, not an exception.
  - `pullStreamsDbAndCompanions`: the three files land with their exact bytes. Each stream command equals `access.wrap("cat " + shellQuote("databases/notes.db"))` (and `-wal`, `-shm`).
  - `oddNamesAreQuoted`: `my "notes" 's.db` lists, then pulls with `shellQuote` applied.
  - `writeSequence`: the recorded calls, in order:
    1. `exec("-s", serial, "push", <local>, "/data/local/tmp/scrcpyvwr-…db")`
    2. `run(access.wrap("cat '/data/local/tmp/…' > 'databases/notes.db'"))`
    3. `run(access.wrap("rm -f 'databases/notes.db-wal' 'databases/notes.db-shm'"))`
    4. `run("rm -f '/data/local/tmp/…'")`
  - `writeRemovesTempEvenWhenCatFails`: the cat exits 1, so `write` throws `IOException`, and the tmp `rm` still ran.
  - `isRunningReadsPidof`: `"12345\n"` gives true; `""` gives false.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** A non-zero `ShellResult.exitCode` throws `IOException(output)`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): list, pull and write app databases over adb"`

### Task 4: Debuggable apps

**Files:**
- Modify: `M/apps/DeviceApps.kt`:
  - `:10`: add `val debuggable: Boolean = false` as the last param of `DeviceApp`.
  - `:20`: `enum class AppFilter { All, User, System, Debuggable }`.
  - `:23`: `matching` handles `Debuggable`.
  - `:43-45`, `:159`: the grep becomes `'^  Package \\[|versionName=|versionCode=|pkgFlags='`; parse `DEBUGGABLE` inside `pkgFlags=[…]`.
- Test: `T/apps/DeviceAppsTest.kt`

- [ ] **Step 1: Write failing tests.**
  - `parsesDebuggableFromPkgFlags`: a dumpsys sample where package `com.example.notes` has `    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]` and `com.android.chrome` has `    pkgFlags=[ SYSTEM HAS_CODE ]`. Notes comes out `debuggable == true`, chrome `false`.
  - `matchingDebuggableFilter`: only debuggable apps are returned.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Fix every exhaustive `when (AppFilter)` the compiler reports.
- [ ] **Step 4: Run** `--tests 'com.programmersbox.scrcpyviewer.apps.*' --tests 'com.programmersbox.scrcpyviewer.ui.mirror.AppExplorerTest'`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(apps): read debuggable flag and add Debuggable filter"`

### Task 5: `DatabaseController`

**Files:**
- Create: `M/ui/mirror/database/DatabaseController.kt`
- Test: `T/ui/mirror/database/DatabaseControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces (Compose state backed by `mutableStateOf`, `@Stable`):
```kotlin
enum class DbView { Browse, Query, Structure }
sealed interface SyncStatus { data object Idle; data class Pulling(val via: String); data class Pulled(val at: LocalTime); data object Stopping; data object Writing; data class Written(val at: LocalTime); data class Failed(val message: String) }
sealed interface WriteDialog { data class Confirm(val appLabel: String, val running: Boolean, val count: Int); data class Conflict(val appLabel: String) }
@Stable class DatabaseController(
  private val devices: DeviceDatabases, private val probe: RootProbe, private val apps: () -> List<DeviceApp>?,
  private val tempRoot: Path, private val scope: CoroutineScope, private val notify: (String) -> Unit,
  private val launch: (DeviceApp) -> Unit, private val clock: () -> LocalTime = LocalTime::now,
) {
  val root: RootState; val app: DeviceApp?; val access: AppDataAccess?; val databases: List<DeviceDatabase>; val db: String?
  val tables: List<TableInfo>; val table: String?; val view: DbView; val sync: SyncStatus; val staged: Int; val dialog: WriteDialog?
  var popped: Boolean; val raiseRequests: Int; internal var onDock: () -> Unit
  val local: LocalDatabase?
  fun ensureRoot()                                  // detect once; Unknown -> result
  fun askRoot()                                     // Available|Denied -> Asking -> ask(); on Rooted re-pick current app
  fun pick(pkg: String)                             // guarded by staged; sets app, lists, picks first db, pulls
  fun selectDb(name: String); fun selectTable(name: String); fun selectView(v: DbView)
  fun edited()                                      // re-read staged from local.log
  fun discard(); fun refresh()                      // refresh guarded by staged
  fun requestWrite()                                // isRunning -> dialog Confirm(running)
  fun confirmWrite(relaunch: Boolean)               // stop if running; fingerprint check -> Conflict or write
  fun writeAnyway(); fun pullAgain(); fun dismissDialog()
  fun popOut(); fun dock(); fun raise(); fun close() // close deletes tempRoot/<pkg>
}
```
- [ ] **Step 1: Write failing tests** with fake `DeviceDatabases` / `RootProbe` subclasses and `runTest` + `StandardTestDispatcher`.
  - `pickDebuggablePullsFirstDb`: `pick("com.example.notes")` gives `access == RunAs`, then `sync` Pulling("run-as") then Pulled, `db == "notes.db"`, and `tables` non-empty.
  - `pickReleaseWithoutRootLeavesAccessNull`: `access == null` and nothing pulled.
  - `pickReleaseWhenAvailableAsksRoot`: root is `Available`, so `pick` calls `probe.ask()` once. After Rooted, it pulls with `Su`.
  - `stagedGuardBlocksSwitch`: after one `setCell` plus `edited()`, `staged == 1`. `pick(other)` doesn't change `app` and calls `notify("Write or discard staged changes first")`.
  - `writeNotRunningSkipsStop`: `isRunning` false, so `dialog == Confirm(_, running = false, 1)`. `confirmWrite(false)` never calls `forceStop`, then `sync` Written, `staged == 0`.
  - `writeRunningStopsAndRelaunches`: `forceStop` then `write` then `launch(app)` in that order.
  - `conflictWhenFingerprintChanged`:
    - The fingerprint at pull time is `"100:1"`; at write time it's `"120:2"`.
    - Then `dialog == Conflict` and `write` isn't called.
    - `writeAnyway()` then calls it.
  - `disconnectMidWriteKeepsEdits`: `write` throws `IOException("device offline")`. Then `sync == Failed("device offline")`, `staged == 1`, and `local.log` is unchanged.
  - `closeDeletesTemp`: the temp folder is gone after `close()`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Run blocking JDBC and device work on `Dispatchers.IO` inside `scope.launch`. Fingerprint at pull, compare after the stop.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): controller for pick, stage, write and pop-out state"`

### Task 6: Sheet plumbing, tab order, root chip

**Files:**
- `M/ui/mirror/QuickActions.kt`:
  - `:181`: the enum per Global Constraints, plus `fun SheetTab.label(): String`.
  - `QuickActions`: add `val database: DatabaseController? = null`. Set `database?.onDock = ::showDatabaseTab` in `init`.
  - `onEvent`: route `AppsEvent.ShowDatabases(app)` to `database.pick(app.packageName)`, then `raise()` if popped, else `select(SheetTab.Database)`.
  - Copy `database.root` into `AppsUiState.root`.
  - Add `databasePopped` to `QuickActionsUiState`.
  - Add `QuickActionEvent.AskRoot`.
- `rememberQuickActions` (`:828`): build `DatabaseController(DeviceDatabases(it, runner, serial), RootProbe(runner), { apps.listed }, dataDir.temp / "databases" / serial, …)`. Call `close()` in the dispose path.
- `M/ui/mirror/AppExplorer.kt:22,52`:
  - `AppsUiState`: add `root: RootState = RootState.Unknown`.
  - `AppsEvent`: add `data class ShowDatabases(val app: DeviceApp)`. `AppExplorer.onEvent` returns `Unit` for it, like `ShowInLogcat`.
- `M/ui/mirror/SheetWidths.kt`: add the `database` field, `get`/`with` branches, and `fromSettings(..., database: Int = 620)` as the last parameter.
- `M/state/AppSettings.kt:32-42`: `databaseWidth: Int = 620`. Save branch at `QuickActions.kt:879-886`.
- `M/ui/mirror/QuickActionsSheet.kt`:
  - `:126-141`: `QUICK_ACTIONS_DATABASE_WIDTH = 620.dp`.
  - `:382-387`: subtitle "Browse and edit this app's databases".
  - `:468`: icon `Icons.Filled.Storage`.
  - Segment labels and measuring (`:485-545`) use `label()`.
  - Popped marker (`:591-643`) for Database the same as Logcat.
  - Pass a `database` param through `rememberQuickActionsPanel` / `QuickActionsPanel` / `SheetContent`. The `when` branch renders a placeholder `Text("Data")` until Task 7.
  - Root chip beside "Quick actions": `RootChip(state: RootState, onAsk: () -> Unit)` in a new `M/ui/mirror/database/RootChip.kt`. Its text swaps with `AnimatedContent`; its colors animate; it has a tooltip per spec §2.
- `M/ui/mirror/MirrorWindow.kt:1212-1216`, `:1536`: pass `quickActions.database`. Trigger `ensureRoot()` when the sheet first opens on Apps or Data.
- Tests: update the `SheetTab.entries` loops in `T/ui/mirror/SheetTabsTest.kt` and `MirrorNarrowTest.kt` so expectations follow the new order. Create `T/ui/mirror/QuickActionsDatabaseTest.kt` and `T/ui/mirror/database/RootChipTest.kt`.

- [ ] **Step 1: Write failing tests.**
  - `tabOrder`: `SheetTab.entries.map { it.label() } == listOf("Actions","Apps","Files","Data","Shell","Links","Logcat")`.
  - `showDatabasesSelectsDataTab`: `ShowDatabases(notes)` makes `uiState.tab == SheetTab.Database` and gives the fake controller `pick("com.example.notes")`.
  - `showDatabasesRaisesWhenPopped`: `raiseRequests` goes up by 1 and the tab doesn't change.
  - `databaseWidthSavesToSettings`.
  - `RootChipTest`: for each `RootState`, the rendered text is the spec's (e.g. `Available` shows "Root available"). Clicking `Available` calls `onAsk`.
  - `SheetTabsTest`: all seven segments fit at `QUICK_ACTIONS_ACTIONS_WIDTH` in icon mode.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the plumbing listed above.**
- [ ] **Step 4: Run** `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.*'`. Expected: PASS. Then run the wide screenshot tests (`-Pscreenshots`) and compare: only the tab strip may differ.
- [ ] **Step 5: Commit.** `git commit -m "feat(sheet): Data tab slot, new tab order and root chip"`

### Task 7: Data tab UI

**Files:**
- Create in `M/ui/mirror/database/`:
  - `DatabaseTab.kt`: `@Composable fun DatabaseTab(controller: DatabaseController, sheetWidth: Dp, motion: Boolean, onShowDebuggableApps: () -> Unit, inWindow: Boolean = false)`. `onShowDebuggableApps` sends the new `QuickActionEvent.ShowDebuggableApps`, which docks the window if it's popped out, selects Apps and sets `AppFilter.Debuggable`. The tab has the top bar, `AdaptiveListDetail(listMinWidth = 160.dp, listPreferredWidth = 200.dp)`, and the locked state.
  - `TableGrid.kt`: `@Composable fun TableGrid(page: Page, edits: Set<Pair<Int,Int>>, deleted: Set<Int>, onEdit: (row: Int, col: Int, SqlValue) -> Unit, onToggleDelete: (Int) -> Unit, editable: Boolean)`. Sticky header, `LazyColumn` rows, double-click edit, Enter/Esc.
  - `QueryView.kt`: highlighting via `fun highlightSql(sql: String, colors: SqlColors): AnnotatedString`, ⌘↵ / Ctrl+↵.
  - `StructureView.kt`
  - `StagedBar.kt`
  - `WriteDialogs.kt`: renders `WriteDialog`.
- Modify: `QuickActionsSheet.kt`: the Database branch renders `DatabaseSheetTab(controller, sheetWidth, motion)`, which switches between `DatabaseTab` and `DatabasePoppedCard` (Task 9) with `AnimatedContent(controller.popped)`.
- Test: `T/ui/mirror/database/DatabaseTabTest.kt`, `DatabaseNarrowTest.kt`, and `T/ui/NarrowWidthsTest.kt` (new "Data - Write to device" entry).

- [ ] **Step 1: Write failing tests.** Use the controller with fakes from Task 5 over a real `LocalDatabase` fixture, with scenes built by `helpScene` / `SceneDriver` (`T/ui/settings/HelpDialogTestSupport.kt`).
  - `browseShowsRowsAndTotal`: text "500 of 120,000" is shown.
  - `doubleClickEditStagesAndShowsBar`: double-click a cell, type `1`, press Enter. "1 change staged in notes.db" appears and `controller.staged == 1`.
  - `escCancelsEdit`: `staged` stays 0.
  - `queryRunsWithShortcut`: typing a SELECT and pressing ⌘↵ shows "3 rows".
  - `lockedStateOffersDebuggableApps`: clicking "Show debuggable apps" selects the Apps tab with `AppFilter.Debuggable`.
  - `writeDialogSaysRunning`: when the app is running, the dialog shows "Notes is running" and "Stop app and write". When it isn't, it shows "Write".
  - `stagedBarAnimates`: with `motion = true`, the bar's offset is strictly between hidden and shown mid-way, then settles.
  - Narrow: at 360, 520, 700 and 1000 the primary action is shown and inside the window, and no text is under 40 px. At 360, picking a table pushes the detail, and Esc goes back.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Use the spec §5 motion:
  - `SelectionHighlight` for the table list.
  - Sub-tab content slides on its axis.
  - Rows rise in.
  - The staged bar on `layoutSpring()`.
  - Sync text through `AnimatedContent`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): Data tab with browse, query, structure and write-back"`

### Task 8: Apps tab additions

**Files:**
- Modify `M/ui/mirror/AppsTab.kt`:
  - `FilterRow` / `CompactFilter` (`:363-430`): the fourth chip, with a bug icon for `Debuggable`.
  - `AppCard` badges (`:595-615`): `DebugBadge(short = true)`, styled like `SystemBadge` (`:716`) in `tertiaryContainer` with the text "DEBUG".
  - `OptionsSheet` (`:880-887`): a third `InfoBox("Data access", …)` with a `marquee: Boolean` param. When true, the value `Text` uses `Modifier.basicMarquee()` and `maxLines = 1`. Its color and icon cross-fade when `state.root` changes.
  - `AppActions` (`:272`): add `showDatabases`.
  - `ActionButtons` (`:982-995`): Show in Logcat and Databases in a `FlowRow`, `maxItemsInEachRow = if (compact) 1 else 2`, with glide like the existing grid.
  - When locked, Databases is disabled and the reason text shows under it ("Release build. Android only shares app data for debug builds or on a rooted phone.").
  - When opening through root, the line "Release build. Opens through root." uses `colors.tertiary`.
  - A `RootState.Available` app is enabled: clicking it asks for root through the controller's `pick`.
- Test: `T/ui/mirror/AppsDatabaseTest.kt`, plus updates to `AppsNarrowTest`.

- [ ] **Step 1: Write failing tests.**
  - `debuggableChipFilters`: clicking "Debuggable" shows only the 3 debug cards.
  - `debugBadgeShown`.
  - `dataAccessTexts`: with `RootState.None`, a debug app shows "Debuggable · run-as" and a release app "Release build · locked". With `Rooted`, a release app shows "Through root (su)".
  - `dataAccessMarquees`: at 360 dp, the Data access value node's text is still the full string (not ellipsized). Two frames 500 ms apart give different pixel snapshots of that box (the marquee moves).
  - `databasesButtonDisabledWhenLocked`: clicking does nothing; the reason text is shown.
  - `databasesButtonSendsEvent`: emits `AppsEvent.ShowDatabases(notes)`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.Apps*'`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(apps): Debuggable filter, DEBUG badge, Data access and Databases button"`

### Task 9: Own window

**Files:**
- Create: `M/ui/mirror/database/DatabaseWindow.kt`, which mirrors `M/ui/mirror/logcat/LogcatWindow.kt`:
  - `@Composable fun DatabaseWindow(controller: DatabaseController, deviceName: String, theme: AppTheme)`.
  - `@Composable fun DatabaseWindowContent(controller: DatabaseController, motion: Boolean, shown: Boolean, onRaise: () -> Unit)`.
  - The title is "Databases · $deviceName". Closing the window calls `controller.dock()`. It uses the same fade-in and fade-out as Logcat.
- Create: `M/ui/mirror/database/DatabasePoppedCard.kt`, modelled on `LogcatPoppedCard.kt`, with the texts "Databases is open in its own window", "Show window" and "Move back here".
- Modify: `M/ui/mirror/MirrorWindow.kt:1254`: add `quickActions.database?.let { DatabaseWindow(it, model, theme) }`.
- Modify: `CLAUDE.md` (Desktop gotchas min-size line): add "database 360×360".
- Test: `T/ui/mirror/database/DatabaseWindowTest.kt`

- [ ] **Step 1: Write failing tests.**
  - `poppedCardShownInSheet`: after `popOut()`, the sheet tab shows the card text.
  - `moveBackDocksAndSelectsTab`: clicking it gives `popped == false` and the sheet on `SheetTab.Database`.
  - `windowContentRendersTab`: `DatabaseWindowContent(..., motion = false, shown = true)` shows the tables list and no Back arrow at 900 dp.
  - `windowNarrowAt360`: the primary action is visible.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(database): open Databases in its own window"`

### Task 10: Device check, packaged build, PR

- [ ] **Step 1: Run the full suite.** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 2: Check on the Samsung SM-A156U1 (R5CX12GDWPZ), held via cdm per memory.**
  - Install a debuggable APK with a Room database. Any debug build works; if none is at hand, build Android Studio's "Empty Views Activity" template with one Room entity.
  - Run the app. Confirm:
    1. The Debuggable filter lists it.
    2. The Databases button opens it.
    3. A cell edit, then Write to device, changes the value the app shows after relaunch.
    4. The root chip reads "Not rooted".
  - Confirm the `pkgFlags` grep output on the real device matches Task 4's sample. If it doesn't, fix the parser and add the real line as a test fixture.
- [ ] **Step 3: Check adb root** on an emulator (`google_apis` image, `adb root`): a release app such as Chrome lists `History` through "adb root".
- [ ] **Step 4: Build the packaged app.** Run `./gradlew :composeApp:packageReleaseDmg` (or the project's release task from README "Releasing"). Launch the packaged app and open a database, to confirm sqlite-jdbc loads its native library under the hardened runtime with `java.sql` present.
- [ ] **Step 5: Push and open the PR.** Push the branch and open a PR with the template body, ending with the Claude Code attribution line. Merge when CI is green, per memory.
