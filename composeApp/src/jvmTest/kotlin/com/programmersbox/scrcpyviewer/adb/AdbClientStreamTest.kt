package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbClientStreamTest {
    private fun client(server: ServerSocket) = AdbClient(adbPath = { error("") }, port = server.localPort)

    /** Reads the two hex-length-prefixed requests and answers OKAY to each, as the adb server does. */
    private fun handshake(s: java.net.Socket) {
        val input = java.io.DataInputStream(s.getInputStream())
        repeat(2) {
            val len = String(ByteArray(4).also { input.readFully(it) }).toInt(16)
            input.readFully(ByteArray(len))
            s.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
        }
    }

    @Test
    fun `shellStream emits all output`() = runBlocking {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    handshake(s); out.write("a\n".toByteArray()); out.flush()
                    out.write("b\n".toByteArray()); out.flush()
                }
            }
            val chunks = withTimeout(10_000) { client(server).shellStream("s", "x").toList() }
            t.join()
            assertEquals("a\nb\n", chunks.joinToString(""))
        }
    }

    @Test
    fun `shellStream keeps a multibyte character split across reads intact`() = runBlocking {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    handshake(s)
                    val out = s.getOutputStream()
                    out.write(byteArrayOf(0xC3.toByte())); out.flush()
                    Thread.sleep(200)
                    out.write(byteArrayOf(0xA9.toByte(), '\n'.code.toByte())); out.flush()
                }
            }
            val chunks = withTimeout(10_000) { client(server).shellStream("s", "x").toList() }
            t.join()
            assertEquals("é\n", chunks.joinToString(""))
        }
    }

    @Test
    fun `shellStream delivers the first chunk before the server finishes`() = runBlocking {
        val gate = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    handshake(s); out.write("a\n".toByteArray()); out.flush()
                    gate.await(5, TimeUnit.SECONDS)
                    out.write("b\n".toByteArray()); out.flush()
                }
            }
            val first = withTimeout(15_000) { client(server).shellStream("s", "x").first() }
            assertEquals("a\n", first)
            assertEquals(1L, gate.count, "first chunk must arrive before the latch opens")
            gate.countDown()
            t.join()
        }
    }

    @Test
    fun `cancelling the collector closes the socket`() = runBlocking {
        ServerSocket(0).use { server ->
            val closed = CountDownLatch(1)
            thread {
                server.accept().use { s ->
                    handshake(s); s.getOutputStream().apply { write("a\n".toByteArray()); flush() }
                    val input = s.getInputStream()
                    val buf = ByteArray(256)
                    // Block until the client closes the socket (EOF or reset).
                    while (true) {
                        val n = try { input.read(buf) } catch (_: Exception) { -1 }
                        if (n == -1) break
                    }
                    closed.countDown()
                }
            }
            client(server).shellStream("s", "x").first()
            assertTrue(closed.await(2, TimeUnit.SECONDS), "server should see the socket closed")
        }
    }
}
