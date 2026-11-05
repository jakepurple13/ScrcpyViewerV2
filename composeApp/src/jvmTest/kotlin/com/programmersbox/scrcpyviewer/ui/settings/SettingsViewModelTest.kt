package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.assumePosix
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("settings-vm")
    private val settings = AppSettings(dir.resolve("settings.json"))
    private val presets = PresetStore(dir.resolve("presets.json"), scope)
    private var restarts = 0
    private val bundled = dir.resolve("bundled-adb")
    private fun installer() = AiSkillInstaller(dir.resolve("home"), dir.resolve("inbox"), dir.resolve("actions.json"))

    private fun adb(bundledAdb: () -> Path = { bundled }, version: (Path) -> String?) = object : SettingsAdb {
        override fun bundledAdb() = bundledAdb()
        override suspend fun version(path: Path) = version(path)
        override fun restartTracker() {
            restarts++
        }
    }

    /**
     * Waits for the AI-skill rows the view model loads on an IO thread at start. Until then that thread can update
     * the combined state at the same time as a test's action, and a test reading state.value could miss its change.
     */
    private fun vm(version: (Path) -> String? = { "1.0.41 (35.0.2)" }) =
        SettingsViewModel(settings, presets, adb(version = version), installer(), scope).also { vm ->
            runBlocking { withTimeout(10_000) { vm.state.first { it.aiSkill.isNotEmpty() } } }
        }

    private fun executable(name: String): Path {
        assumePosix()
        return Files.createFile(dir.resolve(name)).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x")) }
    }

    @Test
    fun `theme change is saved`() {
        val vm = vm()
        vm.setTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings.settings.value.themeMode)
        assertEquals(ThemeMode.Dark, vm.state.value.settings.themeMode)
    }

    @Test
    fun `missing and non-executable adb paths are rejected and not saved`() {
        assumePosix()
        val vm = vm()
        vm.useCustomAdb(dir.resolve("nope").toString())
        assertNotNull(vm.state.value.adbPathError)
        assertNull(settings.settings.value.adbPathOverride)

        val plain = Files.createFile(dir.resolve("plain"))
        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-r--r--"))
        vm.useCustomAdb(plain.toString())
        assertEquals("Not executable: $plain", vm.state.value.adbPathError)
        assertNull(settings.settings.value.adbPathOverride)

        vm.useCustomAdb(dir.toString())
        assertEquals("Not a file: $dir", vm.state.value.adbPathError)
        assertEquals(0, restarts)
    }

    @Test
    fun `valid custom adb is saved, versioned, restarts tracker once, and clears the error`() {
        val vm = vm { if (it == bundled) "1.0.41 (34.0.0)" else "1.0.41 (35.0.2)" }
        vm.useCustomAdb(dir.resolve("nope").toString())
        val adb = executable("adb")
        vm.useCustomAdb(adb.toString())
        assertNull(vm.state.value.adbPathError)
        assertEquals(adb.toString(), settings.settings.value.adbPathOverride)
        assertEquals("1.0.41 (35.0.2)", vm.state.value.customAdbVersion)
        assertEquals("1.0.41 (34.0.0)", vm.state.value.bundledAdbVersion)
        assertEquals(1, restarts)
        vm.useCustomAdb(adb.toString())
        assertEquals(1, restarts, "same path again does not restart")
    }

    @Test
    fun `back to bundled clears the override and restarts`() {
        val vm = vm()
        vm.useCustomAdb(executable("adb").toString())
        vm.useBundledAdb()
        assertNull(settings.settings.value.adbPathOverride)
        assertNull(vm.state.value.customAdbVersion)
        assertEquals(2, restarts)
        vm.useBundledAdb()
        assertEquals(2, restarts)
    }

    @Test
    fun `version is Unknown when adb fails`() {
        val vm = vm { null }
        assertEquals(UNKNOWN_VERSION, vm.state.value.bundledAdbVersion)
        val broken = SettingsViewModel(settings, presets, adb(bundledAdb = { error("missing") }, version = { null }), installer(), scope)
        assertEquals(UNKNOWN_VERSION, broken.state.value.bundledAdbVersion)
    }

    @Test
    fun `default preset can be set and cleared with None`() {
        val vm = vm()
        presets.upsert(Preset("qa", "QA default", options = OptionValues(emptyMap())))
        vm.setDefaultPreset("qa")
        assertEquals("qa", vm.state.value.defaultPresetId)
        vm.setDefaultPreset(null)
        assertNull(vm.state.value.defaultPresetId)
        assertNull(presets.defaultPresetId.value)
    }

    @Test
    fun `capture dir is saved`() {
        val vm = vm()
        vm.setCaptureDir("/tmp/caps")
        assertEquals("/tmp/caps", settings.settings.value.captureDir)
    }

    @Test
    fun `system title bar choice is saved`() {
        val vm = vm()
        vm.setUseSystemTitleBar(true)
        assertEquals(true, settings.settings.value.useSystemTitleBar)
        vm.setUseSystemTitleBar(false)
        assertEquals(false, settings.settings.value.useSystemTitleBar)
    }

    @Test
    fun `parses adb version output`() {
        assertEquals(
            "1.0.41 (35.0.2)",
            parseAdbVersion("Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\nInstalled as /x/adb\nRunning on Darwin\n"),
        )
        assertEquals("1.0.39", parseAdbVersion("Android Debug Bridge version 1.0.39\n"))
        assertNull(parseAdbVersion("garbage"))
    }

    @Test
    fun `selection survives as view model state`() {
        val nav = vm().apply { selectCategory(SettingsCategoryId.Help); setQuery("x") }.nav.value
        assertEquals(SettingsCategoryId.Help, nav.selected)
        assertEquals("x", nav.query)
    }
}
