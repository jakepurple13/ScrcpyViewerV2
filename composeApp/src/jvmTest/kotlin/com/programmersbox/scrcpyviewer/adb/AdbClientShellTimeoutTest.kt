package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbClientShellTimeoutTest {
    /** An adb server whose shell stays silent for [silentMs] before answering "done". */
    private fun serve(server: ServerSocket, silentMs: Long) = thread {
        runCatching {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream())
                repeat(2) {
                    val len = String(ByteArray(4).also { input.readFully(it) }).toInt(16)
                    input.readFully(ByteArray(len))
                    s.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
                }
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
}
