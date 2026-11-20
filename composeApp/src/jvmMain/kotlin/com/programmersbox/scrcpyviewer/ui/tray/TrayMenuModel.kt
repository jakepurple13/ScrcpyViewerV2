package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder

/** What a tray menu or tray popup item does. */
sealed interface TrayAction {
    data object Mirror : TrayAction
    data object Focus : TrayAction
    data object OpenMain : TrayAction
    data object Settings : TrayAction
    data object Quit : TrayAction

    /** Opens the menu bar popup (the tray handles it; Linux menu only). */
    data object ShowPopup : TrayAction

    /** Arranges the mirror windows in [layout]; Focus puts the most recently focused mirror window in the large area. */
    data class Arrange(val layout: ArrangeLayout) : TrayAction

    /** Turns Keep tiled [on] or off. */
    data class KeepTiled(val on: Boolean) : TrayAction
}

/** One row of the tray menu. Plain data, so equal inputs give equal menus. */
sealed interface TrayRow {
    data object Divider : TrayRow
    data class Command(val label: String, val action: TrayAction, val enabled: Boolean = true) : TrayRow
}

/**
 * The tray icon's right-click menu. Fixed, so the native menu is only rebuilt when the icon or tooltip changes; devices
 * live in the popup. On Linux "Show devices…" comes first, since some desktops never deliver the icon's left-click.
 */
fun fixedTrayMenu(linux: Boolean = Os.isLinux): List<TrayRow> = buildList {
    if (linux) add(TrayRow.Command("Show devices…", TrayAction.ShowPopup))
    add(TrayRow.Command("Open Scrcpy Vwr", TrayAction.OpenMain))
    add(TrayRow.Command("Settings…", TrayAction.Settings))
    add(TrayRow.Divider)
    add(TrayRow.Command("Quit Scrcpy Vwr", TrayAction.Quit))
}

/** Writes [rows] into the native menu. Disabled rows stay visible. No item icons. */
fun TrayMenuBuilder.render(rows: List<TrayRow>, onAction: (TrayAction) -> Unit) {
    rows.forEach { row ->
        when (row) {
            TrayRow.Divider -> Divider()
            is TrayRow.Command ->
                if (!row.enabled) Item(label = row.label, isEnabled = false)
                else Item(label = row.label) { onAction(row.action) }
        }
    }
}
