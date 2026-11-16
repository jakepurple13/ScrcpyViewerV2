package com.programmersbox.scrcpyviewer.logcat

/**
 * A logcat line as shown in the list. [seq] is its stable arrival order, [receivedAtMillis] when it arrived,
 * and [trace] the stack trace lines folded into it.
 */
data class LogcatEntry(
    val seq: Long,
    val line: LogcatLine,
    val receivedAtMillis: Long,
    val trace: List<String> = emptyList(),
) {
    /** True for the `FATAL EXCEPTION` head line AndroidRuntime logs when the app crashes. */
    val isCrash: Boolean
        get() = line.tag == "AndroidRuntime" &&
            line.level == LogLevel.Error &&
            line.message.startsWith("FATAL EXCEPTION")

    val hasTrace: Boolean get() = trace.isNotEmpty()
}

/** What [TraceFolder.add] did with a line. */
sealed interface FoldResult {
    /** The line started a new entry. */
    data class New(val entry: LogcatEntry) : FoldResult

    /** The line folded into the open entry; [entry] is that entry updated, with the same seq. */
    data class Appended(val entry: LogcatEntry) : FoldResult
}

private val traceContinuation = Regex(
    """^\s+at |^Caused by: |^\s*\.\.\. \d+ more|^\s*Suppressed: |^[\w${'$'}.]+(Exception|Error|Throwable)[\w${'$'}]*(: .*)?$""",
)

/**
 * Folds stack trace lines into the entry above them. A line folds when it has the same pid, tid, tag and level
 * as the open entry and looks like trace text, or when the open entry is a crash head still short of its
 * `Process:` and exception lines.
 */
class TraceFolder {
    private var open: LogcatEntry? = null

    fun add(line: LogcatLine, seq: Long, now: Long): FoldResult {
        val cur = open
        if (cur != null && sameSource(cur.line, line) && continues(cur, line.message)) {
            val updated = cur.copy(trace = cur.trace + line.message)
            open = updated
            return FoldResult.Appended(updated)
        }
        val entry = LogcatEntry(seq, line, now)
        open = entry
        return FoldResult.New(entry)
    }

    private fun sameSource(a: LogcatLine, b: LogcatLine) =
        a.pid == b.pid && a.tid == b.tid && a.tag == b.tag && a.level == b.level

    private fun continues(entry: LogcatEntry, message: String) =
        traceContinuation.containsMatchIn(message) || (entry.isCrash && entry.trace.size < 2)
}
