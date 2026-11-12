package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import java.io.IOException

private const val LONG_TIMEOUT_MS = 120_000L

/** File operations on the device over `adb shell`; mutating calls throw [IOException] with the device's message. */
open class DeviceFiles(
    private val runner: ShellRunner,
    private val scanFile: (String) -> Unit,
) {
    open suspend fun list(dir: String): Listing {
        val r = runner.run(listCommand(dir))
        return parseListing(r.output, r.exitCode)
    }

    open suspend fun counts(dir: String, folders: List<String>): Map<String, Int> {
        if (folders.isEmpty()) return emptyMap()
        return parseCounts(runner.run(countsCommand(dir, folders)).output)
    }

    open suspend fun freeSpace(): Long? {
        val r = runner.run(freeSpaceCommand())
        return if (r.exitCode == 0) parseFreeSpace(r.output) else null
    }

    open suspend fun mkdir(dir: String, name: String) {
        mutate("mkdir ${shellQuote(devicePath(dir, name))}")
        scanFile(dir)
    }

    /** `mv -n` never overwrites, so an existing target fails instead of being replaced. */
    open suspend fun rename(dir: String, from: String, to: String) {
        mutate("mv -n ${shellQuote(devicePath(dir, from))} ${shellQuote(devicePath(dir, to))}")
        scanFile(dir)
    }

    open suspend fun move(
        dir: String,
        names: List<String>,
        toDir: String,
        renames: Map<String, String> = emptyMap(),
    ) {
        if (names.isEmpty()) return
        val cmd = names.joinToString(" && ") {
            "mv ${shellQuote(devicePath(dir, it))} ${shellQuote(devicePath(toDir, renames[it] ?: it))}"
        }
        mutate(cmd, LONG_TIMEOUT_MS)
        scanFile(dir)
        if (toDir != dir) scanFile(toDir)
    }

    open suspend fun delete(dir: String, names: List<String>) {
        if (names.isEmpty()) return
        mutate("rm -rf -- ${names.joinToString(" ") { shellQuote(devicePath(dir, it)) }}", LONG_TIMEOUT_MS)
        scanFile(dir)
    }

    private suspend fun mutate(cmd: String, timeoutMs: Long? = null) {
        val r: ShellResult = if (timeoutMs == null) runner.run(cmd) else runner.run(cmd, timeoutMs)
        if (r.exitCode != 0) {
            val last = r.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            throw IOException(last ?: if (r.exitCode == null) "No response from device" else "Exit code ${r.exitCode}")
        }
    }
}
