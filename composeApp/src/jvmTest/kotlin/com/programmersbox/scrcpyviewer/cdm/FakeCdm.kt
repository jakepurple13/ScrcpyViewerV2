package com.programmersbox.scrcpyviewer.cdm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * A small Claude Device Manager for tests, speaking the same HTTP protocol on 127.0.0.1: one hold per device, a FIFO
 * queue, heartbeats, `TIMEOUT`, and 400 for unknown or don't-use devices. [portFile] points a [CdmClient] at it.
 */
class FakeCdm(
    val devices: List<String> = listOf("A", "B"),
    private val dontUse: Set<String> = emptySet(),
    private val heartbeatMs: Long = 50,
) : AutoCloseable {
    private val lock = Object()
    /** device -> holder */
    private val holds = HashMap<String, String>()
    /** Waiting acquires, in order: holder to device. */
    private val queue = ArrayList<Pair<String, String>>()
    private val requests = ArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { ex -> runCatching { handle(ex) }; ex.close() }
        start()
    }
    val port: Int get() = server.address.port
    val portFile: Path = Files.createTempFile("cdm-port", "").also { Files.writeString(it, "$port\n") }

    fun client(renewTimeoutMs: Int = 3_000) = CdmClient(portFile = portFile, readTimeoutMs = renewTimeoutMs, heartbeatTimeoutMs = 2_000)

    fun holderOf(device: String): String? = synchronized(lock) { holds[device] }
    fun queued(): List<String> = synchronized(lock) { queue.map { it.first } }
    fun requests(): List<String> = synchronized(lock) { requests.toList() }

    /** Another tool takes [device] (as `cdm acquire` would when it is free). */
    fun hold(device: String, holder: String) = synchronized(lock) {
        holds[device] = holder
        lock.notifyAll()
    }

    /** The user releases [device] from the CDM window. */
    fun takeBack(device: String) = synchronized(lock) {
        holds -= device
        lock.notifyAll()
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val q = query(ex)
        synchronized(lock) { requests += "${ex.requestMethod} $path ${q["holder"].orEmpty()}" }
        when (path) {
            "/cdm/ping" -> text(ex, 200, "cdm-ok")
            "/cdm/acquire" -> acquire(ex, q)
            "/cdm/renew" -> text(ex, 200, synchronized(lock) { if (q["holder"] in holds.values) "RENEWED" else "NONE" })
            "/cdm/release" -> text(ex, 200, synchronized(lock) {
                val mine = holds.filterValues { it == q["holder"] }.keys
                mine.forEach { holds -= it }
                lock.notifyAll()
                if (mine.isEmpty()) "NONE" else "RELEASED"
            })
            "/cdm/status" -> text(ex, 200, synchronized(lock) {
                holds.filterValues { it == q["holder"] }.keys.joinToString("") { "HOLDING android $it $it\n" }.ifEmpty { "NONE" }
            })
            "/cdm/devices" -> text(ex, 200, snapshotJson())
            "/cdm/events" -> {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.write("event: devices\ndata: ${snapshotJson()}\n\n".toByteArray())
                ex.responseBody.flush()
            }
            else -> text(ex, 404, "")
        }
    }

    private fun acquire(ex: HttpExchange, q: Map<String, String>) {
        val holder = q.getValue("holder")
        val device = q["device"]
        if (device == null || device !in devices) return text(ex, 400, "No device matches \"$device\".")
        if (device in dontUse) return text(ex, 400, "$device is marked don't use.")
        val timeoutMs = (q["timeoutSec"]?.toLong() ?: 110) * 1000
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        fun line(s: String) {
            out.write("$s\n".toByteArray())
            out.flush()
        }
        val entry = holder to device
        try {
            synchronized(lock) {
                if (holds[device] == null || holds[device] == holder) {
                    holds[device] = holder
                    line("GRANTED android $device Phone $device")
                    return
                }
                queue += entry
                line("QUEUED ${queue.count { it.second == device }}")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                synchronized(lock) {
                    val first = queue.firstOrNull { it.second == device }
                    if (holds[device] == null && first == entry) {
                        queue -= entry
                        holds[device] = holder
                        line("GRANTED android $device Phone $device")
                        return
                    }
                    if (System.currentTimeMillis() > deadline) {
                        queue -= entry
                        line("TIMEOUT ${queue.count { it.second == device } + 1}")
                        return
                    }
                    lock.wait(heartbeatMs)
                }
                line(".") // throws once the client has gone, which takes it out of the queue
            }
        } catch (_: IOException) {
            synchronized(lock) { queue -= entry }
        } catch (_: InterruptedException) {
            synchronized(lock) { queue -= entry }
        }
    }

    private fun snapshotJson(): String = synchronized(lock) {
        val list = devices.joinToString(",") { d ->
            val holder = holds[d]?.let { """{"id":"$it","label":"$it","agent":"x"}""" } ?: "null"
            """{"key":"$d","name":"Phone $d","platform":"android","category":"phone","virtual":false,"online":true,""" +
                """"dontUse":${d in dontUse},"serial":"$d","holder":$holder}"""
        }
        // Shaped like the real CDM's queue: holder is an object, virtual may be null.
        val waiting = queue.mapIndexed { i, (holder, device) ->
            """{"position":${i + 1},"holder":{"id":"$holder","label":"$holder","agent":"$device"},""" +
                """"categories":["any"],"platform":"android","virtual":null,"device":"$device"}"""
        }.joinToString(",")
        """{"devices":[$list],"queue":[$waiting]}"""
    }

    private fun text(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    override fun close() {
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        Files.deleteIfExists(portFile)
    }
}
