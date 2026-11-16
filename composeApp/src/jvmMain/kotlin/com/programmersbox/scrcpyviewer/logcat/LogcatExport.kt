package com.programmersbox.scrcpyviewer.logcat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Device facts an export needs. [today], [offset] and [zone] are the device's own clock, because logcat
 * timestamps carry no year or zone and must be read in the device's frame. [zone] is null when the
 * device reports none; then [offset] is used for every line.
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
    val zone: ZoneId? = null,
)

/** One round trip that prints the props, the device date and its time zone as `key=value` lines. */
const val EXPORT_DEVICE_COMMAND: String =
    "for p in ro.product.manufacturer ro.product.model ro.build.version.release " +
        "ro.build.version.sdk ro.build.characteristics; do echo \"\$p=\$(getprop \$p)\"; done; " +
        "echo \"date=\$(date +%Y-%m-%d%z)\"; echo \"tz=\$(getprop persist.sys.timezone)\""

private val dateLine = Regex("""^(\d{4}-\d\d-\d\d)([+-])(\d\d)(\d\d)$""")

/**
 * Parses [EXPORT_DEVICE_COMMAND] output. Missing values become empty (sdk 0); a bad or missing
 * `date=` line falls back to [hostNow] so export still works on a device that answers badly. A blank
 * or unknown `tz=` gives a null zone.
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
        zone = values["tz"]?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() },
    )
}

/**
 * Absolute time of [line]. Logcat has no year, so use [today]'s year, or the previous one when the
 * line's `MM-DD` is later than today (a log that spans New Year). `02-29` in a non-leap year is
 * clamped to `02-28`. Read in [zone] when known, so lines before a DST change get their own offset;
 * else in [offset].
 */
fun epochOf(line: LogcatLine, today: LocalDate, offset: ZoneOffset, zone: ZoneId? = null): Instant {
    val month = line.date.substring(0, 2).toInt()
    val day = line.date.substring(3, 5).toInt()
    val afterToday = month > today.monthValue || (month == today.monthValue && day > today.dayOfMonth)
    val year = if (afterToday) today.year - 1 else today.year
    val safeDay = minOf(day, YearMonth.of(year, month).lengthOfMonth())
    val time = LocalTime.parse(line.time)
    val local = LocalDateTime.of(LocalDate.of(year, month, safeDay), time)
    return if (zone != null) ZonedDateTime.of(local, zone).toInstant() else local.toInstant(offset)
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

/** One entry to export, with the process name its pid resolved to (null if unknown). */
data class ExportRow(val entry: LogcatEntry, val process: String?)

/** Export file formats. */
enum class ExportFormat(val extension: String, val label: String) {
    Studio("logcat", "Android Studio (.logcat)"),
    Text("txt", "Plain text (.txt)"),
}

private const val PROGRESS_EVERY = 500

@Serializable
private data class StudioApiLevel(val majorVersion: Int, val minorVersion: Int)

@Serializable
private data class StudioPhysicalDevice(
    val serialNumber: String,
    val isOnline: Boolean,
    val release: String,
    val apiLevel: StudioApiLevel,
    val featureLevel: Int,
    val manufacturer: String,
    val model: String,
    val type: String,
)

@Serializable
private data class StudioDevice(val physicalDevice: StudioPhysicalDevice)

@Serializable
private data class StudioMetadata(
    val device: StudioDevice,
    val filter: String,
    val projectApplicationIds: List<String>,
)

@Serializable
private data class StudioTimestamp(val seconds: Long, val nanos: Long)

@Serializable
private data class StudioHeader(
    val logLevel: String,
    val pid: Int,
    val tid: Int,
    val applicationId: String,
    val processName: String,
    val tag: String,
    val timestamp: StudioTimestamp,
)

@Serializable
private data class StudioMessage(val header: StudioHeader, val message: String)

private val studioJson = Json { encodeDefaults = true }

/**
 * Writes [rows] as an Android Studio `.logcat` JSON file. Messages are encoded one at a time so
 * [onProgress] runs per row (every 500 rows and at the end) and a cancel stops promptly.
 */
suspend fun writeStudioLogcat(
    out: Appendable,
    rows: List<ExportRow>,
    device: ExportDevice,
    filter: String,
    projectIds: List<String>,
    onProgress: (Int) -> Unit,
) {
    val metadata = StudioMetadata(
        device = StudioDevice(
            StudioPhysicalDevice(
                serialNumber = device.serial,
                isOnline = true,
                release = device.release,
                apiLevel = StudioApiLevel(device.sdk, 0),
                featureLevel = device.sdk,
                manufacturer = device.manufacturer,
                model = device.model,
                type = device.type,
            ),
        ),
        filter = filter,
        projectApplicationIds = projectIds,
    )
    out.append("{\"metadata\":").append(studioJson.encodeToString(metadata)).append(",\"logcatMessages\":[")
    rows.forEachIndexed { i, row ->
        currentCoroutineContext().ensureActive()
        val line = row.entry.line
        val process = row.process ?: "pid-${line.pid}"
        val instant = epochOf(line, device.today, device.offset, device.zone)
        val message = StudioMessage(
            header = StudioHeader(
                logLevel = line.level.name.uppercase(),
                pid = line.pid,
                tid = line.tid,
                applicationId = packageOf(process),
                processName = process,
                tag = line.tag,
                timestamp = StudioTimestamp(instant.epochSecond, instant.nano.toLong()),
            ),
            message = (listOf(line.message) + row.entry.trace).joinToString("\n"),
        )
        if (i > 0) out.append(",\n")
        out.append(studioJson.encodeToString(message))
        if ((i + 1) % PROGRESS_EVERY == 0 && i + 1 < rows.size) onProgress(i + 1)
    }
    out.append("]}")
    onProgress(rows.size)
}

/**
 * Writes [rows] as `logcat -v threadtime` text: one line per entry plus one per folded trace line.
 * Formatted in [Locale.ROOT], so pid and tid are ASCII digits whatever the host locale.
 */
suspend fun writeThreadtime(out: Appendable, rows: List<ExportRow>, onProgress: (Int) -> Unit) {
    rows.forEachIndexed { i, row ->
        currentCoroutineContext().ensureActive()
        val l = row.entry.line
        (listOf(l.message) + row.entry.trace).forEach { text ->
            out.append(String.format(Locale.ROOT, "%s %s %5d %5d %c %-8s: %s", l.date, l.time, l.pid, l.tid, l.level.letter, l.tag, text))
            out.append('\n')
        }
        if ((i + 1) % PROGRESS_EVERY == 0 && i + 1 < rows.size) onProgress(i + 1)
    }
    onProgress(rows.size)
}
