package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    private val dir = Files.createTempDirectory("settings")
    private val file = dir.resolve("settings.json")

    @Test
    fun `settings persist and default when file missing or corrupt`() {
        val missing = AppSettings(file).settings.value
        assertEquals(ThemeMode.System, missing.themeMode)
        assertNull(missing.adbPathOverride)
        assertTrue(missing.captureDir.endsWith("ScrcpyViewer"))
        assertEquals(defaultCaptureDir(), missing.captureDir)

        AppSettings(file).update { it.copy(themeMode = ThemeMode.Dark, adbPathOverride = "/opt/adb", captureDir = "/tmp/cap") }
        // Saved on a first launch, before the guide was closed.
        assertEquals(Settings(ThemeMode.Dark, "/opt/adb", "/tmp/cap", onboardingSeen = false), AppSettings(file).settings.value)

        Files.writeString(file, "{garbage")
        assertEquals(Settings(), AppSettings(file).settings.value)
        assertTrue(Files.exists(dir.resolve("settings.json.bak")))
    }

    @Test
    fun `captureDir resolved when absent from file`() {
        Files.writeString(file, """{"themeMode":"Light"}""")
        val s = AppSettings(file).settings.value
        assertEquals(ThemeMode.Light, s.themeMode)
        assertEquals(defaultCaptureDir(), s.captureDir)
    }

    @Test
    fun `useSystemTitleBar absent from older file loads as null`() {
        Files.writeString(file, """{"themeMode":"Light"}""")
        assertNull(AppSettings(file).settings.value.useSystemTitleBar)
    }

    @Test
    fun `useSystemTitleBar persists`() {
        AppSettings(file).update { it.copy(useSystemTitleBar = true) }
        assertEquals(true, AppSettings(file).settings.value.useSystemTitleBar)
    }

    @Test
    fun `onboarding shows on first launch and stays seen once closed`() {
        assertFalse(AppSettings(file).settings.value.onboardingSeen)
        AppSettings(file).update { it.copy(onboardingSeen = true) }
        assertTrue(AppSettings(file).settings.value.onboardingSeen)
    }

    @Test
    fun `onboarding absent from older file counts as seen`() {
        Files.writeString(file, """{"themeMode":"Light"}""")
        assertTrue(AppSettings(file).settings.value.onboardingSeen)
    }

    @Test
    fun `actions intro is unseen by default, also for an older file, and persists once seen`() {
        assertFalse(AppSettings(file).settings.value.actionsIntroSeen)
        Files.writeString(file, """{"themeMode":"Light","onboardingSeen":true}""")
        assertFalse(AppSettings(file).settings.value.actionsIntroSeen)
        AppSettings(file).update { it.copy(actionsIntroSeen = true) }
        assertTrue(AppSettings(file).settings.value.actionsIntroSeen)
    }
}
