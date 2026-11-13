package com.programmersbox.scrcpyviewer.logcat

import com.programmersbox.scrcpyviewer.adb.AdbClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val sinceFormat = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$""")

/**
 * Device shell command for a logcat stream: the last 1000 lines when [since] is null, otherwise
 * everything from [since] (`MM-DD HH:MM:SS.mmm`, single-quoted). Throws on any other [since].
 */
fun logcatCommand(since: String?): String {
    if (since == null) return "logcat -v threadtime -T 1000"
    require(sinceFormat.matches(since)) { "Bad logcat timestamp: $since" }
    return "logcat -v threadtime -T '$since'"
}

/** Parses `ps -A -o PID,NAME` output into pid to process name; skips the header and non-numeric pids. */
fun parsePs(output: String): Map<Int, String> {
    val map = LinkedHashMap<Int, String>()
    for (line in output.lineSequence()) {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) continue
        val pid = parts[0].toIntOrNull() ?: continue
        map[pid] = parts[1].trim()
    }
    return map
}

/** Package part of a process name: `com.example:remote` gives `com.example`. */
fun packageOf(process: String): String = process.substringBefore(':')

/** Live logcat and process names of one device, read over adb. */
open class LogcatSource(private val adb: AdbClient, private val serial: String) {
    /** Streams parsed log lines; markers are skipped. Cancelling the collector closes the socket. */
    open fun lines(since: String?): Flow<LogcatLine> = flow {
        val command = logcatCommand(since)
        val splitter = LineSplitter()
        adb.shellStream(serial, command).collect { chunk ->
            for (line in splitter.feed(chunk)) parseThreadtime(line)?.let { emit(it) }
        }
        splitter.flush()?.let { line -> parseThreadtime(line)?.let { emit(it) } }
    }

    /** Pid to process name for every running process. */
    open suspend fun processes(): Map<Int, String> = parsePs(adb.shell(serial, "ps -A -o PID,NAME"))
}
