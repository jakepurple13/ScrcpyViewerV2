package com.programmersbox.scrcpyviewer.tools

import com.programmersbox.scrcpyviewer.Os
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledToolsTest {
    private val windows = Os.isWindows
    private val adbName = if (windows) "adb.exe" else "adb"

    private fun fakeResources() = Files.createTempDirectory("res").also {
        Files.writeString(it.resolve(adbName), "dummy-adb")
        Files.writeString(it.resolve("scrcpy-server"), "dummy-server")
    }

    @Test
    fun `adbExecutable copies to data dir and is executable`() {
        val data = Files.createTempDirectory("data")
        val adb = BundledTools(fakeResources(), data).adbExecutable()
        assertTrue(Files.exists(adb))
        assertTrue(adb.startsWith(data.resolve("tools")))
        if (!windows) assertTrue(Files.isExecutable(adb))
    }

    @Test
    fun `adbExecutable reuses existing copy`() {
        val tools = BundledTools(fakeResources(), Files.createTempDirectory("data"))
        val first = tools.adbExecutable()
        val mtime = Files.getLastModifiedTime(first)
        Thread.sleep(20)
        val second = tools.adbExecutable()
        assertEquals(first, second)
        assertEquals(mtime, Files.getLastModifiedTime(second))
    }

    @Test
    fun `adbExecutable reads and hashes the bundled adb only once`() {
        val res = fakeResources()
        val data = Files.createTempDirectory("data")
        val tools = BundledTools(res, data)
        val first = tools.adbExecutable()
        // New content would give a new hash and a new adb-<prefix> folder, so the same path means it was not re-read.
        Files.writeString(res.resolve(adbName), "other-adb")
        assertEquals(first, tools.adbExecutable())
        assertEquals(1, Files.list(data.resolve("tools")).use { it.count() })
    }

    @Test
    fun `concurrent first calls all get one complete copy`() {
        val res = Files.createTempDirectory("res")
        val bytes = ByteArray(4 shl 20) { it.toByte() }
        Files.write(res.resolve(adbName), bytes)
        val tools = BundledTools(res, Files.createTempDirectory("data"))
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<java.nio.file.Path>()
        val threads = List(8) {
            kotlin.concurrent.thread {
                start.await()
                val adb = tools.adbExecutable()
                assertTrue(Files.readAllBytes(adb).contentEquals(bytes))
                results.add(adb)
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(8, results.size)
        assertEquals(1, results.toSet().size)
        // The copy goes through a temp file that is moved into place: none is left behind. (Only temp files are
        // checked: on Windows the adb DLLs can be copied alongside from a bundled dir found on the machine.)
        val left = Files.list(results.first().parent).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(emptyList(), left.filter { it.endsWith(".tmp") })
        assertTrue(adbName in left)
    }

    @Test
    fun `serverJar resolves from common dir`() {
        val bundled = Files.createTempDirectory("bundled")
        Files.createDirectories(bundled.resolve("common"))
        Files.writeString(bundled.resolve("common/scrcpy-server"), "x")
        System.setProperty("scrcpyviewer.bundledDir", bundled.toString())
        try {
            val tools = BundledTools(null, Files.createTempDirectory("data"))
            assertEquals(bundled.resolve("common/scrcpy-server"), tools.serverJar)
            assertEquals("5.0", tools.serverVersion)
        } finally {
            System.clearProperty("scrcpyviewer.bundledDir")
        }
    }

    private fun bundledAt(root: java.nio.file.Path): java.nio.file.Path {
        val common = Files.createDirectories(root.resolve("common"))
        return Files.writeString(common.resolve("scrcpy-server"), "x")
    }

    @Test
    fun `serverJar falls back to composeApp bundled under working dir`() {
        val wd = Files.createTempDirectory("wd")
        val expected = bundledAt(wd.resolve("composeApp/bundled"))
        val tools = BundledTools(null, Files.createTempDirectory("data"), wd)
        assertEquals(expected, tools.serverJar)
    }

    @Test
    fun `serverJar falls back to bundled in working dir`() {
        val wd = Files.createTempDirectory("wd")
        val expected = bundledAt(wd.resolve("bundled"))
        assertEquals(expected, BundledTools(null, Files.createTempDirectory("data"), wd).serverJar)
    }

    @Test
    fun `serverJar fallback walks up from a subdirectory`() {
        val root = Files.createTempDirectory("root")
        val expected = bundledAt(root.resolve("composeApp/bundled"))
        val sub = Files.createDirectories(root.resolve("composeApp/build/run"))
        assertEquals(expected, BundledTools(null, Files.createTempDirectory("data"), sub).serverJar)
    }

    @Test
    fun `packaged resources dir wins over dev fallback`() {
        val wd = Files.createTempDirectory("wd")
        bundledAt(wd.resolve("composeApp/bundled"))
        val res = fakeResources()
        assertEquals(res.resolve("scrcpy-server"), BundledTools(res, Files.createTempDirectory("data"), wd).serverJar)
    }

    @Test
    fun `missing server gives actionable message`() {
        val wd = Files.createTempDirectory("wd")
        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            BundledTools(null, Files.createTempDirectory("data"), wd).serverJar
        }
        val msg = e.message.orEmpty()
        assertTrue("scrcpy-server is missing. Run ./gradlew :composeApp:downloadBundledTools, then restart." in msg, msg)
        assertTrue(wd.resolve("bundled").toString() in msg, msg)
    }
}
