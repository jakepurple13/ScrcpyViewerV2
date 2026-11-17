package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.LocalMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_SHRINK_IN_PLACE
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_IN_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_OUT_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_SLEEP_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_WAKE_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.TaoMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.animateWindowBounds
import com.programmersbox.scrcpyviewer.ui.mirror.showWindowAt
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shrunkAroundCenter
import com.programmersbox.scrcpyviewer.ui.mirror.usableScreenBounds
import com.programmersbox.scrcpyviewer.ui.mirror.zoomTarget
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.bringToFront
import com.programmersbox.scrcpyviewer.ui.window.setMacAlpha
import dev.nucleusframework.application.LocalNucleusApplicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.awt.Rectangle
import kotlin.math.roundToInt

/** The editor window can be made no smaller than this. */
val EDITOR_WINDOW_MIN_SIZE = DpSize(360.dp, 420.dp)

/** The editor window opens at least this large. */
val EDITOR_WINDOW_OPEN_MIN_SIZE = DpSize(420.dp, 480.dp)

/**
 * The toolbar's width on one row (window units): Pen, Arrow, Box, Circle, Pixelate, Undo, the four swatches, Save and
 * Copy, with their gaps and padding, plus room for wider platform fonts. The window opens at least this wide.
 */
const val EDITOR_TOOLBAR_WIDTH = 640

/** It opens with the canvas this share of the usable screen's height (less, when that would be too wide). */
const val EDITOR_CANVAS_SCREEN_SHARE = 0.8f

/**
 * The window around the canvas, in window units: the canvas's padding across; the title bar, the toolbar, the
 * dividers, the footer and the canvas's padding down. Sizes the window to the screenshot's aspect ratio.
 */
internal const val EDITOR_CHROME_WIDTH = 24
internal const val EDITOR_CHROME_HEIGHT = 152

/** The editor window's title. */
fun editorWindowTitle(deviceName: String): String = "Edit screenshot · $deviceName"

/**
 * Where the editor window opens on [screen] (the usable area of the mirror window's screen): the canvas at the
 * screenshot's ([imageWidth] x [imageHeight]) aspect ratio, [EDITOR_CANVAS_SCREEN_SHARE] of the screen's height (or its
 * width, for a wide one), plus the window's chrome; at least [EDITOR_WINDOW_OPEN_MIN_SIZE], centred.
 */
internal fun editorWindowBounds(imageWidth: Int, imageHeight: Int, screen: Rectangle): Rectangle {
    val aspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
    var canvasH = screen.height * EDITOR_CANVAS_SCREEN_SHARE
    var canvasW = canvasH * aspect
    val maxW = screen.width * EDITOR_CANVAS_SCREEN_SHARE
    if (canvasW > maxW) {
        canvasW = maxW
        canvasH = canvasW / aspect
    }
    // Never narrower than the toolbar on one row: every button shows (the canvas is letterboxed if need be).
    val w = maxOf(
        (canvasW + EDITOR_CHROME_WIDTH).roundToInt(),
        EDITOR_WINDOW_OPEN_MIN_SIZE.width.value.roundToInt(),
        EDITOR_TOOLBAR_WIDTH,
    )
    val h = maxOf((canvasH + EDITOR_CHROME_HEIGHT).roundToInt(), EDITOR_WINDOW_OPEN_MIN_SIZE.height.value.roundToInt())
    return zoomTarget(null, null, w, h, screen)
}

/**
 * One mirror window's markup editor window: which screenshot it edits ([shot], null when there is no window) and the
 * screen bounds of the button it grows from ([origin]). One editor per mirror window: [open] while it is open brings it
 * to the front ([raises]) instead. Closing (Save, Discard, a close with no marks) sets [leaving] while the window plays
 * its close; [shot] goes once it has. While it is open the [thumbnail]'s timer holds and only that shot dims. UI thread
 * only.
 */
@Stable
class MarkupEditorWindowState(private val thumbnail: ScreenshotThumbnailState) {
    var shot by mutableStateOf<Shot?>(null)
        private set

    /** Where the pressed button was on screen when it opened; null: none (it does not grow). */
    var origin: Rectangle? = null
        private set

    /** Bumped for every request to bring the open window to the front. */
    var raises by mutableIntStateOf(0)
        private set

    /** The window plays its close: the content no longer acts. */
    var leaving by mutableStateOf(false)
        private set

    val isOpen: Boolean get() = shot != null

    /** The open window's edit, set by its content: the mirror window's close asks it. */
    internal var editor: MarkupEditorState? = null

    /** The mirror window's close waits on this: true once the editor is gone, false on Keep editing. */
    private var answer: CompletableDeferred<Boolean>? = null

    /** A mirror window's close waits for the editor's answer ([closeThen]). */
    private var asking = false

    /**
     * Opens the editor window on [shot], growing from [origin] (a button's screen bounds). Already open: it comes to the
     * front, and no second window opens. While it is closing a press does nothing.
     */
    fun open(shot: Shot, origin: Rectangle?) {
        if (this.shot != null) {
            if (!leaving) raise()
            return
        }
        this.origin = origin?.let(::Rectangle)
        this.shot = shot
        thumbnail.editing(shot)
    }

    /** Brings the open window to the front (not while it closes). */
    fun raise() {
        if (shot != null && !leaving) raises++
    }

    /** The content closed (Save, Discard, no marks): the window plays its close, then calls [closed]. */
    internal fun leave() {
        if (shot == null || leaving) return
        leaving = true
    }

    /**
     * [shot]'s window has gone: the thumbnail's timer restarts, and a mirror window's close waiting on it goes on.
     * Nothing for another shot (a window disposed after the next one opened).
     */
    internal fun closed(shot: Shot) {
        if (this.shot !== shot) return
        this.shot = null
        origin = null
        leaving = false
        editor = null
        thumbnail.editing(null)
        answer?.complete(true)
        answer = null
    }

    /** Keep editing in the editor's "Discard your marks?": a mirror window's close waiting on it is off. */
    internal fun declined() {
        answer?.complete(false)
        answer = null
    }

    /**
     * The mirror window is closing: closes the editor too, asking "Discard your marks?" in its window first when there
     * are marks not saved or copied. True once the editor has gone (at once without marks, or after Discard); false on
     * Keep editing, or while a copy or save runs.
     */
    suspend fun closeForMirror(): Boolean {
        if (shot == null) return true
        val waiting = answer ?: CompletableDeferred<Boolean>().also { answer = it }
        if (!leaving) {
            val e = editor
            when {
                e == null -> shot?.let(::closed) // never composed: nothing to play
                e.requestClose() -> Unit // closing: answered once it has gone
                e.confirming -> raise() // the prompt asks in the editor window
                else -> {
                    // A copy or save runs: not now.
                    answer = null
                    waiting.complete(false)
                }
            }
        }
        return waiting.await()
    }

    /**
     * The mirror window's close button: [close] runs at once without an editor, else once the editor has gone
     * ([closeForMirror], on [scope]). Pressed again while the editor asks, it brings the editor to the front.
     */
    fun closeThen(scope: CoroutineScope, close: () -> Unit) {
        if (asking) {
            raise()
            return
        }
        if (shot == null) {
            close()
            return
        }
        asking = true
        scope.launch {
            try {
                if (closeForMirror()) close()
            } finally {
                asking = false
            }
        }
    }
}

/**
 * Copy or Save from the editor window: true when it worked. [say] shows a message in the editor window's own snackbar
 * (a failure, while the editor stays open).
 */
typealias EditorAction = suspend (shot: Shot, marks: List<Mark>, say: (String) -> Unit) -> Boolean

/** How the editor window opens: from [from] (a button's screen bounds, null: it appears) to [to]; [full] motion or fades. */
internal class EditorWindowPlan(val from: Rectangle?, val to: Rectangle, val full: Boolean)

/** Moves and resizes a window: [animateWindowBounds] with the screen geometry bound. */
internal typealias AnimateBounds = suspend (window: MirrorWindowHandle, to: Rectangle, millis: Int, grow: Boolean) -> Unit

/**
 * Opens the editor window as a mirror window opens. [EditorWindowPlan.full]: shown at the pressed button's bounds,
 * grown to its own over [MIRROR_ZOOM_IN_MILLIS], then the screenshot switches on ([wake] 0 to 1 over
 * [SCREEN_WAKE_MILLIS], emphasized decelerate); without a button it appears at its size and the screenshot switches
 * on. Otherwise (reduced motion, or window animations off): it appears at its size and fades in ([alpha], 150 ms), then
 * the screenshot fades in (150 ms). [show] makes the hidden window visible.
 */
internal suspend fun openEditorWindow(
    window: MirrorWindowHandle,
    plan: EditorWindowPlan,
    wake: Animatable<Float, AnimationVector1D>,
    show: () -> Unit,
    alpha: (Float) -> Unit,
    animateBounds: AnimateBounds,
) {
    val from = plan.from
    if (plan.full) {
        if (from != null) {
            showWindowAt(window, from, show)
            animateBounds(window, plan.to, MIRROR_ZOOM_IN_MILLIS, true)
        } else {
            show()
        }
        repeat(3) { withFrameNanos { } } // let the native window take its new size
        window.toFront() // its keys (Esc, Ctrl/Cmd+Z) work at once
        wake.animateTo(1f, tween(SCREEN_WAKE_MILLIS, easing = EmphasizedDecelerate))
    } else {
        alpha(0f)
        show()
        withFrameNanos { }
        window.toFront()
        Animatable(0f).animateTo(1f, tween(EDITOR_FADE_MILLIS)) { alpha(value) }
        wake.animateTo(1f, tween(EDITOR_FADE_MILLIS))
    }
}

/**
 * Closes the editor window as a mirror window closes. [full]: the screenshot switches off ([wake] 1 to 0 over
 * [SCREEN_SLEEP_MILLIS], emphasized accelerate), then a [floating] window shrinks into [into] (the thumbnail, read
 * now) over [MIRROR_ZOOM_OUT_MILLIS]; with no thumbnail on screen it shrinks to [MIRROR_SHRINK_IN_PLACE] around its
 * center while it fades ([alpha]). Otherwise the screenshot fades out, then the window (150 ms each).
 */
internal suspend fun closeEditorWindow(
    window: MirrorWindowHandle,
    full: Boolean,
    floating: Boolean,
    into: () -> Rectangle?,
    wake: Animatable<Float, AnimationVector1D>,
    alpha: (Float) -> Unit,
    animateBounds: AnimateBounds,
) {
    if (!full) {
        wake.animateTo(0f, tween(EDITOR_FADE_MILLIS))
        Animatable(1f).animateTo(0f, tween(EDITOR_FADE_MILLIS)) { alpha(value) }
        return
    }
    wake.animateTo(0f, tween(SCREEN_SLEEP_MILLIS, easing = EmphasizedAccelerate))
    if (!floating) return
    val target = into()
    if (target != null) {
        animateBounds(window, target, MIRROR_ZOOM_OUT_MILLIS, false)
    } else {
        coroutineScope {
            launch { Animatable(1f).animateTo(0f, tween(MIRROR_ZOOM_OUT_MILLIS, easing = EmphasizedAccelerate)) { alpha(value) } }
            animateBounds(window, shrunkAroundCenter(window.bounds, MIRROR_SHRINK_IN_PLACE), MIRROR_ZOOM_OUT_MILLIS, false)
        }
    }
}

/**
 * The markup editor's own window, "Edit screenshot · [deviceName]", while [editor] has a shot: resizable and movable,
 * at least [EDITOR_WINDOW_MIN_SIZE]. It opens on the screen of the mirror window ([near], its bounds) sized by
 * [editorWindowBounds], growing out of the pressed button and switching the screenshot on as a mirror window does
 * ([openEditorWindow]; [animate] is `Settings.animateMirrorWindows`, [motion] false under reduced motion). It closes
 * into the thumbnail ([thumbnail], its screen bounds while it shows) or in place ([closeEditorWindow]).
 *
 * The window's close button and Esc ask "Discard your marks?" when there are marks not saved or copied (Keep editing
 * leaves it open). Composed inside the mirror window's composition, so it goes with that window. Keys typed here are
 * this window's: they never reach the device.
 */
@Composable
fun MarkupEditorWindow(
    editor: MarkupEditorWindowState,
    deviceName: String,
    theme: AppTheme,
    animate: Boolean,
    motion: Boolean,
    near: () -> Rectangle?,
    thumbnail: () -> Rectangle?,
    onCopy: EditorAction,
    onSave: EditorAction,
    leases: ScreenshotThumbnailState,
    screenGeometry: ScreenGeometry,
) {
    val shot = editor.shot ?: return
    key(shot.id) {
        EditorWindow(editor, shot, deviceName, theme, animate, motion, near, thumbnail, onCopy, onSave, leases, screenGeometry)
    }
}

@Composable
private fun EditorWindow(
    editor: MarkupEditorWindowState,
    shot: Shot,
    deviceName: String,
    theme: AppTheme,
    animate: Boolean,
    motion: Boolean,
    near: () -> Rectangle?,
    thumbnail: () -> Rectangle?,
    onCopy: EditorAction,
    onSave: EditorAction,
    leases: ScreenshotThumbnailState,
    screenGeometry: ScreenGeometry,
) {
    val plan = remember {
        val full = animate && motion
        val origin = editor.origin
        val screen = usableScreenBounds(near() ?: origin ?: Rectangle(0, 0, 1, 1), screenGeometry.screens())
        EditorWindowPlan(
            from = origin?.takeIf { full },
            to = editorWindowBounds(shot.bitmap.width, shot.bitmap.height, screen),
            full = full,
        )
    }
    val start = plan.from ?: plan.to
    val windowState = rememberWindowState(
        position = WindowPosition(start.x.dp, start.y.dp),
        size = DpSize(start.width.dp, start.height.dp),
    )
    // Hidden until it is put where it grows from (or see-through, to fade in).
    var shown by remember { mutableStateOf(false) }
    val state = rememberMarkupEditorState(shot)
    val title = editorWindowTitle(deviceName)
    LocalNucleusApplicationScope.current.AppWindow(
        // The window goes only once the editor has closed: with marks this asks first.
        onCloseRequest = { state.requestClose() },
        title = title,
        theme = theme,
        state = windowState,
        visible = shown,
        reduceMotion = !motion,
        titleBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) {
        val window = nucleusWindow
        val handle = remember(window) { TaoMirrorWindowHandle(window, screenGeometry) }
        LaunchedEffect(editor) {
            // A request from before this window is not one to it.
            snapshotFlow { editor.raises }.drop(1).collect { bringToFront(window) }
        }
        CompositionLocalProvider(LocalMirrorWindowHandle provides handle) {
            MarkupEditorWindowBody(
                editor, shot, state, handle, plan, motion,
                show = { shown = true },
                alpha = { a -> handle.nsWindow?.let { setMacAlpha(it, a.toDouble()) } },
                floating = { windowState.placement == WindowPlacement.Floating },
                minimum = { on ->
                    val min = EDITOR_WINDOW_MIN_SIZE.takeIf { on }
                    window.unsafe.taoWindow?.setMinimumSize(min?.width?.value?.toDouble(), min?.height?.value?.toDouble())
                },
                thumbnail = thumbnail,
                onCopy = onCopy,
                onSave = onSave,
                leases = leases,
                animateBounds = { w, to, millis, grow -> animateWindowBounds(w, to, millis, grow, screenGeometry) },
            )
        }
    }
}

/**
 * What the editor window shows and plays: [MarkupEditorContent] for [state], opened by [openEditorWindow] as it
 * composes and closed by [closeEditorWindow] once the content closes ([MarkupEditorWindowState.leaving]), after which
 * [editor] lets the window go. [window] is the native window ([show] makes it visible, [alpha] sets its opacity).
 */
@Composable
internal fun MarkupEditorWindowBody(
    editor: MarkupEditorWindowState,
    shot: Shot,
    state: MarkupEditorState,
    window: MirrorWindowHandle,
    plan: EditorWindowPlan,
    motion: Boolean,
    show: () -> Unit,
    alpha: (Float) -> Unit,
    floating: () -> Boolean,
    /**
     * Sets (true) or lifts (false) the window's minimum size, [EDITOR_WINDOW_MIN_SIZE]. Only while it is open: a
     * minimum would clamp the grow from a button and the shrink into the thumbnail (Windows applies it to every move).
     */
    minimum: (Boolean) -> Unit,
    thumbnail: () -> Rectangle?,
    onCopy: EditorAction,
    onSave: EditorAction,
    leases: ScreenshotThumbnailState,
    animateBounds: AnimateBounds,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val wake = remember { Animatable(0f) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    DisposableEffect(editor, state) {
        if (editor.shot === shot) editor.editor = state
        // Gone with the mirror window: nothing waits on it any more.
        onDispose { editor.closed(shot) }
    }
    val leaving = editor.leaving
    LaunchedEffect(leaving) {
        if (!leaving) {
            openEditorWindow(window, plan, wake, show, alpha, animateBounds)
            minimum(true)
        } else {
            show() // closed before it showed
            minimum(false)
            closeEditorWindow(window, plan.full, floating(), thumbnail, wake, alpha, animateBounds)
            editor.closed(shot)
        }
    }
    Box(modifier) {
        MarkupEditorContent(
            shot, motion,
            onCopy = { s, marks -> onCopy(s, marks, say) },
            onSave = { s, marks -> onSave(s, marks, say) },
            onClose = editor::leave,
            leases = leases,
            modifier = Modifier.fillMaxSize(),
            state = state,
            wake = { wake.value },
            active = !leaving,
            onCloseDeclined = editor::declined,
            wakeFades = !plan.full,
        )
        // Copy and Save failures, in this window (it covers the mirror window's snackbar); above the footer.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp))
    }
}
