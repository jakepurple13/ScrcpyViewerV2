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
            assertTrue((files.freeSpace() ?: 0L) > 0L)

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
}
