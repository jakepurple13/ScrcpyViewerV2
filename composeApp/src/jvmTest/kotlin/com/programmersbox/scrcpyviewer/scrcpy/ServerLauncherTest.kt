package com.programmersbox.scrcpyviewer.scrcpy

import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerLauncherTest {
    init {
        TestLogs.install()
    }

    private val serial = "SER123"

    private fun launcher(adb: FakeAdb) = ServerLauncher(adb, fakeTools(), stopTimeoutMs = 100)

    private fun List<List<String>>.indexOfCmd(vararg prefix: String) =
        indexOfFirst { it.size >= prefix.size + 2 && it.subList(2, 2 + prefix.size) == prefix.toList() }

    @Test
    fun `start pushes then reverses then runs server with version 5_0`() = runBlocking {
        val adb = FakeAdb()
        val conn = withTimeout(10_000) { launcher(adb).start(serial, OptionValues(emptyMap())) }
        try {
            val calls = adb.calls
            val push = calls.indexOfCmd("push")
            val reverse = calls.indexOfCmd("reverse")
            val shell = calls.indexOfFirst { "app_process" in it }
            assertTrue(push in 0 until reverse && reverse < shell, "order was $calls")
            assertEquals(listOf("-s", serial, "push"), calls[push].take(3))
            assertEquals("/data/local/tmp/scrcpy-server.jar", calls[push][4])
            val socketName = calls[reverse][3]
            assertTrue(socketName.matches(Regex("localabstract:scrcpy_[0-9a-f]{8}")), socketName)

            val cmd = adb.shellCommand()
            assertEquals(
                listOf("-s", serial, "shell", "CLASSPATH=/data/local/tmp/scrcpy-server.jar", "app_process", "/"),
                cmd.take(6),
            )
            val line = cmd.joinToString(" ")
            assertTrue("com.genymobile.scrcpy.Server 5.0 scid=" in line, line)
            assertTrue("scid=${socketName.removePrefix("localabstract:scrcpy_")}" in line, line)
            assertTrue("tunnel_forward=false" in line, line)

            assertNotNull(conn.video); assertNotNull(conn.audio); assertNotNull(conn.control)
            assertEquals("Pixel 8", conn.deviceMeta.name)
            assertTrue(conn.control.tcpNoDelay)
        } finally {
            conn.cleanup()
        }
    }

    @Test
    fun `falls back to forward when reverse fails`() = runBlocking {
        val adb = FakeAdb(reverseFails = true)
        val conn = withTimeout(10_000) { launcher(adb).start(serial, OptionValues(emptyMap())) }
        try {
            val reverse = adb.calls.indexOfCmd("reverse")
            val forward = adb.calls.indexOfCmd("forward")
            assertTrue(reverse in 0 until forward, "order was ${adb.calls}")
            assertTrue(adb.calls[forward][3].startsWith("tcp:"))
            assertTrue(adb.calls[forward][4].startsWith("localabstract:scrcpy_"))
            assertTrue("tunnel_forward=true" in adb.shellCommand())
            // The dummy byte was consumed, so the device meta parsed correctly.
            assertEquals("Pixel 8", conn.deviceMeta.name)
        } finally {
            conn.cleanup()
        }
    }

    @Test
    fun `force-adb-forward skips reverse`() = runBlocking {
        val adb = FakeAdb()
        val conn = withTimeout(10_000) {
            launcher(adb).start(serial, OptionValues(mapOf("force-adb-forward" to null)))
        }
        try {
            assertEquals(-1, adb.calls.indexOfCmd("reverse"))
            assertTrue(adb.calls.indexOfCmd("forward") >= 0)
            assertEquals("Pixel 8", conn.deviceMeta.name)
        } finally {
            conn.cleanup()
        }
    }

    @Test
    fun `cleanup removes reverse`() = runBlocking {
        val adb = FakeAdb()
        val conn = withTimeout(10_000) { launcher(adb).start(serial, OptionValues(emptyMap())) }
        val name = adb.calls[adb.calls.indexOfCmd("reverse")][3]
        conn.cleanup()
        conn.cleanup() // runs once
        assertEquals(1, adb.calls.count { it == listOf("-s", serial, "reverse", "--remove", name) })
        assertTrue(adb.processes.single().destroyed)
        assertTrue(conn.video!!.isClosed && conn.audio!!.isClosed && conn.control!!.isClosed)
        // The device side sees EOF.
        assertEquals(-1, adb.deviceSockets[0].getInputStream().read())
    }

    @Test
    fun `cleanup removes forward`() = runBlocking {
        val adb = FakeAdb(reverseFails = true)
        val conn = withTimeout(10_000) { launcher(adb).start(serial, OptionValues(emptyMap())) }
        val local = adb.calls[adb.calls.indexOfCmd("forward")][3]
        conn.cleanup()
        assertEquals(1, adb.calls.count { it == listOf("-s", serial, "forward", "--remove", local) })
    }

    @Test
    fun `disabled streams get no socket`() = runBlocking {
        val adb = FakeAdb()
        val conn = withTimeout(10_000) {
            launcher(adb).start(serial, OptionValues(mapOf("no-audio" to null, "no-control" to null)))
        }
        try {
            assertNotNull(conn.video)
            assertNull(conn.audio)
            assertNull(conn.control)
            assertEquals("Pixel 8", conn.deviceMeta.name)
        } finally {
            conn.cleanup()
        }
    }

    @Test
    fun `server exiting before connecting fails start and cleans up`() = runBlocking {
        val adb = FakeAdb(deviceConnects = false)
        val start = CoroutineScope(Dispatchers.IO).async { launcher(adb).start(serial, OptionValues(emptyMap())) }
        withTimeout(5_000) { while (adb.processes.isEmpty()) delay(10) }
        adb.processes.single().exit(1)
        val e = assertFailsWith<IOException> { withTimeout(5_000) { start.await() } }
        assertTrue("exited" in e.message!!, e.message)
        assertTrue(adb.calls.indexOfCmd("reverse", "--remove") >= 0)
    }

    @Test
    fun `cleanup gives up on a hung adb reverse --remove`() = runBlocking {
        val adb = FakeAdb(deviceConnects = false, removeHangs = true)
        val launcher = ServerLauncher(adb, fakeTools(), stopTimeoutMs = 100, cleanupAdbTimeoutMs = 300)
        val start = CoroutineScope(Dispatchers.IO).async { launcher.start(serial, OptionValues(emptyMap())) }
        withTimeout(5_000) { while (adb.processes.isEmpty()) delay(10) }
        adb.processes.single().exit(1)
        val t0 = System.nanoTime()
        // Without the bound, cleanup would wait on the hung adb forever and start would never fail.
        assertFailsWith<IOException> { withTimeout(5_000) { start.await() } }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        assertTrue(adb.calls.indexOfCmd("reverse", "--remove") >= 0)
    }

    @Test
    fun `a hung adb push fails start with a clear message`() = runBlocking {
        val adb = FakeAdb(pushHangs = true)
        val launcher = ServerLauncher(adb, fakeTools(), stopTimeoutMs = 100, pushTimeoutMs = 200)
        val e = assertFailsWith<IOException> { withTimeout(5_000) { launcher.start(serial, OptionValues(emptyMap())) } }
        assertEquals("adb push timed out", e.message)
        assertTrue(adb.processes.isEmpty(), "the server is not started")
    }

    @Test
    fun `cancelling start while waiting for the device cleans up`() = runBlocking {
        val adb = FakeAdb(deviceConnects = false)
        val start = CoroutineScope(Dispatchers.IO).async { launcher(adb).start(serial, OptionValues(emptyMap())) }
        withTimeout(5_000) { while (adb.processes.isEmpty()) delay(10) }
        withTimeout(5_000) { start.cancel(); start.join() }
        assertTrue(adb.processes.single().destroyed)
        assertTrue(adb.calls.indexOfCmd("reverse", "--remove") >= 0)
    }

    @Test
    fun `falls back to forward when no port can be listened on after reverse`() = runBlocking {
        val adb = FakeAdb()
        val launcher = ServerLauncher(adb, fakeTools(), stopTimeoutMs = 100, listen = { throw IOException("in use") })
        val conn = withTimeout(10_000) {
            launcher.start(serial, OptionValues(mapOf("port" to "27183:27185")))
        }
        try {
            val reverses = adb.calls.filter { it.getOrNull(2) == "reverse" && it[3] != "--remove" }
            val removes = adb.calls.filter { it.getOrNull(2) == "reverse" && it[3] == "--remove" }
            assertTrue(reverses.isNotEmpty() && reverses.size == removes.size, "calls: ${adb.calls}")
            assertTrue(adb.calls.indexOfCmd("forward") > adb.calls.lastIndexOf(removes.last()))
            assertTrue("tunnel_forward=true" in adb.shellCommand())
            assertEquals("Pixel 8", conn.deviceMeta.name)
        } finally {
            conn.cleanup()
        }
    }
}
