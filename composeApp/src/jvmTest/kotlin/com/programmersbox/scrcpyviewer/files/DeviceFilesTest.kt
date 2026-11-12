package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeRunner(var result: ShellResult = ShellResult("", 0, 0)) :
    ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
    val commands = mutableListOf<String>()
    val timeouts = mutableListOf<Long>()
    override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
        commands += cmd
        timeouts += timeoutMs
        return result
    }
}

class DeviceFilesTest {
    private val scans = mutableListOf<String>()
    private val runner = FakeRunner()
    private val files = DeviceFiles(runner) { scans += it }
    private val tricky = "it's a"

    @Test
    fun mkdir() = runBlocking<Unit> {
        files.mkdir("/sdcard/Download", tricky)
        assertEquals(listOf("mkdir '/sdcard/Download/it'\\''s a'"), runner.commands)
        assertEquals(listOf("/sdcard/Download"), scans)
    }

    @Test
    fun rename() = runBlocking<Unit> {
        files.rename("/sdcard/Download", tricky, "b c")
        assertEquals(
            listOf("mv -n '/sdcard/Download/it'\\''s a' '/sdcard/Download/b c'"),
            runner.commands,
        )
        assertEquals(listOf("/sdcard/Download"), scans)
    }

    @Test
    fun moveWithRenames() = runBlocking<Unit> {
        files.move("/sdcard/A", listOf(tricky, "x"), "/sdcard/B", mapOf("x" to "x (1)"))
        assertEquals(
            listOf("mv '/sdcard/A/it'\\''s a' '/sdcard/B/it'\\''s a' && mv '/sdcard/A/x' '/sdcard/B/x (1)'"),
            runner.commands,
        )
        assertEquals(listOf(120_000L), runner.timeouts)
        assertEquals(listOf("/sdcard/A", "/sdcard/B"), scans)
    }

    @Test
    fun deleteIsSafeForDashNames() = runBlocking<Unit> {
        files.delete("/sdcard/Download", listOf("-rf", tricky))
        assertEquals(
            listOf("rm -rf -- '/sdcard/Download/-rf' '/sdcard/Download/it'\\''s a'"),
            runner.commands,
        )
        assertEquals(listOf(120_000L), runner.timeouts)
        assertEquals(listOf("/sdcard/Download"), scans)
    }

    @Test
    fun failureThrowsLastLineAndDoesNotScan() = runBlocking<Unit> {
        runner.result = ShellResult("noise\nmv: bad: Permission denied\n\n", 1, 0)
        val e = assertFailsWith<IOException> { files.move("/sdcard/A", listOf("bad"), "/sdcard/B") }
        assertEquals("mv: bad: Permission denied", e.message)
        assertTrue(scans.isEmpty())
        runner.result = ShellResult("", null, 0)
        assertFailsWith<IOException> { files.delete("/sdcard", listOf("a")) }
    }

    @Test
    fun listBlocked() = runBlocking<Unit> {
        runner.result = ShellResult("ls: /sdcard/Android/data: Permission denied", 13, 0)
        assertEquals(Listing.Blocked, files.list("/sdcard/Android/data"))
    }

    @Test
    fun countsEmptyRunsNothing() = runBlocking<Unit> {
        assertEquals(emptyMap(), files.counts("/sdcard", emptyList()))
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun countsAndFreeSpace() = runBlocking<Unit> {
        runner.result = ShellResult("3|a|__svend__\n", 0, 0)
        assertEquals(mapOf("a" to 3), files.counts("/sdcard", listOf("a")))
        runner.result = ShellResult("Filesystem 1K-blocks Used Available Use% Mounted on\n/x 10 5 4 50% /sdcard", 0, 0)
        assertEquals(4096L, files.freeSpace())
        runner.result = ShellResult("boom", 1, 0)
        assertEquals(null, files.freeSpace())
    }
}
