package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun line(
    time: String = "10:00:00.000",
    message: String = "hello",
    pid: Int = 100,
    tid: Int = 100,
    tag: String = "Tag",
    level: LogLevel = LogLevel.Info,
) = LogcatLine("11-13", time, pid, tid, level, tag, message)

/** Each [lines] call opens a channel the test pushes into; [ps] answers [processes] once [psGate] completes. */
private class FakeSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val sinces = mutableListOf<String?>()
    val streams = mutableListOf<Channel<LogcatLine>>()
    var ps: Map<Int, String> = emptyMap()
    var psCalls = 0
    var psGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)

    val stream get() = streams.last()

    override fun lines(since: String?): Flow<LogcatLine> {
        sinces += since
        val ch = Channel<LogcatLine>(Channel.UNLIMITED)
        streams += ch
        return ch.consumeAsFlow()
    }

    override suspend fun processes(): Map<Int, String> {
        psCalls++
        psGate.await()
        return ps
    }
}

private fun hostTime(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

private fun LogcatController.lines() = items.filterIsInstance<LogcatItem.Line>()
private fun LogcatController.messages() = lines().map { it.entry.line.message }

class LogcatControllerTest {
    private val start = 1_763_000_000_000L

    private fun TestScope.controller(
        source: LogcatSource? = FakeSource(),
        toasts: MutableList<String> = mutableListOf(),
        maxEntries: Int = LOGCAT_MAX_ENTRIES,
    ) = LogcatController(
        source = source,
        scope = backgroundScope,
        notify = { toasts += it },
        clock = { start + testScheduler.currentTime },
        maxEntries = maxEntries,
        io = kotlin.coroutines.EmptyCoroutineContext,
    )

    @Test
    fun `nothing runs before play`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        runCurrent()
        assertEquals(LogcatRun.NotStarted, c.run)
        assertTrue(source.sinces.isEmpty())
        assertEquals(0, source.psCalls)
    }

    @Test
    fun `play starts with since = null and lines appear`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        assertEquals(LogcatRun.Running, c.run)
        assertEquals(listOf<String?>(null), source.sinces)
        source.stream.trySend(line(message = "a"))
        source.stream.trySend(line(message = "b"))
        runCurrent()
        assertEquals(listOf("a", "b"), c.messages())
        assertEquals(2, c.totalLines)
    }

    @Test
    fun `pause cancels the stream and records pausedAt`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        advanceTimeBy(5_000)
        c.pause()
        runCurrent()
        assertEquals(LogcatRun.Paused, c.run)
        assertEquals(hostTime(start + 5_000), c.pausedAt)
        assertTrue(source.stream.isClosedForSend)
    }

    @Test
    fun `stopForLeaving gives StoppedByLeaving`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        c.stopForLeaving()
        runCurrent()
        assertEquals(LogcatRun.StoppedByLeaving, c.run)
        assertTrue(source.stream.isClosedForSend)
    }

    @Test
    fun `stopForLeaving before any play stays NotStarted`() = runTest {
        val c = controller()
        c.stopForLeaving()
        assertEquals(LogcatRun.NotStarted, c.run)
    }

    @Test
    fun `stopForLeaving while paused keeps it paused`() = runTest {
        val c = controller()
        c.play()
        runCurrent()
        c.pause()
        val at = c.pausedAt
        advanceTimeBy(3_000)
        c.stopForLeaving()
        assertEquals(LogcatRun.Paused, c.run)
        assertEquals(at, c.pausedAt)
    }

    @Test
    fun `resume asks since the last line's timestamp, drops replayed lines, keeps a new line with the same timestamp, and puts a Gap before read-back lines`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line("10:00:00.000", "old"))
        source.stream.trySend(line("10:00:01.000", "x"))
        source.stream.trySend(line("10:00:01.000", "y"))
        runCurrent()
        advanceTimeBy(2_000)
        c.pause()
        val pausedAt = c.pausedAt
        advanceTimeBy(60_000)
        c.play()
        runCurrent()
        assertEquals(LogcatRun.Running, c.run)
        assertNull(c.pausedAt)
        assertEquals("11-13 10:00:01.000", source.sinces.last())
        source.stream.trySend(line("10:00:01.000", "x"))
        source.stream.trySend(line("10:00:01.000", "y"))
        source.stream.trySend(line("10:00:01.000", "z"))
        source.stream.trySend(line("10:00:02.000", "later"))
        source.stream.trySend(line("10:00:02.000", "x"))
        runCurrent()
        assertEquals(listOf("old", "x", "y", "z", "later", "x"), c.messages())
        val gapAt = c.items.indexOfFirst { it is LogcatItem.Gap }
        assertEquals(3, gapAt)
        val gap = assertIs<LogcatItem.Gap>(c.items[gapAt])
        assertEquals(pausedAt, gap.pausedAt)
        assertEquals(hostTime(start + 62_000), gap.resumedAt)
        assertEquals(c.items.size, c.items.map { it.key }.toSet().size)
    }

    @Test
    fun `resume with nothing new adds no Gap`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line("10:00:01.000", "x"))
        runCurrent()
        c.pause()
        c.play()
        runCurrent()
        source.stream.trySend(line("10:00:01.000", "x"))
        runCurrent()
        advanceTimeBy(100)
        assertTrue(c.items.none { it is LogcatItem.Gap })
        assertEquals(listOf("x"), c.messages())
    }

    @Test
    fun `buffer keeps the newest maxEntries`() = runTest {
        val source = FakeSource()
        val c = controller(source, maxEntries = 5)
        c.play()
        runCurrent()
        repeat(8) { source.stream.trySend(line(message = "m$it")) }
        runCurrent()
        assertEquals((3..7).map { "m$it" }, c.messages())
    }

    @Test
    fun `lines are batched`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        val before = c.itemWrites
        repeat(2_000) { source.stream.trySend(line(message = "m$it")) }
        runCurrent()
        assertEquals(2_000, c.items.size)
        assertEquals(before + 1, c.itemWrites)
        // More lines inside the same 50 ms window wait for the next drain.
        source.stream.trySend(line(message = "late"))
        advanceTimeBy(20)
        runCurrent()
        assertEquals(2_000, c.items.size)
        assertEquals(before + 1, c.itemWrites)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(2_001, c.items.size)
        assertEquals(before + 2, c.itemWrites)
    }

    @Test
    fun `unknown pid triggers one ps lookup per second and fills the package`() = runTest {
        val source = FakeSource()
        source.ps = mapOf(100 to "com.example:remote")
        source.psGate = CompletableDeferred()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a", pid = 100))
        runCurrent()
        assertEquals(1, source.psCalls)
        assertNull(c.lines().single().pkg)
        source.stream.trySend(line(message = "b", pid = 200))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, source.psCalls)
        source.psGate.complete(Unit)
        runCurrent()
        assertEquals("com.example", c.lines()[0].pkg)
        assertNull(c.lines()[1].pkg)
        // pid 200 is still unknown: the next lookup waits until a second after the first.
        source.stream.trySend(line(message = "c", pid = 200))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, source.psCalls)
        source.ps = mapOf(100 to "com.example:remote", 200 to "com.other")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, source.psCalls)
        assertEquals(listOf("com.example", "com.other", "com.other"), c.lines().map { it.pkg })
        // Known pids need no lookup.
        source.stream.trySend(line(message = "d", pid = 100))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, source.psCalls)
        assertEquals("com.example", c.lines().last().pkg)
        val ctx = c.suggestionContext()
        assertEquals(setOf("com.example", "com.other"), ctx.packages)
        assertEquals(setOf("com.example:remote", "com.other"), ctx.processes)
        assertEquals(mapOf("Tag" to 4), ctx.tags)
    }

    @Test
    fun `query filters visible and levelCounts follow`() = runTest {
        val source = FakeSource()
        source.ps = mapOf(100 to "com.example", 200 to "com.other")
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a", level = LogLevel.Info, pid = 100))
        source.stream.trySend(line(message = "b", level = LogLevel.Warn, pid = 200))
        source.stream.trySend(line(message = "c", level = LogLevel.Error, pid = 100))
        runCurrent()
        assertEquals(3, c.visible.size)
        assertEquals(mapOf(LogLevel.Info to 1, LogLevel.Warn to 1, LogLevel.Error to 1), c.levelCounts)
        c.query = "level:warn"
        assertEquals(listOf("b", "c"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
        assertEquals(mapOf(LogLevel.Warn to 1, LogLevel.Error to 1), c.levelCounts)
        assertEquals(3, c.totalLines)
        c.query = ""
        c.pick("com.example")
        assertEquals("com.example", c.mine)
        assertEquals("package:mine", c.query)
        assertEquals(listOf("a", "c"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
        c.pick("com.other")
        assertEquals("package:mine", c.query)
        assertEquals(listOf("b"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
    }

    @Test
    fun `clear empties items and toasts`() = runTest {
        val source = FakeSource()
        val toasts = mutableListOf<String>()
        val c = controller(source, toasts)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a"))
        runCurrent()
        c.query = "tag:Tag"
        c.clear()
        assertTrue(c.items.isEmpty())
        assertEquals(LogcatRun.Running, c.run)
        assertEquals("tag:Tag", c.query)
        assertEquals(listOf("Cleared. Older lines stay on the device buffer."), toasts)
    }

    @Test
    fun `stream failure pauses and notifies`() = runTest {
        val source = FakeSource()
        val toasts = mutableListOf<String>()
        val c = controller(source, toasts)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a"))
        source.stream.close(IOException("device gone"))
        runCurrent()
        assertEquals(LogcatRun.Paused, c.run)
        assertEquals(listOf("a"), c.messages())
        assertEquals(listOf("Logcat stopped: device gone"), toasts)
    }

    @Test
    fun `requestNudge counts up`() = runTest {
        val c = controller()
        c.requestNudge()
        c.requestNudge()
        assertEquals(2, c.nudge)
    }

    @Test
    fun `stack trace lines fold into the line above`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "boom"))
        runCurrent()
        source.stream.trySend(line(message = "\tat a.B.c(B.kt:1)"))
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf("\tat a.B.c(B.kt:1)"), c.lines().single().entry.trace)
    }
}
