package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mid-animation frames of the stage's motion (first-frame reveal, rotation turn, disconnect grey, click ripple,
 * zoom roll). Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`.
 */
class StageScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots/stage")

    private fun frame(w: Int, h: Int): VideoFrame {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                pixels[i] = (0x3F - 0x2B * t).toInt().toByte()
                pixels[i + 1] = (0x40 - 0x33 * t + 0x60 * (x.toFloat() / w)).toInt().toByte()
                pixels[i + 2] = (0x12 + 0x70 * (x.toFloat() / w)).toInt().toByte()
                pixels[i + 3] = -1
            }
        }
        bitmap.installPixels(pixels)
        val canvas = Canvas(bitmap)
        val white = Paint().apply { color = 0xFFE6F2F1.toInt() }
        canvas.drawTextLine(TextLine.make("9:41", Font(null, h / 12f)), w * 0.08f, h * 0.18f, white)
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(w * 0.1f, h * 0.8f, w * 0.8f, h * 0.06f), white)
        return VideoFrame.of(bitmap)
    }

    private val base = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(540, 1200),
        fps = 59.8,
        fittedScale = 0.37f,
    )

    /**
     * Composes the mirror with [state] and [frame], runs [steps] (each changes the state, then renders at its time in
     * ms and writes `<name>-<ms>.png`).
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun sequence(
        name: String,
        initial: MirrorUiState,
        initialFrame: VideoFrame?,
        mode: ThemeMode = ThemeMode.Dark,
        clickIndicator: ClickIndicator? = null,
        steps: (state: MutableState<MirrorUiState>, frame: MutableState<VideoFrame?>, shot: (Long) -> Unit) -> Unit,
    ) = SwingUtilities.invokeAndWait {
        val state = mutableStateOf(initial)
        val frame = mutableStateOf(initialFrame)
        val scene = ImageComposeScene(900, 700, Density(1f)) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MirrorContent(state.value, frame, {}, {}, {}, {}, {}, {}, clickIndicator = clickIndicator)
                }
            }
        }
        try {
            scene.render(0)
            steps(state, frame) { ms ->
                val image = scene.render(ms * 1_000_000)
                val out = outDir.resolve("$name-${"%04d".format(ms)}.png")
                Files.createDirectories(out.parent)
                Files.write(out, image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                assertTrue(Files.size(out) > 5_000)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `first frame reveal`() = sequence("reveal", base.copy(status = SessionStatus.Connecting, frameSize = null), null) { state, frame, shot ->
        shot(16)
        shot(400) // shimmer mid-sweep
        frame.value = frame(540, 1200)
        state.value = base
        shot(1000) // the reveal starts on the next frame
        shot(1016)
        shot(1100)
        shot(1200)
        shot(1500)
    }

    @Test
    fun `rotation turn`() = sequence("turn", base, frame(540, 1200)) { state, frame, shot ->
        shot(16)
        frame.value = frame(1200, 540)
        state.value = base.copy(frameSize = IntSize(1200, 540))
        shot(100)
        shot(116)
        shot(150)
        shot(250)
        shot(400)
        shot(700)
    }

    @Test
    fun `disconnect grey and reconnect`() = sequence("grey", base, frame(540, 1200), mode = ThemeMode.Light) { state, _, shot ->
        shot(16)
        state.value = base.copy(status = SessionStatus.Disconnected, error = "video: Connection reset", canReconnect = true)
        shot(100)
        shot(116)
        shot(250)
        shot(400)
        shot(800)
        state.value = base
        shot(900)
        shot(1000)
        shot(1500)
    }

    @Test
    fun `click ripple`() {
        val view = Rect(0f, 0f, 772f, 612f)
        val mapper = InputMapper(frameSize = { IntSize(540, 1200) }, viewRect = { view })
        val dots = ClickIndicator(mapper::displayedRect).apply {
            press(Offset(386f, 300f))
            release(monotonicMillis() - 150)
        }
        sequence("ripple", base, frame(540, 1200), clickIndicator = dots) { _, _, shot -> shot(16) }
    }

    @Test
    fun `zoom roll`() = sequence("zoom", base, frame(540, 1200)) { state, _, shot ->
        shot(16)
        state.value = base.copy(zoom = 1f)
        shot(100)
        shot(116)
        shot(124)
        shot(132)
        shot(500)
    }
}
