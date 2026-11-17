package com.programmersbox.scrcpyviewer.telemetry

import java.util.concurrent.ConcurrentHashMap

/**
 * Removes personal data from free text before it leaves the machine, then caps it at [MAX_LENGTH] characters.
 * In order: the home folder becomes `~`, every serial given to [rememberSerial] becomes `<device>`, IPv4 and IPv6
 * addresses become `<ip>` (an IPv4 port stays). IPv6 only matches full eight-group addresses or ones with `::`, so
 * times like `12:34:56` are left alone. Serials live in memory only.
 */
class Scrubber(private val home: String = System.getProperty("user.home") ?: "") {
    private val serials: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rememberSerial(serial: String) {
        if (serial.isNotBlank()) serials += serial
    }

    fun scrub(s: String): String {
        var out = s
        if (home.length > 1) out = out.replace(home, "~")
        // Longest first so a serial that contains another one is replaced whole.
        serials.sortedByDescending { it.length }.forEach { out = out.replace(it, "<device>") }
        out = IPV4.replace(out, "<ip>")
        out = IPV6.replace(out, "<ip>")
        return out.take(MAX_LENGTH)
    }

    companion object {
        const val MAX_LENGTH = 500
        private val IPV4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.]*\w)""")
        private const val H = "[0-9a-fA-F]{1,4}"
        private val IPV6 = Regex(
            "(?<![0-9A-Za-z:])(?:" +
                "(?:$H:){7}$H" +
                "|(?:$H:){1,7}:(?:$H(?::$H){0,6})?" +
                "|::$H(?::$H){0,6}" +
                ")(?![0-9A-Za-z:])"
        )
    }
}
