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
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.ui.mirror.SheetTab
import com.programmersbox.scrcpyviewer.ui.mirror.SheetWidths
import com.programmersbox.scrcpyviewer.ui.mirror.ShellConsole
import com.programmersbox.scrcpyviewer.ui.mirror.ShellHistory
import com.programmersbox.scrcpyviewer.ui.mirror.ShellTabContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.programmersbox.scrcpyviewer.ui.mirror.PendingRun
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
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.programmersbox.scrcpyviewer.apps.AppFilter
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.mirror.files.FilesBrowser
import com.programmersbox.scrcpyviewer.ui.mirror.files.FilesTab
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.cancel
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatTab
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneOffset
import java.util.Locale
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

    private fun render(name: String, mode: ThemeMode, state: MirrorUiState = this.state, clickIndicator: ClickIndicator? = null, width: Int = 1280, height: Int = 800, showTopStrip: Boolean = true, settleMillis: Long = 0, shellConsole: ShellConsole? = null, theme: AppTheme = AppTheme(mode)) {
        val out = outDir.resolve(name)
        val frame = mutableStateOf<VideoFrame?>(syntheticFrame())
        renderToPng(width, height, out, settleMillis) {
            ScrcpyViewerTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MirrorContent(
                        state, frame, {}, {}, {}, {}, {}, {}, clickIndicator = clickIndicator, showTopStrip = showTopStrip,
                        shellConsole = shellConsole,
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `mirror light`() = render("mirror-light.png", ThemeMode.Light)

    @Test
    fun `mirror dark`() = render("mirror-dark.png", ThemeMode.Dark)

    @Test
    fun `mirror neon synthwave`() = render(
        "mirror-neon-synthwave.png", ThemeMode.Dark, theme = AppTheme(ThemeMode.Dark, ThemePalette.Synthwave), settleMillis = 1_000,
    )

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
        toggles = mapOf(QuickToggle.DarkMode to false, QuickToggle.WiFi to true, QuickToggle.AirplaneMode to false, QuickToggle.ShowTaps to null, QuickToggle.AutoRotate to false),
        pending = setOf(QuickToggle.AirplaneMode),
    )

    @Test
    fun `mirror quick actions open`() = render("mirror-quick-actions.png", ThemeMode.Light, state.copy(quickActions = quickActionsOpen))

    @Test
    fun `mirror quick actions open dark`() = render("mirror-quick-actions-dark.png", ThemeMode.Dark, state.copy(quickActions = quickActionsOpen))

    private val customActions = listOf(
        ToggleAction(id = "stay", name = "Stay awake", icon = "bolt", on = "a", off = "b"),
        RunOnceAction(
            id = "clear", name = "Clear data", icon = "delete", command = "pm clear {pkg}", confirm = true,
            variables = mapOf("pkg" to Variable(label = "App to clear", kind = VariableKind.App)),
        ),
    )

    private val tilesState = quickActionsOpen.copy(
        pending = emptySet(),
        custom = customActions,
        customState = mapOf("stay" to true),
    )

    @Test
    fun `mirror quick actions tiles`() {
        render("mirror-quick-actions-tiles.png", ThemeMode.Light, state.copy(quickActions = tilesState), height = 1000)
        render("mirror-quick-actions-tiles-dark.png", ThemeMode.Dark, state.copy(quickActions = tilesState), height = 1000)
    }

    @Test
    fun `mirror quick actions wide`() {
        // Dragged to 520 dp: four tile columns; the stage gives up the width.
        val wide = state.copy(quickActions = tilesState.copy(widths = SheetWidths(actions = 520.dp)))
        render("mirror-quick-actions-wide.png", ThemeMode.Light, wide, height = 1000)
        render("mirror-quick-actions-wide-dark.png", ThemeMode.Dark, wide, height = 1000)
    }

    @Test
    fun `mirror quick actions prompt`() {
        val prompt = PendingRun(customActions[1] as RunOnceAction, needsValues = true, needsConfirm = true, values = mapOf("pkg" to ""))
        val packages = listOf("com.android.chrome", "com.example.notes", "com.spotify.music", "org.mozilla.firefox")
        render(
            "mirror-quick-actions-prompt.png", ThemeMode.Light,
            state.copy(quickActions = tilesState.copy(prompt = prompt, packages = packages)), height = 1000, settleMillis = 1000,
        )
    }

    /** Streams the scripted events for each command, then waits forever when [hang]. */
    private class ScriptRunner(private val script: Map<String, List<ShellEvent>>, private val hang: Boolean = false) :
        ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial") {
        override fun stream(cmd: String): Flow<ShellEvent> = flow {
            script[cmd].orEmpty().forEach { emit(it) }
            if (hang) awaitCancellation()
        }
    }

    private fun renderShell(name: String, mode: ThemeMode, console: ShellConsole, chips: List<RunOnceAction>) {
        renderToPng(400, 640, outDir.resolve(name)) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    ShellTabContent(console, chips) {}
                }
            }
        }
    }

    private val shellChips = listOf(
        RunOnceAction(id = "bugreport", name = "Bug report", command = "bugreportz"),
        RunOnceAction(id = "clear", name = "Clear data", command = "pm clear {pkg}"),
    )

    @Test
    fun `mirror shell finished`() {
        val runner = ScriptRunner(
            mapOf(
                "wm size" to listOf(ShellEvent.Output("Physical size: 1344x2992\n"), ShellEvent.Exit(0, 48)),
                "getprop ro.product.model" to listOf(ShellEvent.Output("Pixel 9 Pro\n"), ShellEvent.Exit(0, 31)),
                "cat /missing" to listOf(ShellEvent.Output("cat: /missing: No such file or directory\n"), ShellEvent.Exit(1, 22)),
            ),
        )
        val console = ShellConsole(runner, ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
        for (cmd in listOf("wm size", "getprop ro.product.model", "cat /missing")) {
            console.input = cmd
            console.submit()
        }
        renderShell("mirror-shell-finished.png", ThemeMode.Light, console, shellChips)
        renderShell("mirror-shell-finished-dark.png", ThemeMode.Dark, console, shellChips)
    }

    @Test
    fun `mirror shell streaming`() {
        val runner = ScriptRunner(
            mapOf(
                "logcat" to listOf(
                    ShellEvent.Output("I/ActivityManager( 612): Start proc 4121:com.example.notes/u0a212\n"),
                    ShellEvent.Output("D/WindowManager( 612): Relayout Window{4f2a u0 com.example.notes}\n"),
                    ShellEvent.Output("I/ActivityManager( 612): Start proc 4188:com.spotify.music/u0a198\n"),
                    ShellEvent.Output("W/System( 4121): ClassLoader referenced unknown path\n"),
                    ShellEvent.Output("I/ActivityManager( 612): Start proc 4230:com.android.chrome/u0a101\n"),
                ),
            ),
            hang = true,
        )
        val console = ShellConsole(runner, ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
        console.input = "logcat"
        console.submit()
        console.filter = "Start proc"
        renderShell("mirror-shell-streaming.png", ThemeMode.Light, console, shellChips)
        renderShell("mirror-shell-streaming-dark.png", ThemeMode.Dark, console, shellChips)
        console.stop()
    }

    /** A plain [argb] square standing in for an app icon read from an APK. */
    private fun fakeIcon(argb: Int): androidx.compose.ui.graphics.ImageBitmap =
        org.jetbrains.skia.Surface.makeRasterN32Premul(96, 96).use { s ->
            s.canvas.clear(argb)
            s.canvas.drawCircle(48f, 48f, 26f, org.jetbrains.skia.Paint().apply { color = 0xFFFFFFFF.toInt() })
            s.makeImageSnapshot().toComposeImageBitmap()
        }

    @Test
    fun `mirror quick actions apps tab`() {
        val apps = listOf(
            DeviceApp("com.android.chrome", "Chrome", false, "131.0.6778.135", 677813533),
            DeviceApp("com.sec.android.app.camera", "Camera", true, "14.1.01.67", 1410167),
            DeviceApp("com.google.android.gm", "Gmail", false, "2024.11.24.703081498", 64972154),
            DeviceApp("com.gabb.messenger.android.app", "Gabb Messenger", true, "3.8.2", 30802),
            DeviceApp("com.google.android.apps.maps", "Maps", false, "11.157.0102", 1066178372),
            DeviceApp("com.android.settings", "Settings", true, "14", 34),
            DeviceApp("com.spotify.music", "Spotify", false, null, null),
            DeviceApp("com.samsung.android.app.notes", "Samsung Notes With A Long Name", false, "4.4.26.13", 442613000),
        )
        val appsState = AppsUiState(
            apps = apps,
            icons = mapOf(
                "com.android.chrome" to fakeIcon(0xFF1F6FEB.toInt()),
                "com.google.android.gm" to fakeIcon(0xFFD93025.toInt()),
                "com.spotify.music" to fakeIcon(0xFF1DB954.toInt()),
            ),
            busy = setOf("com.google.android.apps.maps"),
        )
        val tab = state.copy(quickActions = tilesState.copy(tab = SheetTab.Apps, apps = appsState))
        render("mirror-quick-actions-apps.png", ThemeMode.Light, tab, settleMillis = 1000)
        render("mirror-quick-actions-apps-dark.png", ThemeMode.Dark, tab, settleMillis = 1000)
        val filtered = tab.copy(quickActions = tab.quickActions.copy(apps = appsState.copy(query = "android", filter = AppFilter.System)))
        render("mirror-quick-actions-apps-filtered.png", ThemeMode.Dark, filtered, settleMillis = 1000)
        render("mirror-quick-actions-apps-loading.png", ThemeMode.Dark, tab.copy(quickActions = tab.quickActions.copy(apps = AppsUiState(loading = true))), settleMillis = 600)
    }

    @Test
    fun `mirror quick actions shell tab running`() {
        val runner = ScriptRunner(
            mapOf("logcat" to listOf(ShellEvent.Output("I/ActivityManager( 612): Start proc 4121:com.example.notes/u0a212\n"))),
            hang = true,
        )
        val console = ShellConsole(runner, ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
        console.input = "logcat"
        console.submit()
        val shell = state.copy(
            quickActions = tilesState.copy(tab = SheetTab.Shell, consoleRunning = true),
        )
        render("mirror-quick-actions-shell.png", ThemeMode.Light, shell, shellConsole = console)
        render("mirror-quick-actions-shell-dark.png", ThemeMode.Dark, shell, shellConsole = console)
        console.stop()
    }

    // Files tab, at the sheet's default 440 dp width.

    private class FilesShotDevice : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
        private fun file(name: String, size: Long) = DeviceEntry(name, false, size, 1_731_000_000_000)
        private fun folder(name: String) = DeviceEntry(name, true, 4096, 1_731_000_000_000)
        override suspend fun list(dir: String): Listing = when (dir) {
            "/sdcard/Download" -> Listing.Entries(
                listOf(
                    folder("Telegram"), folder("Screenshots"),
                    file("a-very-long-file-name-for-a-holiday-photo-album-2024.jpg", 4_200_000),
                    file("notes.txt", 5_400), file("app-release.apk", 48_000_000), file("trip.mp4", 380_000_000),
                    file("voice.ogg", 91_000), file("report.pdf", 1_200_000), file("backup.zip", 22_000_000),
                ),
            )
            "/sdcard/Documents" -> Listing.Entries(emptyList())
            else -> Listing.Blocked
        }
        override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 7 }
        override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
    }

    private fun filesTransfers() = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}

    private fun filesBrowser(start: String, transfers: FileTransfers = filesTransfers()) = FilesBrowser(
        "SER", FilesShotDevice(), transfers, QuickActionsMemory(),
        AppSettings(Files.createTempDirectory("files-shot").resolve("settings.json")), start,
        CoroutineScope(Dispatchers.Unconfined), {}, enqueue = { emptyList() }, jobs = emptyFlow(),
    )

    private fun renderFiles(name: String, theme: AppTheme, browser: FilesBrowser) {
        val out = outDir.resolve(name)
        renderToPng(440, 800, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(theme) {
                Box(Modifier.fillMaxSize().styleBackdrop()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                        FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = false)
                    }
                }
            }
        }
        assertTrue(Files.size(out) > 5_000, "$name should not be blank")
    }

    /** The five Files tab states in [style]; [onlySelection] renders just the list with a selection. */
    private fun filesStates(style: ThemeStyle, onlySelection: Boolean) {
        val tag = style.name.lowercase()
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            val theme = AppTheme(mode, style = style)
            val m = mode.name.lowercase()
            renderFiles("mirror-files-$tag-selection-$m.png", theme, filesBrowser("/sdcard/Download").apply {
                click("Telegram"); toggle("notes.txt"); toggle("app-release.apk")
            })
            if (onlySelection) continue
            renderFiles("mirror-files-$tag-empty-$m.png", theme, filesBrowser("/sdcard/Documents"))
            renderFiles("mirror-files-$tag-blocked-$m.png", theme, filesBrowser("/sdcard/Android/data"))
            renderFiles("mirror-files-$tag-delete-$m.png", theme, filesBrowser("/sdcard/Download").apply {
                click("notes.txt"); toggle("Telegram"); askDelete()
            })
            val transfers = filesTransfers()
            transfers.jobs = listOf(
                TransferJob(
                    1, TransferRequest(Paths.get("/Users/me/trip.mp4"), TransferKind.Push),
                    JobState.Running, 120_000_000, 380_000_000, 18_000_000,
                ),
            )
            renderFiles("mirror-files-$tag-tray-$m.png", theme, filesBrowser("/sdcard/Download", transfers))
        }
    }

    // Logcat tab, at the own window's default 720 dp width.

    private class LogcatShotSource(private val shot: List<LogcatLine>) : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
        override fun lines(since: String?): Flow<LogcatLine> = flow {
            shot.forEach { emit(it) }
            awaitCancellation()
        }
        override suspend fun processes(): Map<Int, String> =
            mapOf(4121 to "com.example.notes", 612 to "system_server", 1890 to "com.android.systemui")
    }

    private fun logLine(time: String, pid: Int, tid: Int, level: LogLevel, tag: String, message: String) =
        LogcatLine("11-13", time, pid, tid, level, tag, message)

    private val logcatShot: List<LogcatLine> = listOf(
        logLine("10:41:02.118", 612, 640, LogLevel.Info, "ActivityManager", "Start proc 4121:com.example.notes/u0a212 for activity"),
        logLine("10:41:02.402", 4121, 4121, LogLevel.Debug, "NotesRepo", "Loaded 128 notes from cache in 14ms"),
        logLine("10:41:02.517", 4121, 4133, LogLevel.Verbose, "SyncWorker", "Sync started: 3 dirty notes"),
        logLine("10:41:02.733", 1890, 1890, LogLevel.Debug, "Choreographer", "Skipped 38 frames!  The application may be doing too much work on its main thread."),
        logLine("10:41:03.020", 4121, 4133, LogLevel.Warn, "OkHttp", "Sync request failed, will retry"),
        logLine("10:41:03.020", 4121, 4133, LogLevel.Warn, "OkHttp", "java.net.SocketTimeoutException: timeout"),
        logLine("10:41:03.020", 4121, 4133, LogLevel.Warn, "OkHttp", "\tat okhttp3.internal.http2.Http2Stream.waitForIo(Http2Stream.kt:675)"),
        logLine("10:41:03.311", 4121, 4121, LogLevel.Info, "NoteListViewModel", "Showing 128 notes"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "FATAL EXCEPTION: main"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "Process: com.example.notes, PID: 4121"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "java.lang.IllegalStateException: Note 42 has no body"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "\tat com.example.notes.NotesRepo.load(NotesRepo.kt:118)"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "\tat com.example.notes.NoteListViewModel.open(NoteListViewModel.kt:57)"),
        logLine("10:41:04.006", 4121, 4121, LogLevel.Error, "AndroidRuntime", "\tat android.os.Handler.dispatchMessage(Handler.java:99)"),
        logLine("10:41:04.215", 612, 1204, LogLevel.Warn, "ActivityManager", "Process com.example.notes (pid 4121) has died: fg TOP"),
        logLine("10:41:04.330", 1890, 1890, LogLevel.Info, "SystemUI", "Notes keeps stopping: dialog shown"),
    )

    /** The Logcat tab: [setup] plays, pauses or leaves the controller as it is before the frame. */
    private fun renderLogcat(name: String, mode: ThemeMode, setup: (LogcatController) -> Unit) {
        val out = outDir.resolve(name)
        val scope = CoroutineScope(Dispatchers.Unconfined + kotlinx.coroutines.Job())
        val controller = LogcatController(LogcatShotSource(logcatShot), scope, {}, io = kotlin.coroutines.EmptyCoroutineContext)
        setup(controller)
        try {
            renderToPng(720, 640, out, settleMillis = 1_000) {
                ScrcpyViewerTheme(AppTheme(mode)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                        LogcatTab(controller, 720.dp, motion = false)
                    }
                }
            }
        } finally {
            scope.cancel()
        }
        assertTrue(Files.size(out) > 5_000, "$name should not be blank")
    }

    @Test
    fun `mirror logcat tab`() {
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            val m = mode.name.lowercase()
            renderLogcat("mirror-logcat-running-$m.png", mode) {
                it.pick("com.example.notes")
                it.query = ""
                it.play()
            }
            renderLogcat("mirror-logcat-paused-$m.png", mode) {
                it.play()
                it.pause()
            }
            renderLogcat("mirror-logcat-start-$m.png", mode) {}
        }
    }

    @Test
    fun `mirror files tab material`() = filesStates(ThemeStyle.Material, onlySelection = false)

    @Test
    fun `mirror files tab glass`() = filesStates(ThemeStyle.Glass, onlySelection = true)

    @Test
    fun `mirror files tab retro`() = filesStates(ThemeStyle.Retro, onlySelection = true)
}
