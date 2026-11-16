package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.logcat.EntryInfo
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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

    @Test
    fun `a cancellation from the source that isn't ours ends in Paused`() = runTest {
        val source = FakeSource()
        val toasts = mutableListOf<String>()
        val c = controller(source, toasts)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a"))
        source.stream.close(CancellationException("stray"))
        runCurrent()
        assertEquals(LogcatRun.Paused, c.run)
        assertEquals(listOf("a"), c.messages())
        assertTrue(toasts.isEmpty())
        c.play()
        runCurrent()
        assertEquals(LogcatRun.Running, c.run)
        assertEquals(2, source.sinces.size)
    }

    @Test
    fun `a missed pid is looked up again after 10 s`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a", pid = 300))
        runCurrent()
        assertEquals(1, source.psCalls)
        source.ps = mapOf(300 to "com.reused")
        advanceTimeBy(5_000)
        source.stream.trySend(line(message = "b", pid = 300))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, source.psCalls)
        assertNull(c.lines().last().pkg)
        advanceTimeBy(4_000)
        source.stream.trySend(line(message = "c", pid = 300))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, source.psCalls)
        assertEquals("com.reused", c.lines().last().pkg)
    }

    @Test
    fun `a continuation after resume leaves no trailing Gap`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line("10:00:00.000", "boom"))
        runCurrent()
        c.pause()
        c.play()
        runCurrent()
        source.stream.trySend(line("10:00:00.500", "\tat a.B.c(B.kt:1)"))
        runCurrent()
        assertTrue(c.items.none { it is LogcatItem.Gap })
        assertEquals(listOf("\tat a.B.c(B.kt:1)"), c.lines().single().entry.trace)
        source.stream.trySend(line("10:00:01.000", "next"))
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf("Line", "Gap", "Line"), c.items.map { it::class.simpleName })
    }

    @Test
    fun `popOut keeps lines, query and run state, and dock keeps them too`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        var docked = 0
        c.onDock = { docked++ }
        c.query = "tag:Tag"
        c.compact = true
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "one"))
        advanceTimeBy(60)
        runCurrent()
        val key = c.lines().single().key
        c.flipTrace(key)

        c.popOut()
        assertTrue(c.popped)
        assertEquals(LogcatRun.Running, c.run)
        assertEquals(listOf("one"), c.messages())
        assertEquals("tag:Tag", c.query)
        assertTrue(c.compact)
        assertEquals(true, c.traceFlips[key])
        assertEquals(1, source.streams.size) // the same stream, not restarted

        c.dock()
        assertEquals(false, c.popped)
        assertEquals(LogcatRun.Running, c.run)
        assertEquals(listOf("one"), c.messages())
        assertEquals(true, c.traceFlips[key])
        assertEquals(1, docked)

        // Paused stays paused through both moves.
        c.pause()
        c.popOut()
        assertEquals(LogcatRun.Paused, c.run)
        c.dock()
        assertEquals(LogcatRun.Paused, c.run)
        assertEquals(1, source.streams.size)
        assertEquals(2, docked)
        // Docking what isn't out does nothing.
        c.dock()
        assertEquals(2, docked)
    }

    @Test
    fun `alwaysOnTop starts off on every popOut and toggles with a toast`() = runTest {
        val toasts = mutableListOf<String>()
        val c = controller(toasts = toasts)
        c.popOut()
        assertEquals(false, c.alwaysOnTop)
        c.toggleAlwaysOnTop()
        assertTrue(c.alwaysOnTop)
        assertEquals(listOf("Logcat window stays on top"), toasts)
        c.toggleAlwaysOnTop()
        assertEquals(false, c.alwaysOnTop)
        assertEquals(listOf("Logcat window stays on top", "Logcat window no longer stays on top"), toasts)
        c.toggleAlwaysOnTop()
        c.dock()
        c.popOut()
        assertEquals(false, c.alwaysOnTop)
    }

    @Test
    fun `raise asks the window to come forward`() = runTest {
        val c = controller()
        c.popOut()
        val before = c.raiseRequests
        c.raise()
        assertEquals(before + 1, c.raiseRequests)
    }

    @Test
    fun `a reused pid takes the process from the newest ps, not the old one`() = runTest {
        val source = FakeSource()
        source.ps = mapOf(100 to "com.a")
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "a1", pid = 100))
        runCurrent()
        assertEquals("com.a", c.lines().single().pkg)
        assertEquals(1, source.psCalls)

        // com.a exits, Android hands pid 100 to com.b; a line from an unknown pid brings the next ps.
        source.ps = mapOf(100 to "com.b", 200 to "com.c")
        source.stream.trySend(line(message = "c1", pid = 200))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, source.psCalls) // at most one ps a second
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, source.psCalls)
        source.stream.trySend(line(message = "b1", pid = 100))
        advanceTimeBy(100)
        runCurrent()
        assertEquals("com.b", c.lines().last().pkg)
        assertEquals("com.b", c.lines().last().process)
        assertEquals(listOf("com.b", "com.c", "com.b"), c.lines().map { it.pkg })
        assertEquals(2, source.psCalls)
        c.query = "package=:com.b"
        assertEquals(listOf("a1", "b1"), c.visible.map { (it as LogcatItem.Line).entry.line.message })

        // A ps that no longer lists a pid (its process exited) leaves that pid's lines as they are.
        source.ps = mapOf(300 to "com.d")
        source.stream.trySend(line(message = "d1", pid = 300))
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(3, source.psCalls)
        assertEquals(listOf("com.b", "com.c", "com.b", "com.d"), c.lines().map { it.pkg })
        assertEquals(setOf("com.b", "com.c", "com.d"), c.suggestionContext().processes)
    }

    @Test
    fun `refiltering 20k entries after a 100-line batch stays within budget`() = runTest {
        val source = FakeSource()
        source.ps = (1..40).associate { it to "com.app$it" }
        val c = controller(source)
        c.play()
        runCurrent()
        val tags = listOf("OkHttp", "SyncManager", "Choreographer", "ActivityManager", "AndroidRuntime", "OkHttpClient")
        var n = 0
        fun batch(size: Int) {
            repeat(size) {
                val i = n++
                val tag = tags[i % tags.size]
                val pid = 1 + i % 40
                if (i % 50 == 0) {
                    // An entry with a 3-line trace.
                    source.stream.trySend(line(message = "java.lang.IllegalStateException: retry $i", pid = pid, tid = pid, tag = "W", level = LogLevel.Warn))
                    source.stream.trySend(line(message = "    at com.example.Foo.bar(Foo.kt:$i)", pid = pid, tid = pid, tag = "W", level = LogLevel.Warn))
                    source.stream.trySend(line(message = "    at com.example.Foo.baz(Foo.kt:$i)", pid = pid, tid = pid, tag = "W", level = LogLevel.Warn))
                } else {
                    val level = LogLevel.entries[i % LogLevel.entries.size]
                    source.stream.trySend(line(message = "request $i ${if (i % 3 == 0) "retry" else "ok"} to host", pid = pid, tag = tag, level = level))
                }
            }
            advanceTimeBy(60)
            runCurrent()
        }
        batch(LOGCAT_MAX_ENTRIES)
        assertEquals(LOGCAT_MAX_ENTRIES, c.totalLines)
        c.query = "tag~:^(Ok|Sync) retry"
        assertTrue(c.visible.isNotEmpty())

        val times = mutableListOf<Long>()
        val batchTimes = mutableListOf<Long>()
        repeat(40) { round ->
            val b0 = System.nanoTime()
            batch(100)
            val t0 = System.nanoTime()
            val visible = c.visible
            val counts = c.levelCounts
            val took = System.nanoTime() - t0
            c.suggestionContext()
            val batchTook = System.nanoTime() - b0
            assertTrue(visible.isNotEmpty() && counts.isNotEmpty())
            // The first 10 warm up the JIT.
            if (round >= 10) {
                times += took
                batchTimes += batchTook
            }
        }
        assertEquals(LOGCAT_MAX_ENTRIES, c.totalLines)
        val median = times.sorted()[times.size / 2] / 1_000_000.0
        val batchMedian = batchTimes.sorted()[batchTimes.size / 2] / 1_000_000.0
        println("Logcat refilter of $LOGCAT_MAX_ENTRIES entries: median %.2f ms over ${times.size} rounds".format(median))
        println("Logcat 100-line batch with filter and suggestions: median %.2f ms".format(batchMedian))
        // Budget is 8 ms on a dev machine; the assert is generous so a slow CI box doesn't flake.
        assertTrue(median < 25.0, "median refilter took $median ms")
    }

    /** What [LogcatController.visible] and the counts must be, filtered from scratch over every item at [now]. */
    private fun LogcatController.assertMatchesScratch(now: Long, what: String) {
        val scratch = items.filter { it !is LogcatItem.Line || parsed.matches(it.entry, EntryInfo(it.pkg, it.process), mine, now) }
        assertEquals(scratch, visible, "visible after $what")
        val shown = scratch.filterIsInstance<LogcatItem.Line>()
        assertEquals(shown.groupingBy { it.entry.line.level }.eachCount(), levelCounts, "levelCounts after $what")
        val all = items.filterIsInstance<LogcatItem.Line>()
        assertEquals(all.size, totalLines, "totalLines after $what")
        val ctx = suggestionContext()
        assertEquals(all.groupingBy { it.entry.line.tag }.eachCount(), ctx.tags, "tags after $what")
        assertEquals(all.mapNotNullTo(HashSet()) { it.pkg }, ctx.packages, "packages after $what")
        assertEquals(all.mapNotNullTo(HashSet()) { it.process }, ctx.processes, "processes after $what")
    }

    @Test
    fun `incremental filtering matches filtering from scratch`() {
        val queries = listOf(
            "", "Ok", "tag:Sync", "-tag:Sync", "level:warn", "message:at", "message~:Foo\\.b", "is:crash", "is:stacktrace",
            "-is:stacktrace", "retry | level:error", "package:mine", "-package:mine", "process:com.app2", "package:com.app1",
            "age:2s", "-age:1s", "age:3s -age:1s", "age:1s | is:crash", "level:info age:2s",
        )
        val tags = listOf("OkHttp", "SyncManager", "Choreographer", "AndroidRuntime")
        for (seed in 1..6) runTest {
            val rnd = Random(seed)
            val source = FakeSource()
            source.ps = mapOf(1 to "com.app1", 2 to "com.app2")
            val c = controller(source, maxEntries = 60)
            c.play()
            runCurrent()
            var t = 0
            fun time() = "10:%02d:%02d.%03d".format(t / 60_000 % 60, t / 1_000 % 60, t % 1_000)
            repeat(300) { step ->
                when (rnd.nextInt(20)) {
                    0 -> c.query = queries.random(rnd)
                    1 -> c.pick("com.app${rnd.nextInt(1, 4)}")
                    2 -> if (c.run == LogcatRun.Running) c.pause() else c.play()
                    3 -> source.ps = (1..4).filter { rnd.nextBoolean() }.associateWith { "com.app${rnd.nextInt(1, 5)}" }
                    4 -> if (rnd.nextInt(5) == 0) c.clear()
                    else -> if (c.run == LogcatRun.Running) {
                        repeat(rnd.nextInt(1, 25)) {
                            if (rnd.nextInt(4) != 0) t += rnd.nextInt(0, 40)
                            val pid = rnd.nextInt(1, 6)
                            when (rnd.nextInt(8)) {
                                0 -> {
                                    // A crash head, its Process: line and a trace.
                                    val crash = line(time(), "FATAL EXCEPTION: main", pid, pid, "AndroidRuntime", LogLevel.Error)
                                    source.stream.trySend(crash)
                                    source.stream.trySend(crash.copy(message = "Process: com.app$pid, PID: $pid"))
                                    repeat(rnd.nextInt(3)) { source.stream.trySend(crash.copy(message = "    at com.example.Foo.bar(Foo.kt:$it)")) }
                                }
                                1 -> {
                                    val head = line(time(), "java.lang.IllegalStateException: retry", pid, pid, "W", LogLevel.Warn)
                                    source.stream.trySend(head)
                                    repeat(rnd.nextInt(4)) { source.stream.trySend(head.copy(message = "    at com.example.Foo.baz(Foo.kt:$it)")) }
                                }
                                // A trace line that may fold into the entry the previous batch ended with.
                                2 -> source.stream.trySend(line(time(), "    at com.example.Foo.qux(Foo.kt:1)", pid, pid, "W", LogLevel.Warn))
                                else -> source.stream.trySend(
                                    line(time(), "request ${if (rnd.nextBoolean()) "retry" else "ok"}", pid, rnd.nextInt(1, 3), tags.random(rnd), LogLevel.entries.random(rnd)),
                                )
                            }
                        }
                    }
                }
                advanceTimeBy(rnd.nextLong(0, 700))
                runCurrent()
                // The list's ticker would call this; age: is judged at its last tick.
                c.tickAge()
                c.assertMatchesScratch(start + testScheduler.currentTime, "step $step of seed $seed, query \"${c.query}\"")
            }
        }
    }

    @Test
    fun `age filter drops lines as time passes on a quiet stream`() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.play()
        runCurrent()
        source.stream.trySend(line(message = "old", level = LogLevel.Warn))
        advanceTimeBy(60)
        runCurrent()
        advanceTimeBy(20_000)
        source.stream.trySend(line(message = "new"))
        advanceTimeBy(60)
        runCurrent()
        c.query = "age:30s"
        assertEquals(listOf("old", "new"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
        c.pause()
        advanceTimeBy(15_000)
        c.tickAge()
        assertEquals(listOf("new"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
        assertEquals(mapOf(LogLevel.Info to 1), c.levelCounts)
        advanceTimeBy(30_000)
        c.tickAge()
        assertTrue(c.visible.isEmpty())
        assertTrue(c.levelCounts.isEmpty())
        assertEquals(2, c.totalLines)
        // Negated, the same lines come back once they are older than the limit.
        c.query = "-age:40s"
        assertEquals(listOf("old", "new"), c.visible.map { (it as LogcatItem.Line).entry.line.message })
    }

    @Test
    fun `flipTrace flips and flips back`() = runTest {
        val c = controller()
        c.flipTrace(7)
        assertEquals(true, c.traceFlips[7])
        c.flipTrace(7)
        assertEquals(false, c.traceFlips[7])
    }
}
