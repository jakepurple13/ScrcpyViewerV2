package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbClientShellTimeoutTest {
    /**
     * An adb server whose shell stays silent for [silentMs] before answering "done". [answered] counts down once both
     * requests got their OKAY, so the client is then blocked reading the output.
     */
    private fun serve(
        server: ServerSocket,
        silentMs: Long,
        answered: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
    ) = thread {
        runCatching {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream())
                repeat(2) {
                    val len = String(ByteArray(4).also { input.readFully(it) }).toInt(16)
                    input.readFully(ByteArray(len))
                    s.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
                }
                answered.countDown()
                Thread.sleep(silentMs)
                s.getOutputStream().apply { write("done".toByteArray()); flush() }
            }
        }
    }

    @Test
    fun `a silent shell times out at the default but not under ShellReadTimeout`() = runBlocking {
        ServerSocket(0).use { s1 ->
            val t = serve(s1, 600)
            val c = AdbClient(adbPath = { error("") }, port = s1.localPort, shellReadTimeoutMs = 150)
            assertFailsWith<SocketTimeoutException> { c.shell("s", "rm -rf x") }
            t.join()
        }
        ServerSocket(0).use { s2 ->
            val t = serve(s2, 600)
            val c = AdbClient(adbPath = { error("") }, port = s2.localPort, shellReadTimeoutMs = 150)
            assertEquals("done", withContext(ShellReadTimeout(5_000)) { c.shell("s", "rm -rf x") })
            t.join()
        }
    }

    @Test
    fun `cancelling a silent shell or execOut returns at once, not at the read timeout`() = runBlocking {
        val calls = listOf<suspend (AdbClient) -> Any>({ it.shell("s", "getprop") }, { it.execOut("s", "cat x") })
        for (call in calls) {
            ServerSocket(0).use { server ->
                val answered = java.util.concurrent.CountDownLatch(1)
                val t = serve(server, 2_000, answered)
                val c = AdbClient(adbPath = { error("") }, port = server.localPort, shellReadTimeoutMs = 10_000)
                val job = launch(Dispatchers.IO) { call(c) }
                // Cancel only once the client is blocked reading the silent output.
                kotlin.test.assertTrue(runInterruptible(Dispatchers.IO) { answered.await(10, TimeUnit.SECONDS) })
                val t0 = System.nanoTime()
                withTimeout(5_000) { job.cancelAndJoin() }
                val ms = (System.nanoTime() - t0) / 1_000_000
                kotlin.test.assertTrue(ms < 1_000, "took $ms ms")
                t.join(5_000)
            }
        }
    }
}
