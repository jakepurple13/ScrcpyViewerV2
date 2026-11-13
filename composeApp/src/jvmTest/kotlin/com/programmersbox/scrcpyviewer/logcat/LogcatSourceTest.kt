package com.programmersbox.scrcpyviewer.logcat

import com.programmersbox.scrcpyviewer.adb.AdbClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogcatSourceTest {
    private class FakeAdb(private val chunks: List<String> = emptyList(), private val shellOut: String = "") :
        AdbClient({ error("") }) {
        var streamCmd: String? = null
        var shellCmd: String? = null
        override fun shellStream(serial: String, cmd: String): Flow<String> = flow {
            streamCmd = cmd
            chunks.forEach { emit(it) }
        }

        override suspend fun shell(serial: String, cmd: String): String {
            shellCmd = cmd
            return shellOut
        }
    }

    @Test
    fun `first start asks for the last 1000 lines`() {
        assertEquals("logcat -v threadtime -T 1000", logcatCommand(null))
    }

    @Test
    fun `resume quotes the timestamp`() {
        assertEquals("logcat -v threadtime -T '11-13 10:20:30.456'", logcatCommand("11-13 10:20:30.456"))
        assertFailsWith<IllegalArgumentException> { logcatCommand("11-13 10:20:30.456'; reboot") }
    }

    @Test
    fun `lines parse across chunk splits and skip markers`() {
        val adb = FakeAdb(
            listOf(
                "--------- beginning of main\n11-13 10:20:30.456  100  200 I Tag: hel",
                "lo\n11-13 10:20:31.000  100  200 E Tag2: last",
            ),
        )
        val out = runBlocking { LogcatSource(adb, "s").lines(null).toList() }
        assertEquals(listOf("hello", "last"), out.map { it.message })
        assertEquals(listOf("Tag", "Tag2"), out.map { it.tag })
        assertEquals(logcatCommand(null), adb.streamCmd)
    }

    @Test
    fun `parsePs maps pids and skips the header`() {
        val out = "PID NAME\n  1 init\n 523   system_server \nabc bad\n 900 com.example.notes:remote\n"
        assertEquals(mapOf(1 to "init", 523 to "system_server", 900 to "com.example.notes:remote"), parsePs(out))
    }

    @Test
    fun `processes runs ps`() {
        val adb = FakeAdb(shellOut = "PID NAME\n7 a.b\n")
        assertEquals(mapOf(7 to "a.b"), runBlocking { LogcatSource(adb, "s").processes() })
        assertEquals("ps -A -o PID,NAME", adb.shellCmd)
    }

    @Test
    fun `packageOf strips the process suffix`() {
        assertEquals("com.example.notes", packageOf("com.example.notes:remote"))
        assertEquals("system_server", packageOf("system_server"))
    }
}
