package com.programmersbox.scrcpyviewer.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequests
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute

/**
 * What each tray menu item does. Every call only posts a request (a flow emission or a state change), so the native
 * menu's callback thread never runs app code itself.
 */
class TrayCommands(
    private val mirrorRequests: MirrorRequests,
    private val mainWindow: MainWindowRequests,
    /** Whether [serial] is ready and its options are valid, as for the Devices screen's Mirror button. */
    private val canMirror: (serial: String) -> Boolean,
    private val focus: (serial: String) -> Unit,
    /** Selects [serial] on the Devices screen. */
    private val select: (serial: String) -> Unit,
    /** The same shutdown as closing the main window. */
    private val quit: () -> Unit,
) {
    fun dispatch(action: TrayAction, serial: String?) {
        when (action) {
            TrayAction.Mirror -> serial?.let(::mirror)
            TrayAction.Focus -> serial?.let(focus)
            TrayAction.OpenMain -> mainWindow.show()
            TrayAction.Settings -> mainWindow.show(TopLevelRoute.Settings)
            TrayAction.Quit -> quit()
        }
    }

    /** Mirrors like the Devices Mirror button; options it can't mirror with open the device on the Devices screen. */
    private fun mirror(serial: String) {
        if (canMirror(serial)) {
            mirrorRequests.request(serial)
        } else {
            select(serial)
            mainWindow.show(TopLevelRoute.Devices)
        }
    }
}

/**
 * The menu bar (macOS) / system tray (Windows, Linux) icon: one row per device, then Open, Settings… and Quit.
 * [mirroring] holds the serials with an open mirror window.
 */
@Composable
fun ScrcpyTray(
    icons: TrayIcons,
    devices: List<Device>,
    mirroring: Set<String>,
    onAction: (TrayAction, serial: String?) -> Unit,
) {
    // The native menu is only rebuilt when its content hash changes, and the hash ignores click lambdas, so its
    // items keep the lambdas of the last rebuild. They read the handler through this state to always call the current one.
    val currentOnAction by rememberUpdatedState(onAction)
    val rows = remember(devices, mirroring) { trayMenu(devices, mirroring, ::deviceTitle) }
    OffMainTray(
        iconPath = icons.png.toString(),
        windowsIconPath = icons.ico.toString(),
        tooltip = "Scrcpy Vwr",
    ) {
        render(rows) { action, serial -> currentOnAction(action, serial) }
    }
}
