package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ARRANGE_LABEL
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeState
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.KEEP_TILED
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.NOT_MOVABLE_NOTE
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.menuLabel
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder

/** What a tray menu item does. */
sealed interface TrayAction {
    data object Mirror : TrayAction
    data object Focus : TrayAction
    data object OpenMain : TrayAction
    data object Settings : TrayAction
    data object Quit : TrayAction

    /** Arranges the mirror windows in [layout]; Focus puts the most recently focused mirror window in the large area. */
    data class Arrange(val layout: ArrangeLayout) : TrayAction

    /** Turns Keep tiled [on] or off. */
    data class KeepTiled(val on: Boolean) : TrayAction
}

/** One row of the tray menu. Plain data, so equal inputs give equal menus. */
sealed interface TrayRow {
    data class Header(val text: String) : TrayRow
    data class DeviceRow(val serial: String, val label: String, val action: TrayAction?) : TrayRow
    data object Divider : TrayRow
    data class Command(val label: String, val action: TrayAction, val enabled: Boolean = true) : TrayRow
    data class Submenu(val label: String, val rows: List<TrayRow>) : TrayRow
}

/** The prefix that marks the current layout and Keep tiled when on (no item icons in the native menu). */
const val TRAY_CHECK = "✓ "

/**
 * The "Arrange mirror windows" submenu for [state]: the five layouts (the current one checked) and Keep tiled. When the
 * desktop does not let the app move windows, the note first and every entry disabled.
 */
fun arrangeSubmenu(state: ArrangeState): TrayRow.Submenu = TrayRow.Submenu(
    ARRANGE_LABEL,
    buildList {
        if (!state.movable) add(TrayRow.Header(NOT_MOVABLE_NOTE))
        ArrangeLayout.entries.forEach { layout ->
            val check = if (layout == state.layout) TRAY_CHECK else ""
            add(TrayRow.Command(check + layout.trayLabel, TrayAction.Arrange(layout), enabled = state.movable))
        }
        add(TrayRow.Divider)
        add(
            TrayRow.Command(
                (if (state.keepTiled) TRAY_CHECK else "") + KEEP_TILED,
                TrayAction.KeepTiled(!state.keepTiled),
                enabled = state.movable,
            )
        )
    },
)

/** From the menu bar there is no "this window": Focus arranges around the front (most recently focused) one. */
private val ArrangeLayout.trayLabel: String
    get() = if (this == ArrangeLayout.Focus) "Focus the front window" else menuLabel

/**
 * The tray menu for [devices], in the given order. [mirroring] holds serials with an open mirror window. With [arrange]
 * (the arranger's state) and at least two mirror windows open, the "Arrange mirror windows" submenu follows the devices.
 */
fun trayMenu(
    devices: List<Device>,
    mirroring: Set<String>,
    arrange: ArrangeState? = null,
    title: (Device) -> String,
): List<TrayRow> = buildList {
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
    // Mirror windows whose device is gone from adb entirely stay reachable.
    val listed = devices.mapTo(HashSet()) { it.serial }
    mirroring.filter { it !in listed }.forEach { serial ->
        add(TrayRow.DeviceRow(serial, "🟢 $serial — Mirroring", TrayAction.Focus))
    }
    if (arrange != null && mirroring.size >= 2) {
        add(TrayRow.Divider)
        add(arrangeSubmenu(arrange))
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
            is TrayRow.Command ->
                if (!row.enabled) Item(label = row.label, isEnabled = false)
                else Item(label = row.label) { onAction(row.action, null) }
            is TrayRow.Submenu -> SubMenu(label = row.label) { render(row.rows, onAction) }
            is TrayRow.DeviceRow ->
                if (row.action == null) Item(label = row.label, isEnabled = false)
                else Item(label = row.label) { onAction(row.action, row.serial) }
        }
    }
}
