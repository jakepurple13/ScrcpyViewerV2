package com.programmersbox.scrcpyviewer.ui.tray

import kotlin.test.Test
import kotlin.test.assertEquals

class TrayMenuModelTest {
    private val fixed = listOf(
        TrayRow.Command("Open Scrcpy Vwr", TrayAction.OpenMain),
        TrayRow.Command("Settings…", TrayAction.Settings),
        TrayRow.Divider,
        TrayRow.Command("Quit Scrcpy Vwr", TrayAction.Quit),
    )

    @Test
    fun fixedMenuHasOpenSettingsQuit() {
        assertEquals(fixed, fixedTrayMenu(linux = false))
    }

    @Test
    fun linuxMenuStartsWithShowDevices() {
        // Some Linux desktops never deliver the icon's left-click, so the popup is reachable from the menu too.
        assertEquals(listOf(TrayRow.Command("Show devices…", TrayAction.ShowPopup)) + fixed, fixedTrayMenu(linux = true))
    }
}
