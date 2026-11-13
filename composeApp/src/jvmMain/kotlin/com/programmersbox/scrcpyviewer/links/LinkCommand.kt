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

/** The shell command to run on the device (no `adb shell` prefix). Every user value is single-quoted. */
fun deviceCommand(r: LinkRequest): String {
    val link = r.link.trim()
    val isIntentUri = linkKind(link).first == LinkKind.Intent
    // An intent: URI carries its own package=, so "Open with" is ignored for it (and so is force-stop).
    val pkg = r.packageName?.takeIf { it.isNotBlank() && !isIntentUri }
    val start = buildList {
        add("am start -W")
        // An intent: URI carries its own action and categories, and must be positional: `-d` fails to resolve.
        if (!isIntentUri) {
            r.action?.takeIf { it.isNotBlank() }?.let { add("-a ${DeviceApps.quote(it)}") }
            r.category?.takeIf { it.isNotBlank() }?.let { add("-c ${DeviceApps.quote(it)}") }
        }
        if (!isIntentUri) add("-d ${DeviceApps.quote(link)}")
        r.extra?.takeIf { it.key.isNotBlank() }?.let {
            add("${it.type.flag} ${DeviceApps.quote(it.key)} ${DeviceApps.quote(it.value)}")
        }
        if (r.before == Before.ClearTask) add("--activity-clear-task")
        // The positional argument goes last: the intent URI, or the chosen package.
        if (isIntentUri) add(DeviceApps.quote(link)) else if (pkg != null) add(DeviceApps.quote(pkg))
    }.joinToString(" ")
    return if (r.before == Before.ForceStop && pkg != null) "am force-stop ${DeviceApps.quote(pkg)} && $start" else start
}

/** The text to show and copy: safe to paste into a local shell. */
fun previewCommand(serial: String, r: LinkRequest): String =
    "adb -s ${DeviceApps.quote(serial)} shell ${DeviceApps.quote(deviceCommand(r))}"
