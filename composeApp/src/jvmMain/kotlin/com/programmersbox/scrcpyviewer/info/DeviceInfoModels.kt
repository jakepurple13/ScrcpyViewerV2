package com.programmersbox.scrcpyviewer.info

enum class ChargeStatus { Charging, Discharging, NotCharging, Full, Unknown }

enum class PowerSource { Ac, Usb, Wireless, Dock, None }

enum class BatteryHealth { Good, Overheat, Dead, OverVoltage, Cold, Unknown }

data class BatteryInfo(
    val level: Int,
    val status: ChargeStatus,
    val power: PowerSource,
    val temperatureC: Double?,
    val voltageMv: Int?,
    val health: BatteryHealth,
)

data class MemoryInfo(val totalKb: Long, val availableKb: Long) {
    val usedKb: Long get() = totalKb - availableKb
}

/**
 * Byte sizes from `dumpsys diskstats`. [system] is the system partition, which is not part of /data and is not shown.
 * [other] is diskstats' own figure; the UI does not show it and shows used minus [apps] minus [media] as Other instead.
 */
data class StorageBreakdown(val apps: Long, val media: Long, val system: Long, val other: Long)

data class StorageInfo(val totalBytes: Long, val usedBytes: Long, val breakdown: StorageBreakdown?)

data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val overrideWidthPx: Int?,
    val overrideHeightPx: Int?,
    val overrideDensityDpi: Int?,
    val refreshHz: Double?,
) {
    /** Smallest width in dp, using override size and density when present. */
    val smallestWidthDp: Int
        get() {
            val w = overrideWidthPx ?: widthPx
            val h = overrideHeightPx ?: heightPx
            val density = overrideDensityDpi ?: densityDpi
            return if (density <= 0) 0 else minOf(w, h) * 160 / density
        }
}

data class NetworkInfo(val ssid: String?, val ipAddress: String?, val linkMbps: Int?)

data class SoftwareInfo(
    val androidVersion: String?,
    val sdk: Int?,
    val securityPatch: String?,
    val buildId: String?,
    val abi: String?,
    val manufacturer: String?,
    val model: String?,
    val oneUiVersion: String?,
    val kernel: String?,
)

data class StaticInfo(
    val software: SoftwareInfo,
    val display: DisplayInfo?,
    val storage: StorageInfo?,
    val network: NetworkInfo,
)

data class LiveInfo(val battery: BatteryInfo?, val memory: MemoryInfo?)
