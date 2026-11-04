package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.IOException

data class ShellResult(val output: String, val exitCode: Int?, val millis: Long)

sealed interface ShellEvent {
    data class Output(val text: String) : ShellEvent
    data class Exit(val code: Int?, val millis: Long) : ShellEvent
}

class ShellTimeoutException : IOException("Timed out after 30 s; set Output to Shell tab for long commands")

/** Runs shell commands on one device and recovers the exit code the legacy shell service drops. */
open class ShellRunner(
    private val adb: AdbClient,
    private val serial: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs [cmd] buffered and returns its output without the exit marker. Throws [ShellTimeoutException] after
     * [timeoutMs] overall (the socket closes on timeout, so a silent or slow command cannot overrun it).
     * Trailing newlines are trimmed from the output. [ShellResult.exitCode] is null when the marker never arrived,
     * for example when the command ends the shell itself (`exit 3`).
     */
    open suspend fun run(cmd: String, timeoutMs: Long = BUFFERED_TIMEOUT_MS): ShellResult {
        val start = clock()
        val buffer = StringBuilder()
        try {
            withTimeout(timeoutMs) { adb.shellStream(serial, wrap(cmd)).collect { buffer.append(it) } }
        } catch (e: TimeoutCancellationException) {
            throw ShellTimeoutException()
        }
        val raw = buffer.toString()
        val idx = raw.lastIndexOf(EXIT_MARKER)
        val code = if (idx < 0) null else raw.substring(idx + EXIT_MARKER.length).trim().toIntOrNull()
        val output = (if (idx < 0) raw else raw.substring(0, idx)).trimEnd('\n', '\r')
        return ShellResult(output, code, clock() - start)
    }

    /**
     * Streams [cmd] as [ShellEvent.Output] chunks with the exit marker removed, then one [ShellEvent.Exit]. The marker
     * may follow unterminated output or be split across chunks, so a trailing partial marker is held back until it is
     * resolved. [ShellEvent.Exit.code] is null when the stream ends without the marker (stopped or killed). Like
     * [run], the marker may directly follow output; unlike [run] the first occurrence wins, since later text cannot be
     * known yet. Collect it in a cancellable scope to stop the command.
     */
    open fun stream(cmd: String): Flow<ShellEvent> = flow {
        val start = clock()
        var pending = ""
        var afterMarker: String? = null
        adb.shellStream(serial, wrap(cmd)).collect { chunk ->
            val tail = afterMarker
            if (tail != null) {
                afterMarker = tail + chunk
            } else {
                pending += chunk
                val idx = pending.indexOf(EXIT_MARKER)
                if (idx >= 0) {
                    if (idx > 0) emit(ShellEvent.Output(pending.substring(0, idx)))
                    afterMarker = pending.substring(idx + EXIT_MARKER.length)
                    pending = ""
                } else {
                    val hold = partialMarkerSuffix(pending)
                    val ready = pending.length - hold
                    if (ready > 0) emit(ShellEvent.Output(pending.substring(0, ready)))
                    pending = pending.substring(ready)
                }
            }
        }
        if (pending.isNotEmpty()) emit(ShellEvent.Output(pending))
        val code = afterMarker?.trim()?.toIntOrNull()
        emit(ShellEvent.Exit(code, clock() - start))
    }

    private fun partialMarkerSuffix(text: String): Int {
        for (len in minOf(text.length, EXIT_MARKER.length - 1) downTo 1) {
            if (text.endsWith(EXIT_MARKER.substring(0, len))) return len
        }
        return 0
    }

    companion object {
        const val BUFFERED_TIMEOUT_MS = 30_000L
        const val EXIT_MARKER = "__scrcpyviewer_exit="

        /** The marker goes on its own line: a trailing `#comment` or `&` in [cmd] cannot swallow it. */
        fun wrap(cmd: String) = "$cmd\necho $EXIT_MARKER\$?"
    }
}
