package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder

enum class TrayAction { Mirror, Focus, OpenMain, Settings, Quit }

/** One row of the tray menu. Plain data, so equal inputs give equal menus. */
sealed interface TrayRow {
    data class Header(val text: String) : TrayRow
    data class DeviceRow(val serial: String, val label: String, val action: TrayAction?) : TrayRow
    data object Divider : TrayRow
    data class Command(val label: String, val action: TrayAction) : TrayRow
}

/** The tray menu for [devices], in the given order. [mirroring] holds serials with an open mirror window. */
fun trayMenu(devices: List<Device>, mirroring: Set<String>, title: (Device) -> String): List<TrayRow> = buildList {
    add(
        TrayRow.Header(
            when (devices.size) {
                0 -> "No devices connected"
                1 -> "Scrcpy Vwr · 1 device"
                else -> "Scrcpy Vwr · ${devices.size} devices"
            }
        )
    )
    if (devices.isNotEmpty()) add(TrayRow.Divider)
    devices.forEach { device ->
        val name = title(device)
        // An open mirror is reachable whatever adb now says about its device (unauthorized, offline).
        val (label, action) = when {
            device.serial in mirroring -> "🟢 $name — Mirroring" to TrayAction.Focus
            device.state == DeviceState.Device -> "⚪ $name — Mirror" to TrayAction.Mirror
            device.state == DeviceState.Unauthorized -> "🟠 $name — Accept the USB debugging prompt" to null
            device.state == DeviceState.Offline -> "⚫ $name — Offline" to null
            else -> "⚫ $name — ${device.rawState}" to null
        }
        add(TrayRow.DeviceRow(device.serial, label, action))
    }
    add(TrayRow.Divider)
    add(TrayRow.Command("Open Scrcpy Vwr", TrayAction.OpenMain))
    add(TrayRow.Command("Settings…", TrayAction.Settings))
    add(TrayRow.Divider)
    add(TrayRow.Command("Quit Scrcpy Vwr", TrayAction.Quit))
}

/** Writes [rows] into the native menu. Rows without an action are disabled. No item icons. */
fun TrayMenuBuilder.render(rows: List<TrayRow>, onAction: (TrayAction, serial: String?) -> Unit) {
    rows.forEach { row ->
        when (row) {
            is TrayRow.Header -> Item(label = row.text, isEnabled = false)
            TrayRow.Divider -> Divider()
            is TrayRow.Command -> Item(label = row.label) { onAction(row.action, null) }
            is TrayRow.DeviceRow ->
                if (row.action == null) Item(label = row.label, isEnabled = false)
                else Item(label = row.label) { onAction(row.action, row.serial) }
        }
    }
}
