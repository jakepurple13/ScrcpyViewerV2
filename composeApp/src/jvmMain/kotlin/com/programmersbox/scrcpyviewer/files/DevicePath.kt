package com.programmersbox.scrcpyviewer.files

/** Internal storage: where the Files tab opens, named "Internal storage" by the friendly crumbs. */
const val DEVICE_ROOT = "/sdcard"

/** The top of the device's file system; Up stops here. */
const val FS_ROOT = "/"

private const val EMULATED_ROOT = "/storage/emulated/0"

/** Other spellings of [DEVICE_ROOT]. */
private val STORAGE_ALIASES = listOf(EMULATED_ROOT, "/storage/self/primary")

/** POSIX single-quote [s] so the device shell treats it as one literal word. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun devicePath(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

/** Parent directory of [path]: [FS_ROOT] for [DEVICE_ROOT] (a link there) and the root's own folders; null at [FS_ROOT]. */
fun parentOf(path: String): String? {
    val p = normalizeDir(path)
    if (p == FS_ROOT) return null
    if (p == DEVICE_ROOT) return FS_ROOT
    val i = p.lastIndexOf('/')
    if (i < 0) return null
    return if (i == 0) FS_ROOT else p.substring(0, i)
}

/**
 * (label, path) pairs from the device root down to [path]. Friendly crumbs start at "Device" and name [DEVICE_ROOT]
 * "Internal storage"; [realPaths] crumbs are "/" and then the path's own segments.
 */
fun crumbs(path: String, realPaths: Boolean = false): List<Pair<String, String>> {
    val p = normalizeDir(path)
    val result = mutableListOf((if (realPaths) FS_ROOT else "Device") to FS_ROOT)
    var current = FS_ROOT
    var rest = p
    if (!realPaths && isInside(p, DEVICE_ROOT)) {
        result += "Internal storage" to DEVICE_ROOT
        current = DEVICE_ROOT
        rest = p.removePrefix(DEVICE_ROOT)
    }
    for (segment in rest.split('/').filter { it.isNotEmpty() }) {
        current = devicePath(current, segment)
        result += segment to current
    }
    return result
}

/** Without a trailing "/", every spelling of internal storage as [DEVICE_ROOT]; "" is [DEVICE_ROOT], "/" stays. */
fun normalizeDir(path: String): String {
    if (path.isEmpty()) return DEVICE_ROOT
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return FS_ROOT
    for (alias in STORAGE_ALIASES) {
        if (trimmed == alias) return DEVICE_ROOT
        if (trimmed.startsWith("$alias/")) return DEVICE_ROOT + trimmed.removePrefix(alias)
    }
    return trimmed
}

/**
 * A readable folder inside the blocked folder [path], as (button label, path), when one is known: adb can't list
 * `/data` but can open `/data/local/tmp`, and on some devices can't list `/storage/emulated` but can open its `0`.
 */
fun wayInto(path: String): Pair<String, String>? = when (normalizeDir(path)) {
    "/data" -> "Open /data/local/tmp" to "/data/local/tmp"
    "/storage/emulated" -> "Open Internal storage" to DEVICE_ROOT
    else -> null
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
