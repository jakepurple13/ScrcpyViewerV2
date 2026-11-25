package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.adb.ShellTimeoutException

/** Finds out whether the device is rooted. [detect] is silent; only [ask] runs `su`, which can prompt on the phone. */
open class RootProbe(private val runner: ShellRunner) {
    /** adb root when the shell is uid 0, [RootState.Available] when an `su` binary exists, otherwise none. */
    open suspend fun detect(): RootState {
        if (uidZero(runner.run("id -u"))) return RootState.AdbRoot
        val su = runner.run("command -v su")
        return if (su.exitCode == 0 && su.output.isNotBlank()) RootState.Available else RootState.None
    }

    /** Runs `id -u` through each su style in turn; the first that answers 0 wins. Allows time for the phone's prompt. */
    open suspend fun ask(): Pair<RootState, SuStyle?> {
        for (style in SuStyle.entries) {
            val cmd = when (style) {
                SuStyle.DashC -> "su -c 'id -u'"
                SuStyle.Zero -> "su 0 sh -c 'id -u'"
            }
            val result = try {
                runner.run(cmd, ASK_TIMEOUT_MS)
            } catch (_: ShellTimeoutException) {
                continue
            }
            if (uidZero(result)) return RootState.Rooted to style
        }
        return RootState.Denied to null
    }

    private fun uidZero(result: com.programmersbox.scrcpyviewer.adb.ShellResult) =
        result.exitCode == 0 && result.output.trim().lines().lastOrNull()?.trim() == "0"

    companion object {
        /** Long enough for someone to notice Magisk's prompt and tap Grant. */
        const val ASK_TIMEOUT_MS = 20_000L
    }
}
