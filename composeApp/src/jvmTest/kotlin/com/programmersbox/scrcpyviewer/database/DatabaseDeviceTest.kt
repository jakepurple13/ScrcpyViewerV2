package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device round trip of an app database: write a fixture into a debuggable app's databases folder, list it, pull
 * it, edit the copy and write it back, then pull again. Excluded from the default `jvmTest` run. Run with
 * `./gradlew :composeApp:jvmTest -PdeviceTests --tests '*DatabaseDeviceTest' -PdeviceSerials=SERIAL
 * -PdebugPackage=PKG` (PKG: an installed debuggable app).
 */
class DatabaseDeviceTest {
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val adb = AdbClient(adbPath = { adbPath })
    private val serial: String = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first() ?: "R5CX12GDWPZ"
    private val pkg: String = System.getProperty("scrcpyviewer.debugPackage") ?: "com.azzahid.hof"
    private val runner = ShellRunner(adb, serial)
    private val access = AppDataAccess.RunAs(pkg)
    private val name = "scrcpyvwr test's.db"

    @Test
    fun `a database round-trips through run-as`() = runBlocking<Unit> {
        val devices = DeviceDatabases(adb, runner, serial)
        runner.run(access.wrap("mkdir -p databases"))
        val temp = Files.createTempDirectory("dbdevice")
        try {
            val fixture = temp.resolve("fixture.db")
            DriverManager.getConnection("jdbc:sqlite:$fixture").use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT)")
                    s.execute("INSERT INTO notes VALUES (1, 'from the computer')")
                }
            }
            devices.write(access, name, fixture)
            val listed = devices.list(access).single { it.name == name }
            val pulled = devices.pull(access, listed, temp.resolve("pulled"))
            val db = LocalDatabase.open(pulled, temp.resolve("work"))
            assertEquals(SqlValue.Text("from the computer"), db.page("notes").rows.single().values[1])
            db.setCell("notes", RowKey.Rowid(1), "title", SqlValue.Text("edited"))
            val fingerprint = devices.fingerprint(access, name)
            assertTrue(fingerprint.isNotEmpty(), "stat answers")
            devices.write(access, name, db.prepareForWrite())
            db.markWritten()
            db.close()
            val again = devices.pull(access, devices.list(access).single { it.name == name }, temp.resolve("again"))
            val check = LocalDatabase.open(again, temp.resolve("check"))
            assertEquals(SqlValue.Text("edited"), check.page("notes").rows.single().values[1])
            check.close()
            assertTrue(runner.run("ls /data/local/tmp").output.lines().none { it.startsWith("scrcpyvwr-") }, "the temp copy is gone")
        } finally {
            runner.run(access.wrap("rm -f ${com.programmersbox.scrcpyviewer.files.shellQuote("databases/$name")}"))
        }
    }

    @Test
    fun `root detection never prompts and reports adb root or none`() = runBlocking<Unit> {
        val state = RootProbe(runner).detect()
        assertTrue(state in setOf(RootState.None, RootState.Available, RootState.AdbRoot), state.toString())
    }

    @Test
    fun `the debuggable flag is read from dumpsys`() = runBlocking<Unit> {
        val out = adb.shell(serial, "dumpsys package packages | grep -E '^  Package \\[|versionName=|versionCode=|pkgFlags='")
        assertTrue(com.programmersbox.scrcpyviewer.apps.AppListParser.versions(out).getValue(pkg).debuggable, "$pkg is debuggable")
    }
}
