package com.programmersbox.scrcpyviewer.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

enum class TelemetryKind { Usage, Crash }

/** One event waiting to be sent. [timestamp] is ISO-8601. */
@Serializable
data class QueuedEvent(
    val uuid: String,
    val event: String,
    val kind: TelemetryKind,
    val timestamp: String,
    val properties: JsonObject,
)

/**
 * Disk-backed FIFO of [QueuedEvent]s, one JSON object per line. Thread-safe through a single lock and safe across
 * restarts. [append] fsyncs so a crash handler's write survives. Over [maxEvents] or [maxBytes] the oldest events are
 * dropped. Lines that do not decode (for example a truncated last line) are skipped, never thrown.
 */
class TelemetryQueue(
    private val file: Path,
    private val maxEvents: Int = 1000,
    private val maxBytes: Long = 1_000_000,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun append(e: QueuedEvent) = synchronized(lock) {
        file.parent?.let { Files.createDirectories(it) }
        val line = encode(e)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            // A crash can leave a last line without a newline; start on a fresh line so it cannot swallow this one.
            val size = ch.size()
            val prefix = if (size > 0 && !endsWithNewline(size)) "\n" else ""
            ch.write(ByteBuffer.wrap((prefix + line).toByteArray()))
            ch.force(false)
        }
        enforceCaps()
    }

    /** Oldest [n] events. */
    fun peek(n: Int): List<QueuedEvent> = synchronized(lock) { readAll().take(n) }

    fun remove(uuids: Set<String>) = synchronized(lock) {
        val all = readAll()
        val kept = all.filterNot { it.uuid in uuids }
        if (kept.size != all.size) rewrite(kept)
    }

    fun removeKind(kind: TelemetryKind) = synchronized(lock) {
        val all = readAll()
        val kept = all.filterNot { it.kind == kind }
        if (kept.size != all.size) rewrite(kept)
    }

    fun size(): Int = synchronized(lock) { readAll().size }

    private fun endsWithNewline(size: Long): Boolean =
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate(1)
            ch.read(buf, size - 1)
            buf.get(0) == '\n'.code.toByte()
        }

    private fun readAll(): List<QueuedEvent> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file, Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { json.decodeFromString(QueuedEvent.serializer(), line) }.getOrNull()
        }
    }

    private fun enforceCaps() {
        if (Files.size(file) <= maxBytes && lineCount() <= maxEvents) return
        val all = readAll().toMutableList()
        var bytes = all.sumOf { encode(it).toByteArray().size.toLong() }
        while (all.isNotEmpty() && (all.size > maxEvents || bytes > maxBytes)) {
            bytes -= encode(all.removeAt(0)).toByteArray().size
        }
        rewrite(all)
    }

    private fun lineCount(): Int = Files.newBufferedReader(file).use { r -> r.lineSequence().count() }

    private fun encode(e: QueuedEvent) = json.encodeToString(QueuedEvent.serializer(), e) + "\n"

    private fun rewrite(events: List<QueuedEvent>) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, events.joinToString("") { encode(it) })
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
