package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.mirror.ClickIndicator
import com.programmersbox.scrcpyviewer.ui.mirror.DisplayOrientation
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorContent
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorUiState
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.QuickToggle
import com.programmersbox.scrcpyviewer.ui.mirror.TopStrip
import com.programmersbox.scrcpyviewer.ui.mirror.dropHint
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** The custom title bar's scope, without a window to mark regions in. */
private object CustomBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.Custom
    override fun Modifier.interactive(key: String): Modifier = this
}

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class MirrorScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    /** A lock-screen-like 1344x2992 frame. */
    private fun syntheticFrame(): VideoFrame {
        val w = 1344
        val h = 2992
        val bitmap = Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)) }
        // Vertical teal-to-navy gradient, written as BGRA pixels.
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val r = (0x12 + (0x05 - 0x12) * t).toInt()
            val g = (0x40 + (0x0D - 0x40) * t).toInt()
            val b = (0x3F + (0x14 - 0x3F) * t).toInt()
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                pixels[i] = b.toByte(); pixels[i + 1] = g.toByte(); pixels[i + 2] = r.toByte(); pixels[i + 3] = -1
            }
        }
        bitmap.installPixels(pixels)
        val canvas = Canvas(bitmap)
        val white = Paint().apply { color = 0xFFE6F2F1.toInt() }
        canvas.drawTextLine(TextLine.make("9:41", Font(null, 260f)), 100f, 520f, white)
        canvas.drawTextLine(TextLine.make("Friday, October 23", Font(null, 64f)), 110f, 640f, white)
        val icon = Paint().apply { color = 0x29FFFFFF }
        for (row in 0 until 2) for (col in 0 until 4) {
            canvas.drawCircle(220f + col * 300f, 2300f + row * 300f, 110f, icon)
        }
        return VideoFrame.of(bitmap)
    }

    private val state = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(1344, 2992),
        maxSize = 1920,
        codec = "H.264",
        bitRate = "8 Mbps",
        fps = 59.8,
        fittedScale = 0.37f,
        command = "scrcpy -s 39121FDJH004AB --max-size=1920",
    )

    private fun render(name: String, mode: ThemeMode, state: MirrorUiState = this.state, clickIndicator: ClickIndicator? = null, width: Int = 1280, height: Int = 800, showTopStrip: Boolean = true) {
        val out = outDir.resolve(name)
        val frame = mutableStateOf<VideoFrame?>(syntheticFrame())
        renderToPng(width, height, out) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MirrorContent(state, frame, {}, {}, {}, {}, {}, {}, clickIndicator = clickIndicator, showTopStrip = showTopStrip)
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `mirror light`() = render("mirror-light.png", ThemeMode.Light)

    @Test
    fun `mirror dark`() = render("mirror-dark.png", ThemeMode.Dark)

    /** The window's content when the strip is drawn by the window's title bar instead. */
    @Test
    fun `mirror without top strip`() {
        render("mirror-no-strip-light.png", ThemeMode.Light, showTopStrip = false)
        render("mirror-no-strip-dark.png", ThemeMode.Dark, showTopStrip = false)
    }

    private fun renderCustomStrip(name: String, mode: ThemeMode) {
        val out = outDir.resolve(name)
        renderToPng(640, 40, out) {
            ScrcpyViewerTheme(mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 80.dp)) {
                    CustomBarScope.TopStrip(state) {}
                }
            }
        }
        assertTrue(Files.size(out) > 1_000, "$name should not be blank")
    }

    /** [TopStrip] as the custom title bar paints it: on the bar's `surfaceContainer`, 40dp tall, after an 80dp control inset. */
    @Test
    fun `mirror top strip in custom title bar`() {
        renderCustomStrip("mirror-strip-custom-light.png", ThemeMode.Light)
        renderCustomStrip("mirror-strip-custom-dark.png", ThemeMode.Dark)
    }

    @Test
    fun `mirror disconnected`() = render(
        "mirror-disconnected.png", ThemeMode.Light,
        state.copy(status = SessionStatus.Disconnected, error = "video: Connection reset", fps = 0.0, canReconnect = true, actionsEnabled = false),
    )

    @Test
    fun `mirror disconnected waiting dark`() = render(
        "mirror-disconnected-dark.png", ThemeMode.Dark,
        state.copy(status = SessionStatus.Disconnected, fps = 0.0, canReconnect = false, actionsEnabled = false),
    )

    @Test
    fun `mirror display-orientation 90`() = render(
        "mirror-orientation-90.png", ThemeMode.Light, state.copy(orientation = DisplayOrientation.R90),
    )

    @Test
    fun `mirror display-orientation flip0`() = render(
        "mirror-orientation-flip0.png", ThemeMode.Light, state.copy(orientation = DisplayOrientation.Flip0),
    )

    @Test
    fun `mirror show-touches click dot`() {
        // A press on the clock of the 1344x2992 frame, letterboxed in the 1152x712 stage of a 1280x800 window.
        val view = Rect(0f, 0f, 1152f, 712f)
        val mapper = InputMapper(frameSize = { IntSize(1344, 2992) }, viewRect = { view })
        val dots = ClickIndicator(mapper::displayedRect).apply { press(Offset(560f, 120f)) }
        render("mirror-show-touches.png", ThemeMode.Light, clickIndicator = dots)
    }

    @Test
    fun `mirror drop overlay mixed`() = render(
        "mirror-drop-mixed.png", ThemeMode.Light,
        state.copy(transferEnabled = true, dropHint = dropHint(listOf(Paths.get("/x/app.apk"), Paths.get("/x/a.jpg"), Paths.get("/x/b.txt")), "/sdcard/Download/", true) { false }),
    )

    @Test
    fun `mirror drop overlay push dark`() = render(
        "mirror-drop-push-dark.png", ThemeMode.Dark,
        state.copy(transferEnabled = true, dropHint = dropHint(listOf(Paths.get("/x/photo.jpg")), "/sdcard/Download/", true) { false }),
    )

    @Test
    fun `mirror drop overlay apk narrow`() = render(
        "mirror-drop-apk-420x800.png", ThemeMode.Light,
        state.copy(transferEnabled = true, dropHint = dropHint(listOf(Paths.get("/x/app.apk")), "/sdcard/Download/", true) { false }),
        width = 420, height = 800,
    )

    @Test
    fun `mirror drop overlay disconnected`() = render(
        "mirror-drop-disconnected.png", ThemeMode.Light,
        state.copy(status = SessionStatus.Disconnected, fps = 0.0, actionsEnabled = false, dropHint = dropHint(listOf(Paths.get("/x/app.apk")), "/sdcard/Download/", false) { false }),
    )

    @Test
    fun `mirror transfer buttons and progress`() = render(
        "mirror-transfer-progress.png", ThemeMode.Light,
        state.copy(transferEnabled = true, transferLabel = "Pushing 2 of 3: photo.jpg…"),
    )

    @Test
    fun `mirror transfer buttons short window dark`() = render(
        "mirror-transfer-short-640x560-dark.png", ThemeMode.Dark,
        state.copy(transferEnabled = true, transferLabel = "Installing app.apk…"),
        width = 640, height = 560,
    )

    @Test
    fun `mirror narrow 320`() = render("mirror-narrow-320x700.png", ThemeMode.Light, width = 320, height = 700)

    @Test
    fun `mirror narrow 420`() = render("mirror-narrow-420x800.png", ThemeMode.Light, width = 420, height = 800)

    @Test
    fun `mirror narrow 560`() = render("mirror-narrow-560x900.png", ThemeMode.Light, width = 560, height = 900)

    @Test
    fun `mirror narrow 640`() = render("mirror-narrow-640x900.png", ThemeMode.Light, width = 640, height = 900)

    @Test
    fun `mirror narrow 700`() = render("mirror-narrow-700x900.png", ThemeMode.Light, width = 700, height = 900)

    @Test
    fun `mirror narrow 760`() = render("mirror-narrow-760x900.png", ThemeMode.Light, width = 760, height = 900)

    @Test
    fun `mirror narrow 420 long model dark`() = render(
        "mirror-narrow-420x800-long-dark.png", ThemeMode.Dark,
        state.copy(model = "Samsung Galaxy S24 Ultra (SM-S928U1)", recordingLabel = "● REC 01:23", audioNote = "No audio from device"),
        width = 420, height = 800,
    )

    @Test
    fun `mirror zoom 200`() = render("mirror-zoom-200.png", ThemeMode.Light, state.copy(zoom = 2f))

    @Test
    fun `mirror zoom 50`() = render("mirror-zoom-50.png", ThemeMode.Light, state.copy(zoom = 0.5f))

    private val quickActionsOpen = QuickActionsUiState(
        open = true,
        enabled = true,
        toggles = mapOf(QuickToggle.DarkMode to false, QuickToggle.WiFi to true, QuickToggle.AirplaneMode to false, QuickToggle.ShowTaps to null),
        pending = setOf(QuickToggle.AirplaneMode),
    )

    @Test
    fun `mirror quick actions open`() = render("mirror-quick-actions.png", ThemeMode.Light, state.copy(quickActions = quickActionsOpen))

    @Test
    fun `mirror quick actions open dark`() = render("mirror-quick-actions-dark.png", ThemeMode.Dark, state.copy(quickActions = quickActionsOpen))
}
