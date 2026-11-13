package com.programmersbox.scrcpyviewer.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatQueryTest {
    private val now = 10_000_000L

    private fun entry(
        seq: Long,
        tag: String,
        level: LogLevel,
        message: String,
        ageMillis: Long = 1_000,
        trace: List<String> = emptyList(),
    ) = LogcatEntry(seq, LogcatLine("11-13", "10:00:00.000", 100, 100, level, tag, message), now - ageMillis, trace)

    private val okhttp = entry(1, "OkHttp", LogLevel.Warn, "retrying request to host", ageMillis = 6 * 60_000L)
    private val crash = entry(
        2, "AndroidRuntime", LogLevel.Error, "FATAL EXCEPTION: main",
        trace = listOf("Process: com.example.notes", "java.lang.IllegalStateException: boom"),
    )
    private val choreo = entry(3, "Choreographer", LogLevel.Info, "Skipped 31 frames")
    private val system = entry(4, "system_server", LogLevel.Debug, "user switch")
    private val all = listOf(okhttp, crash, choreo, system)

    private val infos = mapOf(
        1L to EntryInfo("com.example.notes", "com.example.notes"),
        2L to EntryInfo("com.example.notes", "com.example.notes"),
        3L to EntryInfo("com.example.other", "com.example.other"),
        4L to EntryInfo(null, "system_server"),
    )

    private fun run(q: String, mine: String? = null): List<Long> {
        val query = LogcatQuery.parse(q)
        return all.filter { query.matches(it, infos.getValue(it.seq), mine, now) }.map { it.seq }
    }

    @Test
    fun `empty query matches all`() {
        assertTrue(LogcatQuery.parse("").isEmpty)
        assertEquals(listOf(1L, 2, 3, 4), run(""))
    }

    @Test
    fun `tag contains ignores case`() = assertEquals(listOf(1L), run("tag:OKHTTP"))

    @Test
    fun `minus tag excludes`() = assertEquals(listOf(2L, 3, 4), run("-tag:okhttp"))

    @Test
    fun `tag regex`() = assertEquals(listOf(3L, 4), run("tag~:^(choreo|system)"))

    @Test
    fun `tag equals is exact`() {
        assertEquals(listOf(1L), run("tag=:OkHttp"))
        assertEquals(emptyList(), run("tag=:okh"))
    }

    @Test
    fun `package mine uses the picked app`() =
        assertEquals(listOf(1L, 2), run("package:mine", mine = "com.example.notes"))

    @Test
    fun `package mine matches nothing when none is picked`() = assertEquals(emptyList(), run("package:mine"))

    @Test
    fun `level warn keeps W E A`() = assertEquals(listOf(1L, 2), run("level:warn"))

    @Test
    fun `level prefix works`() = assertEquals(listOf(1L, 2), run("level:w"))

    @Test
    fun `age excludes older entries`() = assertEquals(listOf(2L, 3, 4), run("age:5m"))

    @Test
    fun `is crash`() = assertEquals(listOf(2L), run("is:crash"))

    @Test
    fun `is stacktrace`() = assertEquals(listOf(2L), run("is:stacktrace"))

    @Test
    fun `message also searches trace lines`() = assertEquals(listOf(2L), run("message:IllegalState"))

    @Test
    fun `bare word matches tag or message`() {
        assertEquals(listOf(1L), run("okhttp"))
        assertEquals(listOf(3L), run("frames"))
    }

    @Test
    fun `leading minus on a bare word excludes`() = assertEquals(listOf(2L, 3, 4), run("-okhttp"))

    @Test
    fun `quoted text with spaces`() {
        assertEquals(listOf(3L), run("\"skipped 31\""))
        assertEquals(listOf(2L, 3, 4), run("-\"retrying request\""))
    }

    @Test
    fun `and binds tighter than or`() {
        // (okhttp AND level:error) OR choreographer
        assertEquals(listOf(3L), run("okhttp level:error | choreographer"))
        assertEquals(listOf(1L, 3L), run("okhttp level:warn | choreographer"))
    }

    @Test
    fun `empty or group is dropped`() = assertEquals(listOf(1L), run("| okhttp |"))

    @Test
    fun `errors carry the spec hint and the bad term is ignored`() {
        val unknown = LogcatQuery.parse("foo:bar tag:okhttp")
        assertEquals("Unknown key “foo:”. Try tag:, package:, message:, level:, age: or is:.", unknown.error)
        assertEquals(listOf(1L), run("foo:bar tag:okhttp"))
        assertEquals("level: expects verbose, debug, info, warn, error or assert.", LogcatQuery.parse("level:loud").error)
        assertEquals("age: expects a number and s, m, h or d, like 5m.", LogcatQuery.parse("age:5x").error)
        assertEquals("is: expects crash or stacktrace.", LogcatQuery.parse("is:foo").error)
        assertEquals("That regular expression doesn't parse yet.", LogcatQuery.parse("tag~:(").error)
    }

    @Test
    fun `half-typed terms are ignored without error`() {
        for (q in listOf("level:", "tag:", "-", "\"")) {
            assertNull(LogcatQuery.parse(q).error, q)
            assertEquals(listOf(1L, 2, 3, 4), run(q), q)
        }
    }

    @Test
    fun spans() {
        val spans = LogcatQuery.parse("-tag~:\"a b\"").spans
        assertEquals(
            listOf(QuerySpan(0, 1, SpanKind.Negation), QuerySpan(1, 6, SpanKind.Key), QuerySpan(6, 11, SpanKind.Quoted)),
            spans,
        )
        assertEquals(listOf(QuerySpan(2, 3, SpanKind.Operator)), LogcatQuery.parse("a | b").spans)
        assertEquals(SpanKind.Error, LogcatQuery.parse("level:loud").spans.first().kind)
    }

    @Test
    fun `highlight words come from positive text and message terms`() {
        val q = LogcatQuery.parse("frames message:retry -skipped tag:x a")
        assertEquals(listOf("frames", "retry"), q.highlightWords)
    }

    @Test
    fun `keys are listed in order`() =
        assertEquals(listOf("tag", "package", "process", "message", "level", "age", "is"), LogcatQuery.KEYS.keys.toList())

    @Test
    fun `withTerm toggles and level replaces level`() {
        assertEquals("tag:x level:warn", withTerm("level:info tag:x", "level:warn", true))
        assertEquals("tag:x", withTerm("tag:x level:warn", "level:warn", false))
        assertEquals("tag:x package:mine", withTerm("tag:x", "package:mine", true))
        assertEquals("tag:x package:mine", withTerm("tag:x package:mine", "package:mine", true))
        assertEquals(listOf("tag:\"a b\"", "level:warn"), queryTerms("tag:\"a b\" | level:warn"))
    }
}
