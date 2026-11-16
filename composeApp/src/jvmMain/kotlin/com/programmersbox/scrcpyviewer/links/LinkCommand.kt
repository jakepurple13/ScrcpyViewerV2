package com.programmersbox.scrcpyviewer.links

import com.programmersbox.scrcpyviewer.apps.DeviceApps

enum class Before { None, ForceStop, ClearTask }

enum class ExtraType(val flag: String) { String("--es"), Int("--ei"), Bool("--ez") }

data class Extra(val type: ExtraType, val key: String, val value: String)

data class LinkRequest(
    val link: String,
    val packageName: String? = null,
    val before: Before = Before.None,
    val action: String? = null,
    val category: String? = null,
    val extra: Extra? = null,
)

/** The shell command to run on the device (no `adb shell` prefix). A user value is left bare when it is only safe characters, else single-quoted. */
fun deviceCommand(r: LinkRequest): String {
    val link = r.link.trim()
    val isIntentUri = linkKind(link).first == LinkKind.Intent
    // An intent: URI carries its own package=, so "Open with" is ignored for it (and so is force-stop).
    val pkg = r.packageName?.takeIf { it.isNotBlank() && !isIntentUri }
    val start = buildList {
        add("am start -W")
        // An intent: URI carries its own action and categories, and must be positional: `-d` fails to resolve.
        if (!isIntentUri) {
            r.action?.takeIf { it.isNotBlank() }?.let { add("-a ${arg(it)}") }
            r.category?.takeIf { it.isNotBlank() }?.let { add("-c ${arg(it)}") }
        }
        if (!isIntentUri) add("-d ${arg(link)}")
        r.extra?.takeIf { it.key.isNotBlank() }?.let {
            add("${it.type.flag} ${arg(it.key)} ${arg(it.value)}")
        }
        if (r.before == Before.ClearTask) add("--activity-clear-task")
        // The positional argument goes last: the intent URI, or the chosen package.
        if (isIntentUri) add(arg(link)) else if (pkg != null) add(arg(pkg))
    }.joinToString(" ")
    return if (r.before == Before.ForceStop && pkg != null) "am force-stop ${arg(pkg)} && $start" else start
}

private val SAFE = Regex("[A-Za-z0-9._/:=@%+,-]+")

/** [value] bare when it is only safe characters, else in POSIX single quotes (so `&`, `?`, spaces, `'` stay literal). */
private fun arg(value: String): String = if (SAFE.matches(value)) value else DeviceApps.quote(value)

private val DOUBLE_QUOTE_UNSAFE = charArrayOf('"', '$', '`', '\\', '!')

/**
 * The text to show and copy: safe to paste into a local shell. The device command goes in double quotes when nothing in
 * it is special inside them (its single quotes stay as they are), else in single quotes. Either way the device
 * receives exactly [deviceCommand].
 */
fun previewCommand(serial: String, r: LinkRequest): String {
    val device = deviceCommand(r)
    val wrapped = if (device.none { it in DOUBLE_QUOTE_UNSAFE }) "\"$device\"" else DeviceApps.quote(device)
    return "adb -s ${arg(serial)} shell $wrapped"
}
