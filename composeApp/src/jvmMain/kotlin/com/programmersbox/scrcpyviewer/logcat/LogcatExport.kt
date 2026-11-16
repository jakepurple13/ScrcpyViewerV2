package com.programmersbox.scrcpyviewer.logcat

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Device facts an export needs. [today] and [offset] are the device's own clock, because logcat
 * timestamps carry no year or zone and must be read in the device's frame.
 */
data class ExportDevice(
    val serial: String,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: Int,
    val type: String,
    val today: LocalDate,
    val offset: ZoneOffset,
)

/** One round trip that prints the props and the device date as `key=value` lines. */
const val EXPORT_DEVICE_COMMAND: String =
    "for p in ro.product.manufacturer ro.product.model ro.build.version.release " +
        "ro.build.version.sdk ro.build.characteristics; do echo \"\$p=\$(getprop \$p)\"; done; " +
        "echo \"date=\$(date +%Y-%m-%d%z)\""

private val dateLine = Regex("""^(\d{4}-\d\d-\d\d)([+-])(\d\d)(\d\d)$""")

/**
 * Parses [EXPORT_DEVICE_COMMAND] output. Missing values become empty (sdk 0); a bad or missing
 * `date=` line falls back to [hostNow] so export still works on a device that answers badly.
 */
fun parseExportDevice(serial: String, output: String, hostNow: ZonedDateTime): ExportDevice {
    val values = output.lineSequence()
        .mapNotNull { l -> l.trim().split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { (k, v) -> k to v.trim() }

    var today = hostNow.toLocalDate()
    var offset: ZoneOffset = hostNow.offset
    dateLine.matchEntire(values["date"].orEmpty())?.let { m ->
        runCatching {
            val d = LocalDate.parse(m.groupValues[1])
            val o = ZoneOffset.of("${m.groupValues[2]}${m.groupValues[3]}:${m.groupValues[4]}")
            today = d
            offset = o
        }
    }

    val characteristics = values["ro.build.characteristics"].orEmpty().split(',').map { it.trim() }
    val type = when {
        "watch" in characteristics -> "WEAR"
        "tv" in characteristics -> "TV"
        else -> "HANDHELD"
    }
    return ExportDevice(
        serial = serial,
        manufacturer = values["ro.product.manufacturer"].orEmpty(),
        model = values["ro.product.model"].orEmpty(),
        release = values["ro.build.version.release"].orEmpty(),
        sdk = values["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
        type = type,
        today = today,
        offset = offset,
    )
}

/**
 * Absolute time of [line]. Logcat has no year, so use [today]'s year, or the previous one when the
 * line's `MM-DD` is later than today (a log that spans New Year). `02-29` in a non-leap year is
 * clamped to `02-28`.
 */
fun epochOf(line: LogcatLine, today: LocalDate, offset: ZoneOffset): Instant {
    val month = line.date.substring(0, 2).toInt()
    val day = line.date.substring(3, 5).toInt()
    val afterToday = month > today.monthValue || (month == today.monthValue && day > today.dayOfMonth)
    val year = if (afterToday) today.year - 1 else today.year
    val safeDay = minOf(day, YearMonth.of(year, month).lengthOfMonth())
    val time = LocalTime.parse(line.time)
    return LocalDateTime.of(LocalDate.of(year, month, safeDay), time).toInstant(offset)
}

private val unsafeChars = Regex("[^A-Za-z0-9._-]")
private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

/** Android Studio style export name; blank parts become `device`/`unknown` and unsafe characters `_`. */
fun exportFileName(device: ExportDevice, hostNow: LocalDateTime, ext: String): String {
    fun part(value: String, blank: String) = value.ifBlank { blank }.replace(unsafeChars, "_")
    val maker = part(device.manufacturer, "device")
    val model = part(device.model, "unknown")
    val release = part(device.release, "unknown")
    return "$maker-$model-Android-${release}_${stamp.format(hostNow)}.$ext"
}
