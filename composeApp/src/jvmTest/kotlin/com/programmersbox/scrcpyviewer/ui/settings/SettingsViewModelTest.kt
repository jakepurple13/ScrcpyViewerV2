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
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * The state once [until] holds, or after 5 s the state as it is (so the assertion that follows reports the value).
     * The view model's state combines flows some of which update on an IO thread; while that thread is inside the
     * combine, an update from the test's thread reaches the state a moment later, so state.value read right after an
     * action can still hold the value from before it.
     */
    private fun SettingsViewModel.settled(until: (SettingsUiState) -> Boolean): SettingsUiState =
        runBlocking { withTimeoutOrNull(5_000) { state.first(until) } } ?: state.value

    private fun executable(name: String): Path {
        assumePosix()
        return Files.createFile(dir.resolve(name)).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x")) }
    }

    @Test
    fun `picking a neon palette turns neon on, and it can be turned off again`() {
        val vm = vm()
        vm.setPalette(com.programmersbox.scrcpyviewer.ui.theme.ThemePalette.CustomNeon)
        assertEquals(true, settings.settings.value.neon)
        vm.setNeon(false)
        assertEquals(false, settings.settings.value.neon)
        assertEquals("CustomNeon", settings.settings.value.themePalette)
        // A regular palette leaves the switch as it is.
        vm.setNeon(true)
        vm.setPalette(com.programmersbox.scrcpyviewer.ui.theme.ThemePalette.Rose)
        assertEquals(true, settings.settings.value.neon)
    }

    @Test
    fun `theme change is saved`() {
        val vm = vm()
        vm.setTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings.settings.value.themeMode)
        assertEquals(ThemeMode.Dark, vm.settled { it.settings.themeMode == ThemeMode.Dark }.settings.themeMode)
    }

    @Test
    fun `missing and non-executable adb paths are rejected and not saved`() {
        assumePosix()
        val vm = vm()
        vm.useCustomAdb(dir.resolve("nope").toString())
        assertNotNull(vm.settled { it.adbPathError != null }.adbPathError)
        assertNull(settings.settings.value.adbPathOverride)

        val plain = Files.createFile(dir.resolve("plain"))
        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-r--r--"))
        vm.useCustomAdb(plain.toString())
        assertEquals("Not executable: $plain", vm.settled { it.adbPathError == "Not executable: $plain" }.adbPathError)
        assertNull(settings.settings.value.adbPathOverride)

        vm.useCustomAdb(dir.toString())
        assertEquals("Not a file: $dir", vm.settled { it.adbPathError == "Not a file: $dir" }.adbPathError)
        assertEquals(0, restarts)
    }

    @Test
    fun `valid custom adb is saved, versioned, restarts tracker once, and clears the error`() {
        val vm = vm { if (it == bundled) "1.0.41 (34.0.0)" else "1.0.41 (35.0.2)" }
        vm.useCustomAdb(dir.resolve("nope").toString())
        val adb = executable("adb")
        vm.useCustomAdb(adb.toString())
        val settled = vm.settled {
            it.adbPathError == null && it.customAdbVersion == "1.0.41 (35.0.2)" && it.bundledAdbVersion == "1.0.41 (34.0.0)"
        }
        assertNull(settled.adbPathError)
        assertEquals(adb.toString(), settings.settings.value.adbPathOverride)
        assertEquals("1.0.41 (35.0.2)", settled.customAdbVersion)
        assertEquals("1.0.41 (34.0.0)", settled.bundledAdbVersion)
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
        assertNull(vm.settled { it.customAdbVersion == null }.customAdbVersion)
        assertEquals(2, restarts)
        vm.useBundledAdb()
        assertEquals(2, restarts)
    }

    @Test
    fun `version is Unknown when adb fails`() {
        val vm = vm { null }
        assertEquals(UNKNOWN_VERSION, vm.settled { it.bundledAdbVersion == UNKNOWN_VERSION }.bundledAdbVersion)
        val broken = SettingsViewModel(settings, presets, adb(bundledAdb = { error("missing") }, version = { null }), installer(), scope)
        assertEquals(UNKNOWN_VERSION, broken.settled { it.bundledAdbVersion == UNKNOWN_VERSION }.bundledAdbVersion)
    }

    @Test
    fun `default preset can be set and cleared with None`() {
        val vm = vm()
        presets.upsert(Preset("qa", "QA default", options = OptionValues(emptyMap())))
        vm.setDefaultPreset("qa")
        assertEquals("qa", vm.settled { it.defaultPresetId == "qa" }.defaultPresetId)
        vm.setDefaultPreset(null)
        assertNull(vm.settled { it.defaultPresetId == null }.defaultPresetId)
        assertNull(presets.defaultPresetId.value)
    }

    @Test
    fun `capture dir is saved`() {
        val vm = vm()
        vm.setCaptureDir("/tmp/caps")
        assertEquals("/tmp/caps", settings.settings.value.captureDir)
    }

    @Test
    fun `mirror window animation is on by default and saved`() {
        val vm = vm()
        assertEquals(true, settings.settings.value.animateMirrorWindows)
        vm.setAnimateMirrorWindows(false)
        assertEquals(false, settings.settings.value.animateMirrorWindows)
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
