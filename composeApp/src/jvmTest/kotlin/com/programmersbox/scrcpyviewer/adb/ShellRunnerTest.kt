package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShellRunnerTest {
    private class FakeAdb(private val reply: suspend (String) -> String) : AdbClient({ error("") }) {
        val cmds = mutableListOf<String>()
        override suspend fun shell(serial: String, cmd: String): String {
            cmds += cmd
            return reply(cmd)
        }
    }

    @Test
    fun `wrap puts the marker on its own line`() {
        assertEquals(
            "logcat -d # all\necho __scrcpyviewer_exit=$?",
            ShellRunner.wrap("logcat -d # all"),
        )
    }

    @Test
    fun `run parses output and exit code`() = runBlocking {
        val adb = FakeAdb { "out\n__scrcpyviewer_exit=3\n" }
        val r = ShellRunner(adb, "s").run("x")
        assertEquals("out", r.output)
        assertEquals(3, r.exitCode)
        assertEquals(listOf(ShellRunner.wrap("x")), adb.cmds)
    }

    @Test
    fun `run reports null exit code when the marker is missing`() = runBlocking {
        val r = ShellRunner(FakeAdb { "partial\n" }, "s").run("x")
        assertEquals("partial", r.output)
        assertNull(r.exitCode)
    }

    @Test
    fun `run times out with the documented message`() = runBlocking {
        val runner = ShellRunner(FakeAdb { delay(5_000); "" }, "s")
        val e = assertFailsWith<ShellTimeoutException> { runner.run("x", timeoutMs = 50) }
        assertEquals("Timed out after 30 s; set Output to Shell tab for long commands", e.message)
    }
}
