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
    /** When set, the result of each call by its index; [result] otherwise. */
    var results: ((Int) -> ShellResult)? = null
    override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
        commands += cmd
        timeouts += timeoutMs
        return results?.invoke(commands.size - 1) ?: result
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
        val t = "'/sdcard/Download/b c'"
        assertEquals(
            listOf(
                "if [ -e $t ] || [ -L $t ]; then printf '%s: already exists\\n' 'b c'; false; else " +
                    "mv -n '/sdcard/Download/it'\\''s a' $t; fi",
            ),
            runner.commands,
        )
        assertEquals(listOf("/sdcard/Download"), scans)
    }

    @Test
    fun renameByCaseOnlyGoesThroughATemporaryName() = runBlocking<Unit> {
        // /sdcard ignores case: photo.jpg -> Photo.jpg finds the target is the source itself.
        files.rename("/sdcard/DCIM", "photo.jpg", "Photo.jpg")
        val s = "'/sdcard/DCIM/photo.jpg'"
        val t = "'/sdcard/DCIM/Photo.jpg'"
        val cmd = runner.commands.single()
        val tmp = Regex("mv $s ('/sdcard/DCIM/\\.rename-[0-9a-f-]+') && ").find(cmd)?.groupValues?.get(1)
        kotlin.test.assertNotNull(tmp, cmd)
        assertEquals(
            "if [ $s -ef $t ]; then mv $s $tmp && mv $tmp $t; " +
                "elif [ -e $t ] || [ -L $t ]; then printf '%s: already exists\\n' 'Photo.jpg'; false; else mv -n $s $t; fi",
            cmd,
        )
        assertEquals(listOf("/sdcard/DCIM"), scans)
    }

    @Test
    fun bulkDeleteRunsInBatchesUnderTheRequestLimit() = runBlocking<Unit> {
        val names = List(3000) { "IMG_20261116_${"%06d".format(it)}_long_name.jpg" }
        files.delete("/sdcard/DCIM/Camera", names)
        assertTrue(runner.commands.size > 1, "expected several batches")
        for (cmd in runner.commands) {
            assertTrue(cmd.startsWith("rm -rf -- "), cmd.take(40))
            assertTrue(cmd.toByteArray().size <= 32 * 1024, "${cmd.toByteArray().size} bytes")
        }
        val deleted = runner.commands.flatMap { c ->
            Regex("'/sdcard/DCIM/Camera/([^']+)'").findAll(c).map { it.groupValues[1] }.toList()
        }
        assertEquals(names, deleted)
        assertTrue(runner.timeouts.all { it == 120_000L })
        assertEquals(listOf("/sdcard/DCIM/Camera"), scans)
    }

    @Test
    fun bulkDeleteStopsAtTheFirstFailingBatch() = runBlocking<Unit> {
        val names = List(3000) { "IMG_20261116_${"%06d".format(it)}_long_name.jpg" }
        runner.results = { i -> if (i == 1) ShellResult("rm: x: Permission denied", 1, 0) else ShellResult("", 0, 0) }
        val e = assertFailsWith<IOException> { files.delete("/sdcard/DCIM/Camera", names) }
        assertEquals("rm: x: Permission denied", e.message)
        assertEquals(2, runner.commands.size)
        // The first batch is gone, so the folder is rescanned.
        assertEquals(listOf("/sdcard/DCIM/Camera"), scans)
    }

    @Test
    fun bulkMoveRunsInBatchesAndStopsAtTheFirstFailure() = runBlocking<Unit> {
        val names = List(1500) { "IMG_20261116_${"%06d".format(it)}.jpg" }
        files.move("/sdcard/DCIM/Camera", names, "/sdcard/Pictures")
        assertTrue(runner.commands.size > 1, "expected several batches")
        assertTrue(runner.commands.all { it.toByteArray().size <= 32 * 1024 })
        val moved = runner.commands.flatMap { c ->
            Regex("else mv '/sdcard/DCIM/Camera/([^']+)'").findAll(c).map { it.groupValues[1] }.toList()
        }
        assertEquals(names, moved)
        // Items within a batch still chain with && so the first failure stops the rest.
        assertTrue("; fi && if " in runner.commands.first())

        runner.commands.clear()
        scans.clear()
        runner.results = { i -> if (i == 0) ShellResult("x: already exists", 1, 0) else ShellResult("", 0, 0) }
        assertFailsWith<IOException> { files.move("/sdcard/DCIM/Camera", names, "/sdcard/Pictures") }
        assertEquals(1, runner.commands.size)
        assertEquals(listOf("/sdcard/DCIM/Camera", "/sdcard/Pictures"), scans)
    }

    @Test
    fun moveWithRenamesAndReplace() = runBlocking<Unit> {
        files.move("/sdcard/A", listOf(tricky, "x"), "/sdcard/B", mapOf("x" to "x (1)"), setOf("x"))
        val s1 = "'/sdcard/A/it'\\''s a'"
        val t1 = "'/sdcard/B/it'\\''s a'"
        val s2 = "'/sdcard/A/x'"
        val t2 = "'/sdcard/B/x (1)'"
        assertEquals(
            listOf(
                // An item that is already its own target (another spelling of the same folder) is left alone first.
                "if [ $s1 -ef $t1 ]; then :; elif [ -e $t1 ] || [ -L $t1 ]; then printf '%s: already exists\\n' 'it'\\''s a'; false; else " +
                    "mv $s1 $t1; fi && if [ $s2 -ef $t2 ]; then :; else " +
                    keepOutsideGuard("/sdcard/A/x", "/sdcard/B/x (1)", "x (1)", CONTAINS_MOVED) +
                    " && rm -rf -- $t2 && mv $s2 $t2; fi",
            ),
            runner.commands,
        )
        assertEquals(listOf(120_000L), runner.timeouts)
        assertEquals(listOf("/sdcard/A", "/sdcard/B"), scans)
    }

    @Test
    fun keepOutsideGuardComparesRealPathsIgnoringCase() {
        assertEquals(
            "case \"\$( (realpath '/sdcard/D/it'\\''s' 2>/dev/null || printf '%s\\n' '/sdcard/D/it'\\''s') | tr A-Z a-z)/\" in " +
                "\"\$( (realpath '/sdcard/it'\\''s' 2>/dev/null || printf '%s\\n' '/sdcard/it'\\''s') | tr A-Z a-z)\"/*) " +
                "printf '%s: %s\\n' 'it'\\''s' 'contains the items being moved'; false;; esac",
            keepOutsideGuard("/sdcard/D/it's", "/sdcard/it's", "it's", CONTAINS_MOVED),
        )
    }

    @Test
    fun replaceOntoAnAncestorOfTheItemIsRefusedBeforeAnythingRuns() = runBlocking<Unit> {
        // /sdcard/Docs holds Docs: replacing /sdcard/Docs with it would rm -rf the item and its whole folder.
        val e = assertFailsWith<IOException> {
            files.move("/sdcard/Docs", listOf("Docs"), "/sdcard", replace = setOf("Docs"))
        }
        assertEquals("Docs: contains the items being moved", e.message)
        // Another case, the emulated spelling, a rename onto the parent's name: all refused.
        assertFailsWith<IOException> { files.move("/sdcard/docs", listOf("Docs"), "/sdcard", replace = setOf("Docs")) }
        assertFailsWith<IOException> {
            files.move("/storage/emulated/0/A/B", listOf("x"), "/sdcard/A", mapOf("x" to "b"), setOf("x"))
        }
        assertTrue(runner.commands.isEmpty(), runner.commands.toString())
        assertTrue(scans.isEmpty())
    }

    @Test
    fun moveNeverTouchesAnItemWhoseTargetIsItself() = runBlocking<Unit> {
        // Same folder (spelled differently), replace asked: an `rm -rf` of the target would delete the source.
        files.move("/sdcard/A/", listOf("x", "y"), "/sdcard/A", replace = setOf("x", "y"))
        assertTrue(runner.commands.isEmpty(), "nothing runs when every item is already in place")

        files.move("/sdcard/A", listOf("x", "y"), "/sdcard/A", mapOf("y" to "z"), replace = setOf("x"))
        assertEquals(listOf("if [ '/sdcard/A/y' -ef '/sdcard/A/z' ]; then :; elif [ -e '/sdcard/A/z' ] || [ -L '/sdcard/A/z' ]; then printf '%s: already exists\\n' 'z'; false; else mv '/sdcard/A/y' '/sdcard/A/z'; fi"), runner.commands)
    }

    @Test
    fun moveScansEvenWhenItFails() = runBlocking<Unit> {
        runner.result = ShellResult("x: already exists", 1, 0)
        assertFailsWith<IOException> { files.move("/sdcard/A", listOf("x"), "/sdcard/B") }
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
        val e = assertFailsWith<IOException> { files.rename("/sdcard/A", "bad", "worse") }
        assertEquals("mv: bad: Permission denied", e.message)
        assertTrue(scans.isEmpty())
        runner.result = ShellResult("", null, 0)
        assertFailsWith<IOException> { files.delete("/sdcard", listOf("a")) }
    }

    @Test
    fun listBlocked() = runBlocking<Unit> {
        runner.result = ShellResult("ls: /sdcard/Android/data: Permission denied", 13, 0)
        assertEquals(Listing.Blocked("ls: /sdcard/Android/data: Permission denied"), files.list("/sdcard/Android/data"))
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
        assertEquals(DeviceSpace(4096L, 10_240L), files.freeSpace())
        runner.result = ShellResult("boom", 1, 0)
        assertEquals(null, files.freeSpace())
    }
}
