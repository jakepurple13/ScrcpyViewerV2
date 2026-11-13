package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-device file operations on a throwaway folder under /sdcard/Download; excluded from the default `jvmTest` run.
 * Run with `./gradlew :composeApp:jvmTest -PdeviceTests --tests '*DeviceFilesDeviceTest' [-PdeviceSerials=SERIAL]`.
 */
class DeviceFilesDeviceTest {
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val adb = AdbClient(adbPath = { adbPath })
    private val serial: String = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first() ?: "R5CX12GDWPZ"

    @Test
    fun `mkdir, list, rename, move and delete a tricky folder name`() = runBlocking<Unit> {
        val scans = mutableListOf<String>()
        val files = DeviceFiles(ShellRunner(adb, serial)) { scans += it }
        val base = "/sdcard/Download"
        val tricky = "svw-test 'q' \$x"
        val renamed = "svw-renamed 'q' \$y"
        val target = "svw-target-${System.currentTimeMillis()}"
        try {
            files.mkdir(base, tricky)
            files.mkdir(base, target)
            val entries = (files.list(base) as Listing.Entries).entries
            assertTrue(entries.any { it.name == tricky && it.isDir }, "tricky folder listed")

            files.rename(base, tricky, renamed)
            assertTrue((files.list(base) as Listing.Entries).entries.none { it.name == tricky })

            files.move(base, listOf(renamed), devicePath(base, target))
            val inside = (files.list(devicePath(base, target)) as Listing.Entries).entries
            assertEquals(listOf(renamed), inside.map { it.name })
            assertEquals(mapOf(target to 1), files.counts(base, listOf(target)).filterKeys { it == target })
            assertTrue((files.freeSpace()?.free ?: 0L) > 0L)

            // Clashes: rename and move onto an existing name fail; replace overwrites.
            files.mkdir(base, tricky)
            files.mkdir(base, renamed)
            val e1 = assertFailsWith<IOException> { files.rename(base, tricky, renamed) }
            assertEquals("$renamed: already exists", e1.message)
            val e2 = assertFailsWith<IOException> { files.move(base, listOf(renamed), devicePath(base, target)) }
            assertEquals("$renamed: already exists", e2.message)
            files.move(base, listOf(renamed), devicePath(base, target), replace = setOf(renamed))
            assertEquals(listOf(renamed), (files.list(devicePath(base, target)) as Listing.Entries).entries.map { it.name })
        } finally {
            runCatching { files.delete(base, listOf(tricky, renamed, target)) }
        }
        assertTrue((files.list(base) as Listing.Entries).entries.none { it.name == target || it.name == tricky })
        assertTrue(scans.isNotEmpty())
    }

    @Test
    fun `replace never deletes the folder that holds the moved item`() = runBlocking<Unit> {
        val files = DeviceFiles(ShellRunner(adb, serial)) {}
        val runner = ShellRunner(adb, serial)
        val outer = "/sdcard/Download/svwt"
        val inner = "$outer/svwt"
        val a = shellQuote("$inner/a.txt")
        try {
            runner.run("mkdir -p ${shellQuote(inner)} && echo keep > $a")
            // Moving the inner svwt to Download with Replace would rm -rf the outer svwt, the inner one with it.
            val e = assertFailsWith<IOException> { files.move(outer, listOf("svwt"), "/sdcard/Download", replace = setOf("svwt")) }
            assertEquals("svwt: contains the items being moved", e.message)
            assertEquals("keep", runner.run("cat $a").output.trim())

            // The device-side guard alone (another case of the folder, as a link or another spelling would reach it).
            val guarded = keepOutsideGuard(inner, "/sdcard/download/SVWT", "svwt", CONTAINS_MOVED) +
                " && rm -rf -- ${shellQuote("/sdcard/download/SVWT")}"
            val r = runner.run(guarded)
            assertTrue(r.exitCode != 0, "guard refused: $r")
            assertEquals("svwt: contains the items being moved", r.output.trim())
            assertEquals("keep", runner.run("cat $a").output.trim())
            // Through the emulated spelling too.
            val r2 = runner.run(keepOutsideGuard("/storage/emulated/0/Download/svwt/svwt", outer, "svwt", CONTAINS_MOVED))
            assertTrue(r2.exitCode != 0, "guard refused: $r2")
            // An unrelated target passes.
            assertEquals(0, runner.run(keepOutsideGuard(inner, "/sdcard/Download/svwt-other", "x", CONTAINS_MOVED)).exitCode)
            assertEquals(0, runner.run(keepOutsideGuard(outer, inner, "x", CONTAINS_MOVED)).exitCode, "a folder's child is not above it")
        } finally {
            runCatching { files.delete("/sdcard/Download", listOf("svwt")) }
        }
    }

    @Test
    fun `replace never deletes an item moved onto itself through another spelling`() = runBlocking<Unit> {
        val files = DeviceFiles(ShellRunner(adb, serial)) {}
        val runner = ShellRunner(adb, serial)
        val name = "svw-self-${System.currentTimeMillis()}.txt"
        val file = shellQuote(devicePath("/sdcard/Download", name))
        try {
            runner.run("echo keep > $file")
            // /sdcard ignores case: /sdcard/download is the same folder as /sdcard/Download.
            files.move("/sdcard/download", listOf(name), "/sdcard/Download", replace = setOf(name))
            assertEquals("keep", runner.run("cat $file").output.trim())
        } finally {
            runCatching { files.delete("/sdcard/Download", listOf(name)) }
        }
    }

    @Test
    fun `tree lists a folder's contents and exec streams read a file byte for byte`() = runBlocking<Unit> {
        val files = DeviceFiles(ShellRunner(adb, serial)) {}
        val runner = ShellRunner(adb, serial)
        val base = "/sdcard/Download"
        val top = "svwt-tree 'q'"
        val dir = devicePath(base, top)
        try {
            runner.run(
                "mkdir -p ${shellQuote("$dir/sub dir")} && printf 'abc' > ${shellQuote("$dir/a b.txt")} && " +
                    ": > ${shellQuote("$dir/empty")} && head -c 300000 /dev/urandom > ${shellQuote("$dir/sub dir/big.bin")} && " +
                    "ln -s /sdcard ${shellQuote("$dir/link")}",
            )
            val tree = files.tree(base, listOf(top))
            assertEquals(
                setOf(
                    TreeEntry(top, true, 0),
                    TreeEntry("$top/a b.txt", false, 3),
                    TreeEntry("$top/empty", false, 0),
                    TreeEntry("$top/sub dir", true, 0),
                    TreeEntry("$top/sub dir/big.bin", false, 300000),
                ),
                tree.toSet(),
            )
            assertEquals(top, tree.first().path, "the folder comes before its contents")

            val big = "$dir/sub dir/big.bin"
            val streamed = adb.openExecStream(serial, "cat ${shellQuote(big)}").use { it.readBytes() }
            assertEquals(300000, streamed.size)
            val sum = runner.run("md5sum ${shellQuote(big)}").output.trim().substringBefore(' ')
            val local = java.security.MessageDigest.getInstance("MD5").digest(streamed).joinToString("") { "%02x".format(it) }
            assertEquals(sum, local)
            assertEquals("abc", adb.openExecStream(serial, "cat ${shellQuote("$dir/a b.txt")}").use { String(it.readBytes()) })
        } finally {
            runCatching { runner.run("rm -rf ${shellQuote(dir)}") }
        }
    }
}
