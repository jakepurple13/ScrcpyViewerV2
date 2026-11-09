package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.skia.EncodedImageFormat

/**
 * Mid-animation frames of the drop zone, the toggle tiles' radial fill and the shell's arriving lines, for eyeballing
 * the motion. Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SheetMotionScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots/motion")

    /** Renders [content], then runs [script] with a frame grabber whose times are in ms since the first frame. */
    private fun frames(width: Int, height: Int, content: @Composable () -> Unit, script: Frames.() -> Unit) {
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width, height, Density(1f), content = content)
            try {
                scene.render(0)
                Frames(scene, outDir).script()
            } finally {
                scene.close()
            }
        }
    }

    private class Frames(val scene: ImageComposeScene, val dir: Path) {
        /** Renders a frame without saving it, so effects launched by a state change start at [atMillis]. */
        fun tick(atMillis: Long) {
            scene.render(atMillis * 1_000_000)
        }

        fun shot(name: String, atMillis: Long) {
            val image = scene.render(atMillis * 1_000_000)
            Files.createDirectories(dir)
            Files.write(dir.resolve(name), checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        }
    }

    @Test
    fun `drop zone marches, then refuses`() {
        var hint by mutableStateOf(DropHint("Drop to install APK", com.programmersbox.scrcpyviewer.files.TransferKind.Install, true))
        frames(420, 420, {
            ScrcpyViewerTheme(ThemeMode.Light) { DropOverlay(hint) }
        }) {
            shot("drop-0060.png", 60)
            shot("drop-0400.png", 400)
            shot("drop-0800.png", 800)
            hint = DropHint("Device not connected", null, false)
            tick(800)
            shot("drop-refuse-0850.png", 850)
            shot("drop-refuse-0900.png", 900)
            shot("drop-refuse-1000.png", 1000)
            shot("drop-refuse-1600.png", 1600)
        }
    }

    @Test
    fun `tile fill grows from the press`() {
        var state by mutableStateOf(
            QuickActionsUiState(enabled = true, toggles = QuickToggle.entries.associateWith { false }),
        )
        frames(400, 420, {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    ActionsTabContent(state, {})
                }
            }
        }) {
            shot("tile-0000.png", 100)
            // Press near the first tile's top-left, then the device reports it on.
            scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 60f))
            tick(100)
            scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 60f))
            tick(100)
            val first = QuickToggle.entries.first()
            state = state.copy(toggles = state.toggles + (first to true))
            tick(100)
            shot("tile-on-0080.png", 180)
            shot("tile-on-0160.png", 260)
            shot("tile-on-0300.png", 400)
            shot("tile-on-0600.png", 700)
            // A change read back from the device: grows from the centre.
            val second = QuickToggle.entries[1]
            state = state.copy(toggles = state.toggles + (second to true))
            tick(700)
            shot("tile-device-0120.png", 820)
            state = state.copy(toggles = state.toggles + (first to false))
            tick(820)
            shot("tile-off-0100.png", 920)
            shot("tile-off-0400.png", 1300)
        }
    }

    private class ChannelRunner(val events: Channel<ShellEvent>) :
        ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
        override fun stream(cmd: String): Flow<ShellEvent> = events.receiveAsFlow()
    }

    @Test
    fun `shell lines rise in, then the exit pill pops`() {
        val events = Channel<ShellEvent>(Channel.UNLIMITED)
        val console = ShellConsole(ChannelRunner(events), ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
        frames(400, 520, {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    ShellTabContent(console, emptyList()) {}
                }
            }
        }) {
            console.input = "getprop ro.product.model"
            console.submit()
            shot("shell-caret-on-0100.png", 100)
            shot("shell-caret-off-0600.png", 600)
            events.trySend(ShellEvent.Output("Pixel 8 Pro\nsecond line\nthird line\n"))
            tick(1000)
            shot("shell-rise-0060.png", 1060)
            shot("shell-rise-0140.png", 1140)
            shot("shell-rise-0400.png", 1400)
            events.trySend(ShellEvent.Exit(0, 48))
            tick(1400)
            shot("shell-exit-0080.png", 1480)
            shot("shell-exit-0200.png", 1600)
            shot("shell-exit-0500.png", 1900)
        }
    }
}
