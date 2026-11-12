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

/** [name], or "base (n).ext" with the lowest n >= 1 that is not in [taken]. */
fun uniqueName(taken: Set<String>, name: String): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (candidate !in taken) return candidate
        n++
    }
}

fun nameProblem(name: String, taken: Set<String>): String? = when {
    name.isBlank() -> "Name can’t be empty"
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
