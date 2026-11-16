package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import java.io.IOException
import java.util.UUID

private const val LONG_TIMEOUT_MS = 120_000L

/** Size limit of one batched command; well under the adb request limit, leaving room for the shell wrapper. */
private const val MAX_COMMAND_BYTES = 32 * 1024

private const val RM = "rm -rf -- "

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

    /** Every folder and regular file under [names] in [dir] (see [treeCommand]). */
    open suspend fun tree(dir: String, names: List<String>): List<TreeEntry> =
        if (names.isEmpty()) emptyList() else parseTree(runner.run(treeCommand(dir, names)).output)

    open suspend fun freeSpace(): DeviceSpace? {
        val r = runner.run(freeSpaceCommand())
        return if (r.exitCode == 0) parseFreeSpace(r.output) else null
    }

    open suspend fun mkdir(dir: String, name: String) {
        mutate("mkdir ${shellQuote(devicePath(dir, name))}")
        scanFile(dir)
    }

    /**
     * Fails with "<to>: already exists" when the target exists; toybox `mv -n` would skip it and exit 0. A rename by
     * case only (photo.jpg to Photo.jpg) on case-insensitive `/sdcard` finds the source itself as the target (`-ef`):
     * it moves through a temporary name instead, since a direct `mv` there can be a no-op.
     */
    open suspend fun rename(dir: String, from: String, to: String) {
        val source = shellQuote(devicePath(dir, from))
        val target = shellQuote(devicePath(dir, to))
        val clashOrMove = "${exists(target)}; then ${clash(to)}; else mv -n $source $target; fi"
        if (from != to && from.equals(to, ignoreCase = true)) {
            val tmp = shellQuote(devicePath(dir, ".rename-${UUID.randomUUID()}"))
            mutate("if [ $source -ef $target ]; then mv $source $tmp && mv $tmp $target; elif $clashOrMove")
        } else {
            mutate("if $clashOrMove")
        }
        scanFile(dir)
    }

    /**
     * Moves [names] into [toDir] (as [renames] says). An existing target fails the item with "<name>: already
     * exists" unless its source name is in [replace], where it is removed first. Both folders are rescanned even
     * when a later item fails, since earlier ones may already have moved. An item whose target is its own path is
     * skipped: removing the "existing" target would delete the item itself. Each item also checks on the device that
     * source and target are not the same file (`-ef`) before anything else, since `/sdcard` ignores case and other
     * spellings reach the same folder. A Replace whose target is the item's own folder or one above it fails with
     * "<name>: contains the items being moved" (checked here and again on the device), before anything is removed.
     */
    open suspend fun move(
        dir: String,
        names: List<String>,
        toDir: String,
        renames: Map<String, String> = emptyMap(),
        replace: Set<String> = emptySet(),
    ) {
        val moving = names.filter {
            devicePath(normalizeDir(dir), it) != devicePath(normalizeDir(toDir), renames[it] ?: it)
        }
        if (moving.isEmpty()) return
        // Replacing a folder that holds the item (moving /sdcard/Docs/Docs onto /sdcard/Docs) would delete the item
        // and everything around it: refused before anything runs (nothing to rescan), and again on the device, which
        // also catches links. The item as its own target (another case of the same path) is left to the `-ef` check.
        for (it in moving) {
            if (it !in replace) continue
            val targetName = renames[it] ?: it
            val source = normalizeDir(devicePath(dir, it))
            val target = normalizeDir(devicePath(toDir, targetName))
            if (!source.equals(target, ignoreCase = true) && isInsideIgnoreCase(source, target)) {
                throw IOException("$targetName: $CONTAINS_MOVED")
            }
        }
        val items = moving.map {
            val targetName = renames[it] ?: it
            val sourcePath = devicePath(dir, it)
            val targetPath = devicePath(toDir, targetName)
            val source = shellQuote(sourcePath)
            val target = shellQuote(targetPath)
            val mv = "mv $source $target"
            val same = "if [ $source -ef $target ]; then :;"
            if (it in replace) {
                "$same else ${keepOutsideGuard(sourcePath, targetPath, targetName, CONTAINS_MOVED)} && " +
                    "rm -rf -- $target && $mv; fi"
            } else {
                "$same elif ${exists(target)}; then ${clash(targetName)}; else $mv; fi"
            }
        }
        try {
            // Items chain with && so the first failure stops the rest; a big selection runs as several commands,
            // and a failing one stops the batches after it.
            for (batch in batches(items, " && ")) mutate(batch.joinToString(" && "), LONG_TIMEOUT_MS)
        } finally {
            scanFile(dir)
            if (toDir != dir) scanFile(toDir)
        }
    }

    private fun exists(quoted: String) = "[ -e $quoted ] || [ -L $quoted ]"

    private fun clash(name: String) = "printf '%s: already exists\\n' ${shellQuote(name)}; false"

    /** Removes [names] in [dir], in several `rm` commands for a big selection; stops at the first one that fails. */
    open suspend fun delete(dir: String, names: List<String>) {
        if (names.isEmpty()) return
        var removedAny = false
        try {
            for (batch in batches(names.map { shellQuote(devicePath(dir, it)) }, " ", prefix = RM)) {
                mutate(RM + batch.joinToString(" "), LONG_TIMEOUT_MS)
                removedAny = true
            }
        } finally {
            // An earlier batch may be gone even when a later one failed.
            if (removedAny) scanFile(dir)
        }
    }

    /**
     * Splits [parts] into runs whose command ([prefix] plus the parts joined by [separator]) stays within
     * [MAX_COMMAND_BYTES]: an adb request cannot be longer than 0xFFFF bytes. A part too long on its own gets a run
     * of its own.
     */
    private fun batches(parts: List<String>, separator: String, prefix: String = ""): List<List<String>> {
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8).size
        val sepBytes = separator.toByteArray(Charsets.UTF_8).size
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = prefixBytes
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && size + sepBytes + bytes > MAX_COMMAND_BYTES) {
                result += current
                current = mutableListOf()
                size = prefixBytes
            }
            size += if (current.isEmpty()) bytes else sepBytes + bytes
            current += part
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private suspend fun mutate(cmd: String, timeoutMs: Long? = null) {
        val r: ShellResult = if (timeoutMs == null) runner.run(cmd) else runner.run(cmd, timeoutMs)
        if (r.exitCode != 0) {
            val last = r.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            throw IOException(last ?: if (r.exitCode == null) "No response from device" else "Exit code ${r.exitCode}")
        }
    }
}
