package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellRunnerTest {
    private class FakeAdb(private val reply: suspend (String) -> String) : AdbClient({ error("") }) {
        val cmds = mutableListOf<String>()
        override fun shellStream(serial: String, cmd: String): Flow<String> = flow {
            cmds += cmd
            emit(reply(cmd))
        }
    }

    private val timeoutMessage = "Timed out after 30 s; set Output to Shell tab for long commands"

    /** Answers OKAY to the two adb requests, then runs [body] on the socket. */
    private fun fakeServer(server: ServerSocket, body: (Socket) -> Unit) = thread {
        try {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                repeat(2) {
                    val len = String(ByteArray(4).also { input.readFully(it) }).toInt(16)
                    input.readFully(ByteArray(len))
                    s.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
                }
                body(s)
            }
        } catch (_: Exception) {
            // The client closing the socket on timeout is expected.
        }
    }

    @Test
    fun `wrap puts the marker on its own line`() {
        assertEquals(
            "logcat -d # all\necho __scrcpyviewer_exit=$?",
            ShellRunner.wrap("logcat -d # all"),
        )
    }

    @Test
    fun `run parses output and exit code`() = runBlocking {
        val adb = FakeAdb { "out\n__scrcpyviewer_exit=3\n" }
        val r = ShellRunner(adb, "s").run("x")
        assertEquals("out", r.output)
        assertEquals(3, r.exitCode)
        assertEquals(listOf(ShellRunner.wrap("x")), adb.cmds)
    }

    @Test
    fun `run reports null exit code when the marker is missing`() = runBlocking {
        val r = ShellRunner(FakeAdb { "partial\n" }, "s").run("x")
        assertEquals("partial", r.output)
        assertNull(r.exitCode)
    }

    @Test
    fun `run times out with the documented message`() = runBlocking {
        val runner = ShellRunner(FakeAdb { delay(5_000); "" }, "s")
        val e = assertFailsWith<ShellTimeoutException> { runner.run("x", timeoutMs = 50) }
        assertEquals(timeoutMessage, e.message)
    }

    @Test
    fun `run times out on a silent socket and returns promptly`() = runBlocking {
        ServerSocket(0).use { server ->
            val t = fakeServer(server) { Thread.sleep(2_000) }
            val runner = ShellRunner(AdbClient({ error("") }, port = server.localPort), "s")
            val start = System.nanoTime()
            val e = assertFailsWith<ShellTimeoutException> { runner.run("sleep 15", timeoutMs = 200) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(timeoutMessage, e.message)
            assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
            t.join()
        }
    }

    @Test
    fun `run times out on a command that keeps printing`() = runBlocking {
        ServerSocket(0).use { server ->
            val t = fakeServer(server) { s ->
                repeat(40) {
                    s.getOutputStream().apply { write("tick\n".toByteArray()); flush() }
                    Thread.sleep(50)
                }
            }
            val runner = ShellRunner(AdbClient({ error("") }, port = server.localPort), "s")
            val start = System.nanoTime()
            assertFailsWith<ShellTimeoutException> { runner.run("yes", timeoutMs = 300) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
            t.join()
        }
    }
}
