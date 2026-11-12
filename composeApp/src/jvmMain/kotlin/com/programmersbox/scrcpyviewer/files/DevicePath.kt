package com.programmersbox.scrcpyviewer.files

const val DEVICE_ROOT = "/sdcard"

private const val EMULATED_ROOT = "/storage/emulated/0"

/** POSIX single-quote [s] so the device shell treats it as one literal word. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun devicePath(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

/** Parent directory of [path], or null at [DEVICE_ROOT] (or above it). */
fun parentOf(path: String): String? {
    val p = normalizeDir(path)
    if (p == DEVICE_ROOT) return null
    val i = p.lastIndexOf('/')
    if (i <= 0) return null
    return p.substring(0, i)
}

/** (label, path) pairs from the storage root down to [path]. */
fun crumbs(path: String): List<Pair<String, String>> {
    val p = normalizeDir(path)
    val result = mutableListOf("Internal storage" to DEVICE_ROOT)
    if (p == DEVICE_ROOT || !isInside(p, DEVICE_ROOT)) return result
    var current = DEVICE_ROOT
    for (segment in p.removePrefix("$DEVICE_ROOT/").split('/').filter { it.isNotEmpty() }) {
        current = devicePath(current, segment)
        result += segment to current
    }
    return result
}

fun normalizeDir(path: String): String {
    val trimmed = path.trimEnd('/')
    return when {
        trimmed.isEmpty() -> DEVICE_ROOT
        trimmed == EMULATED_ROOT -> DEVICE_ROOT
        trimmed.startsWith("$EMULATED_ROOT/") -> DEVICE_ROOT + trimmed.removePrefix(EMULATED_ROOT)
        else -> trimmed
    }
}

/**
 * [name], or "base (n).ext" with the lowest n >= 1 that is not in [taken]. A folder ([isDir]) has no extension: it
 * gets " (n)" after its whole name ("v1.2 (1)"). [ignoreCase] compares as `/sdcard` does.
 */
fun uniqueName(taken: Set<String>, name: String, isDir: Boolean = false, ignoreCase: Boolean = false): String {
    val used = if (ignoreCase) taken.mapTo(HashSet()) { it.lowercase() } else taken
    fun free(n: String) = (if (ignoreCase) n.lowercase() else n) !in used
    if (free(name)) return name
    val dot = if (isDir) -1 else name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (free(candidate)) return candidate
        n++
    }
}

fun nameProblem(name: String, taken: Set<String>): String? = when {
    name.isBlank() -> "Name can’t be empty"
    name == "." || name == ".." -> "Name not allowed"
    '/' in name -> "No “/” in names"
    name in taken -> "Name already used"
    else -> null
}

/** True when [path] equals [ancestor] or is a descendant of it. */
fun isInside(path: String, ancestor: String): Boolean {
    val a = ancestor.trimEnd('/')
    val p = path.trimEnd('/')
    return p == a || p.startsWith("$a/")
}

/** [isInside] for device paths, ignoring case and the `/storage/emulated/0` spelling: `/sdcard` ignores case. */
fun isInsideIgnoreCase(path: String, ancestor: String): Boolean =
    isInside(normalizeDir(path).lowercase(), normalizeDir(ancestor).lowercase())

/**
 * A device-side check to run right before `rm -rf -- [target]`: it fails (printing "<[name]>: [reason]") when [keep],
 * which must survive the removal, is [target] itself or inside it. Real paths are compared (`realpath`, else the paths
 * as given), ignoring case, so another spelling of the same folder is caught too. Both paths are quoted.
 */
fun keepOutsideGuard(keep: String, target: String, name: String, reason: String): String {
    fun lower(p: String) = "\$( (realpath ${shellQuote(p)} 2>/dev/null || printf '%s\\n' ${shellQuote(p)}) | tr A-Z a-z)"
    return "case \"${lower(keep)}/\" in \"${lower(target)}\"/*) " +
        "printf '%s: %s\\n' ${shellQuote(name)} ${shellQuote(reason)}; false;; esac"
}

/** Why a Replace may not remove a folder: the items being moved are inside it. */
const val CONTAINS_MOVED = "contains the items being moved"
