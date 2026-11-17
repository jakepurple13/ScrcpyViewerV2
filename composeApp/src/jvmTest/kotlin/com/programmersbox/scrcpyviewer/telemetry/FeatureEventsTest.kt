package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetCodec
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.scrcpy.MirrorStage
import com.programmersbox.scrcpyviewer.scrcpy.MirrorStageException
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.PresetSaved
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.PresetsImported
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.SettingsOpened
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.ThemeChanged
import com.programmersbox.scrcpyviewer.ui.devices.SelectedDevice
import com.programmersbox.scrcpyviewer.ui.presets.PresetsViewModel
import com.programmersbox.scrcpyviewer.ui.settings.SettingsAdb
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId
import com.programmersbox.scrcpyviewer.ui.settings.SettingsViewModel
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Preset and settings events, and the mirror start failure type; the other feature events sit in their classes' tests. */
class FeatureEventsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("feature-events")
    private val telemetry = FakeTelemetry()
    private val store = PresetStore(dir.resolve("presets.json"), scope, telemetry)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private val device = object : SelectedDevice {
        override val optionsKey = MutableStateFlow<String?>("R1")
        override fun label(key: String) = "Pixel 8"
    }

    private fun presetsVm() =
        PresetsViewModel(store, DeviceOptionsStore(dir.resolve("devices.json"), store, scope), device, telemetry, scope)

    private val settings = AppSettings(dir.resolve("settings.json"))

    private fun settingsVm() = SettingsViewModel(
        settings, store,
        object : SettingsAdb {
            override fun bundledAdb(): Path = dir.resolve("adb")
            override suspend fun version(path: Path): String? = null
            override fun restartTracker() = Unit
        },
        AiSkillInstaller(dir.resolve("home"), dir.resolve("inbox"), dir.resolve("actions.json")), telemetry, scope,
    )

    @Test
    fun mirrorStageExceptionKeepsMessage() {
        val e = MirrorStageException(MirrorStage.Push, "adb push failed", null)
        assertEquals("adb push failed", e.message)
        assertTrue(e is IOException)
        // The detail (adb's stderr) is shown, never part of the message that crash reports send.
        val detailed = MirrorStageException(MirrorStage.Push, "adb push failed", null, detail = ": device 'R5CX12' not found")
        assertEquals("adb push failed", detailed.message)
        assertEquals("adb push failed: device 'R5CX12' not found", detailed.displayMessage)
    }

    @Test
    fun savingAPresetSendsTheCount() {
        store.upsert(Preset("a", "Office", options = OptionValues(emptyMap())))
        store.upsert(Preset("b", "Home", options = OptionValues(emptyMap())))
        store.upsert(Preset("a", "Office 2", options = OptionValues(emptyMap()))) // an edit
        assertEquals(listOf(PresetSaved(1), PresetSaved(2), PresetSaved(2)), telemetry.events.toList())
    }

    @Test
    fun importSendsTheCountAndTheMostCommonConflictChoice() {
        store.upsert(Preset("a", "One", options = OptionValues(emptyMap())))
        store.upsert(Preset("b", "Two", options = OptionValues(emptyMap())))
        store.upsert(Preset("c", "Three", options = OptionValues(emptyMap())))
        val vm = presetsVm()
        telemetry.events.clear()
        vm.beginImport(
            PresetCodec.encode(
                listOf("One", "Two", "Three", "Four", "Five").map { Preset(it.lowercase(), it, options = OptionValues(emptyMap())) },
            ),
        )
        vm.setChoice(0, ConflictChoice.KeepBoth)
        vm.setChoice(1, ConflictChoice.KeepBoth)
        vm.setChoice(2, ConflictChoice.Skip)
        vm.confirmImport()
        // One, Two (kept both), Four and Five are added; the import is one event, not a preset_saved per preset.
        assertEquals(listOf<TelemetryEvent>(PresetsImported(4, "keepboth")), telemetry.events.toList())
    }

    @Test
    fun importWithoutConflictsSaysNone() {
        val vm = presetsVm()
        vm.beginImport(PresetCodec.encode(listOf(Preset("n", "New", options = OptionValues(emptyMap())))))
        vm.confirmImport()
        assertEquals(listOf<TelemetryEvent>(PresetsImported(1, "none")), telemetry.events.toList())
    }

    @Test
    fun presetNamesNeverSent() {
        val name = "Jacob's secret lab preset"
        val vm = presetsVm()
        vm.new()
        vm.edit(store.presets.value.single().id, name, "", OptionValues(mapOf("max-size" to "1024")))
        vm.beginImport(PresetCodec.encode(listOf(Preset("x", name, options = OptionValues(emptyMap())))))
        vm.setChoice(0, ConflictChoice.KeepBoth)
        vm.confirmImport()
        assertTrue(telemetry.events.isNotEmpty())
        val values = telemetry.events.flatMap { it.props().values }.map { it.toString() }
        assertTrue(values.none { name in it }, "sent: $values")
    }

    @Test
    fun themeSettersSendTheResultingTheme() {
        val vm = settingsVm()
        vm.setTheme(ThemeMode.Dark)
        vm.setPalette(ThemePalette.Synthwave) // a neon palette turns Neon on
        vm.setNeon(false)
        vm.setStyle(ThemeStyle.Glass)
        assertEquals(
            listOf<TelemetryEvent>(
                ThemeChanged("Material", "Teal", "Dark", false),
                ThemeChanged("Material", "Synthwave", "Dark", true),
                ThemeChanged("Material", "Synthwave", "Dark", false),
                ThemeChanged("Glass", "Synthwave", "Dark", false),
            ),
            telemetry.events.filterIsInstance<ThemeChanged>(),
        )
    }

    @Test
    fun settingsOpenedOnEachCategoryChange() {
        val vm = settingsVm()
        vm.selectCategory(SettingsCategoryId.Appearance) // already shown
        vm.selectCategory(SettingsCategoryId.Privacy)
        vm.selectCategory(SettingsCategoryId.Privacy)
        vm.selectCategory(SettingsCategoryId.Adb)
        assertEquals(
            listOf(SettingsOpened("Privacy"), SettingsOpened("Adb")),
            telemetry.events.filterIsInstance<SettingsOpened>(),
        )
    }
}
