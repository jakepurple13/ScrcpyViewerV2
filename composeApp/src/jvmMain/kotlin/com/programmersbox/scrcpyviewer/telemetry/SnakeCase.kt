package com.programmersbox.scrcpyviewer.telemetry

private val ACRONYM_END = Regex("([A-Z]+)([A-Z][a-z])")
private val WORD_START = Regex("([a-z0-9])([A-Z])")

/**
 * The lowercase snake_case form of a label, so every property value reads the same however the caller spelled it:
 * an underscore goes before each capital that follows a lowercase letter or digit, and before the last capital of a
 * run of capitals that starts a new word, then everything is lowercased. "DarkMode" gives "dark_mode", "SideBySide"
 * "side_by_side", "ADB" "adb", "HTTPServer" "http_server", and "WiFi" "wi_fi". Text already in snake_case is unchanged.
 */
fun String.snakeCase(): String = replace(ACRONYM_END, "$1_$2").replace(WORD_START, "$1_$2").lowercase()
