package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.Libs
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.settings.AboutDialog
import com.programmersbox.scrcpyviewer.ui.settings.AboutInfo
import com.programmersbox.scrcpyviewer.ui.settings.LicensesDialog
import com.programmersbox.scrcpyviewer.ui.settings.SettingsActions
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId
import com.programmersbox.scrcpyviewer.ui.settings.SettingsContent
import com.programmersbox.scrcpyviewer.ui.settings.SettingsNav
import com.programmersbox.scrcpyviewer.ui.settings.SettingsUiState
import com.programmersbox.scrcpyviewer.ui.settings.licenseEntries
import com.programmersbox.scrcpyviewer.ui.settings.readLicensesJson
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Settings › Help › About and Open-source licenses. Excluded by default; run with `-Pscreenshots`. */
class AboutLicensesScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")
    private val entries = licenseEntries(Libs.Builder().withJson(readLicensesJson()!!).build(), "36.0.0")
    private val info = AboutInfo.current("1.0.41 (36.0.0)")

    /** [dialog] over Settings › Help. */
    private fun render(name: String, mode: ThemeMode, dialog: @Composable () -> Unit) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(Settings(themeMode = mode).appTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    SettingsContent(
                        SettingsUiState(settings = Settings(themeMode = mode)), UpdateUiState(),
                        SettingsNav(selected = SettingsCategoryId.Help), SettingsActions(),
                    )
                    dialog()
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun about(mode: ThemeMode) = render("about-${mode.name.lowercase()}.png", mode) {
        AboutDialog(true, info, {}, {}, motion = true, onCopy = {}, onOpenUrl = {})
    }

    private fun licenses(name: String, mode: ThemeMode, query: String = "", open: String? = null) =
        render("$name-${mode.name.lowercase()}.png", mode) {
            LicensesDialog(true, entries, {}, motion = true, onOpenUrl = {}, initialQuery = query, initialOpen = open)
        }

    @Test fun `about light`() = about(ThemeMode.Light)
    @Test fun `about dark`() = about(ThemeMode.Dark)
    @Test fun `licenses light`() = licenses("licenses", ThemeMode.Light)
    @Test fun `licenses dark`() = licenses("licenses", ThemeMode.Dark)
    @Test fun `licenses ffmpeg open light`() = licenses("licenses-ffmpeg", ThemeMode.Light, "ffmpeg", "bundled:ffmpeg")
    @Test fun `licenses ffmpeg open dark`() = licenses("licenses-ffmpeg", ThemeMode.Dark, "ffmpeg", "bundled:ffmpeg")
    @Test fun `licenses koin open light`() = licenses("licenses-koin", ThemeMode.Light, "koin", entries.first { it.name == "Koin" }.id)
    @Test fun `licenses empty search light`() = licenses("licenses-empty", ThemeMode.Light, "zzzz")
    @Test fun `licenses empty search dark`() = licenses("licenses-empty", ThemeMode.Dark, "zzzz")
}
