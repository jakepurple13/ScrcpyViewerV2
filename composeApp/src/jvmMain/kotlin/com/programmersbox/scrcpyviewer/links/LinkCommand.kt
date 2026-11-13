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
    val pkg = r.packageName?.takeIf { it.isNotBlank() }
    val isIntentUri = linkKind(link).first == LinkKind.Intent
    val start = buildList {
        add("am start -W")
        // An intent: URI carries its own action and categories.
        if (!isIntentUri) {
            r.action?.takeIf { it.isNotBlank() }?.let { add("-a ${DeviceApps.quote(it)}") }
            r.category?.takeIf { it.isNotBlank() }?.let { add("-c ${DeviceApps.quote(it)}") }
        }
        add("-d ${DeviceApps.quote(link)}")
        r.extra?.takeIf { it.key.isNotBlank() }?.let {
            add("${it.type.flag} ${DeviceApps.quote(it.key)} ${DeviceApps.quote(it.value)}")
        }
        if (r.before == Before.ClearTask) add("--activity-clear-task")
        if (pkg != null) add(DeviceApps.quote(pkg))
    }.joinToString(" ")
    return if (r.before == Before.ForceStop && pkg != null) "am force-stop ${DeviceApps.quote(pkg)} && $start" else start
}

/** The text to show and copy: safe to paste into a local shell. */
fun previewCommand(serial: String, r: LinkRequest): String =
    "adb -s ${DeviceApps.quote(serial)} shell ${DeviceApps.quote(deviceCommand(r))}"
