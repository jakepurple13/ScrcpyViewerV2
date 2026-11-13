package com.programmersbox.scrcpyviewer.ui.devices.info

import com.programmersbox.scrcpyviewer.info.BatteryHealth
import com.programmersbox.scrcpyviewer.info.BatteryInfo
import com.programmersbox.scrcpyviewer.info.ChargeStatus
import com.programmersbox.scrcpyviewer.info.DisplayInfo
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.MemoryInfo
import com.programmersbox.scrcpyviewer.info.NetworkInfo
import com.programmersbox.scrcpyviewer.info.PowerSource
import com.programmersbox.scrcpyviewer.info.SoftwareInfo
import com.programmersbox.scrcpyviewer.info.StaticInfo
import com.programmersbox.scrcpyviewer.info.StorageInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Info tab's wording: card values and the "Copy as text" report. Pure, so it is tested without Compose. */
object InfoText {
    const val NO_BATTERY = "No battery"
    const val NOT_ON_WIFI = "Not on Wi-Fi"

    /** Decimal gigabytes with one decimal: 18_601_705_472 → "18.6". */
    fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000_000.0)

    /** [gb] for a kB value from `/proc/meminfo` or `df -k`. */
    fun gbFromKb(kb: Long): String = gb(kb * 1024)

    /** What the battery is doing, for the card: "Charging · USB", "Full · plugged in", "On battery". */
    fun batteryState(b: BatteryInfo): String {
        val plugged = b.power != PowerSource.None
        return when (b.status) {
            ChargeStatus.Charging -> powerName(b.power)?.let { "Charging · $it" } ?: "Charging"
            ChargeStatus.Full -> if (plugged) "Full · plugged in" else "Full"
            ChargeStatus.NotCharging -> if (plugged) "Plugged in, not charging" else "Not charging"
            ChargeStatus.Discharging -> "On battery"
            ChargeStatus.Unknown -> if (plugged) "Plugged in" else "On battery"
        }
    }

    /** The card's charge source name, or null on battery. */
    fun powerName(power: PowerSource): String? = when (power) {
        PowerSource.Ac -> "AC"
        PowerSource.Usb -> "USB"
        PowerSource.Wireless -> "wireless"
        PowerSource.Dock -> "dock"
        PowerSource.None -> null
    }

    /** "Good", "Overheat", … or null when the device doesn't say. */
    fun health(health: BatteryHealth): String? = when (health) {
        BatteryHealth.Good -> "Good"
        BatteryHealth.Overheat -> "Overheat"
        BatteryHealth.Dead -> "Dead"
        BatteryHealth.OverVoltage -> "Over voltage"
        BatteryHealth.Cold -> "Cold"
        BatteryHealth.Unknown -> null
    }

    fun temperature(celsius: Double): String = String.format(Locale.ROOT, "%.1f °C", celsius)

    fun voltage(mv: Int): String = String.format(Locale.ROOT, "%.2f V", mv / 1000.0)

    /** "90 Hz", or "59.9 Hz" for a rate that isn't whole. */
    fun refresh(hz: Double): String {
        val whole = hz.roundToInt()
        return if (abs(hz - whole) < 0.05) "$whole Hz" else String.format(Locale.ROOT, "%.1f Hz", hz)
    }

    /** "1080 × 2340", or "720 × 1560 (set, device is 1080 × 2340)" while a size override is active. */
    fun resolution(d: DisplayInfo): String {
        val physical = "${d.widthPx} × ${d.heightPx}"
        val w = d.overrideWidthPx
        val h = d.overrideHeightPx
        return if (w != null && h != null) "$w × $h (set, device is $physical)" else physical
    }

    /** "450 dpi", or "300 dpi (set, device is 450)" while a density override is active. */
    fun density(d: DisplayInfo): String =
        d.overrideDensityDpi?.let { "$it dpi (set, device is ${d.densityDpi})" } ?: "${d.densityDpi} dpi"

    fun smallestWidth(d: DisplayInfo): String = "sw${d.smallestWidthDp}dp"

    /** "16 (API 36)", "16", "API 36", or null when neither is known. */
    fun androidLine(s: SoftwareInfo): String? = when {
        s.androidVersion != null && s.sdk != null -> "${s.androidVersion} (API ${s.sdk})"
        s.androidVersion != null -> s.androidVersion
        s.sdk != null -> "API ${s.sdk}"
        else -> null
    }

    fun linkSpeed(mbps: Int): String = "$mbps Mbps"

    /** True when the device has no Wi-Fi name or address: the Network card reads [NOT_ON_WIFI]. */
    fun offWifi(n: NetworkInfo): Boolean = n.ssid == null && n.ipAddress == null

    /** How the used bytes on `/data` split for the Storage card's bar and legend. The three parts sum to used. */
    data class StorageParts(val apps: Long, val media: Long, val other: Long)

    /**
     * Apps (app size + app data) and media (photos, videos, audio, downloads) from diskstats, and other = used minus
     * both. diskstats' System Size is the system partition, not `/data`, so it is left out. When apps and media alone
     * exceed used, they are scaled to fit it and other is 0. Null without a breakdown.
     */
    fun storageParts(s: StorageInfo): StorageParts? {
        val b = s.breakdown ?: return null
        val used = s.usedBytes.coerceAtLeast(0)
        val apps = b.apps.coerceAtLeast(0)
        val media = b.media.coerceAtLeast(0)
        if (apps + media <= used) return StorageParts(apps, media, used - apps - media)
        val scaledApps = Math.round(used.toDouble() * apps / (apps + media))
        return StorageParts(scaledApps, used - scaledApps, 0)
    }

    private const val BREAK = '​'

    /**
     * [value] with a zero-width break chance after every '.', '-' and '_', so a long build id or kernel version wraps
     * between its parts instead of in the middle of one.
     */
    fun breakable(value: String): String = buildString(value.length + 8) {
        value.forEachIndexed { i, c ->
            append(c)
            if ((c == '.' || c == '-' || c == '_') && i < value.lastIndex) append(BREAK)
        }
    }

    fun storageLine(s: StorageInfo): String = "${gb(s.usedBytes)} of ${gb(s.totalBytes)} GB used"

    fun memoryLine(m: MemoryInfo): String = "${gbFromKb(m.usedKb)} of ${gbFromKb(m.totalKb)} GB in use"

    /**
     * Every known field as plain lines for a bug report. The first line is "title · serial"; a section whose values
     * are all missing has no line.
     */
    fun report(title: String, serial: String, static: StaticInfo?, live: LiveInfo?): String = buildList {
        add("$title · $serial")
        live?.battery?.let { add("Battery: " + batteryReport(it)) }
        static?.storage?.let { add("Storage: " + storageLine(it)) }
        live?.memory?.let { add("Memory: " + memoryLine(it)) }
        static?.display?.let { d ->
            add("Display: " + listOfNotNull(resolution(d), density(d), d.refreshHz?.let(::refresh), smallestWidth(d)).joinToString(", "))
        }
        static?.network?.let { n ->
            val parts = listOfNotNull(n.ssid, n.ipAddress, n.linkMbps?.let(::linkSpeed))
            add("Network: " + if (offWifi(n)) NOT_ON_WIFI else parts.joinToString(", "))
        }
        static?.software?.let { s ->
            val android = listOfNotNull(
                androidLine(s)?.let { "Android $it" },
                s.oneUiVersion?.let { "One UI $it" },
                s.securityPatch?.let { "patch $it" },
            )
            if (android.isNotEmpty()) add(android.joinToString(", "))
            val build = listOfNotNull(s.buildId, s.abi, s.kernel?.let { "kernel $it" })
            if (build.isNotEmpty()) add("Build: " + build.joinToString(", "))
        }
    }.joinToString("\n")

    /** "100%, full, USB, 29.2 °C, good". */
    private fun batteryReport(b: BatteryInfo): String = listOfNotNull(
        "${b.level}%",
        when (b.status) {
            ChargeStatus.Charging -> "charging"
            ChargeStatus.Discharging -> "on battery"
            ChargeStatus.NotCharging -> "not charging"
            ChargeStatus.Full -> "full"
            ChargeStatus.Unknown -> null
        },
        powerName(b.power),
        b.temperatureC?.let(::temperature),
        health(b.health)?.lowercase(),
    ).joinToString(", ")
}
