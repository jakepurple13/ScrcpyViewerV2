package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.ImportPlan
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetCodec
import com.programmersbox.scrcpyviewer.ui.presets.ImportDialogContent
import com.programmersbox.scrcpyviewer.ui.presets.ImportUiState
import com.programmersbox.scrcpyviewer.ui.presets.PresetsContent
import com.programmersbox.scrcpyviewer.ui.presets.PresetsUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class PresetsScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val qa = Preset(
        "qa", "QA default", "Sharp video, no audio, touches visible, screen off on the device.",
        OptionValues(
            mapOf("max-size" to "1920", "max-fps" to "60", "no-audio" to null, "show-touches" to null, "stay-awake" to null, "turn-screen-off" to null),
        ),
    )
    private val low = Preset("low", "Low bandwidth", options = OptionValues(mapOf("max-size" to "800", "video-bit-rate" to "2M", "max-fps" to "24", "no-audio" to null)))
    private val demo = Preset("demo", "Record demo", options = OptionValues(mapOf("record" to "demo.mp4", "show-touches" to null, "no-audio" to null, "stay-awake" to null, "video-codec" to "h265")))

    private val state = PresetsUiState(
        presets = listOf(qa, low, demo),
        defaultId = "qa",
        selected = qa,
        selectedJson = PresetCodec.encode(listOf(qa)),
        canApply = true,
    )

    private fun shot(name: String, mode: ThemeMode, content: @androidx.compose.runtime.Composable () -> Unit) = shot(name, mode, 1280, content)

    private fun shot(name: String, mode: ThemeMode, width: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val out = outDir.resolve(name)
        renderToPng(width, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(mode) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() } }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun screen(state: PresetsUiState, listWidthPx: Int? = null, detailOpen: Boolean = false) = @androidx.compose.runtime.Composable {
        PresetsContent(
            state, {}, { qa }, { _, _, _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
            panes = screenshotPanes(listWidthPx, detailOpen),
        )
    }

    @Test
    fun `presets light wide divider`() = shot("presets-light-divider.png", ThemeMode.Light, content = screen(state, listWidthPx = 300))

    @Test
    fun `presets dark wide divider`() = shot("presets-dark-divider.png", ThemeMode.Dark, content = screen(state, listWidthPx = 300))

    @Test
    fun `presets narrow list`() = shot("presets-light-narrow-list.png", ThemeMode.Light, width = 640, content = screen(state))

    @Test
    fun `presets narrow detail`() = shot("presets-light-narrow-detail.png", ThemeMode.Light, width = 640, content = screen(state, detailOpen = true))

    @Test
    fun `presets dark narrow detail`() = shot("presets-dark-narrow-detail.png", ThemeMode.Dark, width = 640, content = screen(state, detailOpen = true))

    @Test
    fun `presets phone width detail`() = shot("presets-light-phone-detail.png", ThemeMode.Light, width = 360, content = screen(state, detailOpen = true))

    @Test
    fun `presets light`() = shot("presets-light.png", ThemeMode.Light, screen(state))

    @Test
    fun `presets dark`() = shot("presets-dark.png", ThemeMode.Dark, screen(state))

    @Test
    fun `presets light no device and save error`() = shot(
        "presets-light-banner.png", ThemeMode.Light,
        screen(state.copy(canApply = false, persistenceError = "presets.json is corrupt and was backed up")),
    )

    private val importState: ImportUiState = run {
        val incoming = listOf(
            Preset("a", "Wi-Fi lab", options = OptionValues(mapOf("max-size" to "1280", "no-audio" to null))),
            Preset("b", "QA default", options = OptionValues(mapOf("max-fps" to "30"))),
            Preset("c", "Old capture", options = OptionValues(mapOf("no-audio" to null))),
        )
        ImportUiState(ImportPlan.plan(listOf(qa), incoming), mapOf("Old capture" to listOf("legacy-flag")))
    }

    private fun dialog(mode: ThemeMode, name: String) = shot(name, mode) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.width(560.dp)) {
                androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
                    androidx.compose.material3.Text("Import 3 presets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                    ImportDialogContent(importState, { _, _ -> }, { _, _ -> })
                }
            }
        }
    }

    @Test
    fun `import dialog light`() = dialog(ThemeMode.Light, "presets-import-light.png")

    @Test
    fun `import dialog dark`() = dialog(ThemeMode.Dark, "presets-import-dark.png")
}
