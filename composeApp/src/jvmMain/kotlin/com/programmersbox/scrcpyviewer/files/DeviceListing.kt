package com.programmersbox.scrcpyviewer.files

data class DeviceEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMillis: Long,
    val itemCount: Int? = null,
) {
    val hidden get() = name.startsWith(".")
}

sealed interface Listing {
    data class Entries(val entries: List<DeviceEntry>) : Listing
    data object Blocked : Listing
    data class Failed(val reason: String) : Listing
}

private const val MARKER = "|__svend__"
private const val BLOCKED_EXIT = 13

/** One shell process: exits 13 when [dir] cannot be listed, else prints one stat record per entry. */
fun listCommand(dir: String): String =
    "d=${shellQuote(dir)}; ls -A \"\$d\" >/dev/null || exit $BLOCKED_EXIT; " +
        "for f in \"\$d\"/.[!.]* \"\$d\"/..?* \"\$d\"/*; do [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
        "stat -L -c '%F|%s|%Y|%n$MARKER' \"\$f\" 2>/dev/null || echo \"broken|0|0|\$f$MARKER\"; done"

/** Splits [output] into records ending in the marker, tolerating a missing final newline. */
private fun records(output: String): List<String> {
    val text = if (output.trimEnd().endsWith(MARKER)) output.trimEnd() + "\n" else output
    val parts = text.split("$MARKER\n")
    return parts.dropLast(1)
}

fun parseListing(output: String, exitCode: Int?): Listing {
    if (exitCode == 0) {
        val entries = ArrayList<DeviceEntry>()
        for (record in records(output)) {
            val a = record.indexOf('|')
            if (a < 0) continue
            val b = record.indexOf('|', a + 1)
            if (b < 0) continue
            val c = record.indexOf('|', b + 1)
            if (c < 0) continue
            // Anything before the type field is stray stderr noise.
            val type = record.substring(0, a).substringAfterLast('\n')
            val size = record.substring(a + 1, b).trim().toLongOrNull() ?: continue
            val mtime = record.substring(b + 1, c).trim().toLongOrNull() ?: continue
            val name = record.substring(c + 1).substringAfterLast('/')
            if (name.isEmpty()) continue
            entries += DeviceEntry(name, type.trim() == "directory", size, mtime * 1000)
        }
        return Listing.Entries(entries)
    }
    if (exitCode == BLOCKED_EXIT &&
        ("Permission denied" in output || "Operation not permitted" in output)
    ) {
        return Listing.Blocked
    }
    val last = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    return Listing.Failed(last ?: if (exitCode == null) "No response from device" else "Exit code $exitCode")
}

/** Prints "<count>|<name>|__svend__" for each of [folders] under [dir]. */
fun countsCommand(dir: String, folders: List<String>): String =
    "d=${shellQuote(dir)}; for f in ${folders.joinToString(" ") { shellQuote(it) }}; do " +
        "echo \"\$(ls -A \"\$d/\$f\" 2>/dev/null | wc -l)|\$f$MARKER\"; done"

fun parseCounts(output: String): Map<String, Int> {
    val result = LinkedHashMap<String, Int>()
    for (record in records(output)) {
        val i = record.indexOf('|')
        if (i < 0) continue
        val count = record.substring(0, i).trim().toIntOrNull() ?: continue
        result[record.substring(i + 1)] = count
    }
    return result
}

fun freeSpaceCommand(): String = "df -k $DEVICE_ROOT"

/** Bytes available, from the 4th column of the last `df -k` line. */
fun parseFreeSpace(output: String): Long? {
    val last = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
    val cols = last.split(Regex("\\s+"))
    if (cols.size < 5) return null
    return cols[3].toLongOrNull()?.times(1024)
}
