package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Answers each command with the first matching (prefix, result) pair; anything else exits 127. */
internal class ScriptRunner(private vararg val answers: Pair<String, ShellResult>) :
    ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
    val commands = mutableListOf<String>()
    override suspend fun run(cmd: String, timeoutMs: Long): ShellResult {
        commands += cmd
        return answers.firstOrNull { cmd.startsWith(it.first) }?.second ?: ShellResult("not found", 127, 0)
    }
}

internal fun ok(output: String) = ShellResult(output, 0, 0)

class RootProbeTest {
    @Test
    fun detectNeverRunsSu() = runBlocking {
        val runner = ScriptRunner("id -u" to ok("2000\n"), "command -v su" to ok("/system/bin/su\n"))
        assertEquals(RootState.Available, RootProbe(runner).detect())
        assertTrue(runner.commands.none { it.startsWith("su") }, runner.commands.toString())
    }

    @Test
    fun detectNone() = runBlocking {
        val runner = ScriptRunner("id -u" to ok("2000\n"), "command -v su" to ShellResult("", 1, 0))
        assertEquals(RootState.None, RootProbe(runner).detect())
    }

    @Test
    fun detectAdbRoot() = runBlocking {
        assertEquals(RootState.AdbRoot, RootProbe(ScriptRunner("id -u" to ok("0\n"))).detect())
    }

    @Test
    fun askDashC() = runBlocking {
        val runner = ScriptRunner("su -c" to ok("0\n"))
        assertEquals(RootState.Rooted to SuStyle.DashC, RootProbe(runner).ask())
    }

    @Test
    fun askFallsBackToZeroStyle() = runBlocking {
        val runner = ScriptRunner("su -c" to ShellResult("su: invalid option", 1, 0), "su 0 sh -c" to ok("0\n"))
        assertEquals(RootState.Rooted to SuStyle.Zero, RootProbe(runner).ask())
    }

    @Test
    fun askDenied() = runBlocking {
        val runner = ScriptRunner("su -c" to ShellResult("Permission denied", 1, 0), "su 0 sh -c" to ShellResult("Permission denied", 1, 0))
        assertEquals(RootState.Denied to null, RootProbe(runner).ask())
    }
}
