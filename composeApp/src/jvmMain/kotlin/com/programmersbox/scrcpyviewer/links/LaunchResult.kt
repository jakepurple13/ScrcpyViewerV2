package com.programmersbox.scrcpyviewer.links

data class LaunchResult(
    val ok: Boolean,
    val packageName: String? = null,
    val launchState: String? = null,
    val totalMs: Long? = null,
    val waitMs: Long? = null,
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
        // Android 16 prints "UNKNOWN (0)" for a state it could not classify.
        launchState = value("LaunchState")?.takeIf { it.isNotBlank() && !it.startsWith("UNKNOWN", ignoreCase = true) },
        totalMs = value("TotalTime")?.toLongOrNull(),
        waitMs = value("WaitTime")?.toLongOrNull(),
        errorLine = error,
    )
}

/** The success line: "Opened in <label> · <cold|warm|hot> start <n> ms", or without the state when unknown. */
fun LaunchResult.summary(label: String): String {
    val ms = totalMs ?: waitMs
    val tail = when {
        launchState != null && ms != null -> " · ${launchState.lowercase()} start $ms ms"
        launchState != null -> " · ${launchState.lowercase()} start"
        ms != null -> " · $ms ms"
        else -> ""
    }
    return "Opened in $label$tail"
}
