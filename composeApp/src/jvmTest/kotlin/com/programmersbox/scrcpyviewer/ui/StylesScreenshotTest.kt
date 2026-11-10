package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.devices.DevicesContent
import com.programmersbox.scrcpyviewer.ui.devices.DevicesUiState
import com.programmersbox.scrcpyviewer.ui.settings.SettingsActions
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId
import com.programmersbox.scrcpyviewer.ui.settings.SettingsContent
import com.programmersbox.scrcpyviewer.ui.settings.SettingsNav
import com.programmersbox.scrcpyviewer.ui.settings.SettingsUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.update.UpdateState
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/** Each style on the Devices and Appearance screens. Excluded from the default `jvmTest` run; run with `-Pscreenshots`. */
class StylesScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val values = OptionValues(mapOf("max-size" to "1920", "show-touches" to null, "stay-awake" to null))

    private val devices = DevicesUiState(
        devices = listOf(
            Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35)),
            Device("adb-R5CT90ABCDE-xK2fPq._adb-tls-connect._tcp", DeviceState.Device, "device", "SM_X710", null, 2, true),
            Device("ZY22H8KLMN", DeviceState.Unauthorized, "unauthorized", null, null, null, false),
        ),
        selectedSerial = "39121FDJH004AB",
        visibleOptions = OptionCatalog.all,
        values = values,
        presetLabel = "QA default",
        command = "scrcpy -s 39121FDJH004AB --max-size=1920 --show-touches --stay-awake",
        canMirror = true,
        adbConnected = true,
        hasBasePreset = true,
        presets = listOf(Preset("qa", "QA default", "Stay awake, show touches", OptionValues(emptyMap()))),
        basePresetId = "qa",
        advanced = false,
    )

    private fun settings(style: ThemeStyle, mode: ThemeMode, retroPalette: Boolean) = Settings(
        themeMode = mode, themePalette = ThemePalette.Indigo.name, themeStyle = style.name, retroPalette = retroPalette,
    )

    @androidx.compose.runtime.Composable
    private fun shell(s: Settings, content: @androidx.compose.runtime.Composable () -> Unit) {
        ScrcpyViewerTheme(s.appTheme) {
            Box(Modifier.fillMaxSize().styleBackdrop()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
    }

    private fun devices(style: ThemeStyle, mode: ThemeMode, retroPalette: Boolean = false, advanced: Boolean = false) {
        val name = "styles-devices-${style.name.lowercase()}${if (retroPalette) "-palette" else ""}${if (advanced) "-advanced" else ""}-${mode.name.lowercase()}.png"
        val out = outDir.resolve(name)
        renderToPng(1280, 760, out, settleMillis = 1_000) {
            shell(settings(style, mode, retroPalette)) {
                DevicesContent(
                    devices.copy(advanced = advanced), {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                    panes = screenshotPanes(),
                )
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun appearance(style: ThemeStyle, mode: ThemeMode, retroPalette: Boolean = false) {
        val name = "styles-settings-${style.name.lowercase()}${if (retroPalette) "-palette" else ""}-${mode.name.lowercase()}.png"
        val out = outDir.resolve(name)
        val s = settings(style, mode, retroPalette)
        renderToPng(1280, 900, out, settleMillis = 1_000) {
            shell(s) {
                SettingsContent(
                    SettingsUiState(settings = s), UpdateUiState(state = UpdateState.Idle, currentVersion = "1.12.0", lastChecked = Instant.EPOCH),
                    SettingsNav(selected = SettingsCategoryId.Appearance), SettingsActions(), customTitleBarSupported = true,
                )
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test fun `glass devices light`() = devices(ThemeStyle.Glass, ThemeMode.Light)
    @Test fun `glass devices dark`() = devices(ThemeStyle.Glass, ThemeMode.Dark)
    @Test fun `glass devices advanced dark`() = devices(ThemeStyle.Glass, ThemeMode.Dark, advanced = true)
    @Test fun `soft devices light`() = devices(ThemeStyle.Soft, ThemeMode.Light)
    @Test fun `soft devices dark`() = devices(ThemeStyle.Soft, ThemeMode.Dark)
    @Test fun `soft devices advanced light`() = devices(ThemeStyle.Soft, ThemeMode.Light, advanced = true)
    @Test fun `retro devices light`() = devices(ThemeStyle.Retro, ThemeMode.Light)
    @Test fun `retro devices dark`() = devices(ThemeStyle.Retro, ThemeMode.Dark)
    @Test fun `retro devices advanced light`() = devices(ThemeStyle.Retro, ThemeMode.Light, advanced = true)
    @Test fun `retro palette devices light`() = devices(ThemeStyle.Retro, ThemeMode.Light, retroPalette = true)
    @Test fun `retro palette devices dark`() = devices(ThemeStyle.Retro, ThemeMode.Dark, retroPalette = true)
    @Test fun `glass settings light`() = appearance(ThemeStyle.Glass, ThemeMode.Light)
    @Test fun `soft settings light`() = appearance(ThemeStyle.Soft, ThemeMode.Light)
    @Test fun `retro settings light`() = appearance(ThemeStyle.Retro, ThemeMode.Light)
    @Test fun `material settings dark`() = appearance(ThemeStyle.Material, ThemeMode.Dark)
}
