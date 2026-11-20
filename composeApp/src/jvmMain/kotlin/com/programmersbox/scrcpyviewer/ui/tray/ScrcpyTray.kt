package com.programmersbox.scrcpyviewer.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequests
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.ui.window.pointerLocation

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
    /** Arranges the mirror windows; Focus around the most recently focused mirror window. */
    private val arrange: (ArrangeLayout) -> Unit,
    private val keepTiled: (Boolean) -> Unit,
) {
    fun dispatch(action: TrayAction, serial: String?) {
        when (action) {
            TrayAction.Mirror -> serial?.let(::mirror)
            TrayAction.Focus -> serial?.let(focus)
            TrayAction.OpenMain -> mainWindow.show()
            TrayAction.Settings -> mainWindow.show(TopLevelRoute.Settings)
            TrayAction.Quit -> quit()
            is TrayAction.Arrange -> arrange(action.layout)
            is TrayAction.KeepTiled -> keepTiled(action.on)
            // The tray opens the popup itself (ScrcpyTray).
            TrayAction.ShowPopup -> Unit
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
 * The menu bar (macOS) / system tray (Windows, Linux) icon. Left-click calls [onIconClick] on the tray thread (it must
 * only post state); right-click opens the fixed menu ([fixedTrayMenu]). The menu's "Show devices…" (Linux) also calls
 * [onIconClick], with no pointer location.
 */
@Composable
fun ScrcpyTray(
    icons: TrayIcons,
    onAction: (TrayAction) -> Unit,
    onIconClick: (pointer: IntArray?) -> Unit,
) {
    // Items keep the lambdas of the last native rebuild, so they read the handlers through these states.
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnIconClick by rememberUpdatedState(onIconClick)
    val rows = remember { fixedTrayMenu() }
    OffMainTray(
        iconPath = icons.png.toString(),
        windowsIconPath = icons.ico.toString(),
        tooltip = "Scrcpy Vwr",
        key = rows,
        // The pointer read is a quick native call on the tray thread, right at the click.
        primaryAction = { currentOnIconClick(pointerLocation()) },
    ) {
        render(rows) { action ->
            if (action == TrayAction.ShowPopup) currentOnIconClick(null) else currentOnAction(action)
        }
    }
}
