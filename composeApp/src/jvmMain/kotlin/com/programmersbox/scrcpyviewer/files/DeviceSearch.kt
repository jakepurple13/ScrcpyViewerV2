package com.programmersbox.scrcpyviewer.files

/** A match of a device-wide search: [entry] in the folder [dir] (an absolute device path). */
data class SearchHit(val dir: String, val entry: DeviceEntry) {
    val path: String get() = devicePath(dir, entry.name)
}

/**
 * Folders under [DEVICE_ROOT] a search never enters: Android 11+ blocks most of them, and what is readable there is
 * app data nobody looks for by name.
 */
private val SKIPPED = listOf("./Android/data", "./Android/obb")

/** [query] as a literal `find -iname` glob part: the glob's own characters are escaped. */
internal fun globLiteral(query: String): String = buildString {
    for (c in query) {
        if (c == '\\' || c == '*' || c == '?' || c == '[' || c == ']') append('\\')
        append(c)
    }
}

/**
 * One shell printing a stat record (as [listCommand]'s) for every file and folder under [root] whose name contains
 * [query], ignoring case. `find -exec stat {} +` hands matches to `stat` in batches, so records arrive while the
 * search runs. Hidden (dot) entries and everything inside hidden folders are left out unless [includeHidden];
 * [SKIPPED] folders always are. Errors (unreadable folders) are dropped.
 */
fun searchCommand(query: String, includeHidden: Boolean, root: String = DEVICE_ROOT): String {
    val prune = SKIPPED.map { "-path ${shellQuote(it)}" } + if (includeHidden) emptyList() else listOf("-path '*/.*'")
    val pattern = shellQuote("*${globLiteral(query)}*")
    return "cd ${shellQuote(root)} && find . \\( ${prune.joinToString(" -o ")} \\) -prune -o " +
        "-iname $pattern -exec stat -L -c $STAT_FORMAT {} + 2>/dev/null"
}

/**
 * Reads [searchCommand]'s output as it streams: [feed] takes each chunk and returns the hits completed by it, holding
 * a record cut between chunks until the rest arrives. Paths come back absolute under [root].
 */
class SearchParser(private val root: String = DEVICE_ROOT) {
    private val pending = StringBuilder()

    fun feed(chunk: String): List<SearchHit> {
        pending.append(chunk)
        val hits = ArrayList<SearchHit>()
        val end = "$MARKER\n"
        while (true) {
            val i = pending.indexOf(end)
            if (i < 0) break
            parse(pending.substring(0, i))?.let(hits::add)
            pending.delete(0, i + end.length)
        }
        return hits
    }

    /** The last record when the output ended without its newline. */
    fun finish(): List<SearchHit> {
        val rest = pending.toString().trimEnd()
        pending.setLength(0)
        if (!rest.endsWith(MARKER)) return emptyList()
        return listOfNotNull(parse(rest.removeSuffix(MARKER)))
    }

    private fun parse(record: String): SearchHit? {
        val a = record.indexOf('|')
        if (a < 0) return null
        val b = record.indexOf('|', a + 1)
        if (b < 0) return null
        val c = record.indexOf('|', b + 1)
        if (c < 0) return null
        // Anything before the type field is stray stderr noise.
        val type = record.substring(0, a).substringAfterLast('\n').trim()
        val size = record.substring(a + 1, b).trim().toLongOrNull() ?: return null
        val mtime = record.substring(b + 1, c).trim().toLongOrNull() ?: return null
        val rel = record.substring(c + 1).removePrefix("./")
        // "." is the root itself (a query of "."), never a result.
        if (rel.isEmpty() || rel == ".") return null
        val name = rel.substringAfterLast('/')
        val parent = rel.substringBeforeLast('/', "")
        val dir = if (parent.isEmpty()) normalizeDir(root) else devicePath(normalizeDir(root), parent)
        return SearchHit(dir, DeviceEntry(name, type == "directory", size, mtime * 1000))
    }
}
