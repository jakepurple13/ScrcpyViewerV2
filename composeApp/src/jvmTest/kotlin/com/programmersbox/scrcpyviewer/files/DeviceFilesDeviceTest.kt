package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
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
        } finally {
            files.delete(base, listOf(tricky, renamed, target))
        }
        assertTrue((files.list(base) as Listing.Entries).entries.none { it.name == target || it.name == tricky })
        assertTrue(scans.isNotEmpty())
    }
}
