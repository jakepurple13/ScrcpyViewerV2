package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device push (never installs anything); excluded from the default `jvmTest` run. Run with
 * `./gradlew :composeApp:jvmTest -PdeviceTests --tests '*FileTransferDeviceTest' [-PdeviceSerials=SERIAL]`.
 * Pushes a tiny text file to /sdcard/Download/ and removes it again with `adb shell rm`.
 */
class FileTransferDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val adb = AdbClient(adbPath = { adbPath })
    private val serial: String = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first() ?: "MBX6P240401889"

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `push a text file to Download, scan it, and delete it`() = runBlocking<Unit> {
        val name = "scrcpyviewer-push-test-${System.currentTimeMillis()}.txt"
        val local = Files.createTempDirectory("push-test").resolve(name)
        Files.writeString(local, "scrcpy viewer push test\n")
        val remote = DEFAULT_PUSH_TARGET + name
        val scans = mutableListOf<String>()
        val queue = FileTransferQueue(serial, adb, DEFAULT_PUSH_TARGET, { true }, { scans += it }, scope)
        try {
            val result = async(start = CoroutineStart.UNDISPATCHED) { queue.results.first() }
            queue.enqueuePaths(listOf(local))
            val r = withTimeout(60_000) { result.await() }
            println("[$serial] ${r.message}")
            assertEquals(TransferResult.Pushed(name, DEFAULT_PUSH_TARGET), r)
            assertEquals(listOf(DEFAULT_PUSH_TARGET), scans)
            assertEquals("scrcpy viewer push test", adb.shell(serial, "cat '$remote'").trim())
        } finally {
            adb.exec("-s", serial, "shell", "rm", "-f", remote)
            Files.deleteIfExists(local)
        }
        assertTrue(adb.shell(serial, "ls '$remote' 2>/dev/null").isBlank(), "$remote should be deleted")
    }

    @Test
    fun `a Replace push takes the place of a same-named folder instead of nesting in it`() = runBlocking<Unit> {
        val base = "/sdcard/Download/svwt-push"
        val host = Files.createTempDirectory("push-replace")
        val folder = Files.createDirectories(host.resolve("Photos"))
        Files.writeString(folder.resolve("new.txt"), "new\n")
        val file = Files.writeString(host.resolve("Notes"), "a file now\n")
        val queue = FileTransferQueue(serial, adb, DEFAULT_PUSH_TARGET, { true }, {}, scope)
        try {
            adb.shell(serial, "mkdir -p '$base/Photos' '$base/Notes' && echo old > '$base/Photos/old.txt'")
            val results = async(start = CoroutineStart.UNDISPATCHED) { queue.results.take(2).toList() }
            queue.enqueue(
                listOf(
                    TransferRequest(folder, TransferKind.Push, base, "Photos", replace = true),
                    TransferRequest(file, TransferKind.Push, base, "Notes", replace = true),
                ),
            )
            val r = withTimeout(60_000) { results.await() }
            println("[$serial] ${r.map { it.message }}")
            assertEquals(listOf(TransferResult.Pushed("Photos", base), TransferResult.Pushed("Notes", base)), r)
            assertEquals("new.txt", adb.shell(serial, "ls '$base/Photos'").trim(), "replaced, not nested, old one gone")
            assertEquals("a file now", adb.shell(serial, "cat '$base/Notes'").trim(), "a file in place of the folder")
            assertEquals(listOf("Notes", "Photos"), adb.shell(serial, "ls -1A '$base'").lines().map { it.trim() }.filter { it.isNotEmpty() }.sorted(), "no temp copies left")
        } finally {
            adb.exec("-s", serial, "shell", "rm", "-rf", base)
            host.toFile().deleteRecursively()
        }
    }
}
