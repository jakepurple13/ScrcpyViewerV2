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
        "stat -L -c '%F|%s|%Y|%n$MARKER' \"\$f\" 2>/dev/null || printf 'broken|0|0|%s$MARKER\\n' \"\$f\"; done"

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

/** A file or folder under a dragged row, [path] relative to the row's folder ("Camera/1.jpg"). */
data class TreeEntry(val path: String, val isDir: Boolean, val size: Long)

/**
 * One shell process printing a stat record for every folder and regular file under [names] in [dir] (the rows
 * themselves included), for a virtual-file drag. Names go in as "./name" so one starting with "-" is not an option.
 */
fun treeCommand(dir: String, names: List<String>): String =
    "cd ${shellQuote(dir)} && find ${names.joinToString(" ") { shellQuote("./$it") }} \\( -type d -o -type f \\) " +
        "-exec stat -c '%F|%s|%n$MARKER' {} + 2>/dev/null"

/** The folders and regular files of [treeCommand]'s output, in its order; anything else is left out. */
fun parseTree(output: String): List<TreeEntry> {
    val entries = ArrayList<TreeEntry>()
    for (record in records(output)) {
        val a = record.indexOf('|')
        if (a < 0) continue
        val b = record.indexOf('|', a + 1)
        if (b < 0) continue
        val type = record.substring(0, a).substringAfterLast('\n').trim()
        val size = record.substring(a + 1, b).trim().toLongOrNull() ?: continue
        val path = record.substring(b + 1).removePrefix("./")
        if (path.isEmpty()) continue
        entries += when (type) {
            "directory" -> TreeEntry(path, isDir = true, size = 0)
            "regular file", "regular empty file" -> TreeEntry(path, isDir = false, size = size)
            else -> continue
        }
    }
    return entries
}

/** Prints "<count>|<name>|__svend__" for each of [folders] under [dir]. */
fun countsCommand(dir: String, folders: List<String>): String {
    if (folders.isEmpty()) return ":"
    return "d=${shellQuote(dir)}; for f in ${folders.joinToString(" ") { shellQuote(it) }}; do " +
        "printf '%s|%s$MARKER\\n' \"\$(ls -A \"\$d/\$f\" 2>/dev/null | wc -l)\" \"\$f\"; done"
}

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

/** Storage [free] (available to apps) and its [total] size, in bytes. */
data class DeviceSpace(val free: Long, val total: Long) {
    /** Share of [total] not available, 0 to 1. */
    val usedFraction: Float get() = if (total <= 0) 0f else (1f - free.toFloat() / total).coerceIn(0f, 1f)
}

/** Bytes available (4th column) and the size (2nd column) from the last `df -k` line. */
fun parseFreeSpace(output: String): DeviceSpace? {
    val last = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
    val cols = last.split(Regex("\\s+"))
    if (cols.size < 5) return null
    val free = cols[3].toLongOrNull()?.times(1024) ?: return null
    val total = cols[1].toLongOrNull()?.times(1024) ?: return null
    return DeviceSpace(free, total)
}
