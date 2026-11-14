package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeWindow
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ARRANGE_LABEL
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeButton
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeMenu
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeStrip
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.MirrorWindows
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.WindowArranger
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.rememberArrangeStrip
import com.programmersbox.scrcpyviewer.ui.mirror.follow.ArrangePlace
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.WindowEntry
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
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
import com.programmersbox.scrcpyviewer.ui.mirror.files.FilesTab
import com.programmersbox.scrcpyviewer.ui.mirror.files.rememberFilesBrowser
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.links.LinksState
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatWindow
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.programmersbox.scrcpyviewer.files.DragCache
import kotlin.concurrent.thread
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.mapState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.scrcpyviewer.actions.ActionInbox
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.common.PcClipboard
import org.koin.compose.koinInject
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowRole
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowStrip
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowedTouchRings
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowerChip
import com.programmersbox.scrcpyviewer.ui.mirror.follow.InputFollowing
import com.programmersbox.scrcpyviewer.ui.mirror.follow.SendInputButton
import com.programmersbox.scrcpyviewer.ui.mirror.follow.SendInputMenu
import com.programmersbox.scrcpyviewer.ui.mirror.follow.followGlow
import com.programmersbox.scrcpyviewer.ui.mirror.follow.followRunners
import com.programmersbox.scrcpyviewer.ui.mirror.follow.rememberFollowStrip
import com.programmersbox.scrcpyviewer.ui.mirror.follow.rememberWindowFollow
import com.programmersbox.scrcpyviewer.ui.mirror.follow.sendInputLabel
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FOLLOWER_FIXED
import com.programmersbox.scrcpyviewer.ui.mirror.follow.MODEL_KEEP
import com.programmersbox.scrcpyviewer.ui.mirror.follow.SendPlace
import com.programmersbox.scrcpyviewer.ui.mirror.follow.ChipWidths
import com.programmersbox.scrcpyviewer.ui.mirror.follow.StripMins
import com.programmersbox.scrcpyviewer.ui.mirror.follow.rememberChipWidths
import com.programmersbox.scrcpyviewer.ui.mirror.follow.stripEnter
import com.programmersbox.scrcpyviewer.ui.mirror.follow.stripExit
import com.programmersbox.scrcpyviewer.ui.mirror.follow.stripModelStyle
import com.programmersbox.scrcpyviewer.ui.mirror.follow.stripRoom
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.LocalNeonGlow
import com.programmersbox.scrcpyviewer.ui.theme.drawNeonFrame
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import dev.nucleusframework.application.LocalNucleusApplicationScope
import com.programmersbox.scrcpyviewer.ui.window.disableMacAppearAnimation
import com.programmersbox.scrcpyviewer.ui.window.setMacAlpha
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.common.ImageClipboard
import com.programmersbox.scrcpyviewer.ui.common.openExternally
import com.programmersbox.scrcpyviewer.ui.common.revealInFolder
import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import com.programmersbox.scrcpyviewer.ui.mirror.files.ComposeDragOutStarter
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotFlash
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotFlashState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnail
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.finishScreenshot

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

    /** The session ended (or failed): the stage shows its last frame, greyed, under the Disconnected card. */
    val disconnected: Boolean get() = status == SessionStatus.Disconnected || status == SessionStatus.Failed
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
 * pastes the computer clipboard through `SetClipboard(paste=true)` (or as text with `legacy-paste`). Keys typed with
 * focus in the quick actions sheet stay in the sheet ([keyTarget]); a click outside the sheet gives them back.
 *
 * `keyboard=uhid` and `mouse=uhid` create the HID devices on the session ([HidKeyboard], [HidMouse]) and send HID
 * reports instead of key/text/touch injections. The UHID mouse is relative, so the window captures the pointer
 * (see [MouseCapture]): the cursor is hidden and moved back to the video's centre ([NativeCursorControl]), and motion
 * is sent as deltas. Where the cursor cannot be moved (Wayland) there is no capture: a snackbar says so, and the
 * deltas still flow while the pointer is over the video.
 *
 * The top strip ([TopStrip]) is the window's title bar ([AppWindow]): window controls at its edge, empty
 * space drags the window.
 */
@Composable
fun MirrorWindow(
    serial: String,
    session: ScrcpySession,
    controller: MirrorController,
    theme: AppTheme,
    /** `Settings.captureDir`: where screenshots and recordings started from the toolbar go. */
    captureDir: String,
    /** `Settings.afterScreenshot`: a toolbar screenshot shows the thumbnail, or copies the image. */
    afterScreenshot: AfterScreenshot = AfterScreenshot.Thumbnail,
    /** `Settings.animateMirrorWindows`, read when the window opens: grow from the Mirror button, wake the screen. */
    animate: Boolean = false,
    /** Runs `adb install` / `adb push` for dropped files and the Install APK… / Push file… buttons; null disables them. */
    adb: AdbClient? = null,
    /** Null: the OS pickers, with failures shown in this window's snackbar. */
    fileDialogs: TransferFileDialogs? = null,
    /** "+ New action", "Edit…" and "Manage actions…" ask the main window to open the Actions screen through this. */
    actionsNavigation: ActionsNavigation = koinInject(),
    screenGeometry: ScreenGeometry = koinInject(),
    pcClipboard: PcClipboard = koinInject(),
    /** Messages about action files AI tools dropped in the inbox show in every mirror window. */
    inbox: ActionInbox = koinInject(),
    /** The app-wide "Send input to…" link between mirror windows. */
    following: InputFollowing = koinInject(),
    /** The open mirror windows the arranger moves; this window joins once it has its first frame and size. */
    mirrorWindows: MirrorWindows = koinInject(),
    /** Arrange mirror windows: the title bar's Arrange button applies layouts through it. */
    arranger: WindowArranger = koinInject(),
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
    // Only whether there is a frame is read here: reading the frame itself would recompose this whole function (and
    // the window content it rebuilds) on every decoded frame. The window content collects the frame below, and only
    // the stage's canvas redraws on it.
    val hasFrame by remember(session) { session.frames.mapState { it != null } }.collectAsState()
    val reconnectable by controller.reconnectable.collectAsState()
    val recording by session.recording.collectAsState()
    val currentCaptureDir by rememberUpdatedState(captureDir)
    val currentAfterScreenshot by rememberUpdatedState(afterScreenshot)
    val currentModel by rememberUpdatedState(model)
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    fun notify(message: String) {
        uiScope.launch { snackbar.showSnackbar(message) }
    }
    val transferDialogs = fileDialogs ?: remember { NativeTransferFileDialogs(nativeFileDialogs(::notify)) }
    // Results of this device's sessions (a previous one too, after Reconnect); the main window shows the others.
    LaunchedEffect(serial) {
        controller.recordingResults.filter { it.serial == serial }.collect { notify(recordResultMessage(it.result)) }
    }
    LaunchedEffect(inbox) { inbox.messages.collect { notify(it) } }
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

    // The thumbnail after a screenshot; it owns its bitmaps, released when the window closes.
    val thumbnail = remember { ScreenshotThumbnailState(uiScope) }
    DisposableEffect(thumbnail) { onDispose(thumbnail::dispose) }
    val screenshotDragStarter = remember { ComposeDragOutStarter() }
    /** The flash over the video, once per screenshot. Out here, so the content re-entering does not replay it. */
    val screenshotFlash = remember { ScreenshotFlashState() }

    /**
     * Copies the frame now (inside VideoFrame.use), encodes and writes the PNG on the IO dispatcher, then shows the
     * thumbnail or copies the image, by `Settings.afterScreenshot` ([finishScreenshot]).
     */
    fun takeScreenshot() {
        val bitmap = Screenshot.copyLatest { session.frames.value }
        if (bitmap == null) {
            notify("No frame to capture yet")
            return
        }
        screenshotFlash.flash()
        // Taken now, in screenshot order: saves run in parallel and may finish out of order; only the latest shows.
        val ticket = thumbnail.begin()
        uiScope.launch {
            finishScreenshot(
                bitmap, currentAfterScreenshot, thumbnail,
                save = { withContext(Dispatchers.IO) { Screenshot.save(bitmap, Paths.get(currentCaptureDir), currentModel) } },
                copy = { withContext(Dispatchers.IO) { ImageClipboard.copyPng(it) } }, // blocks up to 5 s
                notify = ::notify,
                ticket = ticket,
            )
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
    // Files dragged out of the Files tab wait in the drag cache; the window takes them with it when it closes.
    DisposableEffect(serial) {
        onDispose { thread(name = "drag-cache-delete") { DragCache().deleteSession(serial) } }
    }
    // Read when a picker closes: a Reconnect meanwhile replaces the transfers (pickAndStart).
    val currentTransfers by rememberUpdatedState(transfers)
    val picker = rememberPickerGuard()
    val quickActions = rememberQuickActions(
        serial, session, adb, ::notify, onManage = actionsNavigation::open, onNewAction = actionsNavigation::openNew, title = model,
        followers = {
            followRunners(serial, following.state.value, controller.open.value, adb) { s ->
                devices.firstOrNull { it.serial == s }?.let(::deviceTitle) ?: s
            }
        },
    )
    // Made the first time the Files tab shows, so a window that never opens it never lists a folder. A plain holder.
    val filesWanted = remember { BooleanArray(1) }
    quickActions.uiState.let { if (it.open && it.tab == SheetTab.Files) filesWanted[0] = true }
    val filesBrowser = if (filesWanted[0]) rememberFilesBrowser(serial, session, adb, transfers) else null
    // Read when the picker closes, like currentTransfers.
    val currentFilesBrowser by rememberUpdatedState(filesBrowser)
    val filesTab: (@Composable () -> Unit)? = filesBrowser?.let { browser ->
        remember(browser) {
            @Composable {
                FilesTab(browser, onPushFiles = {
                    picker.launch(uiScope) {
                        val dir = browser.path
                        val paths = transferDialogs.chooseFiles(dir)
                        currentFilesBrowser?.push(paths, dir)
                    }
                }, onPullTo = { names ->
                    val from = browser.path
                    picker.launch(uiScope) {
                        val folder = transferDialogs.chooseFolder("Pull to") ?: return@launch
                        // Only while the names' folder is still open: a pull reads them from the open folder.
                        currentFilesBrowser?.takeIf { it === browser && it.path == from }?.pull(names, folder)
                    }
                })
            }
        }
    }
    val sheetFocus = remember { SheetFocus() }

    val orientation = remember(values) { DisplayOrientation.forOptions(values) }
    // The window is sized for the image as displayed (turned by display-orientation).
    val displayedSize = frameSize?.let(orientation::orientedSize)
    val renderFit = e["render-fit"]
    // Per window, not persisted: null is Fit, else the scale (1 = 100 %). A window starts with Fit off (see
    // Zoom.initialZoom): until the first frame has fitted it is null, then it takes the fitted scale once.
    var zoom by remember { mutableStateOf<Float?>(if (renderFit == "unscaled") 1f else null) }
    var zoomPending by remember { mutableStateOf(renderFit == null) }
    val fit = zoom == null
    val stretch = renderFit == "stretched"
    val aspectLock = "no-window-aspect-ratio-lock" !in e
    val explicitSize = e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } != null ||
        e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } != null

    val requestedX = e["window-x"]?.toIntOrNull()
    val requestedY = e["window-y"]?.toIntOrNull()
    val positioned = requestedX != null || requestedY != null
    val requestedWidth = e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } ?: 480
    val requestedHeight = e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } ?: 900
    // Read once as the window opens: the setting, the OS's reduce motion, and the Mirror button that was pressed.
    // The window starts at the button's bounds and grows to its own (centered unless the options place it).
    val motionOn = remember { animate && !systemReducesMotion() }
    val zoomFrom = remember { if (motionOn && "fullscreen" !in e) MirrorOrigins.take() else null }
    val zoomTo = remember {
        zoomFrom?.let {
            zoomTarget(
                if (positioned) requestedX ?: 100 else null, if (positioned) requestedY ?: 100 else null,
                requestedWidth, requestedHeight, usableScreenBounds(it, screenGeometry.screens()),
            )
        }
    }
    val windowState = rememberWindowState(
        placement = if ("fullscreen" in e) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        position = when {
            zoomFrom != null -> WindowPosition(zoomFrom.x.dp, zoomFrom.y.dp)
            !positioned -> WindowPosition.PlatformDefault
            else -> WindowPosition((requestedX ?: 100).dp, (requestedY ?: 100).dp)
        },
        size = if (zoomFrom != null) DpSize(zoomFrom.width.dp, zoomFrom.height.dp) else DpSize(requestedWidth.dp, requestedHeight.dp),
    )
    // The device screen: 0 off, 1 on (wakeFrame). Null when the window does not animate.
    val wake = remember { if (motionOn) Animatable(0f) else null }
    val readWake = remember(wake) { wake?.let { w -> { w.value } } }
    // True while the window's bounds animate: the first-frame sizing and the aspect lock wait.
    var zooming by remember { mutableStateOf(zoomFrom != null) }
    // True while the arranger moves the window (Arrange mirror windows): the same effects wait, as for the zoom.
    val arranging = remember { mutableStateOf(false) }
    // The window's bounds are someone else's (its open / close animation, or the arranger): sizing and the lock wait.
    val held = zooming || arranging.value
    var closing by remember { mutableStateOf(false) }
    // A growing window stays hidden until its content is composed, then shows at the button and grows at once.
    var shown by remember { mutableStateOf(zoomFrom == null) }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // The visible stage in window pixels: the zoomed canvas can be larger than it.
    var stageBounds by remember { mutableStateOf<Rect?>(null) }
    // The quick actions sheet in root pixels while it shows (a plain holder, read by the window's file drop).
    val sheetBounds = remember { arrayOfNulls<Rect>(1) }
    val overSheet = remember { { p: Offset -> sheetBounds[0]?.contains(p) == true } }
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
    // Set from the window's focus; the window (and its handle) only exists inside AppWindow {} below.
    var windowFocused by remember { mutableStateOf(false) }
    var windowHandle by remember { mutableStateOf<MirrorWindowHandle?>(null) }
    // The video view's top-left in window pixels (it moves as a zoomed stage scrolls); read by the capture only.
    val viewOrigin = remember { arrayOf(Offset.Zero) }
    val capture = remember(session) {
        hidMouse?.let {
            WindowPointerCapture(
                MouseCapture(), mapper, hidKeyboard, send, isFocused = { windowHandle?.focused?.value == true },
                onUnavailable = { notify("Mouse capture is not available: the pointer cannot be moved on this system") },
            )
        }
    }
    capture?.let { DisposableEffect(it) { onDispose(it::dispose) } }
    val captured = capture?.active?.collectAsState()?.value == true
    // show-touches only shows physical touches on the device; mark the injected clicks in the window (not for the
    // captured UHID mouse, whose pointer is hidden and has no position on the frame).
    val clickIndicator = remember(session) {
        if ("show-touches" in e && capture == null) ClickIndicator(mapper::displayedRect) else null
    }
    // Send input to other windows: the leader tees its pointer (not with the UHID mouse), toolbar and key messages to
    // its followers; a follower shows the chip, the glowing border and a click ring where each followed tap lands.
    val follow = rememberWindowFollow(serial, following, controller.open)
    val reduceMotion = remember { systemReducesMotion() }
    val followStrip = rememberFollowStrip(
        serial, following, controller.open, follow,
        devices = devices,
        captureOn = capture != null,
        ownFrame = frameSize,
        reduceMotion = reduceMotion,
        control = control,
    )
    val arrangeStrip = rememberArrangeStrip(serial, arranger, controller.open, reduceMotion)
    val followRings = remember(session) { ClickIndicator(mapper::displayedRect) }
    FollowedTouchRings(serial, following, followRings, mapper::toView)

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
        screenshotEnabled = hasFrame,
        recordEnabled = !recordStarting && (recording != null || status == SessionStatus.Running),
        recordingLabel = if (recording != null) recordingElapsed ?: recordingLabel(0) else null,
        orientation = orientation,
        transferEnabled = transfers.enabled,
        transferLabel = transfers.progressLabel,
        dropHint = transfers.hint,
        quickActions = quickActions.uiState.copy(filesTransfersRunning = transfers.runningCount),
    )

    // Animated: the screen collapses to a line, the window shrinks into the device's Mirror button where it is now
    // (or a little, in place, when the main window does not show it), then it closes.
    val requestClose: () -> Unit = close@{
        val w = windowHandle
        if (wake == null || w == null) return@close controller.close(serial)
        if (closing) return@close
        closing = true
        uiScope.launch {
            try {
                wake.animateTo(0f, tween(SCREEN_SLEEP_MILLIS, easing = EmphasizedAccelerate))
                if (windowState.placement == WindowPlacement.Floating) {
                    zooming = true
                    val to = MirrorOrigins.buttonOnScreen(serial) ?: shrunkAroundCenter(w.bounds, MIRROR_SHRINK_IN_PLACE)
                    animateWindowBounds(w, to, MIRROR_ZOOM_OUT_MILLIS, grow = false, screenGeometry)
                }
            } finally {
                controller.close(serial)
            }
        }
    }

    val currentQuickActions by rememberUpdatedState(quickActions)
    // The device's keys: this window's key events before its content sees them (the sheet's fields get theirs back).
    val keys = remember(session) {
        MirrorKeys(
            mapper = mapper,
            hidKeyboard = hidKeyboard,
            send = send,
            clipboardText = pcClipboard::text,
            scope = uiScope,
            sheetOpen = { currentQuickActions.open },
            sheetFocused = { sheetFocus.focused },
            closeSheet = { currentQuickActions.close() },
            applyZoom = { currentApplyZoom(it) },
            captureKey = { id, code, location -> capture?.key(id, code, location) == true },
            captureFocus = { if (it) capture?.focusGained() else capture?.focusLost() },
            noControl = "no-control" in e,
            keyboardDisabled = e["keyboard"] == "disabled",
            legacyPaste = "legacy-paste" in e,
            followerSend = follow::send,
            followerKeyRepeat = "no-key-repeat" !in e,
        ).also { follow.heldKeyUp = it::followerHeldKeyUp }
    }

    LocalNucleusApplicationScope.current.AppWindow(
        onCloseRequest = requestClose,
        visible = shown,
        title = mirrorTitle(model, serial, values),
        theme = theme,
        state = windowState,
        alwaysOnTop = "always-on-top" in e,
        icon = AppIcon.painter,
        titleBarHeight = 40.dp,
        onPreviewKeyEvent = keys::onKey,
        titleBar = {
            // The overflow menu's Copy command shows its "Copied" message in the window's snackbar.
            CompositionLocalProvider(LocalSnackbarHostState provides snackbar) { TopStrip(state, followStrip, arrangeStrip, applyZoom) }
        },
    ) {
        // Every move, resize, focus and native call on the window goes through this.
        val handle = remember(nucleusWindow) { TaoMirrorWindowHandle(nucleusWindow, screenGeometry) }
        LaunchedEffect(serial) {
            controller.focusRequests.filter { it == serial }.collect { handle.toFront() }
        }
        if ("print-fps" in e) {
            LaunchedEffect(session) { session.fps.collect { if (it > 0) println("[$serial] %.1f fps".format(it)) } }
        }
        // A window that gained focus but closed before registering must not stay the "last focused".
        DisposableEffect(serial) { onDispose { mirrorWindows.clearFocused(serial) } }
        LaunchedEffect(session, handle) {
            windowHandle = handle
            handle.focused.collect {
                windowFocused = it
                // The menu bar's "Focus the front window" arranges around the window focused last.
                if (it) mirrorWindows.focused(serial)
            }
        }
        // Every gain and loss, not the merged state: each loss releases what the device holds.
        LaunchedEffect(keys, handle) {
            handle.focusEvents.collect {
                keys.onFocusChanged(it)
                if (!it) follow.releaseAll() // a release in another window never reaches the followers either
            }
        }
        DisposableEffect(keys) {
            // Keys held for the device when focus moves into the sheet would never see their release: let them go.
            sheetFocus.onGained = keys::onSheetFocusGained
            onDispose { sheetFocus.onGained = {} }
        }

        val density = LocalDensity.current

        var stage by remember { mutableStateOf(IntSize.Zero) }
        var autoSized by remember { mutableStateOf(explicitSize) }
        var lastStage by remember { mutableStateOf(IntSize.Zero) }
        var lastFrame by remember { mutableStateOf<IntSize?>(null) }
        // The native window's size when the lock last looked (a plain holder, read only in the effect below).
        val lastWindowSize = remember { arrayOf(IntSize.Zero) }
        val lastFit = remember { BooleanArray(1) { fit } } // the same, for Fit
        // The stage's width (px) while the window grows or shrinks for the quick actions sheet (see the sheet effect).
        var pinnedStage by remember { mutableStateOf<Int?>(null) }
        // True for a moment after the sizing effect below resizes the window (a new frame size, Fit back on): the
        // arranger must not take that resize for the user's.
        val appResizing = remember { mutableStateOf(false) }
        val appResizeHold = remember { arrayOfNulls<Job>(1) }
        // Initial size from the first frame (fit to the screen), then the aspect ratio lock on every resize.
        // Keyed on the pin clearing too: a height the user dragged during a sheet animation is corrected once it ends.
        LaunchedEffect(stage, displayedSize, fit, pinnedStage == null, held) {
            // Sized once the window has grown or been arranged (or not at all while it shrinks).
            if (held) return@LaunchedEffect
            val fs = displayedSize ?: return@LaunchedEffect
            if (stage == IntSize.Zero || windowState.placement != WindowPlacement.Floating) return@LaunchedEffect
            if (autoSized) {
                // The sheet changed the stage (its bar, or its open, close or tab switch): the new stage is the base
                // the next resize is measured from, right away, so a resize within the settle delay is not measured
                // from the stage before the sheet changed it (and counted as the user's). The sheet only changes the
                // stage's width: while it is pinned, the base keeps its height, so a height the user dragged meanwhile
                // is still the user's change once the pin clears.
                val windowResized = handle.size != lastWindowSize[0]
                val pinned = pinnedStage != null
                if (stageChangeIsSheets(pinned, windowResized, lastFrame, fs, lastFit[0], fit)) {
                    lastStage = if (pinned && lastStage != IntSize.Zero) IntSize(stage.width, lastStage.height) else stage
                    return@LaunchedEffect
                }
                // Correcting the size on every step of a drag fights the drag (the window jitters): restarted by each
                // new stage size, this only goes on once the size has held still.
                delay(ASPECT_SETTLE_MS)
            }
            val windowSize = handle.size
            val refit = fit && !lastFit[0]
            val lockNeeded = aspectLockNeeded(windowSize != lastWindowSize[0], lastFrame, fs, lastFit[0], fit)
            lastWindowSize[0] = windowSize
            lastFit[0] = fit
            val target: IntSize? = when {
                !autoSized -> {
                    autoSized = true
                    val bounds = handle.screen()?.workArea ?: java.awt.Rectangle(0, 0, 1280, 800)
                    val chromeW = windowState.size.width.value * density.density - stage.width
                    val chromeH = windowState.size.height.value * density.density - stage.height
                    val maxW = bounds.width * density.density * 0.9f - chromeW
                    val maxH = bounds.height * density.density * 0.9f - chromeH
                    val scale = min(1f, min(maxW / fs.width, maxH / fs.height))
                    IntSize((fs.width * scale).roundToInt(), (fs.height * scale).roundToInt())
                }
                fit && aspectLock && lockNeeded -> {
                    // Never taller than the screen has room for below the window's top, less its chrome (title bar).
                    val chromeH = handle.size.height * density.density - stage.height
                    val area = handle.screen()
                        ?: ScreenArea(java.awt.Rectangle(0, 0, 1280, 800), java.awt.Rectangle(0, 0, 1280, 800))
                    val (usable, below) = screenRoom(handle.bounds, area)
                    val maxH = aspectMaxHeight(
                        roomBelow = (below * density.density - chromeH).roundToInt(),
                        usable = (usable * density.density - chromeH).roundToInt(),
                        current = lastStage.height,
                    )
                    aspectLockedStage(lastStage, stage, lastFrame, fs, maxH, refit)
                }
                else -> null
            }
            if (zoomPending) {
                // The image as Fit shows it once the window has its size: leave Fit at that scale, once.
                zoomPending = false
                val shown = target ?: stage
                zoom = Zoom.initialZoom(renderFit, min(shown.width.toFloat() / fs.width, shown.height.toFloat() / fs.height))
            }
            lastFrame = fs
            lastStage = target ?: stage
            if (target != null && (abs(target.width - stage.width) > 2 || abs(target.height - stage.height) > 2)) {
                val dw = (target.width - stage.width) / density.density
                val dh = (target.height - stage.height) / density.density
                appResizing.value = true
                appResizeHold[0]?.cancel()
                // On uiScope: this effect restarts as soon as the stage takes the new size.
                appResizeHold[0] = uiScope.launch {
                    delay(APP_RESIZE_HOLD_MS)
                    appResizing.value = false
                }
                windowState.size = DpSize(windowState.size.width + dw.dp, windowState.size.height + dh.dp)
            }
        }

        // The live aspect lock (macOS: AppKit holds the ratio while the user drags, see TaoMirrorWindowHandle). Set from
        // the stage once it has held still, so the chrome around it (title bar, toolbars, an open sheet) is measured
        // against a window of the same moment; cleared at once while the sheet animates, with Fit off, without the
        // lock option, or when not floating. The settle correction above still fits new frames and Fit coming back
        // on, and is the whole lock where there is no live one (Windows, Linux: it corrects once a resize settles).
        LaunchedEffect(handle, stage, displayedSize, fit, aspectLock, pinnedStage == null, held, windowState.placement) {
            val fs = displayedSize
            val wanted = fit && aspectLock && pinnedStage == null && !held && stage != IntSize.Zero &&
                windowState.placement == WindowPlacement.Floating
            if (fs == null || !wanted) {
                handle.lockAspect(null)
                return@LaunchedEffect
            }
            delay(ASPECT_SETTLE_MS)
            val outer = handle.size
            handle.lockAspect(
                AspectLock(
                    frameWidth = fs.width,
                    frameHeight = fs.height,
                    chromeWidth = outer.width - stage.width / density.density.toDouble(),
                    chromeHeight = outer.height - stage.height / density.density.toDouble(),
                ),
            )
        }

        // Opening: grow out of the Mirror button, then wake the screen once the first frame is in and the window
        // has taken its size from it (the size jump happens while the screen is still dark).
        val sizedForWake by rememberUpdatedState(
            displayedSize != null && (autoSized || windowState.placement != WindowPlacement.Floating),
        )
        val downForWake by rememberUpdatedState(state.disconnected)

        // Arrange mirror windows: the window joins the registry once it has its first frame and its size (or is not
        // floating, where it is skipped anyway), and leaves as it closes.
        val arrangeFrame by rememberUpdatedState(displayedSize)
        LaunchedEffect(handle) {
            // After the open animation too, so a Keep tiled re-apply finds the window where it will stay.
            snapshotFlow { sizedForWake && stage != IntSize.Zero && !zooming }.first { it }
            val appSizing = MutableStateFlow(false)
            // The close animation (and any zoom) moves the window too: never the user's, so Keep tiled stays on.
            launch {
                snapshotFlow { pinnedStage != null || appResizing.value || closing || zooming }
                    .collect { appSizing.value = it }
            }
            val entry = WindowEntry(
                handle = handle,
                info = {
                    val outer = handle.size
                    ArrangeWindow(
                        serial,
                        arrangeFrame ?: IntSize(outer.width, outer.height),
                        chromeW = (outer.width - stage.width / density.density).roundToInt().coerceAtLeast(0),
                        chromeH = (outer.height - stage.height / density.density).roundToInt().coerceAtLeast(0),
                    )
                },
                floating = { windowState.placement == WindowPlacement.Floating },
                minimized = { windowState.isMinimized },
                showing = { shown && !zooming && !closing && handle.isShowing },
                arranging = arranging,
                appSizing = appSizing,
                uiContext = uiScope.coroutineContext,
            )
            mirrorWindows.register(serial, entry)
            try {
                awaitCancellation()
            } finally {
                mirrorWindows.unregister(serial, entry)
            }
        }
        if (wake != null) {
            LaunchedEffect(Unit) {
                if (zoomFrom != null && zoomTo != null) {
                    // macOS would play its own zoom as the window appears, on top of this one.
                    handle.nsWindow?.let(::disableMacAppearAnimation)
                    // A hidden window is held to the screen it was made on: macOS moves one meant for another screen
                    // to that screen's edge (and narrows it) until it is shown. So it shows see-through, is put at
                    // the button once on screen, and only then becomes visible.
                    val masked = handle.nsWindow?.let { setMacAlpha(it, 0.0) } == true
                    shown = true
                    // Compose shows the window from a job of its own: wait until it is really on screen.
                    withTimeoutOrNull(1_000) { while (!handle.isShowing) delay(8) }
                    if (handle.bounds != zoomFrom) {
                        handle.bounds = zoomFrom
                        withTimeoutOrNull(300) { while (handle.bounds != zoomFrom) delay(8) }
                    }
                    repeat(2) { withFrameNanos { } }
                    if (masked) handle.nsWindow?.let { setMacAlpha(it, 1.0) }
                    animateWindowBounds(handle, zoomTo, MIRROR_ZOOM_IN_MILLIS, grow = true, screenGeometry)
                    zooming = false
                }
                snapshotFlow { sizedForWake || downForWake }.first { it }
                if (downForWake) {
                    wake.snapTo(1f) // no frame coming: show the stage's Disconnected card as usual
                } else {
                    repeat(3) { withFrameNanos { } } // let the native window take its new size
                    wake.animateTo(1f, tween(SCREEN_WAKE_MILLIS, easing = EmphasizedDecelerate))
                }
            }
        }

        // The quick actions sheet grows the window to the right instead of taking width from the stage: by the
        // current tab's sheet width (280 dp on Actions and 400 dp on Shell by default). While it opens, closes or
        // switches tabs the stage is pinned to its width (px), so the image never changes size, and the window's right
        // edge animates to uncover or cover the panel. One setBounds per frame moves and resizes the window atomically
        // (moved left when it would leave the screen, back on close). Maximized and fullscreen windows cannot grow:
        // there the sheet takes its width from the stage. Dragging the sheet's bar never resizes the window: the stage
        // gives up (or takes back) the width, counted apart from the growth (SheetSplit), so closing shrinks the window
        // by its growth only and the stage, its pin moving along with the window, gets that width back.
        val sheetGrowth = remember { Animatable(0f) } // window units (dp) the window has grown by
        val sheetFromStage = remember { FloatArray(1) } // dp of the sheet's width the stage gave up (SheetSplit)
        val sheetShift = remember { IntArray(1) } // px the window moved left to keep the sheet on screen
        // An arranged window's place is its own: closing the sheet must not move it back by a shift from before.
        LaunchedEffect(arranging.value) { if (arranging.value) sheetShift[0] = 0 }
        val sheetOpen = state.quickActions.open
        val sheetTab = state.quickActions.tab
        val sheetTarget = sheetWidth(sheetOpen, sheetTab, state.quickActions.widths)
        val lastSheet = remember { SheetRun(false, sheetTab) }
        LaunchedEffect(sheetOpen, sheetTab, sheetTarget, held) {
            // While the window grows, shrinks or is arranged its bounds are the animation's: a base taken now would be
            // pinned back on every frame below (the window jumped to where it sat before it was placed). Runs again
            // once it ends.
            if (held) return@LaunchedEffect
            // Same open tab as the last run: only its width changed, by the drag bar.
            val resized = sheetOpen && lastSheet.open && lastSheet.tab == sheetTab
            lastSheet.open = sheetOpen
            lastSheet.tab = sheetTab
            if (windowState.placement != WindowPlacement.Floating) {
                // A maximized or fullscreen window is never resized here; forget any growth from before.
                sheetGrowth.snapTo(0f)
                sheetFromStage[0] = 0f
                sheetShift[0] = 0
                pinnedStage = null
                return@LaunchedEffect
            }
            if (resized) {
                // The bar moved: the window keeps its size, the sheet's new width comes out of the stage. If this cut
                // an open or tab switch short, the window keeps what it has grown so far.
                sheetFromStage[0] = draggedSplit(sheetGrowth.value.dp, sheetTarget).fromStage.value
                lastWindowSize[0] = handle.size // whatever the cut-short animation grew, the user did not
                pinnedStage = null
                return@LaunchedEffect
            }
            // From the window as it is now: the user may have moved or resized it while the sheet was open.
            val base = sheetBaseBounds(handle.bounds, sheetGrowth.value.roundToInt(), sheetShift[0])
            // An unknown screen sets no limit: the window grows in place.
            val screen = handle.screen()?.bounds ?: java.awt.Rectangle(base.x, base.y, Int.MAX_VALUE / 4, base.height)
            val screenRight = screen.x + screen.width
            val split = sheetSplit(
                sheetTarget, sheetTab, (stage.width / density.density).dp,
                SheetSplit(sheetGrowth.value.dp, sheetFromStage[0].dp), (screen.width - base.width).dp,
            )
            val target = split.growth.value
            // The stage's pin moves with the window by what it gets back (or gives up), so the image resizes along.
            val pinFrom = stage.width
            val pinTo = pinFrom + ((sheetFromStage[0] - split.fromStage.value) * density.density).roundToInt()
            sheetFromStage[0] = split.fromStage.value
            pinnedStage = pinFrom
            growForSheet(handle, sheetGrowth, base, target, sheetWidthSpec(sheetGrowth.value.dp, target.dp), screenRight) { shift, progress ->
                sheetShift[0] = shift
                pinnedStage = pinFrom + ((pinTo - pinFrom) * progress).roundToInt()
            }
            pinnedStage = pinTo
            // Keep the pin until the native window has shrunk, or the stage widens for a frame.
            var frames = 0
            while (handle.size.width > base.width + target.roundToInt() + 1 && frames++ < 30) withFrameNanos { }
            // Grown for the sheet, not by the user: nothing for the aspect lock. The height from before the animation,
            // so a height the user dragged meanwhile is still seen as a resize.
            lastWindowSize[0] = IntSize(handle.size.width, base.height)
            pinnedStage = null // not when cancelled: a reversed toggle keeps the pin through its own animation
        }

        // Collected with the window's lifecycle (in here, not above, where the lifecycle is the application's): a
        // minimized mirror stops invalidating its canvas until it is restored. Read only at draw time by the stage.
        val frame = session.frames.collectAsStateWithLifecycle()

        ScrcpyViewerTheme {
            // Drops anywhere on the window but the sheet install / push (COPY only, see fileDropTarget).
            Box(Modifier.fillMaxSize().fileDropTarget(transfers, overSheet).followGlow(followStrip.role is FollowRole.Follower, reduceMotion)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(
                        LocalSnackbarHostState provides snackbar, LocalSheetFocus provides sheetFocus, LocalMirrorWindowHandle provides handle,
                    ) {
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
                                // Off the UI thread where the picker does not pump it (onPickerThread); back on it to start.
                                // One picker at a time (PickerGuard), queued on the transfers current when it closes.
                                MirrorAction.InstallApk -> picker.launch(uiScope) { pickAndStart({ currentTransfers }) { transferDialogs.chooseApks() } }
                                MirrorAction.PushFile -> picker.launch(uiScope) { pickAndStart({ currentTransfers }, transferDialogs::chooseFiles) }
                                MirrorAction.QuickActions -> quickActions.toggleSheet()
                                else -> action.messages().let { send(it); follow.send(it) }
                            }
                        },
                        onReconnect = { controller.reconnect(serial) },
                        onPointer = {
                            when {
                                "no-control" in e -> Unit
                                capture != null -> capture.pointer(it, handle, viewSize, density.density, stageBounds, viewOrigin[0])
                                else -> {
                                    clickIndicator?.onPointer(it, monotonicMillis())
                                    val msgs = mapper.pointer(it)
                                    send(msgs)
                                    follow.send(msgs)
                                }
                            }
                        },
                        onViewSize = { viewSize = it },
                        onStageSize = { stage = it },
                        onQuickAction = quickActions::onEvent,
                        pinnedStageWidth = pinnedStage,
                        wake = readWake,
                        shellConsole = quickActions.console,
                        filesTab = filesTab,
                        logcat = quickActions.logcat,
                        links = quickActions.links,
                        onStageBounds = { stageBounds = it },
                        onViewOrigin = { viewOrigin[0] = it },
                        clickIndicator = clickIndicator,
                        followIndicator = followRings,
                        onSheetBounds = { sheetBounds[0] = it },
                        stageOverlay = {
                            ScreenshotFlash(screenshotFlash, motion = !reduceMotion)
                            ScreenshotThumbnail(
                                thumbnail,
                                motion = !reduceMotion,
                                onCopy = { file ->
                                    withContext(Dispatchers.IO) { ImageClipboard.copyPng(file) }
                                        .onFailure { notify("Saved, but could not copy the image: ${it.message ?: it}") }
                                        .isSuccess
                                },
                                onReveal = { file -> revealInFolder(file) },
                                onOpen = { file -> openExternally(file.toString()) },
                                dragStarter = screenshotDragStarter,
                                modifier = Modifier.align(Alignment.BottomEnd),
                                // Dropped back on this window it would be pushed to the device: the drop targets refuse it.
                                onOsDrag = { transfers.draggingOut = it },
                            )
                        },
                    )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 80.dp, bottom = 44.dp))
            }
        }
    }
    // Logcat's own window, while it is out: part of this window's composition, so it closes with it.
    quickActions.logcat?.let { LogcatWindow(it, model, theme) }
}

/** What the window's sheet-growth effect last ran for; a plain holder, read and written only in that effect. */
private class SheetRun(var open: Boolean, var tab: SheetTab)

/**
 * Start-aligned at its own width but never narrower than the space it is given (so it can overflow, to be clipped,
 * but leaves no gap).
 */
private val AtLeastAvailableWidth: Modifier = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth, maxWidth = Constraints.Infinity))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
}

/** How long the stage size must hold still before the aspect ratio lock corrects the window. */
private const val ASPECT_SETTLE_MS = 150L

/** How long after the window resizes itself (new frame size, Fit) its bounds changes still count as the app's. */
private const val APP_RESIZE_HOLD_MS = 500L

/**
 * Heights (window units) on the screen [window] is on: the screen's height less the menu bar and the Dock or taskbar,
 * and the room from the window's top down to the screen's bottom (less the Dock or taskbar; negative when the window
 * hangs below it).
 */
internal fun screenRoom(window: java.awt.Rectangle, area: ScreenArea): Pair<Int, Int> {
    val work = area.workArea
    return work.height to (work.y + work.height - window.y)
}

/** The smallest height the aspect ratio lock lets the stage have (px), whatever room the screen has. */
private const val ASPECT_MIN_STAGE_HEIGHT = 200

/**
 * The tallest stage (px) the aspect ratio lock allows: the room from the window's top to the screen's bottom
 * ([roomBelow]), no more than the screen's [usable] height. Never under the stage's [current] height (up to [usable]),
 * so a window that hangs low or below the screen's bottom is not shrunk by a resize, and never under
 * [ASPECT_MIN_STAGE_HEIGHT].
 */
fun aspectMaxHeight(roomBelow: Int, usable: Int, current: Int): Int =
    maxOf(minOf(roomBelow, usable), minOf(current, usable), ASPECT_MIN_STAGE_HEIGHT)

/**
 * Whether the aspect ratio lock looks at the stage: after the user resized the window ([windowResized]), when the
 * frame changed size or turned ([lastFrame] to [frame]), or when Fit came back on ([wasFit] to [fit]). Not when only
 * the stage changed inside a window that kept its size: the quick actions sheet's drag bar moved, and the stage's new
 * shape is the user's choice.
 */
fun aspectLockNeeded(windowResized: Boolean, lastFrame: IntSize?, frame: IntSize, wasFit: Boolean, fit: Boolean): Boolean =
    windowResized || lastFrame != frame || (fit && !wasFit)

/**
 * Whether a stage change is the sheet's, not the user's, so the aspect ratio lock takes the new stage at once as the
 * base the next resize is measured from (no settle delay, no correction): with the frame and Fit as they were, the
 * stage is [pinned] while the quick actions sheet opens, closes or switches tabs, or the window kept its size (the
 * sheet's drag bar moved). A new frame or a Fit change always goes through the lock, even during a sheet animation.
 */
fun stageChangeIsSheets(pinned: Boolean, windowResized: Boolean, lastFrame: IntSize?, frame: IntSize, wasFit: Boolean, fit: Boolean): Boolean =
    lastFrame == frame && wasFit == fit && (pinned || !windowResized)

/**
 * Stage size that keeps the frame's aspect ratio after the user resized the stage from [old] (the stage right before
 * the resize) to [new]; the dimension that changed most wins. A stage at the frame's aspect (within 1 px) is rebuilt
 * from that dimension, so rounding never adds up. A stage the sheet's drag bar made wider or narrower than the frame
 * keeps that difference: the other dimension changes by the user's change scaled by the aspect ratio, so a small
 * resize never turns into a large one. For a new frame (another size, or the first) or when Fit came back on
 * ([refit]) the stage is rebuilt too. When the frame turned (rotation), the longer side is kept. A stage taller than
 * [maxHeight] (what the screen can show) is [maxHeight] tall, its width taken from that height: as much of the
 * user's change as fits.
 */
fun aspectLockedStage(
    old: IntSize,
    new: IntSize,
    oldFrame: IntSize?,
    frame: IntSize,
    maxHeight: Int = Int.MAX_VALUE,
    refit: Boolean = false,
): IntSize {
    if (frame.width <= 0 || frame.height <= 0 || new.width <= 0 || new.height <= 0) return new
    val aspect = frame.width.toDouble() / frame.height
    val rotated = oldFrame != null && (oldFrame.width > oldFrame.height) != (frame.width > frame.height)
    val onAspect = abs(old.width - old.height * aspect) <= 1.0
    val fromScratch = rotated || refit || oldFrame != frame || onAspect
    // The width for a height, kept to the frame's aspect from scratch or from the stage before the resize.
    fun widthFor(height: Int): Int =
        if (fromScratch) (height * aspect).roundToInt() else old.width + ((height - old.height) * aspect).roundToInt()
    val widthWins = abs(new.width - old.width) >= abs(new.height - old.height)
    val locked = when {
        rotated -> {
            val long = maxOf(new.width, new.height)
            if (frame.width >= frame.height) IntSize(long, (long / aspect).roundToInt()) else IntSize(widthFor(long), long)
        }
        widthWins && fromScratch -> IntSize(new.width, (new.width / aspect).roundToInt())
        widthWins -> IntSize(new.width, old.height + ((new.width - old.width) / aspect).roundToInt())
        else -> IntSize(widthFor(new.height), new.height)
    }
    if (locked.height <= maxHeight) return IntSize(locked.width.coerceAtLeast(1), locked.height.coerceAtLeast(1))
    return IntSize(widthFor(maxHeight).coerceAtLeast(1), maxHeight.coerceAtLeast(1))
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
    /** The video view's top-left in window pixels, whenever it moves (a zoomed stage scrolls it). */
    onViewOrigin: (Offset) -> Unit = {},
    /** False when the window draws [TopStrip] as its title bar; the divider below it stays. */
    showTopStrip: Boolean = true,
    onQuickAction: (QuickActionEvent) -> Unit = {},
    /**
     * Set while the window grows or shrinks for the quick actions sheet: the stage keeps this width (px) and the sheet
     * shows in whatever width is left. Null: the sheet animates its own width, taken from the stage.
     */
    pinnedStageWidth: Int? = null,
    /** The device screen's wake (0 off, 1 on; see [WakeStage]), read at draw time. Null: no wake animation. */
    wake: (() -> Float)? = null,
    /** The quick actions sheet's Shell tab console; null shows an empty Shell tab. */
    shellConsole: ShellConsole? = null,
    /** The quick actions sheet's Files tab content; null shows an empty Files tab. */
    filesTab: (@Composable () -> Unit)? = null,
    /** The quick actions sheet's Logcat tab; null (no adb) says adb isn't available. */
    logcat: LogcatController? = null,
    /** The quick actions sheet's Links tab; null (no adb) says adb isn't available. */
    links: LinksState? = null,
    /** The quick actions sheet's bounds in root pixels while it shows, null once it is gone. */
    onSheetBounds: (Rect?) -> Unit = {},
    /** Taps the leading window sent here: the same dot and click ring, at the spot they landed. */
    followIndicator: ClickIndicator? = null,
    /** Drawn over the stage, above the video and its overlays (the screenshot flash and thumbnail). */
    stageOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    // A press in the sheet marks itself on the way down (Initial pass); a press anywhere else takes keyboard focus out
    // of the sheet, so typing goes to the device again. Closing the sheet does the same. A plain holder.
    val pressInSheet = remember { BooleanArray(1) }
    val sheetPresses = remember {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) pressInSheet[0] = true
                }
            }
        }
    }
    val sheetOpen = state.quickActions.open
    LaunchedEffect(sheetOpen) { if (!sheetOpen) focusManager.clearFocus() }
    Column(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Final).type != PointerEventType.Press) continue
                    if (!pressInSheet[0]) focusManager.clearFocus()
                    pressInSheet[0] = false
                }
            }
        },
    ) {
        if (showTopStrip) NoTitleBarScope.TopStrip(state, onZoom = onZoom)
        HorizontalDivider(color = colors.outlineVariant)
        val density = LocalDensity.current
        var rowWidth by remember { mutableStateOf<Dp?>(null) }
        Row(Modifier.weight(1f).fillMaxWidth().onSizeChanged { rowWidth = with(density) { it.width.toDp() } }) {
            Box(
                (if (pinnedStageWidth != null) Modifier.width(with(density) { pinnedStageWidth.toDp() }) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .background(state.background ?: colors.surfaceDim)
                    .onSizeChanged(onStageSize)
                    .onGloballyPositioned { onStageBounds(it.boundsInWindow()) },
            ) {
                val disconnected = state.disconnected
                if (wake == null) {
                    Stage(state, frame, latestFrame, onPointer, onViewSize, clickIndicator, onViewOrigin, followIndicator)
                } else {
                    WakeStage(wake, standby = !disconnected) {
                        Stage(state, frame, latestFrame, onPointer, onViewSize, clickIndicator, onViewOrigin, followIndicator)
                    }
                }
                // While the screen is still asleep its standby line stands in for the skeleton.
                val awake by remember(wake) { derivedStateOf { wake == null || wake() >= 1f } }
                // Fully qualified: the enclosing Row's RowScope.AnimatedVisibility would otherwise win.
                androidx.compose.animation.AnimatedVisibility(
                    visible = awake && !disconnected && (state.status == SessionStatus.Connecting || state.frameSize == null),
                    enter = fadeIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)),
                    // Quick: the frame is already fading in underneath, and the skeleton is not its shape.
                    exit = fadeOut(tween(SKELETON_OUT_MILLIS, easing = EmphasizedAccelerate)),
                ) {
                    StageSkeleton(state.frameSize?.let(state.orientation::orientedSize))
                }
                // The card keeps what it said while it leaves (a reconnect is already Connecting). A plain holder.
                val lastDown = remember { arrayOfNulls<MirrorUiState>(1) }
                if (disconnected) lastDown[0] = state
                androidx.compose.animation.AnimatedVisibility(
                    visible = disconnected,
                    enter = fadeIn(tween(SCRIM_IN_MILLIS, easing = EmphasizedDecelerate)),
                    exit = fadeOut(tween(CLOSE_MILLIS, delayMillis = CARD_DELAY_MILLIS, easing = EmphasizedAccelerate)),
                ) {
                    DisconnectedScrim(lastDown[0] ?: state, onReconnect)
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
                stageOverlay()
            }
            SideToolbar(state, onAction)
            // While the window grows or shrinks the row is mid-change; the sheet keeps its own width then.
            val panel = rememberQuickActionsPanel(
                state.quickActions, onQuickAction, shellConsole, rowWidth.takeIf { pinnedStageWidth == null }, filesTab, logcat,
                links,
            )
            // Where the sheet is, so the window's file drop leaves it to the sheet (the Files tab takes drops there).
            val currentSheetBounds by rememberUpdatedState(onSheetBounds)
            val sheetBounds = Modifier.onGloballyPositioned { currentSheetBounds(it.boundsInRoot()) }
            if (pinnedStageWidth != null) {
                // The panel stays put; the growing window uncovers it. Never narrower than the space it is given,
                // so a tab switch's width change leaves no gap while the window catches up.
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    DisposableEffect(Unit) { onDispose { currentSheetBounds(null) } }
                    panel(AtLeastAvailableWidth.then(sheetPresses).then(sheetBounds))
                }
            } else {
                QuickActionsSheet(state.quickActions.open) {
                    DisposableEffect(Unit) { onDispose { currentSheetBounds(null) } }
                    panel(sheetPresses.then(sheetBounds))
                }
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
 *
 * With [follow]: the "Following" chip after the model while this window follows, and the "Send input to…" button
 * before the copy button while other mirror windows are open (never on a follower). With [arrange] (two or more mirror
 * windows open): the Arrange button before the copy button, in the ⋮ menu when narrow. Which parts show is [stripRoom];
 * every part that comes or goes expands or shrinks with a fade ([stripEnter], [stripExit]), so starting or stopping
 * following, opening a second window or resizing never changes the bar in one frame.
 */
@Composable
internal fun AppTitleBarScope.TopStrip(
    state: MirrorUiState,
    follow: FollowStrip? = null,
    arrange: ArrangeStrip? = null,
    onZoom: (ZoomCommand) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val custom = chrome == WindowChrome.Custom
    val reduce = (follow?.reduceMotion ?: arrange?.reduceMotion) == true
    val modelStyle = stripModelStyle()
    val chip = rememberChipWidths(state.model, modelStyle, follow)
    // The chip's widths while it leaves (following stopped, chip already null), so its width cap does not jump. A
    // plain holder.
    val lastChip = remember { arrayOfNulls<ChipWidths>(1) }
    if (chip != null) lastChip[0] = chip
    // The same for the Arrange button while it shrinks away (the second window closed, arrange already null).
    val lastArrange = remember { arrayOfNulls<ArrangeStrip>(1) }
    if (arrange != null) lastArrange[0] = arrange
    BoxWithConstraints(Modifier.fillMaxWidth().background(if (custom) Color.Transparent else colors.surfaceContainer)) {
        val w = maxWidth
        val room = stripRoom(w, STRIP_MINS, state.details.isNotEmpty(), follow, chip, arrange = arrange != null)
        val following = follow?.role is FollowRole.Follower
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (custom) 0.dp else 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = when (state.status) {
                SessionStatus.Running -> colors.primary
                SessionStatus.Connecting -> warnColor()
                else -> colors.error
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            // The model and the details take the room the controls leave; the controls keep their content width,
            // and the model ellipsizes after the details (secondary text) have been dropped. The chip is measured
            // before the model but leaves it MODEL_KEEP (stripRoom moves the controls out first).
            Row(Modifier.weight(1f).padding(start = STRIP_GAP), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.model,
                    Modifier.weight(1f, fill = false),
                    style = modelStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (follow != null) {
                    val keep = minOf((chip ?: lastChip[0])?.model ?: MODEL_KEEP, MODEL_KEEP)
                    FollowerChip(
                        following, follow.leaderTitle, follow.layoutDiffers, follow.reduceMotion,
                        Modifier.widthIn(max = (w - FOLLOWER_FIXED - keep).coerceAtLeast(STRIP_GAP * 4)),
                        compactWarning = room.compactWarning,
                        gap = STRIP_GAP,
                    )
                }
                StripSlot(room.details, reduce) {
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
            StripSlot(room.zoom, reduce) { ZoomControls(state, onZoom) }
            StripSlot(room.fit, reduce) {
                FilterChip(
                    selected = state.fit,
                    onClick = { onZoom(if (state.fit) ZoomCommand.Actual else ZoomCommand.Fit) },
                    label = { Text("Fit to window", maxLines = 1, softWrap = false) },
                    modifier = Modifier.interactive("fit"),
                )
            }
            // A follower never shows it: it changes role only when its leader lets it go or closes.
            val sendStrip = follow != null && !following && (room.send == SendPlace.Words || room.send == SendPlace.Icon)
            StripSlot(sendStrip, reduce) {
                if (follow != null) {
                    SendInputButton(
                        follow.role, follow.others, follow.captureOn, follow.onChange,
                        compact = room.send != SendPlace.Words, modifier = Modifier.interactive("send-input"), reduceMotion = reduce,
                    )
                }
            }
            StripSlot(room.arrange == ArrangePlace.Strip, reduce) {
                (arrange ?: lastArrange[0])?.let { ArrangeButton(it, Modifier.interactive("arrange")) }
            }
            StripSlot(room.copy, reduce) { CopyButton(text = { state.command }, label = "Copy command", modifier = Modifier.interactive("copy")) }
            StripSlot(room.overflow, reduce) {
                OverflowMenu(
                    state, onZoom, showZoom = room.zoom, showFit = room.fit, showCopy = room.copy,
                    follow = follow.takeIf { room.send == SendPlace.Overflow },
                    arrange = arrange.takeIf { room.arrange == ArrangePlace.Overflow },
                )
            }
        }
    }
}

/** One title bar part, with the gap before it inside its expand / shrink + fade, so the gap comes and goes with it. */
@Composable
private fun StripSlot(visible: Boolean, reduce: Boolean, content: @Composable () -> Unit) {
    // Fully qualified: no RowScope variant, the transitions are given here.
    androidx.compose.animation.AnimatedVisibility(visible, enter = stripEnter(reduce), exit = stripExit(reduce)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(STRIP_GAP))
            content()
        }
    }
}

/** Widths (dp) of the top strip at which secondary parts collapse into the overflow menu ([stripRoom] adds following). */
private val STRIP_MINS = StripMins(details = DETAILS_MIN, copy = COPY_MIN, fit = FIT_MIN, zoom = ZOOM_MIN)

/** Space between the title bar's parts. */
private val STRIP_GAP = 10.dp

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
            ZoomReadout(ZoomReading(scale, state.fit))
        }
        IconButton(onClick = { onZoom(ZoomCommand.In) }, Modifier.interactive("zoom-in").size(32.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The zoom percent, rolling to a new value: up (the new one in from below) when the zoom grows, down when it
 * shrinks. A fitted scale that follows a window resize changes at once, without a roll.
 */
@Composable
private fun ZoomReadout(reading: ZoomReading) {
    AnimatedContent(
        targetState = reading,
        transitionSpec = {
            val roll = Zoom.roll(initialState, targetState)
            val spec = tween<IntOffset>(OPEN_MILLIS, easing = EmphasizedDecelerate)
            val fade = tween<Float>(OPEN_MILLIS, easing = EmphasizedDecelerate)
            when (roll) {
                0 -> EnterTransition.None togetherWith ExitTransition.None
                else -> (slideInVertically(spec) { it * roll } + fadeIn(fade)) togetherWith
                    (slideOutVertically(spec) { -it * roll } + fadeOut(fade))
            }.using(SizeTransform(clip = true))
        },
        contentAlignment = Alignment.Center,
        contentKey = { Zoom.label(it.scale) },
        label = "zoom",
    ) {
        Text(
            Zoom.label(it.scale),
            maxLines = 1,
            softWrap = false,
            fontFamily = FontFamily.Monospace,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
    }
}

/** The "⋮" menu holding what the top strip has no room for. */
@Composable
private fun AppTitleBarScope.OverflowMenu(
    state: MirrorUiState,
    onZoom: (ZoomCommand) -> Unit,
    showZoom: Boolean,
    showFit: Boolean,
    showCopy: Boolean,
    /** "Send input to…" when the strip has no room for its button: the item opens its menu here. */
    follow: FollowStrip? = null,
    /** "Arrange mirror windows" when the strip has no room for its button: the item opens its menu here. */
    arrange: ArrangeStrip? = null,
) {
    var open by remember { mutableStateOf(false) }
    var sendOpen by remember { mutableStateOf(false) }
    var arrangeOpen by remember { mutableStateOf(false) }
    val copy = rememberCopyAction()
    Box {
        if (follow != null) {
            SendInputMenu(sendOpen, { sendOpen = false }, follow.role, follow.others, follow.captureOn, follow.onChange)
        }
        // Kept composed after the button moves back to the strip (the pick widened the window), so the menu still
        // closes with its animation.
        val lastArrange = remember { arrayOfNulls<ArrangeStrip>(1) }
        if (arrange != null) lastArrange[0] = arrange
        lastArrange[0]?.let { ArrangeMenu(arrangeOpen, { arrangeOpen = false }, it) }
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
            if (follow != null) {
                item(sendInputLabel(follow.role)) { sendOpen = true }
            }
            if (arrange != null) {
                item(ARRANGE_LABEL) { arrangeOpen = true }
            }
        }
    }
}

/** The full radius of the show-touches release ring. */
private val CLICK_RING_RADIUS = 28.dp

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
    onViewOrigin: (Offset) -> Unit = {},
    followIndicator: ClickIndicator? = null,
) {
    val pointer by rememberUpdatedState(onPointer)
    // Redraws every frame while a click dot fades out.
    var now by remember { mutableLongStateOf(monotonicMillis()) }
    val releaseTime = clickIndicator?.releaseTime
    val followReleaseTime = followIndicator?.releaseTime
    LaunchedEffect(clickIndicator, releaseTime, followIndicator, followReleaseTime) {
        if (clickIndicator == null && followIndicator == null) return@LaunchedEffect
        do {
            withFrameNanos { now = monotonicMillis() }
        } while (clickIndicator?.isFading(now) == true || followIndicator?.isFading(now) == true)
    }
    val dotColor = MaterialTheme.colorScheme.primary
    val dotRing = MaterialTheme.colorScheme.onPrimary
    val glow = LocalNeonGlow.current?.takeIf { it.strength > 0f }
    fun DrawScope.drawClickDot(indicator: ClickIndicator? = clickIndicator) {
        val dot = indicator?.state(now) ?: return
        dot.ring?.let { ring ->
            // The release ripple: grows out of the dot and fades.
            drawCircle(
                dotColor.copy(alpha = ring.alpha), radius = CLICK_RING_RADIUS.toPx() * ring.radiusFraction,
                center = dot.position, style = Stroke(2.dp.toPx()),
            )
        }
        if (dot.alpha <= 0f) return
        val radius = 12.dp.toPx() * dot.scale
        drawCircle(dotColor.copy(alpha = 0.4f * dot.alpha), radius = radius, center = dot.position)
        // A thin light ring keeps the dot visible on content close to the primary color.
        drawCircle(dotRing.copy(alpha = 0.6f * dot.alpha), radius = radius, center = dot.position, style = Stroke(1.5.dp.toPx()))
    }
    // Set by the zoomed stage: pans it by a wheel delta and tells whether it could.
    val pan = remember { arrayOfNulls<(Offset) -> Boolean>(1) }
    val input = Modifier.onGloballyPositioned { onViewOrigin(it.positionInWindow()) }.pointerInput(Unit) {
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
    }
    val orientation = state.orientation
    val fs = state.frameSize?.let(orientation::orientedSize)
    val zoom = state.zoom
    // Only whether there is a frame is read here (it changes once per session), never the frame itself.
    val hasFrame by remember(frame) { derivedStateOf { frame.value != null } }
    val disconnected = state.disconnected
    val waiting = !disconnected && (state.status == SessionStatus.Connecting || state.frameSize == null)
    val stretch = state.stretch && zoom == null
    val motion = rememberStageMotion(
        revealed = hasFrame && !waiting,
        disconnected = disconnected,
        frameSize = fs,
        flip = orientation.flip,
        latestFrame = latestFrame,
        orientation = orientation,
        oldImageSize = { old, canvas ->
            if (zoom != null) {
                Size(old.width * zoom, old.height * zoom)
            } else {
                InputMapper.contentRect(old, Rect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), stretch).size
            }
        },
    )
    if (zoom == null || fs == null) {
        SideEffect { pan[0] = null }
        Canvas(
            Modifier.fillMaxSize().onSizeChanged(onViewSize).then(input).clipToBounds()
                .stageMotionLayer(motion) { size -> fs?.let { InputMapper.contentRect(it, Rect(Offset.Zero, size), stretch) } ?: Rect(Offset.Zero, size) },
        ) {
            frame.value // subscribe: a new frame redraws
            if (glow != null && fs != null) drawNeonFrame(InputMapper.contentRect(fs, Rect(0f, 0f, size.width, size.height), stretch), glow)
            drawLatestFrame(latestFrame, FilterQuality.Low, orientation, greyColorFilter(motion.grey.value)) { f ->
                InputMapper.contentRect(orientation.orientedSize(IntSize(f.width, f.height)), Rect(0f, 0f, size.width, size.height), state.stretch)
            }
            drawClickDot()
            drawClickDot(followIndicator)
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
                            .onSizeChanged(onViewSize).then(input)
                            .stageMotionLayer(motion) { size -> Rect(Offset.Zero, size) },
                    ) {
                        frame.value // subscribe: a new frame redraws
                        val quality = if (zoom >= 1f) FilterQuality.None else FilterQuality.Low
                        drawLatestFrame(latestFrame, quality, orientation, greyColorFilter(motion.grey.value)) { Rect(0f, 0f, size.width, size.height) }
                        drawClickDot()
                        drawClickDot(followIndicator)
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
 * [colorFilter] greys a disconnected frame ([greyColorFilter]); null on a live stage.
 */
private fun DrawScope.drawLatestFrame(
    latest: () -> VideoFrame?,
    quality: FilterQuality,
    orientation: DisplayOrientation,
    colorFilter: ColorFilter?,
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
                drawImage(bitmap.asComposeImageBitmap(), dstOffset = offset, dstSize = size, filterQuality = quality, colorFilter = colorFilter)
            }
        }
        if (drawn != null) return
    }
}

/** The connecting skeleton fades out over this long as the first frame comes in. */
private const val SKELETON_OUT_MILLIS = 140

/** The Disconnected scrim fades in over this long; the card follows [CARD_DELAY_MILLIS] later, over [CARD_IN_MILLIS]. */
private const val SCRIM_IN_MILLIS = 300
private const val CARD_DELAY_MILLIS = 120
private const val CARD_IN_MILLIS = 360

/** How far the Disconnected card rises as it fades in. */
private val CARD_RISE = 24.dp

/** Shown in an AnimatedVisibility: the scrim fades with it, the card rises in a little after it (and drops out first). */
@Composable
private fun AnimatedVisibilityScope.DisconnectedScrim(state: MirrorUiState, onReconnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rise = with(density) { CARD_RISE.roundToPx() }
    Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = styledCorner(16.dp),
            color = colors.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.animateEnterExit(
                enter = slideInVertically(tween(CARD_IN_MILLIS, CARD_DELAY_MILLIS, EmphasizedDecelerate)) { rise } +
                    fadeIn(tween(CARD_IN_MILLIS, CARD_DELAY_MILLIS, EmphasizedDecelerate)),
                exit = slideOutVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { rise } +
                    fadeOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)),
            ),
        ) {
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

/** The side toolbar's width, between the stage and the quick actions sheet. */
internal val SIDE_TOOLBAR_WIDTH = 64.dp

@Composable
private fun SideToolbar(state: MirrorUiState, onAction: (MirrorAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(SIDE_TOOLBAR_WIDTH).fillMaxHeight().background(colors.surfaceContainer).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
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
            Surface(shape = styledCorner(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
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
