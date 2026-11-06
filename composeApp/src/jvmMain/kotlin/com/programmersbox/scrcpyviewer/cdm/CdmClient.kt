package com.programmersbox.scrcpyviewer.cdm

import com.programmersbox.scrcpyviewer.closingOnCancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** What Claude Device Manager answered to an acquire. */
sealed interface AcquireOutcome {
    /** The device is ours: [id] is its adb serial, [name] its display name. */
    data class Granted(val id: String, val name: String) : AcquireOutcome

    /** The queue wait ran out (`TIMEOUT`); acquire again to keep waiting. */
    data object Timeout : AcquireOutcome

    /** The user said no (`BLOCKED`, `REMOVED`) or CDM rejected the request (HTTP 400): [reason] says why. */
    data class Refused(val reason: String) : AcquireOutcome

    /** CDM is not running (no port file, no answer, or it went away mid-wait): carry on without a lease. */
    data object NotRunning : AcquireOutcome
}

@Serializable
data class CdmHolder(val id: String, val label: String? = null, val agent: String? = null)

@Serializable
data class CdmDevice(
    val key: String,
    val name: String = "",
    val platform: String = "",
    val category: String = "",
    val virtual: Boolean = false,
    val online: Boolean = false,
    val dontUse: Boolean = false,
    val serial: String? = null,
    val holder: CdmHolder? = null,
)

@Serializable
data class CdmQueueEntry(
    val position: Int = 0,
    val holder: CdmHolder? = null,
    val categories: List<String> = emptyList(),
    val platform: String? = null,
    val virtual: Boolean? = null,
    val device: String? = null,
)

/** `GET /cdm/devices` and the `devices` event: every device CDM knows, and who waits for which. */
@Serializable
data class CdmSnapshot(val devices: List<CdmDevice> = emptyList(), val queue: List<CdmQueueEntry> = emptyList())

/**
 * HTTP client for Claude Device Manager on 127.0.0.1. The port is read from [portFile] on every call (the user can
 * change it); a missing file or a server that does not answer `cdm-ok` means CDM is closed. Every call is safe to make
 * when CDM is closed: it returns null / [AcquireOutcome.NotRunning] and never throws for that.
 *
 * Given to Koin at start; tests give one whose port file does not exist, so they never reach a real CDM.
 */
@Provided
open class CdmClient(
    private val portFile: Path = defaultPortFile(),
    private val connectTimeoutMs: Int = 1_000,
    private val readTimeoutMs: Int = 3_000,
    /** Heartbeats come every 5 s while queued; this long without one means the server is gone. */
    private val heartbeatTimeoutMs: Int = 20_000,
) {
    /** `http://127.0.0.1:<port>`, or null when there is no usable port file. */
    fun baseUrl(): String? {
        val port = runCatching { Files.readString(portFile).trim().toInt() }.getOrNull() ?: return null
        if (port !in 1..65535) return null
        return "http://127.0.0.1:$port"
    }

    /** True when CDM answers `GET /cdm/ping` with `cdm-ok`. */
    open fun isRunning(): Boolean {
        val base = baseUrl() ?: return false
        return runCatching { request("GET", "$base/cdm/ping").trim() == "cdm-ok" }.getOrDefault(false)
    }

    /**
     * `POST /cdm/acquire`, read line by line. [onQueued] gets each `QUEUED <n>` position. Cancelling the caller closes
     * the connection, which takes [holder] out of the queue.
     */
    open suspend fun acquire(
        holder: String,
        device: String?,
        platform: String = "android",
        timeoutSec: Int = DEFAULT_ACQUIRE_TIMEOUT_SEC,
        onQueued: (Int) -> Unit = {},
    ): AcquireOutcome {
        if (!isRunning()) return AcquireOutcome.NotRunning
        val base = baseUrl() ?: return AcquireOutcome.NotRunning
        val url = buildString {
            append("$base/cdm/acquire?holder=").append(enc(holder))
            append("&platform=").append(enc(platform))
            if (device != null) append("&device=").append(enc(device)) else append("&category=any")
            append("&timeoutSec=").append(timeoutSec)
        }
        val conn = open("POST", url).apply { readTimeout = heartbeatTimeoutMs }
        return closingOnCancel(onCancel = { conn.disconnect() }) {
            runInterruptible(Dispatchers.IO) {
                try {
                    conn.outputStream.close()
                    when (val code = conn.responseCode) {
                        200 -> conn.inputStream.bufferedReader().use { reader ->
                            generateSequence(reader::readLine)
                                .firstNotNullOfOrNull { parseAcquireLine(it, onQueued) }
                                ?: AcquireOutcome.NotRunning
                        }
                        in 400..499 -> {
                            val reason = conn.errorStream?.use { it.readBytes().decodeToString() }?.trim().orEmpty()
                            AcquireOutcome.Refused(reason.ifBlank { "Claude Device Manager rejected the request (HTTP $code)" })
                        }
                        else -> AcquireOutcome.NotRunning
                    }
                } catch (_: IOException) {
                    AcquireOutcome.NotRunning
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /** `POST /cdm/renew`: true = RENEWED, false = NONE (the hold is gone), null = CDM did not answer. */
    open fun renew(holder: String, platform: String = "android"): Boolean? =
        post("/cdm/renew?holder=${enc(holder)}&platform=${enc(platform)}")?.let { it == "RENEWED" }

    /** `POST /cdm/release`: true = RELEASED, false = NONE, null = CDM did not answer. */
    open fun release(holder: String, platform: String = "android"): Boolean? =
        post("/cdm/release?holder=${enc(holder)}&platform=${enc(platform)}")?.let { it == "RELEASED" }

    /** `GET /cdm/status`: the response lines (a `HOLDING …` line per hold), or null when CDM did not answer. */
    open fun status(holder: String): List<String>? {
        val base = baseUrl() ?: return null
        return runCatching { request("GET", "$base/cdm/status?holder=${enc(holder)}").lines().filter { it.isNotBlank() } }
            .getOrNull()
    }

    /** `GET /cdm/devices`, or null when CDM did not answer. */
    open fun devices(): CdmSnapshot? {
        val base = baseUrl() ?: return null
        return runCatching { json.decodeFromString<CdmSnapshot>(request("GET", "$base/cdm/devices")) }.getOrNull()
    }

    /**
     * `GET /cdm/events`: each `devices` event's snapshot. Completes when the stream ends or CDM is not running;
     * cancelling the collector closes the connection.
     */
    open fun events(): Flow<CdmSnapshot> = channelFlow {
        val base = baseUrl() ?: return@channelFlow
        val conn = open("GET", "$base/cdm/events").apply { readTimeout = 0 }
        try {
            closingOnCancel(onCancel = { conn.disconnect() }) {
                runInterruptible(Dispatchers.IO) {
                    if (conn.responseCode != 200) return@runInterruptible
                    conn.inputStream.bufferedReader().use { reader ->
                        readServerSentEvents(reader::readLine) { event, data ->
                            if (event == "devices") {
                                runCatching { json.decodeFromString<CdmSnapshot>(data) }.onSuccess { trySend(it) }
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            conn.disconnect()
        }
    }.buffer(Channel.CONFLATED)

    private fun post(pathAndQuery: String): String? {
        val base = baseUrl() ?: return null
        return runCatching { request("POST", base + pathAndQuery).trim() }.getOrNull()
    }

    private fun request(method: String, url: String): String {
        val conn = open(method, url)
        try {
            if (method == "POST") conn.outputStream.close()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            if (method == "POST") {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }

    companion object {
        const val DEFAULT_ACQUIRE_TIMEOUT_SEC = 110
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultPortFile(): Path =
            Paths.get(System.getProperty("user.home"), ".claude-device-manager", "port")

        private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

        /** Reads `event:` / `data:` blocks until [readLine] returns null, calling [onEvent] at each blank line. */
        internal fun readServerSentEvents(readLine: () -> String?, onEvent: (event: String, data: String) -> Unit) {
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = readLine() ?: return
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) onEvent(event, data.toString())
                        event = "message"
                        data.clear()
                    }
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
        }

        /** One acquire line: an outcome, or null for a heartbeat / queue position (sent to [onQueued]). */
        internal fun parseAcquireLine(line: String, onQueued: (Int) -> Unit): AcquireOutcome? {
            val parts = line.trim().split(' ', limit = 4)
            return when (parts[0]) {
                "QUEUED" -> {
                    parts.getOrNull(1)?.toIntOrNull()?.let(onQueued)
                    null
                }
                "GRANTED" -> AcquireOutcome.Granted(id = parts.getOrElse(2) { "" }, name = parts.getOrElse(3) { "" })
                "TIMEOUT" -> AcquireOutcome.Timeout
                "BLOCKED" -> AcquireOutcome.Refused("Claude Device Manager blocked this request")
                "REMOVED" -> AcquireOutcome.Refused("Removed from the Claude Device Manager queue")
                else -> null
            }
        }
    }
}
