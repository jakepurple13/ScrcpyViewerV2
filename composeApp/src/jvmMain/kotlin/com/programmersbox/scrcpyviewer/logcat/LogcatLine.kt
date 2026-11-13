package com.programmersbox.scrcpyviewer.logcat

/** Logcat priority. [letter] is the single-char code in threadtime output. */
enum class LogLevel(val letter: Char, val word: String) {
    Verbose('V', "verbose"),
    Debug('D', "debug"),
    Info('I', "info"),
    Warn('W', "warn"),
    Error('E', "error"),
    Assert('A', "assert");

    companion object {
        /** Maps a threadtime level letter to a level; `F` (fatal) maps to [Assert]. Null if unknown. */
        fun fromLetter(c: Char): LogLevel? =
            if (c == 'F') Assert else entries.firstOrNull { it.letter == c }
    }
}

/** One parsed logcat entry. [date] is `MM-DD`, [time] is `HH:MM:SS.mmm`. */
data class LogcatLine(
    val date: String,
    val time: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** Date and time joined as `MM-DD HH:MM:SS.mmm`. */
    val timestamp: String get() = "$date $time"
}

private const val HEAD = """^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+) ([VDIWEAF]) """
private val threadtime = Regex("$HEAD(.*?)\\s*: (.*)$")
private val threadtimeEmpty = Regex("$HEAD(.*?)\\s*:$")

/** Parses one `logcat -v threadtime` line; null for markers and other non-log lines. */
fun parseThreadtime(line: String): LogcatLine? {
    val m = threadtime.matchEntire(line) ?: threadtimeEmpty.matchEntire(line) ?: return null
    val g = m.groupValues
    return LogcatLine(
        date = g[1],
        time = g[2],
        pid = g[3].toIntOrNull() ?: return null,
        tid = g[4].toIntOrNull() ?: return null,
        level = LogLevel.fromLetter(g[5][0]) ?: return null,
        tag = g[6],
        message = g.getOrElse(7) { "" },
    )
}

/** Splits a stream of text chunks into lines, stripping `\r` and holding back a partial last line. */
class LineSplitter {
    private val pending = StringBuilder()

    /** Adds [chunk] and returns the lines it completed. */
    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val lines = mutableListOf<String>()
        while (true) {
            val nl = pending.indexOf('\n')
            if (nl < 0) break
            lines += pending.substring(0, nl).trimEnd('\r')
            pending.delete(0, nl + 1)
        }
        return lines
    }

    /** Returns and clears the held partial line, or null if none. */
    fun flush(): String? {
        if (pending.isEmpty()) return null
        return pending.toString().trimEnd('\r').also { pending.clear() }
    }
}
