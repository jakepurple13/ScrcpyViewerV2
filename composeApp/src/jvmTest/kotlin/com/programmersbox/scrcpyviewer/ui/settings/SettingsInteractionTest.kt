package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsInteractionTest {
    @Test
    fun `clicking the title bar label toggles it`() = onEdt {
        val toggles = mutableListOf<Boolean>()
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            SettingsContent(
                SettingsUiState(), UpdateUiState(), SettingsNav(), SettingsActions(onUseSystemTitleBar = { toggles += it }),
                isLinux = false, customTitleBarSupported = true,
            )
        })
        try {
            driver.frames(5)
            // "Use system title bar" label, left of the switch, below the palette grid (see settings-appearance-light.png).
            driver.drag(fromX = 320f, toX = 320f, y = 540f)
            assertEquals(listOf(true), toggles)
        } finally {
            driver.scene.close()
        }
    }
}
