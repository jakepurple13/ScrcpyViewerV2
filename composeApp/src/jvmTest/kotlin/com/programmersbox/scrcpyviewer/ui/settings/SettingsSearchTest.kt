package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val theme = SettingEntry("theme", "Theme", "Light, dark, or follow the system.", listOf("dark mode", "colour"))
    private val titleBar = SettingEntry("title-bar", "Use system title bar")
    private val folder = SettingEntry("capture-dir", "Screenshots and recordings folder", "Where captures are saved.")
    private val version = SettingEntry("version", "Version v1.5")
    private val appearance = SettingsCategory(SettingsCategoryId.Appearance, "Appearance", Icons.Outlined.Palette, listOf(theme, titleBar))
    private val mirroring = SettingsCategory(SettingsCategoryId.Mirroring, "Mirroring", Icons.Outlined.CastConnected, listOf(folder, version))
    private val cats = listOf(appearance, mirroring)

    private fun ids(query: String) = searchSettings(cats, query).associate { m -> m.category.id to m.entries.map { it.id } }

    @Test
    fun `blank query returns every category with all entries`() {
        val all = mapOf(
            SettingsCategoryId.Appearance to listOf("theme", "title-bar"),
            SettingsCategoryId.Mirroring to listOf("capture-dir", "version"),
        )
        assertEquals(all, ids(""))
        assertEquals(all, ids("   "))
    }

    @Test
    fun `matches title description and keywords case-insensitively`() {
        assertEquals(mapOf(SettingsCategoryId.Appearance to listOf("theme")), ids("DARK"))
        assertEquals(mapOf(SettingsCategoryId.Mirroring to listOf("capture-dir")), ids("saved"))
        assertEquals(mapOf(SettingsCategoryId.Appearance to listOf("theme")), ids("theme"))
    }

    @Test
    fun `category title match keeps all its entries`() {
        assertEquals(mapOf(SettingsCategoryId.Mirroring to listOf("capture-dir", "version")), ids("mirror"))
    }

    @Test
    fun `categories without matches are dropped`() {
        assertEquals(listOf(SettingsCategoryId.Appearance), ids("title bar").keys.toList())
        assertTrue(searchSettings(cats, "zzz").isEmpty())
    }

    @Test
    fun `query is trimmed`() {
        assertEquals(ids("folder"), ids("  folder "))
    }

    @Test
    fun `regex metacharacters match literally`() {
        assertTrue(searchSettings(cats, "(").isEmpty())
        assertEquals(mapOf(SettingsCategoryId.Appearance to listOf("theme"), SettingsCategoryId.Mirroring to listOf("capture-dir", "version")), ids("."))
        assertEquals(mapOf(SettingsCategoryId.Mirroring to listOf("version")), ids("1.5"))
        val v105 = SettingsCategory(SettingsCategoryId.Help, "Help", Icons.Outlined.Info, listOf(SettingEntry("x", "v105")))
        assertTrue(searchSettings(listOf(v105), "1.5").isEmpty())
    }

    @Test
    fun `matchRanges finds every occurrence`() {
        assertEquals(listOf(0..3, 10..13), matchRanges("Data path data", "data"))
        assertEquals(listOf(0..1), matchRanges("aaa", "aa"))
        assertEquals(emptyList(), matchRanges("x", " "))
        assertEquals(listOf(1..2), matchRanges("a.b", " .b "))
    }
}
