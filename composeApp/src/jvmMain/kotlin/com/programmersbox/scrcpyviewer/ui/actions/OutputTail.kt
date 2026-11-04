package com.programmersbox.scrcpyviewer.ui.actions

/**
 * The end of a command's output: at most [maxLines] lines and about [maxChars] characters, so a Test run of
 * `logcat` or `dumpsys` cannot grow without bound. Thread-safe: a stream appends while a ticker reads.
 */
internal class OutputTail(private val maxLines: Int = MAX_LINES, private val maxChars: Int = MAX_CHARS) {
    /** Complete lines, each ending in `\n`. */
    private val lines = ArrayDeque<String>()
    private val partial = StringBuilder()
    private var chars = 0

    /** Some output was dropped from the front. */
    var trimmed = false
        @Synchronized get
        private set

    @Synchronized
    fun append(text: String) {
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                partial.append(text, start, text.length)
                break
            }
            partial.append(text, start, newline + 1)
            lines.addLast(partial.toString())
            chars += partial.length
            partial.setLength(0)
            start = newline + 1
        }
        while (lines.isNotEmpty() && (lines.size + (if (partial.isEmpty()) 0 else 1) > maxLines || chars + partial.length > maxChars)) {
            chars -= lines.removeFirst().length
            trimmed = true
        }
        if (partial.length > maxChars) {
            partial.delete(0, partial.length - maxChars)
            trimmed = true
        }
    }

    /** The number of lines kept, counting an unfinished last line. */
    @get:Synchronized
    val lineCount: Int get() = lines.size + if (partial.isEmpty()) 0 else 1

    @Synchronized
    override fun toString(): String = buildString(chars + partial.length) {
        lines.forEach(::append)
        append(partial)
    }

    companion object {
        const val MAX_LINES = 1_000
        const val MAX_CHARS = 64 * 1024

        /** [text] cut to its tail, and the line count when it was cut. */
        fun of(text: String): Pair<String, Int?> {
            val tail = OutputTail().apply { append(text) }
            return tail.toString() to tail.lineCount.takeIf { tail.trimmed }
        }
    }
}
