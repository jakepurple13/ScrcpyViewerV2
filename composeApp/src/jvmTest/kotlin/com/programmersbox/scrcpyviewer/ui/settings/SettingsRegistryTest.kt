package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Adb
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Ai
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Appearance
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Help
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Mirroring
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Updates
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRegistryTest {
    @Test
    fun `categories in spec order`() =
        assertEquals(listOf(Appearance, Mirroring, Adb, Ai, Updates, Help), settingsCategories.map { it.id })

    @Test
    fun `entry ids are unique`() {
        val ids = settingsCategories.flatMap { c -> c.entries.map { it.id } }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `common searches find the right setting`() {
        fun hit(q: String) = searchSettings(settingsCategories, q).first().entries.map { it.id }
        assertEquals(listOf("theme"), hit("dark mode"))
        assertEquals(listOf("capture-dir"), hit("folder"))
        assertEquals(listOf("adb-path"), hit("platform-tools"))
        assertEquals(listOf("updates"), hit("github"))
        assertEquals(listOf("welcome-guide"), hit("wifi"))
        assertEquals(listOf("ai-skill"), hit("gemini"))
        assertEquals(listOf("launch-quit-animation"), hit("animation"))
        assertEquals(listOf("launch-quit-animation"), hit("startup"))
        assertEquals(listOf("animate-mirror"), hit("zoom"))
        assertEquals(listOf("tray-icon"), hit("menu bar"))
        assertEquals(listOf("tray-icon"), hit("system tray"))
    }

    @Test
    fun `launch quit animation is the last appearance entry`() {
        val entry = settingsCategories.first { it.id == Appearance }.entries.last()
        assertEquals("launch-quit-animation", entry.id)
        assertEquals("Launch & quit animation", entry.title)
        assertEquals("Plays when Scrcpy Vwr opens and quits.", entry.description)
        // Preview tiles below the title, like the Style picker.
        assertEquals(EntryLayout.Block, entry.layout)
    }

    @Test
    fun `tray icon row is named for the platform`() {
        assertEquals("Show icon in menu bar", trayIconSettingTitle(isMac = true))
        assertEquals("Show icon in system tray", trayIconSettingTitle(isMac = false))
        val entry = settingsCategories.first { it.id == Appearance }.entries.single { it.id == "tray-icon" }
        assertEquals(trayIconSettingTitle(), entry.title)
    }
}
