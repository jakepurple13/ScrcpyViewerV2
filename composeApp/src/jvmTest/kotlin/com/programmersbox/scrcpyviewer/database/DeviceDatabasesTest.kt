package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.shellQuote
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StreamAdb(private val files: Map<String, ByteArray>) : AdbClient(adbPath = { error("no adb") }) {
    val streams = mutableListOf<String>()
    val execs = mutableListOf<List<String>>()
    var pushResult = ProcessResult(0, "", "")
    override fun openExecStream(serial: String, cmd: String, readTimeoutMs: Int): InputStream {
        streams += cmd
        return ByteArrayInputStream(files[cmd] ?: error("unexpected stream $cmd"))
    }
    override suspend fun exec(vararg args: String): ProcessResult {
        execs += args.toList()
        return pushResult
    }
}

private class RecordingRunner(private val answer: (String) -> ShellResult) :
    ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
    val commands = mutableListOf<String>()
    override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
        commands += cmd
        return answer(cmd)
    }
}

class DeviceDatabasesTest {
    private val access = AppDataAccess.RunAs("com.example.notes")
    private val tmp = Files.createTempDirectory("devdb")

    private fun cat(name: String) = access.wrap("cat " + shellQuote("databases/$name"))

    @Test
    fun listFoldsCompanions() = runBlocking {
        val output = listOf(
            "databases/notes.db|151552|regular file", "databases/notes.db-wal|32768|regular file",
            "databases/notes.db-shm|32768|regular file", "databases/x.db-journal|0|regular file",
            "databases/x.db|4096|regular file", "databases/sub|4096|directory",
        ).joinToString("\n")
        val runner = RecordingRunner { ShellResult(output, 0, 0) }
        val list = DeviceDatabases(StreamAdb(emptyMap()), runner, "serial").list(access)
        assertEquals(listOf(DeviceDatabase("notes.db", 151552, 32768, true), DeviceDatabase("x.db", 4096, null, false)), list)
        assertEquals(access.wrap("stat -c '%n|%s|%F' databases/* 2>/dev/null"), runner.commands.single())
    }

    @Test
    fun listEmptyWhenNoFolder() = runBlocking {
        val runner = RecordingRunner { ShellResult("", 1, 0) }
        assertEquals(emptyList(), DeviceDatabases(StreamAdb(emptyMap()), runner, "serial").list(access))
    }

    @Test
    fun pullStreamsDbAndCompanions() = runBlocking {
        val bytes = mapOf(cat("notes.db") to byteArrayOf(1, 2, 3), cat("notes.db-wal") to byteArrayOf(4), cat("notes.db-shm") to byteArrayOf(5, 6))
        val adb = StreamAdb(bytes)
        val db = DeviceDatabase("notes.db", 3, 1, true)
        val file = DeviceDatabases(adb, RecordingRunner { ShellResult("", 0, 0) }, "serial").pull(access, db, tmp)
        assertEquals(tmp.resolve("notes.db"), file)
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertContentEquals(byteArrayOf(4), tmp.resolve("notes.db-wal").readBytes())
        assertContentEquals(byteArrayOf(5, 6), tmp.resolve("notes.db-shm").readBytes())
        assertEquals(bytes.keys.toList(), adb.streams)
    }

    @Test
    fun oddNamesAreQuoted() = runBlocking {
        val name = "my \"notes\" 's.db"
        val runner = RecordingRunner { ShellResult("databases/$name|10|regular file", 0, 0) }
        val adb = StreamAdb(mapOf(cat(name) to byteArrayOf(9)))
        val devices = DeviceDatabases(adb, runner, "serial")
        val db = devices.list(access).single()
        assertEquals(name, db.name)
        assertContentEquals(byteArrayOf(9), devices.pull(access, db, tmp).readBytes())
    }

    @Test
    fun writeSequence() = runBlocking {
        val runner = RecordingRunner { ShellResult("", 0, 0) }
        val adb = StreamAdb(emptyMap())
        val local = Files.write(tmp.resolve("work.db"), byteArrayOf(1))
        DeviceDatabases(adb, runner, "serial") { "scrcpyvwr-t.db" }.write(access, "notes.db", local)
        assertEquals(listOf(listOf("-s", "serial", "push", local.toString(), "/data/local/tmp/scrcpyvwr-t.db")), adb.execs)
        assertEquals(
            listOf(
                // One command: the stale journals go first, so no old WAL is ever left beside the new file.
                access.wrap(
                    "rm -f 'databases/notes.db-wal' 'databases/notes.db-shm' 'databases/notes.db-journal' && " +
                        "cat '/data/local/tmp/scrcpyvwr-t.db' > 'databases/notes.db'",
                ),
                "rm -f '/data/local/tmp/scrcpyvwr-t.db'",
            ),
            runner.commands,
        )
    }

    @Test
    fun writeRemovesTempEvenWhenCatFails() = runBlocking {
        val runner = RecordingRunner { cmd -> if ("cat" in cmd) ShellResult("Permission denied", 1, 0) else ShellResult("", 0, 0) }
        val local = Files.write(tmp.resolve("work.db"), byteArrayOf(1))
        assertFailsWith<IOException> { DeviceDatabases(StreamAdb(emptyMap()), runner, "serial") { "t.db" }.write(access, "notes.db", local) }
        assertEquals("rm -f '/data/local/tmp/t.db'", runner.commands.last())
    }

    @Test
    fun writeFailsWhenPushFails() = runBlocking {
        val runner = RecordingRunner { ShellResult("", 0, 0) }
        val adb = StreamAdb(emptyMap()).apply { pushResult = ProcessResult(1, "", "device offline") }
        val local = Files.write(tmp.resolve("work.db"), byteArrayOf(1))
        val e = assertFailsWith<IOException> { DeviceDatabases(adb, runner, "serial") { "t.db" }.write(access, "notes.db", local) }
        assertEquals("device offline", e.message)
        assertTrue(runner.commands.none { "cat" in it })
    }

    @Test
    fun localNamesAreSafeOnEveryOs() {
        val local = DeviceDatabases.localFileName("my \"notes\" <1>:a|b?*\\/'s.db")
        assertTrue(local.none { it in "<>:\"/\\|?*" }, local)
        assertTrue(local.endsWith("'s.db"), local)
        assertEquals("notes.db", DeviceDatabases.localFileName("notes.db"))
    }

    @Test
    fun aPullCutShortFails() = runBlocking {
        val runner = RecordingRunner { cmd -> if ("stat -c %s" in cmd) ShellResult("10\n", 0, 0) else ShellResult("", 0, 0) }
        val adb = StreamAdb(mapOf(cat("notes.db") to byteArrayOf(1, 2, 3)))
        val e = assertFailsWith<IOException> {
            DeviceDatabases(adb, runner, "serial").pull(access, DeviceDatabase("notes.db", 10, null, false), tmp)
        }
        assertTrue("cut short" in e.message!!, e.message)
    }

    @Test
    fun isRunningReadsPidof() = runBlocking {
        assertTrue(DeviceDatabases(StreamAdb(emptyMap()), RecordingRunner { ShellResult("12345\n", 0, 0) }, "s").isRunning("com.x"))
        assertFalse(DeviceDatabases(StreamAdb(emptyMap()), RecordingRunner { ShellResult("", 1, 0) }, "s").isRunning("com.x"))
    }

    @Test
    fun fingerprintJoinsStatLines() = runBlocking {
        val runner = RecordingRunner { ShellResult("100:1\n20:2\n", 1, 0) }
        assertEquals("100:1|20:2", DeviceDatabases(StreamAdb(emptyMap()), runner, "s").fingerprint(access, "notes.db"))
        assertEquals(access.wrap("stat -c '%s:%Y' 'databases/notes.db' 'databases/notes.db-wal' 2>/dev/null"), runner.commands.single())
    }
}
