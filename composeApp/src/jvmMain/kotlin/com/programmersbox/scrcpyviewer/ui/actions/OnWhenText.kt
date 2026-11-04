package com.programmersbox.scrcpyviewer.ui.actions

import com.programmersbox.scrcpyviewer.actions.MatchMode

/** The "On when output" rule in plain language, for the line under the value field. */
fun onWhenSentence(mode: MatchMode, value: String): String {
    if (value.isBlank()) return "Enter the output that means On."
    val verb = when (mode) {
        MatchMode.Equals -> "equals"
        MatchMode.Contains -> "contains"
        MatchMode.Regex -> "matches"
    }
    return "Shows On when the read command's output $verb $value."
}

/** Shown in place of [onWhenSentence], and as the Save error, when a regex rule does not compile. */
const val INVALID_PATTERN = "Not a valid pattern"

/** What is wrong with the "On when output" rule: [INVALID_PATTERN] for a regex that does not compile; else null. */
fun onWhenError(mode: MatchMode, value: String): String? =
    INVALID_PATTERN.takeIf { mode == MatchMode.Regex && value.isNotBlank() && runCatching { Regex(value) }.isFailure }

private const val PRINTED_LINE_MAX = 40

/** What a read printed, as a one-liner: the first line after trimming, cut to 40 characters with an ellipsis. */
fun printedLine(output: String): String {
    val line = output.trim().lineSequence().first().trim()
    return if (line.length <= PRINTED_LINE_MAX) line else line.take(PRINTED_LINE_MAX - 1) + "…"
}
