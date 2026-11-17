package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.ui.devices.isEmulatorSerial

/** How a device is connected, as events send it: emulator, wifi (adb over TCP) or usb. */
fun connectionOf(serial: String, isTcp: Boolean): String = when {
    isEmulatorSerial(serial) -> "emulator"
    isTcp -> "wifi"
    else -> "usb"
}
