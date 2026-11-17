package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The detail pane animates between categories, but not while search filters rows inside one category. */
class SettingsTransitionTest {
    private val live = mutableListOf<String>()

    // In the text slot: an Inline row also composes its control in hidden probes.
    private fun entry(id: String, title: String) = SettingEntry(id, title, notes = {
        DisposableEffect(Unit) {
            live += id
            onDispose { live -= id }
        }
    })

    private val categories = listOf(
        SettingsCategory(SettingsCategoryId.Appearance, "Appearance", Icons.Outlined.Palette, listOf(entry("theme", "Theme"), entry("bar", "Title bar"))),
        SettingsCategory(SettingsCategoryId.Mirroring, "Mirroring", Icons.Outlined.CastConnected, listOf(entry("folder", "Folder"))),
    )

    private fun scene(block: (SceneDriver, (SettingsNav) -> Unit) -> Unit) = onEdt {
        var nav by mutableStateOf(SettingsNav())
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            SettingsContent(SettingsUiState(), UpdateUiState(), nav, SettingsActions(), categories = categories)
        })
        try {
            driver.frames(5)
            block(driver) { nav = it }
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `switching category shows both panes mid-transition`() = scene { driver, setNav ->
        setNav(SettingsNav(selected = SettingsCategoryId.Mirroring))
        driver.frames(4)
        assertEquals(setOf("theme", "bar", "folder"), live.toSet())
        driver.frames(40)
        assertEquals(listOf("folder"), live)
    }

    @Test
    fun `filtering rows in the same category does not animate`() = scene { driver, setNav ->
        setNav(SettingsNav().withQuery("title"))
        driver.frames(4)
        assertEquals(listOf("bar"), live)
    }
}
