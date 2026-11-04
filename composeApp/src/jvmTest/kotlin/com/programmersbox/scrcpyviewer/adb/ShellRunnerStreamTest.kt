package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellRunnerStreamTest {
    private class ChunkAdb(private val chunks: List<String>) : AdbClient({ error("") }) {
        var sent: String? = null
        override fun shellStream(serial: String, cmd: String): Flow<String> = flow {
            sent = cmd
            chunks.forEach { emit(it) }
        }
    }

    private fun collect(vararg chunks: String): Pair<String, ShellEvent.Exit> {
        val ticks = ArrayDeque(listOf(1_000L, 1_250L))
        val adb = ChunkAdb(chunks.toList())
        val events = runBlocking { ShellRunner(adb, "s", ticks::removeFirst).stream("cmd").toList() }
        assertEquals(ShellRunner.wrap("cmd"), adb.sent)
        val exit = events.last() as ShellEvent.Exit
        val text = events.dropLast(1).joinToString("") { (it as ShellEvent.Output).text }
        return text to exit
    }

    @Test
    fun `plain output then exit code`() {
        assertEquals("a\nb\n" to ShellEvent.Exit(0, 250), collect("a\n", "b\n__scrcpyviewer_exit=0\n"))
    }

    @Test
    fun `marker split across chunks`() {
        assertEquals("x\n" to ShellEvent.Exit(12, 250), collect("x\n__scrcpyvi", "ewer_exit=", "12\n"))
    }

    @Test
    fun `marker directly after unterminated output`() {
        assertEquals("abc" to ShellEvent.Exit(0, 250), collect("abc__scrcpyviewer_exit=0\n"))
    }

    @Test
    fun `partial marker prefix that is not the marker is emitted intact`() {
        assertEquals("__scrabble\n" to ShellEvent.Exit(0, 250), collect("__scr", "abble\n__scrcpyviewer_exit=0\n"))
    }

    @Test
    fun `no marker gives null exit and keeps held text`() {
        assertEquals("a\n__scr" to ShellEvent.Exit(null, 250), collect("a\n", "__scr"))
    }

    @Test
    fun `exit code without trailing newline`() {
        assertEquals("" to ShellEvent.Exit(7, 250), collect("__scrcpyviewer_exit=7"))
    }
}
