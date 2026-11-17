package com.programmersbox.scrcpyviewer.ui.mirror.shots.gif

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.ui.mirror.LocalMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.TaoMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.animateWindowBounds
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ShotLease
import com.programmersbox.scrcpyviewer.ui.mirror.shots.markup.AnimateBounds
import com.programmersbox.scrcpyviewer.ui.mirror.shots.markup.EditorWindowPlan
import com.programmersbox.scrcpyviewer.ui.mirror.shots.markup.closeEditorWindow
import com.programmersbox.scrcpyviewer.ui.mirror.shots.markup.openEditorWindow
import com.programmersbox.scrcpyviewer.ui.mirror.usableScreenBounds
import com.programmersbox.scrcpyviewer.ui.mirror.zoomTarget
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.bringToFront
import com.programmersbox.scrcpyviewer.ui.window.setMacAlpha
import dev.nucleusframework.application.LocalNucleusApplicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.awt.Rectangle
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** The Export GIF window can be made no smaller than this (only while it is open). */
val GIF_WINDOW_MIN_SIZE = DpSize(600.dp, 500.dp)

/** It opens this tall (window units); the preview takes the height left after the title bar and padding. */
const val GIF_WINDOW_HEIGHT = 540

/** The window around the preview, in window units: the controls' column and the paddings across; title bar and padding down. */
internal const val GIF_SIDE_WIDTH = 400
internal const val GIF_CHROME_WIDTH = 48
internal const val GIF_CHROME_HEIGHT = 38 + 32

/** The preview is at least, and at most, this wide (window units) whatever the recording's shape. */
internal const val GIF_PREVIEW_MIN_WIDTH = 160
internal const val GIF_PREVIEW_MAX_WIDTH = 560

/** The window's title. */
fun gifWindowTitle(deviceName: String): String = "Export GIF · $deviceName"

/**
 * Where the Export GIF window opens on [screen] (the usable area of the mirror window's screen): [GIF_WINDOW_HEIGHT]
 * tall, wide enough for the preview at the recording's ([videoWidth] x [videoHeight]) aspect ratio and the controls,
 * at least [GIF_WINDOW_MIN_SIZE], centred.
 */
internal fun gifWindowBounds(videoWidth: Int, videoHeight: Int, screen: Rectangle): Rectangle {
    val aspect = videoWidth.toFloat() / videoHeight.coerceAtLeast(1)
    val previewH = GIF_WINDOW_HEIGHT - GIF_CHROME_HEIGHT
    val previewW = (previewH * aspect).roundToInt().coerceIn(GIF_PREVIEW_MIN_WIDTH, GIF_PREVIEW_MAX_WIDTH)
    val w = maxOf(previewW + GIF_SIDE_WIDTH + GIF_CHROME_WIDTH, GIF_WINDOW_MIN_SIZE.width.value.roundToInt())
    val h = maxOf(GIF_WINDOW_HEIGHT, GIF_WINDOW_MIN_SIZE.height.value.roundToInt())
    return zoomTarget(null, null, w, h, screen)
}

/**
 * One recording's Export GIF window: the recording ([shot], [video]) and the screen bounds of the button it grows from
 * ([origin]). Export GIF again brings it to the front ([raises]). Closing sets [leaving] while it plays its close; it is
 * gone once [GifExportWindowsState.closed]. [quiet]: closing with the mirror window, nothing to say.
 */
@Stable
class GifWindow internal constructor(val shot: Shot, val video: RecordingInfo, origin: Rectangle?) {
    val origin: Rectangle? = origin?.let(::Rectangle)

    var raises by mutableIntStateOf(0)
        private set

    var leaving by mutableStateOf(false)
        private set

    internal var quiet = false
        private set

    /** Brings the window to the front (not while it closes). */
    fun raise() {
        if (!leaving) raises++
    }

    /** Close, Esc or the window's close button: it plays its close (an export running is cancelled first). */
    fun leave(quiet: Boolean = false) {
        if (quiet) this.quiet = true
        leaving = true
    }
}

/**
 * One mirror window's Export GIF windows, one per recording thumbnail: [open] on a recording that has one brings it to
 * the front instead. While any is open the [thumbnail]'s timer holds and that recording dims under "MAKING GIF".
 * [closeAll] closes them with the mirror window (their exports are cancelled). UI thread only.
 */
@Stable
class GifExportWindowsState(private val thumbnail: ScreenshotThumbnailState) {
    val windows = mutableStateListOf<GifWindow>()

    val isOpen: Boolean get() = windows.isNotEmpty()

    /** Opens [shot]'s window (a recording), growing from [origin]; already open: brought to the front. */
    fun open(shot: Shot, origin: Rectangle?) {
        val video = shot.video ?: return
        windows.firstOrNull { it.shot.id == shot.id }?.let {
            it.raise()
            return
        }
        windows += GifWindow(shot, video, origin)
        thumbnail.makingGif(shot.id, true)
    }

    /** The mirror window is closing: every window closes, cancelling its export, and says nothing. */
    fun closeAll() {
        windows.forEach { it.leave(quiet = true) }
    }

    /** [window] has gone (closed, or disposed with the mirror window). */
    internal fun closed(window: GifWindow) {
        if (windows.remove(window)) thumbnail.makingGif(window.shot.id, false)
    }
}

/**
 * The mirror window's Export GIF windows, "Export GIF · [deviceName]", one per [GifExportWindowsState.windows] entry:
 * each opens on the mirror window's screen ([near]) sized by [gifWindowBounds], growing out of the GIF button and
 * waking its preview as the markup editor's window does ([openEditorWindow]; [animate] is
 * `Settings.animateMirrorWindows`, [motion] false under reduced motion), and closes into the thumbnail ([thumbnail],
 * its screen bounds while it shows) or in place ([closeEditorWindow]).
 *
 * Closing a window while it exports cancels the export and [notify] (the mirror window's snackbar) says "Export
 * cancelled. The partial GIF was deleted." once FFmpeg is gone and the partial file deleted. Composed inside the
 * mirror window's composition: when it goes, so do these windows, and a running export is cancelled with them.
 */
@Composable
fun GifExportWindows(
    windows: GifExportWindowsState,
    deviceName: String,
    theme: AppTheme,
    animate: Boolean,
    motion: Boolean,
    near: () -> Rectangle?,
    thumbnail: () -> Rectangle?,
    export: GifExport,
    frames: GifFrameSource,
    onReveal: (Path) -> Unit,
    notify: (String) -> Unit,
    leases: ScreenshotThumbnailState,
    screenGeometry: ScreenGeometry,
) {
    windows.windows.toList().forEach { w ->
        key(w) {
            ExportWindow(windows, w, deviceName, theme, animate, motion, near, thumbnail, export, frames, onReveal, notify, leases, screenGeometry)
        }
    }
}

@Composable
private fun ExportWindow(
    windows: GifExportWindowsState,
    entry: GifWindow,
    deviceName: String,
    theme: AppTheme,
    animate: Boolean,
    motion: Boolean,
    near: () -> Rectangle?,
    thumbnail: () -> Rectangle?,
    export: GifExport,
    frames: GifFrameSource,
    onReveal: (Path) -> Unit,
    notify: (String) -> Unit,
    leases: ScreenshotThumbnailState,
    screenGeometry: ScreenGeometry,
) {
    val plan = remember {
        val full = animate && motion
        val origin = entry.origin
        val screen = usableScreenBounds(near() ?: origin ?: Rectangle(0, 0, 1, 1), screenGeometry.screens())
        EditorWindowPlan(from = origin?.takeIf { full }, to = gifWindowBounds(entry.video.width, entry.video.height, screen), full = full)
    }
    val start = plan.from ?: plan.to
    val windowState = rememberWindowState(
        position = WindowPosition(start.x.dp, start.y.dp),
        size = DpSize(start.width.dp, start.height.dp),
    )
    // Hidden until it is put where it grows from (or see-through, to fade in).
    var shown by remember { mutableStateOf(false) }
    val title = gifWindowTitle(deviceName)
    LocalNucleusApplicationScope.current.AppWindow(
        onCloseRequest = { entry.leave() },
        title = title,
        theme = theme,
        state = windowState,
        visible = shown,
        reduceMotion = !motion,
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) entry.leave()
            event.key == Key.Escape
        },
        titleBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) {
        val window = nucleusWindow
        val handle = remember(window) { TaoMirrorWindowHandle(window, screenGeometry) }
        LaunchedEffect(entry) {
            // A request from before this window is not one to it.
            snapshotFlow { entry.raises }.drop(1).collect { bringToFront(window) }
        }
        CompositionLocalProvider(LocalMirrorWindowHandle provides handle) {
            GifExportWindowBody(
                windows, entry, handle, plan, motion,
                show = { shown = true },
                alpha = { a -> handle.nsWindow?.let { setMacAlpha(it, a.toDouble()) } },
                floating = { windowState.placement == WindowPlacement.Floating },
                minimum = { on ->
                    val min = GIF_WINDOW_MIN_SIZE.takeIf { on }
                    window.unsafe.taoWindow?.setMinimumSize(min?.width?.value?.toDouble(), min?.height?.value?.toDouble())
                },
                thumbnail = thumbnail,
                export = export,
                frames = frames,
                onReveal = onReveal,
                notify = notify,
                leases = leases,
                animateBounds = { w, to, millis, grow -> animateWindowBounds(w, to, millis, grow, screenGeometry) },
            )
        }
    }
}

/**
 * What an Export GIF window shows and plays: [GifExportContent] for [entry]'s recording, opened by [openEditorWindow]
 * as it composes and closed by [closeEditorWindow] once it leaves ([GifWindow.leaving]); an export still running is
 * cancelled first (and, unless [GifWindow.quiet], [notify] says so). Then [windows] lets it go. Failures and a Cancel
 * show in this window's own snackbar. [window] is the native window ([show] makes it visible, [alpha] its opacity).
 */
@Composable
internal fun GifExportWindowBody(
    windows: GifExportWindowsState,
    entry: GifWindow,
    window: MirrorWindowHandle,
    plan: EditorWindowPlan,
    motion: Boolean,
    show: () -> Unit,
    alpha: (Float) -> Unit,
    floating: () -> Boolean,
    /** Sets (true) or lifts (false) [GIF_WINDOW_MIN_SIZE]; only while open, so it never clamps the grow or the shrink. */
    minimum: (Boolean) -> Unit,
    thumbnail: () -> Rectangle?,
    export: GifExport,
    frames: GifFrameSource,
    onReveal: (Path) -> Unit,
    notify: (String) -> Unit,
    leases: ScreenshotThumbnailState,
    animateBounds: AnimateBounds,
    modifier: Modifier = Modifier.fillMaxSize(),
    decode: CoroutineContext = Dispatchers.Default,
) {
    val shot = entry.shot
    val wake = remember { Animatable(0f) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val state = remember(entry) { GifExportState(shot.file, entry.video) }
    // The first frame shows in the preview until its frames are in: drawn from the thumbnail's bitmap, so leased.
    remember(shot) { ShotLease(leases, shot) }
    // Gone with the mirror window: its export goes with this composition's scope (cancelled, files deleted).
    DisposableEffect(windows, entry) { onDispose { windows.closed(entry) } }
    val leaving = entry.leaving
    LaunchedEffect(leaving) {
        if (!leaving) {
            openEditorWindow(window, plan, wake, show, alpha, animateBounds)
            minimum(true)
        } else {
            show() // closed before it showed
            minimum(false)
            if (state.cancelExport() && !entry.quiet) notify(GIF_CANCELLED_MESSAGE)
            closeEditorWindow(window, plan.full, floating(), thumbnail, wake, alpha, animateBounds)
            windows.closed(entry)
        }
    }
    Box(modifier) {
        GifExportContent(
            state, motion,
            export = export,
            frames = frames,
            onClose = { entry.leave() },
            onReveal = onReveal,
            say = say,
            modifier = Modifier.fillMaxSize(),
            firstFrame = shot.bitmap,
            wake = { wake.value },
            wakeFades = !plan.full,
            active = !leaving,
            decode = decode,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 64.dp))
    }
}
