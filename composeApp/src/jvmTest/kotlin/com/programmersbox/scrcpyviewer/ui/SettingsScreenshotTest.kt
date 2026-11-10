package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.settings.SettingsActions
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId
import com.programmersbox.scrcpyviewer.ui.settings.SettingsContent
import com.programmersbox.scrcpyviewer.ui.settings.SettingsNav
import com.programmersbox.scrcpyviewer.ui.settings.SettingsUiState
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.theme.CustomPalette
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class SettingsScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val state = SettingsUiState(
        settings = Settings(ThemeMode.System, "/opt/android/platform-tools/adb", "/Users/me/Movies/ScrcpyViewer"),
        presets = listOf(Preset("qa", "QA default", options = OptionValues(emptyMap())), Preset("low", "Low bandwidth", options = OptionValues(emptyMap()))),
        defaultPresetId = "qa",
        bundledAdbVersion = "1.0.41 (35.0.2)",
        customAdbVersion = "1.0.41 (34.0.4)",
    )

    private val release = Release(
        version = "1.5.0",
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.5.0",
        publishedAt = "2026-10-27T09:00:00Z",
    )
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun updatesUi(state: UpdateState = UpdateState.Idle, available: Release? = null, canSelfUpdate: Boolean = true) = UpdateUiState(
        state = state, available = available, currentVersion = "1.4.0",
        lastChecked = now.minusSeconds(120), canSelfUpdate = canSelfUpdate,
    )

    private fun render(
        name: String,
        nav: SettingsNav,
        mode: ThemeMode = ThemeMode.Light,
        state: SettingsUiState = this.state,
        updates: UpdateUiState = updatesUi(),
        customTitleBarSupported: Boolean = true,
    ) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(state.settings.copy(themeMode = mode).appTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    SettingsContent(
                        state.copy(settings = state.settings.copy(themeMode = mode)), updates, nav, SettingsActions(),
                        customTitleBarSupported = customTitleBarSupported,
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun category(id: SettingsCategoryId, mode: ThemeMode) =
        render("settings-${id.name.lowercase()}-${mode.name.lowercase()}.png", SettingsNav(selected = id), mode)

    @Test fun `settings appearance light`() = category(SettingsCategoryId.Appearance, ThemeMode.Light)
    @Test fun `settings appearance dark`() = category(SettingsCategoryId.Appearance, ThemeMode.Dark)
    @Test fun `settings mirroring light`() = category(SettingsCategoryId.Mirroring, ThemeMode.Light)
    @Test fun `settings mirroring dark`() = category(SettingsCategoryId.Mirroring, ThemeMode.Dark)
    @Test fun `settings adb light`() = category(SettingsCategoryId.Adb, ThemeMode.Light)
    @Test fun `settings adb dark`() = category(SettingsCategoryId.Adb, ThemeMode.Dark)
    @Test fun `settings updates light`() = category(SettingsCategoryId.Updates, ThemeMode.Light)
    @Test fun `settings updates dark`() = category(SettingsCategoryId.Updates, ThemeMode.Dark)
    @Test fun `settings help light`() = category(SettingsCategoryId.Help, ThemeMode.Light)
    @Test fun `settings help dark`() = category(SettingsCategoryId.Help, ThemeMode.Dark)

    private fun palette(name: String, mode: ThemeMode, settings: Settings) =
        render(name, SettingsNav(selected = SettingsCategoryId.Appearance), mode, state.copy(settings = settings))

    @Test fun `settings palette ocean dark pure black`() =
        palette("settings-palette-ocean-dark-black.png", ThemeMode.Dark, state.settings.copy(themePalette = "Ocean", pureBlack = true))

    @Test fun `settings palette rose light`() =
        palette("settings-palette-rose-light.png", ThemeMode.Light, state.settings.copy(themePalette = "Rose"))

    @Test fun `settings palette custom light`() = palette(
        "settings-palette-custom-light.png", ThemeMode.Light,
        state.settings.copy(themePalette = "Custom", customPalette = CustomPalette(25f, 0.14f, 60f)),
    )

    @Test fun `settings palette custom dark`() = palette(
        "settings-palette-custom-dark.png", ThemeMode.Dark,
        state.settings.copy(themePalette = "Custom", customPalette = CustomPalette(280f, 0.16f, 120f)),
    )

    @Test
    fun `settings search folder`() = render("settings-search-folder.png", SettingsNav(query = "folder"))

    @Test
    fun `settings search no results`() =
        render("settings-search-none.png", SettingsNav(query = "zzz"), state = state.copy(persistenceError = "disk full"))

    @Test
    fun `settings update badge`() =
        render("settings-update-badge.png", SettingsNav(), updates = updatesUi(UpdateState.Available(release), release))

    private fun renderUpdates(name: String, ui: UpdateUiState) = render(name, SettingsNav(selected = SettingsCategoryId.Updates), updates = ui)

    @Test
    fun `updates available`() =
        renderUpdates("settings-updates-available.png", updatesUi(UpdateState.Available(release), release))

    @Test
    fun `updates downloading`() =
        renderUpdates("settings-updates-downloading.png", updatesUi(UpdateState.Downloading(release, 20_000_000, 50_000_000), release))

    @Test
    fun `updates up to date`() = renderUpdates("settings-updates-uptodate.png", updatesUi(UpdateState.UpToDate))

    @Test
    fun `updates idle`() = renderUpdates("settings-updates-idle.png", updatesUi(UpdateState.Idle, canSelfUpdate = false))

    @Test
    fun `updates error`() = renderUpdates(
        "settings-updates-error.png",
        updatesUi(UpdateState.Failed("Couldn't check for updates: GitHub returned HTTP 403", null)),
    )

    @Test
    fun `updates just updated`() = renderUpdates(
        "settings-updates-just-updated.png",
        updatesUi(UpdateState.UpToDate).copy(currentVersion = "1.6.0", updatedFrom = "1.5.0"),
    )

    @Test
    fun `updates mac hint`() = renderUpdates(
        "settings-updates-mac-hint.png",
        updatesUi(UpdateState.Idle).copy(macNotInApplications = true),
    )

    @Test
    fun `settings bundled with path error`() = render(
        "settings-adb-error.png", SettingsNav(selected = SettingsCategoryId.Adb),
        state = state.copy(settings = state.settings.copy(adbPathOverride = null), adbPathError = "File not found: /nope/adb", defaultPresetId = null),
    )

    @Test
    fun `settings without custom title bar support`() =
        render("settings-no-custom-bar.png", SettingsNav(), customTitleBarSupported = false)

    @Test
    fun `app light with rail`() = withFakeAppKoin { koin ->
        // Fake adb: fixed devices, nothing on this machine is touched.
        val out = outDir.resolve("app-light.png")
        koin.get<AppSettings>().update { it.copy(themeMode = ThemeMode.Light) }
        renderToPng(1280, 800, out, settleMillis = 1_000) { App() }
        assertTrue(Files.size(out) > 10_000, "app-light should not be blank")
    }
}
