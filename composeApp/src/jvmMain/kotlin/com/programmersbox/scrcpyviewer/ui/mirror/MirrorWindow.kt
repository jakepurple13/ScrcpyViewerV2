package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.media.Recorder
import com.programmersbox.scrcpyviewer.media.Screenshot
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.options.CommandLine
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.DeviceMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import com.programmersbox.scrcpyviewer.ui.window.windowChrome
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Paths
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Side toolbar buttons; [keycode] buttons send that Android key down and up. */
enum class MirrorAction(val label: String, val keycode: Int? = null) {
    Back("Back", InputMapper.KEYCODE_BACK),
    Home("Home", InputMapper.KEYCODE_HOME),
    Recents("Recents", InputMapper.KEYCODE_APP_SWITCH),
    VolumeUp("Volume up", InputMapper.KEYCODE_VOLUME_UP),
    VolumeDown("Volume down", InputMapper.KEYCODE_VOLUME_DOWN),
    Rotate("Rotate"),
    Power("Power", InputMapper.KEYCODE_POWER),
    QuickActions("Quick actions"),
    Screenshot("Screenshot"),
    Record("Record"),
    InstallApk("Install APK…"),
    PushFile("Push file…"),
}

/** Control messages for a toolbar action; Screenshot, Record, QuickActions, InstallApk and PushFile send nothing (the window handles them). */
fun MirrorAction.messages(): List<ControlMessage> = when {
    keycode != null -> listOf(
        ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_DOWN, keycode, 0, 0),
        ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_UP, keycode, 0, 0),
    )
    this == MirrorAction.Rotate -> listOf(ControlMessage.RotateDevice)
    else -> emptyList()
}

data class MirrorUiState(
    val model: String,
    /** "Android 15 · USB". */
    val details: String,
    val status: SessionStatus,
    val error: String? = null,
    val frameSize: IntSize? = null,
    val maxSize: Int? = null,
    val codec: String = "H.264",
    val bitRate: String = "8 Mbps",
    val fps: Double = 0.0,
    /** Zoom of the image (1 = one frame pixel per screen pixel); null is "Fit to window". */
    val zoom: Float? = null,
    /** On-screen scale of the image while fitted, for the percent label and the first zoom step. */
    val fittedScale: Float = 1f,
    val stretch: Boolean = false,
    val canReconnect: Boolean = false,
    val command: String = "scrcpy",
    val background: Color? = null,
    val actionsEnabled: Boolean = true,
    /** Set while the UHID mouse holds the pointer: "Mouse captured — press <key> to release". */
    val captureHint: String? = null,
    /** "No audio from device" when the device sent no audio stream, "No audio output device" when it cannot play. */
    val audioNote: String? = null,
    /** A frame is shown, so a screenshot can be taken. */
    val screenshotEnabled: Boolean = false,
    /** Recording can be started (session running) or stopped. */
    val recordEnabled: Boolean = false,
    /** "● REC mm:ss" while recording. */
    val recordingLabel: String? = null,
    /** `display-orientation`: how the frame is turned on screen. */
    val orientation: DisplayOrientation = DisplayOrientation.R0,
    /** Install APK… and Push file… can be used (adb available, device connected). */
    val transferEnabled: Boolean = false,
    /** "Installing app.apk…" / "Pushing 2 of 3: photo.jpg…" while the install/push queue runs. */
    val transferLabel: String? = null,
    /** Set while files are dragged over the window. */
    val dropHint: DropHint? = null,
    /** The quick actions sheet right of the toolbar. */
    val quickActions: QuickActionsUiState = QuickActionsUiState(),
) {
    val fit: Boolean get() = zoom == null
}

/** Milliseconds on the monotonic clock the click dots use. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

/** "● REC mm:ss" for a recording running for [elapsedNanos]. */
fun recordingLabel(elapsedNanos: Long): String {
    val seconds = (elapsedNanos / 1_000_000_000L).coerceAtLeast(0)
    return "● REC %02d:%02d".format(seconds / 60, seconds % 60)
}

/** `<model> · <serial>`, or the `window-title` option. */
fun mirrorTitle(model: String, serial: String, values: OptionValues): String =
    values.entries["window-title"]?.takeIf { it.isNotBlank() } ?: "$model · $serial"

/** "8 Mbps" from the `video-bit-rate` value (default 8M). */
fun bitRateLabel(value: String?): String {
    val bits = value?.let { OptionType.BitRate.toBitsPerSecond(it) } ?: 8_000_000L
    return when {
        bits >= 1_000_000 && bits % 1_000_000 == 0L -> "${bits / 1_000_000} Mbps"
        bits >= 1_000_000 -> "%.1f Mbps".format(bits / 1e6)
        else -> "${bits / 1000} Kbps"
    }
}

fun codecLabel(value: String?): String = VideoDecoder.codecName(
    when (value) {
        "h265" -> StreamProtocol.CODEC_H265
        "av1" -> StreamProtocol.CODEC_AV1
        "vp8" -> StreamProtocol.CODEC_VP8
        "vp9" -> StreamProtocol.CODEC_VP9
        else -> StreamProtocol.CODEC_H264
    }
)

/** "#RGB" / "#RRGGBB" (with or without #) as a color. */
fun parseHexColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * One device's mirror window: [session] keeps running on the app scope; closing the window calls
 * [MirrorController.close]. Keys typed while the window is focused go to the device; Cmd+V (Ctrl+V off macOS)
 * pastes the computer clipboard through `SetClipboard(paste=true)` (or as text with `legacy-paste`).
 *
 * `keyboard=uhid` and `mouse=uhid` create the HID devices on the session ([HidKeyboard], [HidMouse]) and send HID
 * reports instead of key/text/touch injections. The UHID mouse is relative, so the window captures the pointer
 * (see [MouseCapture]): the cursor is hidden over the video and moved back to its centre with `java.awt.Robot`,
 * and motion is sent as deltas. Without a working Robot the deltas still flow, until the pointer leaves the video.
 *
 * The top strip ([TopStrip]) is the window's title bar ([AppWindow]): window controls at its edge, empty
 * space drags the window. With the system title bar it sits at the top of the content instead.
 */
@Composable
fun MirrorWindow(
    serial: String,
    session: ScrcpySession,
    controller: MirrorController,
    themeMode: ThemeMode,
    /** `Settings.useSystemTitleBar`, read when the window opens. */
    useSystemTitleBar: Boolean?,
    /** `Settings.captureDir`: where screenshots and recordings started from the toolbar go. */
    captureDir: String,
    /** Runs `adb install` / `adb push` for dropped files and the Install APK… / Push file… buttons; null disables them. */
    adb: AdbClient? = null,
    fileDialogs: TransferFileDialogs = AwtTransferFileDialogs,
) {
    val values = session.values
    val e = values.entries
    val devices by controller.devices.collectAsState()
    // Keep showing the last known device details after it is unplugged.
    var lastDevice by remember { mutableStateOf<Device?>(null) }
    val current = devices.firstOrNull { it.serial == serial }
    LaunchedEffect(current) { if (current != null) lastDevice = current }
    val device = current ?: lastDevice
    val model = device?.let(::deviceTitle) ?: serial

    val status by session.status.collectAsState()
    val error by session.error.collectAsState()
    val frameSize by session.frameSize.collectAsState()
    val fps by session.fps.collectAsState()
    val audioDisabled by session.audioDisabledByDevice.collectAsState()
    val audioPlaybackError by session.audioPlaybackError.collectAsState()
    val frame = session.frames.collectAsState()
    val reconnectable by controller.reconnectable.collectAsState()
    val recording by session.recording.collectAsState()
    val currentCaptureDir by rememberUpdatedState(captureDir)
    val currentModel by rememberUpdatedState(model)
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    fun notify(message: String) {
        uiScope.launch { snackbar.showSnackbar(message) }
    }
    // Results of this device's sessions (a previous one too, after Reconnect); the main window shows the others.
    LaunchedEffect(serial) {
        controller.recordingResults.filter { it.serial == serial }.collect { notify(recordResultMessage(it.result)) }
    }
    var recordingElapsed by remember { mutableStateOf<String?>(null) }
    /** A start is in flight (directory creation on the IO dispatcher): the Record button waits for it. */
    var recordStarting by remember { mutableStateOf(false) }
    LaunchedEffect(recording) {
        val r = recording
        recordingElapsed = null
        while (r != null) {
            val elapsed = System.nanoTime() - r.startedNanos
            recordingElapsed = recordingLabel(elapsed)
            delay(1_000 - (elapsed / 1_000_000) % 1_000) // tick on the second
        }
    }

    /** Copies the frame now (inside VideoFrame.use), encodes and writes the PNG on the IO dispatcher. */
    fun takeScreenshot() {
        val bitmap = Screenshot.copyLatest { session.frames.value }
        if (bitmap == null) {
            notify("No frame to capture yet")
            return
        }
        uiScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Screenshot.save(bitmap, Paths.get(currentCaptureDir), currentModel)
                }
                notify("Screenshot saved to $file")
            } catch (e: Exception) {
                notify("Could not save the screenshot: ${e.message ?: e}")
            } finally {
                bitmap.asSkiaBitmap().close() // the copy's native pixels
            }
        }
    }

    fun toggleRecording() {
        if (recordStarting) return
        if (session.recording.value != null) {
            session.stopRecording() // "Recording saved to …" comes from recordingResults
            return
        }
        recordStarting = true
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val format = Recorder.toolbarFormat(values) // every format name is also its file extension
                    val file = Screenshot.captureFile(Paths.get(currentCaptureDir), currentModel, format)
                    session.startRecording(file, format)
                }
            } catch (e: Exception) {
                notify("Could not start recording: ${e.message ?: e}")
            } finally {
                recordStarting = false
            }
        }
    }

    val transfers = rememberFileTransfers(serial, session, adb, ::notify)
    val quickActions = rememberQuickActions(serial, session, adb, ::notify)

    val orientation = remember(values) { DisplayOrientation.forOptions(values) }
    // The window is sized for the image as displayed (turned by display-orientation).
    val displayedSize = frameSize?.let(orientation::orientedSize)
    val renderFit = e["render-fit"]
    // Per window, not persisted: null is Fit, else the scale (1 = 100 %).
    var zoom by remember { mutableStateOf<Float?>(if (renderFit == "unscaled") 1f else null) }
    val fit = zoom == null
    val stretch = renderFit == "stretched"
    val aspectLock = "no-window-aspect-ratio-lock" !in e
    val explicitSize = e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } != null ||
        e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } != null

    val windowState = rememberWindowState(
        placement = if ("fullscreen" in e) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        position = run {
            val x = e["window-x"]?.toIntOrNull()
            val y = e["window-y"]?.toIntOrNull()
            if (x == null && y == null) WindowPosition.PlatformDefault else WindowPosition((x ?: 100).dp, (y ?: 100).dp)
        },
        size = DpSize(
            (e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } ?: 480).dp,
            (e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } ?: 900).dp,
        ),
    )

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // The visible stage in window pixels: the zoomed canvas can be larger than it.
    var stageBounds by remember { mutableStateOf<Rect?>(null) }
    val currentFit by rememberUpdatedState(fit)
    val control = "no-control" !in e
    val hidKeyboard = remember(session) { if (control && e["keyboard"] == "uhid") HidKeyboard() else null }
    val hidMouse = remember(session) { if (control && e["mouse"] == "uhid") HidMouse() else null }
    val mapper = remember(session) {
        InputMapper.forOptions(
            values,
            // Map against the image on screen (the canvas letterboxes that one), which can lag the session size
            // for a frame after a rotation.
            frameSize = { session.frames.value?.let { IntSize(it.width, it.height) } ?: session.frameSize.value },
            viewRect = { Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat()) },
            stretch = { stretch && currentFit },
            hidMouse = hidMouse,
        )
    }
    val send: (List<ControlMessage>) -> Unit = { msgs -> msgs.forEach(session::send) }
    fun fittedScale(): Float {
        val oriented = frameSize?.let(orientation::orientedSize) ?: return 1f
        val rect = mapper.displayedRect() ?: return 1f
        return if (oriented.width > 0) rect.width / oriented.width else 1f
    }
    val applyZoom: (ZoomCommand) -> Unit = { zoom = Zoom.step(zoom, fittedScale(), it) }
    val currentApplyZoom by rememberUpdatedState(applyZoom)
    // Set by the window's focus listener; the AWT window only exists inside AppWindow {} below.
    var windowFocused by remember { mutableStateOf(false) }
    var awtWindowRef by remember { mutableStateOf<java.awt.Window?>(null) }
    val robot = remember(hidMouse != null) { if (hidMouse != null) WindowPointerCapture.robotOrNull() else null }
    val capture = remember(session) {
        hidMouse?.let {
            WindowPointerCapture(MouseCapture(), mapper, hidKeyboard, send, isFocused = { awtWindowRef?.isFocused == true }, robot)
        }
    }
    val captured = capture?.active?.collectAsState()?.value == true
    // show-touches only shows physical touches on the device; mark the injected clicks in the window (not for the
    // captured UHID mouse, whose pointer is hidden and has no position on the frame).
    val clickIndicator = remember(session) {
        if ("show-touches" in e && capture == null) ClickIndicator(mapper::displayedRect) else null
    }

    // UHID devices live as long as the session: created first thing (the control queue holds them until the
    // socket is up), destroyed when the window lets go of the session.
    DisposableEffect(session) {
        hidKeyboard?.let { session.send(it.open()) }
        hidMouse?.let { session.send(it.open()) }
        onDispose {
            hidKeyboard?.let { session.send(it.close()) }
            hidMouse?.let { session.send(it.close()) }
        }
    }
    if (hidKeyboard != null) {
        LaunchedEffect(session) {
            session.deviceMessages.collect { msg ->
                if (msg is DeviceMessage.UhidOutput && msg.id == HidKeyboard.ID) hidKeyboard.onOutput(msg.data)
            }
        }
    }
    if (capture != null) {
        // As the scrcpy client: capture on the first frame, or at once without video (screen.c).
        LaunchedEffect(session) {
            if (values.hasVideoPlayback()) session.frameSize.first { it != null }
            capture.requestCapture()
        }
    }

    val state = MirrorUiState(
        model = model,
        details = listOfNotNull(
            device?.info?.androidVersion?.let { "Android $it" },
            device?.let { if (it.isTcp) "Wi-Fi" else "USB" },
        ).joinToString(" · "),
        status = status,
        error = error,
        frameSize = frameSize,
        maxSize = e["max-size"]?.toIntOrNull()?.takeIf { it > 0 },
        codec = codecLabel(e["video-codec"]),
        bitRate = bitRateLabel(e["video-bit-rate"]),
        fps = fps,
        zoom = zoom,
        fittedScale = fittedScale(),
        stretch = stretch,
        canReconnect = serial in reconnectable,
        command = CommandLine.build(serial, values),
        background = parseHexColor(e["background-color"]),
        actionsEnabled = status == SessionStatus.Running && "no-control" !in e,
        audioNote = if (audioDisabled) "No audio from device" else audioPlaybackError,
        captureHint = if (captured && windowFocused) "Mouse captured — press ${MouseCapture.keyLabel()} to release" else null,
        screenshotEnabled = frame.value != null,
        recordEnabled = !recordStarting && (recording != null || status == SessionStatus.Running),
        recordingLabel = if (recording != null) recordingElapsed ?: recordingLabel(0) else null,
        orientation = orientation,
        transferEnabled = transfers.enabled,
        transferLabel = transfers.progressLabel,
        dropHint = transfers.hint,
        quickActions = quickActions.uiState,
    )

    AppWindow(
        onCloseRequest = { controller.close(serial) },
        title = mirrorTitle(model, serial, values),
        themeMode = themeMode,
        chrome = remember { windowChrome(useSystemTitleBar) },
        state = windowState,
        alwaysOnTop = "always-on-top" in e,
        icon = AppIcon.painter,
        titleBarHeight = 40.dp,
        titleBar = {
            // The strip's Copy command shows its "Copied" message in the window's snackbar.
            CompositionLocalProvider(LocalSnackbarHostState provides snackbar) { TopStrip(state, applyZoom) }
        },
    ) {
        LaunchedEffect(serial) {
            controller.focusRequests.filter { it == serial }.collect {
                window.toFront()
                window.requestFocus()
            }
        }
        if ("print-fps" in e) {
            LaunchedEffect(session) { session.fps.collect { if (it > 0) println("[$serial] %.1f fps".format(it)) } }
        }
        val awtWindow = window
        DisposableEffect(session) {
            val paste = if (Os.isMac) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
            var pasteDown = false
            val zoomKeys = ZoomKeyFilter(Os.isMac)
            val dispatcher = KeyEventDispatcher { ev ->
                val inWindow = ev.component === awtWindow || SwingUtilities.getWindowAncestor(ev.component) === awtWindow
                if (!inWindow) return@KeyEventDispatcher false
                if (quickActions.open && ev.keyCode == AwtKeyEvent.VK_ESCAPE) {
                    if (ev.id == AwtKeyEvent.KEY_PRESSED) quickActions.close()
                    return@KeyEventDispatcher true
                }
                // Zoom shortcuts belong to the window: never forwarded to the device (any keyboard mode).
                if (zoomKeys.handle(ev.id, ev.keyCode, ev.modifiersEx) { currentApplyZoom(it) }) return@KeyEventDispatcher true
                if ("no-control" in e) return@KeyEventDispatcher false
                if (capture != null && capture.key(ev.id, ev.keyCode, ev.keyLocation)) return@KeyEventDispatcher true
                if (e["keyboard"] == "disabled") return@KeyEventDispatcher false
                if (ev.keyCode == AwtKeyEvent.VK_V && ev.modifiersEx and paste != 0 && ev.id == AwtKeyEvent.KEY_PRESSED) {
                    pasteDown = true
                    val text = runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                    }.getOrNull()
                    if (!text.isNullOrEmpty()) {
                        if ("legacy-paste" in e) text.chunked(100).forEach { session.send(ControlMessage.InjectText(it)) }
                        else session.send(ControlMessage.SetClipboard(0, paste = true, text = text))
                    }
                    return@KeyEventDispatcher true
                }
                if (pasteDown && (ev.keyCode == AwtKeyEvent.VK_V || ev.id == AwtKeyEvent.KEY_TYPED)) {
                    if (ev.id == AwtKeyEvent.KEY_RELEASED) pasteDown = false
                    return@KeyEventDispatcher true
                }
                if (hidKeyboard != null) {
                    send(hidKeyboard.awtKey(ev.id, ev.keyCode, ev.keyLocation))
                    return@KeyEventDispatcher true // HID keys never become text
                }
                val msgs = mapper.awtKey(ev.id, ev.keyCode, ev.keyChar, ev.modifiersEx, ev.keyLocation)
                send(msgs)
                msgs.isNotEmpty()
            }
            val focus = object : WindowAdapter() {
                override fun windowGainedFocus(ev: WindowEvent) {
                    windowFocused = true
                    capture?.focusGained()
                }

                override fun windowLostFocus(ev: WindowEvent) {
                    windowFocused = false
                    zoomKeys.reset()
                    capture?.focusLost()
                    // Releases that happen in another window never arrive: release what the device holds.
                    hidKeyboard?.let { send(it.releaseAll()) }
                    send(mapper.releaseButtons())
                }
            }
            awtWindowRef = awtWindow
            windowFocused = awtWindow.isFocused
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            kfm.addKeyEventDispatcher(dispatcher)
            awtWindow.addWindowFocusListener(focus)
            onDispose {
                kfm.removeKeyEventDispatcher(dispatcher)
                awtWindow.removeWindowFocusListener(focus)
            }
        }

        // Drops anywhere on the window install / push (COPY only, see FileDropTarget).
        FileDropTarget(awtWindow, transfers)

        val density = LocalDensity.current

        var stage by remember { mutableStateOf(IntSize.Zero) }
        var autoSized by remember { mutableStateOf(explicitSize) }
        var lastStage by remember { mutableStateOf(IntSize.Zero) }
        var lastFrame by remember { mutableStateOf<IntSize?>(null) }
        // Initial size from the first frame (fit to the screen), then the aspect ratio lock on every resize.
        LaunchedEffect(stage, displayedSize, fit) {
            val fs = displayedSize ?: return@LaunchedEffect
            if (stage == IntSize.Zero || windowState.placement != WindowPlacement.Floating) return@LaunchedEffect
            // Correcting the size on every step of a drag fights the drag (the window jitters): restarted by each new
            // stage size, this only goes on once the size has held still.
            if (autoSized) delay(ASPECT_SETTLE_MS)
            val target: IntSize? = when {
                !autoSized -> {
                    autoSized = true
                    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    val chromeW = windowState.size.width.value * density.density - stage.width
                    val chromeH = windowState.size.height.value * density.density - stage.height
                    val maxW = bounds.width * density.density * 0.9f - chromeW
                    val maxH = bounds.height * density.density * 0.9f - chromeH
                    val scale = min(1f, min(maxW / fs.width, maxH / fs.height))
                    IntSize((fs.width * scale).roundToInt(), (fs.height * scale).roundToInt())
                }
                fit && aspectLock -> aspectLockedStage(lastStage, stage, lastFrame, fs)
                else -> null
            }
            lastFrame = fs
            lastStage = target ?: stage
            if (target != null && (abs(target.width - stage.width) > 2 || abs(target.height - stage.height) > 2)) {
                val dw = (target.width - stage.width) / density.density
                val dh = (target.height - stage.height) / density.density
                windowState.size = DpSize(windowState.size.width + dw.dp, windowState.size.height + dh.dp)
            }
        }

        // The quick actions sheet grows the window to the right instead of taking width from the stage. While it opens
        // or closes the stage is pinned to its width (px), so the image never changes size, and the window's right
        // edge animates out to uncover the panel, which does not move. One setBounds per frame moves and resizes the
        // window atomically (moved left when it would leave the screen, back on close). Maximized and fullscreen
        // windows cannot grow: there the sheet takes its width from the stage.
        var pinnedStage by remember { mutableStateOf<Int?>(null) }
        val sheetGrowth = remember { Animatable(0f) } // window units (dp) the window has grown by
        var baseBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        LaunchedEffect(quickActions.open) {
            val open = quickActions.open
            if (windowState.placement != WindowPlacement.Floating && sheetGrowth.value == 0f) return@LaunchedEffect
            val base = baseBounds ?: awtWindow.bounds.also { baseBounds = it }
            val screenRight = awtWindow.graphicsConfiguration.bounds.let { it.x + it.width }
            pinnedStage = stage.width
            sheetGrowth.animateTo(if (open) QUICK_ACTIONS_WIDTH.value else 0f, quickActionsSpec(open)) {
                val width = base.width + value.roundToInt()
                val overflow = (base.x + width - screenRight).coerceAtLeast(0)
                awtWindow.setBounds(base.x - overflow, awtWindow.y, width, awtWindow.height)
            }
            if (!open) {
                baseBounds = null
                // Keep the pin until the native window has shrunk, or the stage widens for a frame.
                var frames = 0
                while (awtWindow.width > base.width + 1 && frames++ < 30) withFrameNanos { }
            }
            pinnedStage = null // not when cancelled: a reversed toggle keeps the pin through its own animation
        }

        ScrcpyViewerTheme(themeMode) {
            Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
                    MirrorContent(
                        state = state,
                        frame = frame,
                        showTopStrip = false, // drawn by the window's title bar
                        latestFrame = { session.frames.value },
                        onZoom = applyZoom,
                        onAction = { action ->
                            when (action) {
                                MirrorAction.Screenshot -> takeScreenshot()
                                MirrorAction.Record -> toggleRecording()
                                MirrorAction.InstallApk -> transfers.start(fileDialogs.chooseApks(awtWindow))
                                MirrorAction.PushFile -> transfers.start(fileDialogs.chooseFiles(awtWindow, transfers.pushTarget))
                                MirrorAction.QuickActions -> quickActions.toggleSheet()
                                else -> send(action.messages())
                            }
                        },
                        onReconnect = { controller.reconnect(serial) },
                        onPointer = {
                            when {
                                "no-control" in e -> Unit
                                capture != null -> capture.pointer(it, awtWindow, viewSize, density.density, stageBounds)
                                else -> {
                                    clickIndicator?.onPointer(it, monotonicMillis())
                                    send(mapper.pointer(it))
                                }
                            }
                        },
                        onViewSize = { viewSize = it },
                        onStageSize = { stage = it },
                        onQuickAction = quickActions::onEvent,
                        pinnedStageWidth = pinnedStage,
                        onStageBounds = { stageBounds = it },
                        clickIndicator = clickIndicator,
                    )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 80.dp, bottom = 44.dp))
            }
        }
    }
}

/** How long the stage size must hold still before the aspect ratio lock corrects the window. */
private const val ASPECT_SETTLE_MS = 150L

/**
 * Stage size that keeps the frame's aspect ratio after the user resized the stage from [old] to [new]: the
 * dimension that changed most wins. When the frame turned (rotation), the longer side is kept.
 */
fun aspectLockedStage(old: IntSize, new: IntSize, oldFrame: IntSize?, frame: IntSize): IntSize {
    if (frame.width <= 0 || frame.height <= 0 || new.width <= 0 || new.height <= 0) return new
    val aspect = frame.width.toDouble() / frame.height
    val rotated = oldFrame != null && (oldFrame.width > oldFrame.height) != (frame.width > frame.height)
    return if (rotated) {
        val long = maxOf(new.width, new.height)
        if (frame.width >= frame.height) IntSize(long, (long / aspect).roundToInt()) else IntSize((long * aspect).roundToInt(), long)
    } else if (abs(new.width - old.width) >= abs(new.height - old.height)) {
        IntSize(new.width, (new.width / aspect).roundToInt())
    } else {
        IntSize((new.height * aspect).roundToInt(), new.height)
    }
}

/** The mirror window's content (stateless, so it can be rendered offscreen). */
@Composable
fun MirrorContent(
    state: MirrorUiState,
    frame: State<VideoFrame?>,
    onZoom: (ZoomCommand) -> Unit,
    onAction: (MirrorAction) -> Unit,
    onReconnect: () -> Unit,
    onPointer: (PointerEvent) -> Unit,
    onViewSize: (IntSize) -> Unit,
    onStageSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    /** The frame to draw, read at draw time ([frame] only triggers the redraw). */
    latestFrame: () -> VideoFrame? = { frame.value },
    /** `show-touches`: the click dots drawn over the frame. */
    clickIndicator: ClickIndicator? = null,
    /** The stage's bounds in window pixels. */
    onStageBounds: (Rect) -> Unit = {},
    /** False when the window draws [TopStrip] as its title bar; the divider below it stays. */
    showTopStrip: Boolean = true,
    onQuickAction: (QuickActionEvent) -> Unit = {},
    /**
     * Set while the window grows or shrinks for the quick actions sheet: the stage keeps this width (px) and the sheet
     * shows in whatever width is left. Null: the sheet animates its own width, taken from the stage.
     */
    pinnedStageWidth: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        if (showTopStrip) NoTitleBarScope.TopStrip(state, onZoom)
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            Box(
                (if (pinnedStageWidth != null) Modifier.width(with(density) { pinnedStageWidth.toDp() }) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .background(state.background ?: colors.surfaceDim)
                    .onSizeChanged(onStageSize)
                    .onGloballyPositioned { onStageBounds(it.boundsInWindow()) },
            ) {
                Stage(state, frame, latestFrame, onPointer, onViewSize, clickIndicator)
                if (state.status == SessionStatus.Disconnected || state.status == SessionStatus.Failed) {
                    DisconnectedScrim(state, onReconnect)
                } else if (state.status == SessionStatus.Connecting || state.frameSize == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                // Keep the last hint while fading out, so the overlay doesn't go blank mid-animation.
                // A plain holder, not snapshot state: writing it during composition invalidates nothing.
                val lastHint = remember { DropHintHolder() }
                state.dropHint?.let { lastHint.hint = it }
                // Fully qualified: the enclosing Row's RowScope.AnimatedVisibility would otherwise win.
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.dropHint != null,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(200)),
                ) {
                    lastHint.hint?.let { DropOverlay(it) }
                }
            }
            SideToolbar(state, onAction)
            if (pinnedStageWidth != null) {
                // The panel stays put; the growing window uncovers it.
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    QuickActionsPanel(
                        state.quickActions, onQuickAction,
                        Modifier.align(Alignment.CenterStart).wrapContentWidth(Alignment.Start, unbounded = true),
                    )
                }
            } else {
                QuickActionsSheet(state.quickActions, onQuickAction)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        StatusLine(state)
    }
}

/** Widths (dp) of the top strip at which secondary parts start to collapse into the overflow menu. */
private val DETAILS_MIN = 740.dp
private val COPY_MIN = 620.dp
private val FIT_MIN = 480.dp
private val ZOOM_MIN = 340.dp

/** [TopStrip] drawn inside the content (offscreen renders): nothing to mark for the window. */
private object NoTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.System
    override fun Modifier.interactive(key: String): Modifier = this
}

/**
 * The status dot, model, details, zoom controls and overflow menu; the mirror window's title bar. In the custom bar
 * it is transparent (the bar paints `surfaceContainer`) and as tall as the bar; its clickables are [interactive].
 */
@Composable
internal fun AppTitleBarScope.TopStrip(state: MirrorUiState, onZoom: (ZoomCommand) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val custom = chrome == WindowChrome.Custom
    BoxWithConstraints(Modifier.fillMaxWidth().background(if (custom) Color.Transparent else colors.surfaceContainer)) {
        val w = maxWidth
        val showDetails = state.details.isNotEmpty() && w >= DETAILS_MIN
        val showCopy = w >= COPY_MIN
        val showFit = w >= FIT_MIN
        val showZoom = w >= ZOOM_MIN
        val overflow = !showCopy || !showFit || !showZoom
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (custom) 0.dp else 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dot = when (state.status) {
                SessionStatus.Running -> colors.primary
                SessionStatus.Connecting -> warnColor()
                else -> colors.error
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            // The model and the details take the room the controls leave; the controls keep their content width,
            // and the model ellipsizes after the details (secondary text) have been dropped.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    state.model,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showDetails) {
                    Text(
                        state.details,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (showZoom) ZoomControls(state, onZoom)
            if (showFit) {
                FilterChip(
                    selected = state.fit,
                    onClick = { onZoom(if (state.fit) ZoomCommand.Actual else ZoomCommand.Fit) },
                    label = { Text("Fit to window", maxLines = 1, softWrap = false) },
                    modifier = Modifier.interactive("fit"),
                )
            }
            if (showCopy) CopyButton(text = { state.command }, label = "Copy command", modifier = Modifier.interactive("copy"))
            if (overflow) OverflowMenu(state, onZoom, showZoom = showZoom, showFit = showFit, showCopy = showCopy)
        }
    }
}

/** "−", the zoom percent (a click toggles Fit and 100 %) and "+". */
@Composable
private fun AppTitleBarScope.ZoomControls(state: MirrorUiState, onZoom: (ZoomCommand) -> Unit) {
    val scale = state.zoom ?: state.fittedScale
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onZoom(ZoomCommand.Out) }, Modifier.interactive("zoom-out").size(32.dp)) {
            Text("−", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
        TextButton(
            onClick = { onZoom(if (state.fit) ZoomCommand.Actual else ZoomCommand.Fit) },
            Modifier.interactive("zoom-percent").widthIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            Text(Zoom.label(scale), maxLines = 1, softWrap = false, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = { onZoom(ZoomCommand.In) }, Modifier.interactive("zoom-in").size(32.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
    }
}

/** The "⋮" menu holding what the top strip has no room for. */
@Composable
private fun AppTitleBarScope.OverflowMenu(state: MirrorUiState, onZoom: (ZoomCommand) -> Unit, showZoom: Boolean, showFit: Boolean, showCopy: Boolean) {
    var open by remember { mutableStateOf(false) }
    val copy = rememberCopyAction()
    Box {
        IconButton(onClick = { open = true }, Modifier.interactive("overflow").size(32.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(text: String, command: ZoomCommand? = null, onClick: () -> Unit = {}) {
                DropdownMenuItem(
                    text = { Text(text, maxLines = 1, softWrap = false) },
                    onClick = { open = false; if (command != null) onZoom(command) else onClick() },
                )
            }
            if (!showZoom) {
                item("Zoom in", ZoomCommand.In)
                item("Zoom out", ZoomCommand.Out)
            }
            if (!showFit) {
                item(if (state.fit) "Fit to window ✓" else "Fit to window", ZoomCommand.Fit)
                item("Actual size (100%)", ZoomCommand.Actual)
            }
            if (!showCopy) {
                item("Copy command") { copy(state.command) }
            }
        }
    }
}

/** Pixels a Shift + wheel notch pans the zoomed stage. */
private const val PAN_PX_PER_NOTCH = 60f

@Composable
private fun Stage(
    state: MirrorUiState,
    frame: State<VideoFrame?>,
    latestFrame: () -> VideoFrame?,
    onPointer: (PointerEvent) -> Unit,
    onViewSize: (IntSize) -> Unit,
    clickIndicator: ClickIndicator?,
) {
    val pointer by rememberUpdatedState(onPointer)
    // Redraws every frame while a click dot fades out.
    var now by remember { mutableLongStateOf(monotonicMillis()) }
    val releaseTime = clickIndicator?.releaseTime
    LaunchedEffect(clickIndicator, releaseTime) {
        if (clickIndicator == null) return@LaunchedEffect
        do {
            withFrameNanos { now = monotonicMillis() }
        } while (clickIndicator.isFading(now))
    }
    val dotColor = MaterialTheme.colorScheme.primary
    val dotRing = MaterialTheme.colorScheme.onPrimary
    fun DrawScope.drawClickDot() {
        val dot = clickIndicator?.state(now) ?: return
        val radius = 12.dp.toPx()
        drawCircle(dotColor.copy(alpha = 0.4f * dot.alpha), radius = radius, center = dot.position)
        // A thin light ring keeps the dot visible on content close to the primary color.
        drawCircle(dotRing.copy(alpha = 0.6f * dot.alpha), radius = radius, center = dot.position, style = Stroke(1.5.dp.toPx()))
    }
    // Set by the zoomed stage: pans it by a wheel delta and tells whether it could.
    val pan = remember { arrayOfNulls<(Offset) -> Boolean>(1) }
    val input = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                // Shift + wheel pans a zoomed stage that is larger than the window; any other wheel goes to the device.
                val scrolled = event.changes.firstOrNull()?.scrollDelta
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isShiftPressed && scrolled != null &&
                    pan[0]?.invoke(scrolled) == true
                ) {
                    event.changes.forEach { it.consume() }
                    continue
                }
                pointer(event)
                event.changes.forEach { it.consume() }
            }
        }
    }.then(if (state.captureHint != null) Modifier.pointerHoverIcon(blankPointerIcon) else Modifier)
    val orientation = state.orientation
    val fs = state.frameSize?.let(orientation::orientedSize)
    val zoom = state.zoom
    if (zoom == null || fs == null) {
        SideEffect { pan[0] = null }
        Canvas(Modifier.fillMaxSize().onSizeChanged(onViewSize).then(input)) {
            frame.value // subscribe: a new frame redraws
            drawLatestFrame(latestFrame, FilterQuality.Low, orientation) { f ->
                InputMapper.contentRect(orientation.orientedSize(IntSize(f.width, f.height)), Rect(0f, 0f, size.width, size.height), state.stretch)
            }
            drawClickDot()
        }
    } else {
        // Zoomed: the canvas is the image at that scale (one frame pixel is `zoom` screen pixels), centred in the
        // stage while smaller and scrolled by the scroll bars while larger. Pointer positions are canvas-local, so
        // the scroll offset is already in them and the mapper's view rect is the canvas itself.
        val density = LocalDensity.current
        val v = rememberScrollState()
        val h = rememberScrollState()
        val panScope = rememberCoroutineScope()
        SideEffect {
            pan[0] = { d ->
                val can = v.maxValue > 0 || h.maxValue > 0
                if (can) panScope.launch {
                    h.scrollBy(d.x * PAN_PX_PER_NOTCH)
                    v.scrollBy(d.y * PAN_PX_PER_NOTCH)
                }
                can
            }
        }
        val onSurface = MaterialTheme.colorScheme.onSurface
        val barStyle = LocalScrollbarStyle.current.copy(
            thickness = 10.dp,
            unhoverColor = onSurface.copy(alpha = 0.38f),
            hoverColor = onSurface.copy(alpha = 0.7f),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stageW = maxWidth
            val stageH = maxHeight
            Box(
                Modifier.fillMaxSize().verticalScroll(v).horizontalScroll(h),
            ) {
                Box(Modifier.widthIn(min = stageW).heightIn(min = stageH), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier.size(with(density) { DpSize((fs.width * zoom).toDp(), (fs.height * zoom).toDp()) })
                            .onSizeChanged(onViewSize).then(input),
                    ) {
                        frame.value // subscribe: a new frame redraws
                        val quality = if (zoom >= 1f) FilterQuality.None else FilterQuality.Low
                        drawLatestFrame(latestFrame, quality, orientation) { Rect(0f, 0f, size.width, size.height) }
                        drawClickDot()
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(v), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = barStyle)
            HorizontalScrollbar(rememberScrollbarAdapter(h), Modifier.align(Alignment.BottomCenter).fillMaxWidth(), style = barStyle)
        }
    }
}

/**
 * Draws the latest frame inside [VideoFrame.use], so a released bitmap is never touched. A frame released between
 * the read and the draw (a newer one was just published) is retried with the newer one.
 *
 * [placement] gives the rectangle of the image as displayed; with an [orientation] the frame is drawn flipped
 * (first) and rotated about that rectangle's centre, like `SDL_RenderTextureRotated` in v5.0 `screen.c`.
 */
private fun DrawScope.drawLatestFrame(
    latest: () -> VideoFrame?,
    quality: FilterQuality,
    orientation: DisplayOrientation,
    placement: (VideoFrame) -> Rect,
) {
    repeat(3) {
        val f = latest() ?: return
        val drawn = f.use { bitmap ->
            val dst = placement(f)
            // The un-rotated frame rectangle, centred on dst.
            val w = if (orientation.swaps) dst.height else dst.width
            val h = if (orientation.swaps) dst.width else dst.height
            val center = dst.center
            val offset = IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt())
            val size = IntSize(w.roundToInt(), h.roundToInt())
            withTransform({
                if (orientation.quarterTurns != 0) rotate(orientation.degrees, center)
                if (orientation.flip) scale(-1f, 1f, center)
            }) {
                drawImage(bitmap.asComposeImageBitmap(), dstOffset = offset, dstSize = size, filterQuality = quality)
            }
        }
        if (drawn != null) return
    }
}

@Composable
private fun DisconnectedScrim(state: MirrorUiState, onReconnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp).width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Disconnected", style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 4)
                }
                if (!state.canReconnect) {
                    Text("Waiting for the device…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onReconnect, enabled = state.canReconnect) { Text("Reconnect") }
            }
        }
    }
}

private val toolbarIcons: Map<MirrorAction, ImageVector> = mapOf(
    MirrorAction.Back to Icons.AutoMirrored.Filled.ArrowBack,
    MirrorAction.Home to Icons.Outlined.Circle,
    MirrorAction.Recents to Icons.Outlined.CropSquare,
    MirrorAction.VolumeUp to Icons.AutoMirrored.Filled.VolumeUp,
    MirrorAction.VolumeDown to Icons.AutoMirrored.Filled.VolumeDown,
    MirrorAction.Rotate to Icons.Filled.ScreenRotation,
    MirrorAction.Power to Icons.Filled.PowerSettingsNew,
    MirrorAction.QuickActions to Icons.Filled.Bolt,
    MirrorAction.Screenshot to Icons.Filled.PhotoCamera,
    MirrorAction.Record to Icons.Filled.FiberManualRecord,
    MirrorAction.InstallApk to Icons.Filled.Android,
    MirrorAction.PushFile to Icons.Filled.UploadFile,
)

@Composable
private fun SideToolbar(state: MirrorUiState, onAction: (MirrorAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(colors.surfaceContainer).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val groups = listOf(
            listOf(MirrorAction.Back, MirrorAction.Home, MirrorAction.Recents),
            listOf(MirrorAction.VolumeUp, MirrorAction.VolumeDown, MirrorAction.Rotate, MirrorAction.Power, MirrorAction.QuickActions),
            listOf(MirrorAction.Screenshot, MirrorAction.Record),
            listOf(MirrorAction.InstallApk, MirrorAction.PushFile),
        )
        groups.forEachIndexed { i, group ->
            if (i > 0) HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = colors.outlineVariant)
            group.forEach { action ->
                val recording = action == MirrorAction.Record && state.recordingLabel != null
                val enabled = when (action) {
                    MirrorAction.Screenshot -> state.screenshotEnabled
                    MirrorAction.Record -> state.recordEnabled
                    MirrorAction.InstallApk, MirrorAction.PushFile -> state.transferEnabled
                    MirrorAction.QuickActions -> state.quickActions.enabled
                    else -> state.actionsEnabled
                }
                ToolbarButton(
                    action, enabled,
                    label = if (recording) "Stop recording" else action.label,
                    tint = if (recording) colors.error else null,
                    selected = action == MirrorAction.QuickActions && state.quickActions.open,
                ) { onAction(action) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButton(action: MirrorAction, enabled: Boolean, label: String, tint: Color?, selected: Boolean = false, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        val colors = MaterialTheme.colorScheme
        val buttonColors = if (selected) {
            IconButtonDefaults.iconButtonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
        } else {
            IconButtonDefaults.iconButtonColors()
        }
        IconButton(onClick = onClick, enabled = enabled, colors = buttonColors) {
            Icon(
                toolbarIcons.getValue(action),
                contentDescription = label,
                tint = if (enabled && tint != null) tint else LocalContentColor.current,
            )
        }
    }
}

@Composable
private fun StatusLine(state: MirrorUiState) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surfaceContainer)) {
        // Secondary readouts go first when narrow: the encoder, then the "shown at" suffix, then the fps.
        val showCodec = maxWidth >= 600.dp
        val showMax = maxWidth >= 480.dp
        val showFps = maxWidth >= 300.dp
        val transferMax = maxWidth * 0.6f
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = MaterialTheme.typography.labelMedium
            state.recordingLabel?.let {
                Text(it, style = style, color = colors.error, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            state.transferLabel?.let {
                // Measured before the weighted readouts, so the frame size ellipsizes first; capped for long names.
                Row(Modifier.widthIn(max = transferMax), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(it, Modifier.weight(1f, fill = false), style = style, color = colors.primary, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            val fs = state.frameSize
            val size = if (fs == null) "—" else "${fs.width} × ${fs.height}" + (if (showMax) state.maxSize?.let { " shown at $it max" } ?: "" else "")
            Text(size, Modifier.weight(1f, fill = false), style = style, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (showCodec) Text("${state.codec} · ${state.bitRate}", style = style, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
            if (showFps) Text("%.1f fps".format(state.fps), style = style, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
            state.audioNote?.let {
                Text(it, Modifier.weight(1f, fill = false), style = style, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            state.captureHint?.let {
                Text(it, Modifier.weight(1f, fill = false), style = style, color = colors.primary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
