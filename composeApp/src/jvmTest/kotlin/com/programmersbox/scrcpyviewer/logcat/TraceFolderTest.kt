package com.programmersbox.scrcpyviewer.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TraceFolderTest {
    private fun line(
        message: String,
        tid: Int = 1,
        tag: String = "Tag",
        level: LogLevel = LogLevel.Error,
    ) = LogcatLine("11-13", "10:00:00.000", 100, tid, level, tag, message)

    /** Feeds [lines] through a folder and returns the latest version of each entry by seq. */
    private fun fold(vararg lines: LogcatLine): List<LogcatEntry> {
        val folder = TraceFolder()
        val entries = linkedMapOf<Long, LogcatEntry>()
        lines.forEachIndexed { i, l ->
            val entry = when (val r = folder.add(l, i.toLong(), 0L)) {
                is FoldResult.New -> r.entry
                is FoldResult.Appended -> r.entry
            }
            entries[entry.seq] = entry
        }
        return entries.values.toList()
    }

    @Test
    fun `at lines fold into the line above`() {
        val e = fold(line("boom"), line("\tat a.B.c(B.kt:1)"), line("    at a.B.d(B.kt:2)")).single()
        assertEquals(2, e.trace.size)
        assertTrue(e.hasTrace)
    }

    @Test
    fun `Caused by, more, Suppressed fold`() {
        val e = fold(
            line("boom"),
            line("\tat a.B.c(B.kt:1)"),
            line("Caused by: java.io.IOException: x"),
            line("\t... 3 more"),
            line("\tSuppressed: java.lang.Exception: y"),
        ).single()
        assertEquals(4, e.trace.size)
    }

    @Test
    fun `exception class line folds`() {
        val e = fold(
            line("Sync request failed"),
            line("java.net.SocketTimeoutException: timeout"),
            line("\tat okhttp3.Foo.bar(Foo.kt:1)"),
        ).single()
        assertEquals(2, e.trace.size)
    }

    @Test
    fun `FATAL EXCEPTION takes Process and exception lines`() {
        val e = fold(
            line("FATAL EXCEPTION: main", tag = "AndroidRuntime"),
            line("Process: com.example.notes, PID: 14211", tag = "AndroidRuntime"),
            line("java.lang.IllegalStateException: Note 482 has no folder", tag = "AndroidRuntime"),
            line("\tat a.B.c(B.kt:1)", tag = "AndroidRuntime"),
        ).single()
        assertTrue(e.isCrash)
        assertEquals(3, e.trace.size)
    }

    @Test
    fun `different tid does not fold`() {
        assertEquals(2, fold(line("boom"), line("\tat a.B.c(B.kt:1)", tid = 2)).size)
    }

    @Test
    fun `plain message after a trace starts a new entry`() {
        val entries = fold(line("boom"), line("\tat a.B.c(B.kt:1)"), line("all fine"))
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].trace.size)
        assertTrue(!entries[1].hasTrace)
    }

    @Test
    fun `first line is New and later fold is Appended with same seq`() {
        val folder = TraceFolder()
        assertIs<FoldResult.New>(folder.add(line("boom"), 7L, 5L))
        val r = assertIs<FoldResult.Appended>(folder.add(line("\tat a.B.c(B.kt:1)"), 8L, 6L))
        assertEquals(7L, r.entry.seq)
    }
}
