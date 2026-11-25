package com.programmersbox.scrcpyviewer.ui.mirror.database

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.AppDataAccess
import com.programmersbox.scrcpyviewer.database.DeviceDatabase
import com.programmersbox.scrcpyviewer.database.DeviceDatabases
import com.programmersbox.scrcpyviewer.database.RootProbe
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.database.SqlValue
import com.programmersbox.scrcpyviewer.database.SuStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalTime
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOTES = DeviceApp("com.example.notes", "Notes", false, "1", 1, debuggable = true)
private val CHROME = DeviceApp("com.android.chrome", "Chrome", true, "1", 1)
private val NO_ADB = object : AdbClient(adbPath = { error("no adb") }) {}

/** A device with one database built from SQL; records what the controller asked it to do. */
internal class FakeDevice(private val fixtureDir: Path, vararg schema: String) :
    DeviceDatabases(NO_ADB, ShellRunner(NO_ADB, "s"), "s") {
    val calls = mutableListOf<String>()
    var running = false
    var fingerprints = ArrayDeque(listOf("100:1"))
    var writeFails: IOException? = null
    private val fixture: Path = fixtureDir.resolve("fixture.db").also { f ->
        DriverManager.getConnection("jdbc:sqlite:$f").use { c -> c.createStatement().use { s -> schema.forEach(s::execute) } }
    }

    override suspend fun list(access: AppDataAccess): List<DeviceDatabase> {
        calls += "list ${access.label}"
        return listOf(DeviceDatabase("notes.db", 4096, null, false))
    }

    override suspend fun pull(access: AppDataAccess, db: DeviceDatabase, into: Path): Path {
        calls += "pull ${db.name}"
        Files.createDirectories(into)
        return Files.copy(fixture, into.resolve(db.name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun fingerprint(access: AppDataAccess, db: String): String =
        (if (fingerprints.size > 1) fingerprints.removeFirst() else fingerprints.first()).also { calls += "fingerprint" }

    override suspend fun isRunning(pkg: String) = running.also { calls += "pidof" }

    override suspend fun forceStop(pkg: String) {
        calls += "stop"
    }

    override suspend fun write(access: AppDataAccess, db: String, local: Path) {
        calls += "write"
        writeFails?.let { throw it }
    }
}

internal class FakeProbe(private val detected: RootState, private val asked: Pair<RootState, SuStyle?> = RootState.Denied to null) :
    RootProbe(ShellRunner(NO_ADB, "s")) {
    var asks = 0
    override suspend fun detect() = detected
    override suspend fun ask(): Pair<RootState, SuStyle?> {
        asks++
        return asked
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseControllerTest {
    private val temp: Path = Files.createTempDirectory("dbctl")
    private val toasts = mutableListOf<String>()
    private val launched = mutableListOf<String>()

    private fun TestScope.controller(device: FakeDevice, probe: FakeProbe = FakeProbe(RootState.None)): DatabaseController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DatabaseController(
            devices = device, probe = probe, apps = { listOf(NOTES, CHROME) }, tempRoot = temp.resolve("work"),
            scope = CoroutineScope(dispatcher), notify = { toasts += it }, launch = { launched += it.packageName; device.calls += "launch" },
            clock = { LocalTime.of(9, 21) }, io = dispatcher,
        )
    }

    private fun notesDevice() = FakeDevice(
        Files.createTempDirectory(temp, "fx"),
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, pinned INTEGER)",
        "INSERT INTO notes VALUES (1,'Grocery run',1),(2,'Release',0)",
        "CREATE TABLE android_metadata (locale TEXT)",
    )

    @Test
    fun pickDebuggablePullsFirstDb() = runTest {
        val device = notesDevice()
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        assertEquals(AppDataAccess.RunAs("com.example.notes"), c.access)
        assertEquals("notes.db", c.db)
        assertEquals(SyncStatus.Pulled(LocalTime.of(9, 21)), c.sync)
        assertEquals("notes", c.table)
        assertEquals(2, c.page?.rows?.size)
        assertEquals(listOf("list run-as", "pull notes.db", "fingerprint"), device.calls)
    }

    @Test
    fun pickReleaseWithoutRootLeavesAccessNull() = runTest {
        val device = notesDevice()
        val c = controller(device)
        c.pick(CHROME)
        advanceUntilIdle()
        assertEquals(CHROME, c.app)
        assertNull(c.access)
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun pickReleaseWhenAvailableAsksRoot() = runTest {
        val device = notesDevice()
        val probe = FakeProbe(RootState.Available, RootState.Rooted to SuStyle.DashC)
        val c = controller(device, probe)
        c.pick(CHROME)
        advanceUntilIdle()
        assertEquals(1, probe.asks)
        assertEquals(RootState.Rooted, c.root)
        assertEquals(AppDataAccess.Su("com.android.chrome", SuStyle.DashC), c.access)
        assertEquals("list root · su", device.calls.first())
    }

    @Test
    fun stagedGuardBlocksSwitch() = runTest {
        val c = controller(notesDevice())
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        assertEquals(1, c.staged)
        c.pick(CHROME)
        advanceUntilIdle()
        assertEquals(NOTES, c.app)
        assertEquals(listOf("Write or discard staged changes first"), toasts)
    }

    @Test
    fun discardClearsStaged() = runTest {
        val c = controller(notesDevice())
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 1, SqlValue.Text("Changed"))
        advanceUntilIdle()
        c.discard()
        advanceUntilIdle()
        assertEquals(0, c.staged)
        assertEquals(SqlValue.Text("Grocery run"), c.page!!.rows[0].values[1])
    }

    @Test
    fun writeNotRunningSkipsStop() = runTest {
        val device = notesDevice()
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        c.requestWrite()
        advanceUntilIdle()
        assertEquals(WriteDialog.Confirm("Notes", running = false, count = 1), c.dialog)
        c.confirmWrite(relaunch = false)
        advanceUntilIdle()
        assertFalse("stop" in device.calls)
        assertTrue("write" in device.calls)
        assertEquals(SyncStatus.Written(LocalTime.of(9, 21)), c.sync)
        assertEquals(0, c.staged)
        assertNull(c.dialog)
    }

    @Test
    fun writeRunningStopsAndRelaunches() = runTest {
        val device = notesDevice().apply { running = true }
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        c.requestWrite()
        advanceUntilIdle()
        assertEquals(WriteDialog.Confirm("Notes", running = true, count = 1), c.dialog)
        c.confirmWrite(relaunch = true)
        advanceUntilIdle()
        val tail = device.calls.dropWhile { it != "stop" }
        assertEquals(listOf("stop", "fingerprint", "write", "fingerprint", "launch"), tail)
        assertEquals(listOf("com.example.notes"), launched)
    }

    @Test
    fun conflictWhenFingerprintChanged() = runTest {
        val device = notesDevice().apply { fingerprints = ArrayDeque(listOf("100:1", "120:2")) }
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        c.requestWrite()
        advanceUntilIdle()
        c.confirmWrite(relaunch = false)
        advanceUntilIdle()
        assertEquals(WriteDialog.Conflict("Notes"), c.dialog)
        assertFalse("write" in device.calls)
        c.writeAnyway()
        advanceUntilIdle()
        assertTrue("write" in device.calls)
        assertEquals(0, c.staged)
    }

    @Test
    fun pullAgainDropsEdits() = runTest {
        val device = notesDevice().apply { fingerprints = ArrayDeque(listOf("100:1", "120:2")) }
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        c.requestWrite()
        advanceUntilIdle()
        c.confirmWrite(relaunch = false)
        advanceUntilIdle()
        c.pullAgain()
        advanceUntilIdle()
        assertNull(c.dialog)
        assertEquals(0, c.staged)
        assertEquals(2, device.calls.count { it == "pull notes.db" })
    }

    @Test
    fun disconnectMidWriteKeepsEdits() = runTest {
        val device = notesDevice().apply { writeFails = IOException("device offline") }
        val c = controller(device)
        c.pick(NOTES)
        advanceUntilIdle()
        c.setCell(0, 2, SqlValue.Integer(0))
        advanceUntilIdle()
        c.requestWrite()
        advanceUntilIdle()
        c.confirmWrite(relaunch = false)
        advanceUntilIdle()
        assertEquals(SyncStatus.Failed("device offline"), c.sync)
        assertEquals(1, c.staged)
        assertEquals(SqlValue.Integer(0), c.page!!.rows[0].values[2])
    }

    @Test
    fun runQueryStagesWrites() = runTest {
        val c = controller(notesDevice())
        c.pick(NOTES)
        advanceUntilIdle()
        c.runQuery("UPDATE notes SET pinned = 1")
        advanceUntilIdle()
        assertIs<com.programmersbox.scrcpyviewer.database.SqlOutcome.Changed>(c.queryOutcome)
        assertEquals(1, c.staged)
    }

    @Test
    fun badWhereShowsErrorNotCrash() = runTest {
        val c = controller(notesDevice())
        c.pick(NOTES)
        advanceUntilIdle()
        c.applyWhere("nope = 1")
        advanceUntilIdle()
        assertTrue(c.pageError!!.contains("no such column"), c.pageError)
    }

    @Test
    fun closeDeletesTemp() = runTest {
        val c = controller(notesDevice())
        c.pick(NOTES)
        advanceUntilIdle()
        assertTrue(temp.resolve("work").exists())
        c.close()
        advanceUntilIdle()
        assertFalse(temp.resolve("work").exists())
    }

    @Test
    fun popOutAndDock() = runTest {
        val c = controller(notesDevice())
        var docked = 0
        c.onDock = { docked++ }
        c.popOut()
        assertTrue(c.popped)
        c.raise()
        assertEquals(1, c.raiseRequests)
        c.dock()
        assertFalse(c.popped)
        assertEquals(1, docked)
    }
}
