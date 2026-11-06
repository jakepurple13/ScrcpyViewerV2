package com.programmersbox.scrcpyviewer.adb

enum class DeviceState { Device, Unauthorized, Offline, Other }

/** [serialNo] is `ro.serialno`: the hardware serial, the same over USB and Wi-Fi. */
data class DeviceInfo(val model: String, val androidVersion: String, val sdk: Int, val serialNo: String? = null)

data class Device(
    val serial: String,
    val state: DeviceState,
    val rawState: String,
    val model: String?,
    val product: String?,
    val transportId: Long?,
    val isTcp: Boolean,
    val info: DeviceInfo? = null,
)

/**
 * What Claude Device Manager is asked for: the hardware serial once it is known (the same over USB and Wi-Fi, so both
 * connections of one phone share one CDM hold); otherwise the adb serial.
 */
fun Device.cdmDevice(): String = info?.serialNo?.takeIf { it.isNotBlank() } ?: serial

/**
 * The key device options are stored under: the hardware serial for a Wi-Fi device once it is known, so a phone
 * switched from USB to Wi-Fi keeps its options; otherwise the adb serial.
 */
fun Device.optionsKey(): String =
    info?.serialNo?.takeIf { isTcp && it.isNotBlank() } ?: serial
