package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.ui.settings.SettingsActions
import com.programmersbox.scrcpyviewer.ui.settings.SettingsContent
import com.programmersbox.scrcpyviewer.ui.settings.SettingsNav
import com.programmersbox.scrcpyviewer.ui.settings.SettingsUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Mid-animation frames, to eyeball the motion: each test settles, makes one change, then saves frames at set times
 * after it. Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AnimationScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots/motion")

    private fun frames(
        name: String,
        width: Int,
        height: Int,
        atMillis: List<Long>,
        change: (ImageComposeScene) -> Unit,
        content: @Composable () -> Unit,
    ) = onEdt {
        val scene = ImageComposeScene(width, height, Density(1f), content = content)
        try {
            var t = 0L
            fun step(toMillis: Long) {
                while (t < toMillis * 1_000_000) {
                    t = minOf(t + 16_000_000, toMillis * 1_000_000)
                    scene.render(t)
                }
            }
            scene.render(0)
            step(1_500)
            change(scene)
            val start = t / 1_000_000
            for (ms in atMillis) {
                step(start + ms)
                val image = scene.render(t)
                Files.createDirectories(outDir)
                Files.write(outDir.resolve("$name-${ms}ms.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        } finally {
            scene.close()
        }
    }

    private fun click(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    @Test
    fun `settings search stagger`() {
        val nav: MutableState<SettingsNav> = mutableStateOf(SettingsNav())
        frames("settings-search", 1280, 800, listOf(0, 60, 150, 300, 500, 900), change = { nav.value = SettingsNav().withQuery("re") }) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val n by nav
                    SettingsContent(SettingsUiState(), UpdateUiState(), n, SettingsActions())
                }
            }
        }
    }

    @Test
    fun `update ready check`() {
        val release = Release(version = "1.6.0", htmlUrl = "https://example.invalid/r")
        val state: MutableState<UpdateState> = mutableStateOf(UpdateState.Downloading(release, 50_000_000, 50_000_000))
        frames("update-ready", 800, 500, listOf(0, 120, 260, 400, 560, 800, 1_100), change = { state.value = UpdateState.Installing(release) }) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val s by state
                    UpdateDialog(UpdateUiState(state = s, available = release, currentVersion = "1.5.0", canSelfUpdate = true), {}, {}, {})
                }
            }
        }
    }

    @Test
    fun `update dialog entrance`() {
        val release = Release(version = "1.6.0", htmlUrl = "https://example.invalid/r")
        val show = mutableStateOf(false)
        frames("update-entrance", 800, 500, listOf(0, 50, 120, 300), change = { show.value = true }) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (show.value) {
                        UpdateDialog(UpdateUiState(state = UpdateState.Available(release), available = release, currentVersion = "1.5.0", showPopup = true), {}, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun `rail indicator stretch`() = withFakeAppKoin { koin ->
        koin.get<AppSettings>().update { it.copy(themeMode = ThemeMode.Light) }
        val navigator = TopLevelNavigator()
        frames("rail-down", 400, 400, listOf(0, 48, 96, 160, 240, 450), change = { navigator.select(TopLevelRoute.Settings) }) {
            App(navigator = navigator)
        }
    }

    @Test
    fun `copy button morph`() {
        frames("copy", 300, 80, listOf(0, 60, 150, 300, 1_400, 1_600, 1_900), change = { click(it, Offset(60f, 40f)) }) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        CopyButton(text = { "scrcpy -s emulator-5554" }, label = "Copy command")
                    }
                }
            }
        }
    }
}
