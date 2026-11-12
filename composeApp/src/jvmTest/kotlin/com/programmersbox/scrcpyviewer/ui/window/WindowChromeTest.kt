package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.settings.settingsCategories
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Use system title bar" is retired: every window draws the Nucleus Material title bar. */
class WindowChromeTest {
    @Test
    fun `Settings no longer offers the system title bar`() {
        val ids = settingsCategories.flatMap { c -> c.entries.map { it.id } }
        assertTrue("title-bar" !in ids, "title-bar entry still listed: $ids")
    }

    @Test
    fun `a settings file that chose the system title bar still loads`() {
        val file = Files.createTempDirectory("settings").resolve("settings.json")
        Files.writeString(file, """{"themeMode":"Dark","useSystemTitleBar":true}""")
        val settings = AppSettings(file).settings.value
        assertEquals(com.programmersbox.scrcpyviewer.ui.theme.ThemeMode.Dark, settings.themeMode)
        // Kept so the file parses; nothing reads it.
        assertEquals(true, settings.useSystemTitleBar)
    }
}
