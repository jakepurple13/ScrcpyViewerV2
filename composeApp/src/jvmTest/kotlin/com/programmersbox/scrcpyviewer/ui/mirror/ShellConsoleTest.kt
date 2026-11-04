package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** Streams the scripted events; with [hang] it then waits until cancelled, like a command that never ends. */
private class FakeRunner(private val events: List<ShellEvent>, private val hang: Boolean = false) :
    ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
    val commands = mutableListOf<String>()

    override fun stream(cmd: String): Flow<ShellEvent> {
        commands += cmd
        return flow {
            events.forEach { emit(it) }
            if (hang) awaitCancellation()
        }
    }
}

class ShellConsoleTest {
    // Launches run at once, up to their first suspension.
    private val eager = CoroutineScope(Dispatchers.Unconfined)
    private val toasts = mutableListOf<String>()

    private fun console(
        runner: FakeRunner,
        history: ShellHistory = ShellHistory(),
        maxLines: Int = 5_000,
    ) = ShellConsole(runner, history, eager, { toasts += it }, maxLines)

    @Test
    fun `typed command echoes, splits output on newlines, then exits`() {
        val history = ShellHistory()
        val runner = FakeRunner(
            listOf(ShellEvent.Output("Physical size: 10"), ShellEvent.Output("80x2400\nsecond\n"), ShellEvent.Exit(0, 48)),
        )
        val c = console(runner, history)
        c.input = "wm size"
        assertTrue(c.submit())
        assertEquals(
            listOf(
                ConsoleLine.Header("$ wm size", typed = true),
                ConsoleLine.Out("Physical size: 1080x2400"),
                ConsoleLine.Out("second"),
                ConsoleLine.Exit(0, 48),
            ),
            c.lines,
        )
        assertEquals("exit 0 · 48 ms", c.lines.last().text)
        assertEquals(listOf("wm size"), history.items)
        assertEquals("", c.input)
        assertFalse(c.running)
    }

    @Test
    fun `unterminated last line is flushed at exit`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("a\nb"), ShellEvent.Exit(1, 5))))
        c.input = "x"
        c.submit()
        assertEquals(listOf("a", "b"), c.lines.filterIsInstance<ConsoleLine.Out>().map { it.text })
    }

    @Test
    fun `second submit while running is refused`() {
        val c = console(FakeRunner(emptyList(), hang = true))
        c.input = "logcat"
        assertTrue(c.submit())
        assertTrue(c.running)
        c.input = "ls"
        assertFalse(c.submit())
        assertEquals(listOf("Stop the running command first"), toasts)
        assertFalse(c.runAction("A", "ls"))
        assertEquals(2, toasts.size)
    }

    @Test
    fun `action header shows the name and never the command`() {
        val runner = FakeRunner(listOf(ShellEvent.Output("com.x/.Main\n"), ShellEvent.Exit(0, 3)))
        val c = console(runner)
        assertTrue(c.runAction("Top activity", "dumpsys activity top"))
        assertEquals(ConsoleLine.Header("▶ Top activity", typed = false), c.lines.first())
        assertEquals(listOf("dumpsys activity top"), runner.commands)
        assertTrue(c.lines.none { "dumpsys" in it.text })
    }

    @Test
    fun `keeps only the last maxLines lines`() {
        val text = (1..5_010).joinToString("") { "l$it\n" }
        val c = console(FakeRunner(listOf(ShellEvent.Output(text), ShellEvent.Exit(0, 1))))
        c.input = "x"
        c.submit()
        assertEquals(5_000, c.lines.size)
        assertEquals(5_000, c.lineCount)
        assertEquals(ConsoleLine.Exit(0, 1), c.lines.last())
        assertEquals("l5010", c.lines[c.lines.size - 2].text)
        assertEquals("l12", c.lines.first().text)
    }

    @Test
    fun `filter is case-insensitive and keeps header and exit of matching runs`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("Start proc\nother\n"), ShellEvent.Exit(0, 1))))
        c.input = "a"
        c.submit()
        c.filter = "PROC"
        assertEquals(listOf("$ a", "Start proc", "exit 0 · 1 ms"), c.visible.map { it.text })
        assertEquals(1, c.matchCount)
        assertEquals(4, c.lineCount)
        c.filter = "zzz"
        assertTrue(c.visible.isEmpty())
        assertEquals(0, c.matchCount)
        c.filter = ""
        assertEquals(4, c.visible.size)
    }

    @Test
    fun `filter drops runs without a match`() {
        val outputs = ArrayDeque(listOf("hit\n", "miss\n", "hit again\n"))
        val c = ShellConsole(
            object : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
                override fun stream(cmd: String): Flow<ShellEvent> = flow {
                    emit(ShellEvent.Output(outputs.removeFirst()))
                    emit(ShellEvent.Exit(0, 1))
                }
            },
            ShellHistory(), eager, { toasts += it },
        )
        repeat(3) { c.input = "cmd$it"; c.submit() }
        c.filter = "hit"
        assertEquals(6, c.visible.size)
        assertEquals(2, c.matchCount)
        assertEquals(9, c.lineCount)
    }

    @Test
    fun `stop cancels the stream and ends with a null exit`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("tick\npart")), hang = true))
        c.input = "logcat"
        c.submit()
        assertTrue(c.running)
        c.stop()
        assertFalse(c.running)
        assertEquals(null, (c.lines.last() as ConsoleLine.Exit).code)
        assertEquals("stopped", c.lines.last().text)
        assertEquals(listOf("tick", "part"), c.lines.filterIsInstance<ConsoleLine.Out>().map { it.text })
        // A new command can start.
        c.input = "ls"
        assertTrue(c.submit())
    }

    @Test
    fun `clear empties the console`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("a\n"), ShellEvent.Exit(0, 1))))
        c.input = "x"
        c.submit()
        c.clear()
        assertTrue(c.lines.isEmpty())
    }

    @Test
    fun `history keeps the last 100 and dedups consecutive`() {
        val h = ShellHistory()
        h.add("a")
        h.add("a")
        assertEquals(listOf("a"), h.items)
        (1..120).forEach { h.add("c$it") }
        assertEquals(100, h.items.size)
        assertEquals("c120", h.items.last())
        assertEquals("c21", h.items.first())
    }

    @Test
    fun `up and down walk history and restore the draft`() {
        val h = ShellHistory()
        h.add("one")
        h.add("two")
        val c = console(FakeRunner(emptyList()), h)
        c.input = "dra"
        c.historyUp()
        assertEquals("two", c.input)
        c.historyUp()
        assertEquals("one", c.input)
        c.historyUp()
        assertEquals("one", c.input)
        c.historyDown()
        assertEquals("two", c.input)
        c.historyDown()
        assertEquals("dra", c.input)
        c.historyDown()
        assertEquals("dra", c.input)
    }

    /** A runner whose stream hands out its collector, so a test can emit after the collector was cancelled. */
    private class LeakyRunner : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
        var collector: FlowCollector<ShellEvent>? = null

        override fun stream(cmd: String): Flow<ShellEvent> = object : Flow<ShellEvent> {
            override suspend fun collect(collector: FlowCollector<ShellEvent>) {
                this@LeakyRunner.collector = collector
                awaitCancellation()
            }
        }

        fun leak(event: ShellEvent) = runBlocking { collector!!.emit(event) }
    }

    @Test
    fun `output after stop is ignored`() {
        val runner = LeakyRunner()
        val c = ShellConsole(runner, ShellHistory(), eager, { toasts += it })
        c.input = "logcat"
        c.submit()
        runner.leak(ShellEvent.Output("one\npar"))
        c.stop()
        runner.leak(ShellEvent.Output("late\n"))
        runner.leak(ShellEvent.Exit(0, 9))
        assertEquals(listOf("$ logcat", "one", "par", "stopped"), c.lines.map { it.text })
    }

    @Test
    fun `clear mid-stream leaves an empty console and drops the partial`() {
        val runner = LeakyRunner()
        val c = ShellConsole(runner, ShellHistory(), eager, { toasts += it })
        c.input = "logcat"
        c.submit()
        runner.leak(ShellEvent.Output("one\npar"))
        c.clear()
        assertFalse(c.running)
        runner.leak(ShellEvent.Output("tial\n"))
        assertTrue(c.lines.isEmpty())
        c.input = "ls"
        assertTrue(c.submit())
        assertEquals(listOf("$ ls"), c.lines.map { it.text })
    }

    @Test
    fun `null runner refuses with a message`() {
        val c = ShellConsole(null, ShellHistory(), eager, { toasts += it })
        c.input = "ls"
        assertFalse(c.submit())
        assertEquals(listOf("Not connected"), toasts)
        assertTrue(c.lines.isEmpty())
    }

    @Test
    fun `empty input does not run`() {
        val runner = FakeRunner(emptyList())
        val c = console(runner)
        c.input = "   "
        assertFalse(c.submit())
        assertTrue(runner.commands.isEmpty())
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun `stream failure prints an error line and ends the run`() {
        val c = ShellConsole(
            object : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
                override fun stream(cmd: String): Flow<ShellEvent> = flow { error("boom") }
            },
            ShellHistory(), eager, { toasts += it },
        )
        c.input = "x"
        c.submit()
        assertFalse(c.running)
        assertEquals("error: boom", c.lines[1].text)
        assertNull((c.lines.last() as ConsoleLine.Exit).code)
    }

    @Test
    fun `stream that ends without exit ends the run`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("a\n"))))
        c.input = "x"
        c.submit()
        assertFalse(c.running)
        assertEquals("stopped", c.lines.last().text)
    }

    @Test
    fun `match count is zero without a filter`() {
        val c = console(FakeRunner(listOf(ShellEvent.Output("a\n"), ShellEvent.Exit(0, 1))))
        c.input = "x"
        c.submit()
        assertEquals(0, c.matchCount)
        c.filter = "a"
        assertEquals(1, c.matchCount)
    }

    @Test
    fun `typing after up resets the history position`() {
        val h = ShellHistory()
        h.add("one")
        h.add("two")
        val c = console(FakeRunner(emptyList()), h)
        c.historyUp()
        c.input = "mine"
        c.historyDown()
        assertEquals("mine", c.input)
        c.historyUp()
        assertEquals("two", c.input)
    }
}
