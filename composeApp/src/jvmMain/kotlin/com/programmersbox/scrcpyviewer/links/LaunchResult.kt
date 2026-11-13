package com.programmersbox.scrcpyviewer.links

data class LaunchResult(
    val ok: Boolean,
    val packageName: String? = null,
    val launchState: String? = null,
    val totalMs: Long? = null,
    val errorLine: String? = null,
)

/** Parses `am start -W` output. Tolerates `\r`, blank lines and unknown lines. */
fun parseAmStart(output: String): LaunchResult {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    fun value(key: String) = lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()
    val error = lines.firstOrNull { it.startsWith("Error:") || it.startsWith("Exception ") }
    val statusOk = value("Status")?.equals("ok", ignoreCase = true) == true
    return LaunchResult(
        ok = statusOk && error == null,
        packageName = value("Activity")?.substringBefore('/')?.takeIf { it.isNotBlank() },
        launchState = value("LaunchState")?.takeIf { it.isNotBlank() },
        totalMs = value("TotalTime")?.toLongOrNull(),
        errorLine = error,
    )
}
