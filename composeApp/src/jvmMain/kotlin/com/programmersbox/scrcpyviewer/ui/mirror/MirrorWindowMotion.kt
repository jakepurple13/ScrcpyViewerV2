package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.programmersbox.scrcpyviewer.ui.window.animateMacFrame
import kotlinx.coroutines.delay
import com.sun.jna.Function
import com.sun.jna.ptr.IntByReference
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt

/** The mirror window grows out of the Mirror button over this long. */
const val MIRROR_ZOOM_IN_MILLIS = 380

/**
 * The window's growth curve (cubic-bezier x1, y1, x2, y2): decelerating, but gentler at the start than the emphasized
 * curve. Every step resizes a native window, so a curve that covers a third of the way in its first frame reads as a jump.
 */
private val ZOOM_IN_CURVE = floatArrayOf(0.2f, 0f, 0f, 1f)

/** The window's shrink on close: the app's emphasized-accelerate curve. */
private val ZOOM_OUT_CURVE = floatArrayOf(0.3f, 0f, 0.8f, 0.15f)

/**
 * Moves and resizes [window] to [to] over [millis], growing ([grow]) or shrinking. On macOS AppKit animates the
 * frame (smooth even while the content redraws); elsewhere, or if that cannot start, one setBounds per frame.
 */
internal suspend fun animateWindowBounds(window: Window, to: Rectangle, millis: Int, grow: Boolean) {
    val curve = if (grow) ZOOM_IN_CURVE else ZOOM_OUT_CURVE
    if (window.animateMacFrame(to, millis, curve)) {
        delay(millis + 20L)
        window.bounds = to // in step with AppKit's last frame, should a resize event be still on its way
        return
    }
    val from = window.bounds
    val easing = CubicBezierEasing(curve[0], curve[1], curve[2], curve[3])
    Animatable(0f).animateTo(1f, tween(millis, easing = easing)) { window.bounds = lerpBounds(from, to, value) }
}

/** The device screen switches on over this long, once the first frame is in and the window has its size. */
const val SCREEN_WAKE_MILLIS = 320

/** On close the screen collapses to a line over this long, then the window shrinks back over [MIRROR_ZOOM_OUT_MILLIS]. */
const val SCREEN_SLEEP_MILLIS = 200
const val MIRROR_ZOOM_OUT_MILLIS = 280

/** A press on a Mirror button older than this is not the one that opened a window. */
private const val ORIGIN_MAX_AGE_MS = 1_500L

/**
 * Where the next mirror window grows from: the screen bounds (AWT units) of the Mirror button last pressed. A window
 * takes it once as it opens; a window opened some other way (double-click, menu) finds none, or only a stale one.
 */
object MirrorOrigins {
    private class Press(val bounds: Rectangle, val atMs: Long)

    @Volatile
    private var last: Press? = null

    fun record(bounds: Rectangle, nowMs: Long = System.currentTimeMillis()) {
        last = Press(Rectangle(bounds), nowMs)
    }

    /** The recorded bounds when pressed within [ORIGIN_MAX_AGE_MS] of [nowMs]; cleared either way. */
    @Synchronized
    fun take(nowMs: Long = System.currentTimeMillis()): Rectangle? {
        val press = last ?: return null
        last = null
        return press.bounds.takeIf { nowMs - press.atMs in 0..ORIGIN_MAX_AGE_MS }
    }

    // The Mirror buttons on screen now, by the serial they mirror, so a closing window can shrink into its button.
    private val buttons = java.util.concurrent.ConcurrentHashMap<String, () -> Rectangle?>()

    internal fun registerButton(serial: String, locate: () -> Rectangle?) {
        buttons[serial] = locate
    }

    internal fun unregisterButton(serial: String, locate: () -> Rectangle?) {
        buttons.remove(serial, locate)
    }

    /** Where [serial]'s Mirror button is on screen now, or null when the main window does not show it. */
    fun buttonOnScreen(serial: String): Rectangle? =
        buttons[serial]?.invoke()?.takeIf { it.width > 1 && it.height > 1 }
}

/** [r] scaled by [scale] around its center. */
internal fun shrunkAroundCenter(r: Rectangle, scale: Float): Rectangle {
    val w = (r.width * scale).roundToInt()
    val h = (r.height * scale).roundToInt()
    return Rectangle(r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h)
}

/** With no Mirror button to land on, a closing window shrinks to this share of its size before it goes. */
const val MIRROR_SHRINK_IN_PLACE = 0.9f

/** Holds a composable's layout so its screen bounds can be read on press. A plain holder: never read in composition. */
private class OriginTracker(val window: Window?, val density: Float) {
    var coordinates: LayoutCoordinates? = null

    fun screenBounds(): Rectangle? = try {
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        val w = window?.takeIf { it.isShowing } ?: return null
        // Compose lays out in pixels from the content pane's corner; AWT works in points.
        val root = (w as? RootPaneContainer)?.contentPane?.takeIf { it.isShowing } ?: w
        val at = root.locationOnScreen
        val r = c.boundsInWindow()
        Rectangle(
            at.x + (r.left / density).roundToInt(),
            at.y + (r.top / density).roundToInt(),
            (r.width / density).roundToInt().coerceAtLeast(1),
            (r.height / density).roundToInt().coerceAtLeast(1),
        )
    } catch (_: Exception) {
        null // not on screen any more
    }
}

/**
 * Marks [serial]'s Mirror button: a press records its screen bounds in [MirrorOrigins], so the window it opens grows
 * from it, and a closing window of [serial] shrinks back into it while it is on screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.mirrorOrigin(serial: String?): Modifier {
    val window = LocalAwtWindow.current
    val density = LocalDensity.current.density
    val tracker = remember(window, density) { OriginTracker(window, density) }
    if (serial != null) {
        DisposableEffect(serial, tracker) {
            val locate: () -> Rectangle? = tracker::screenBounds
            MirrorOrigins.registerButton(serial, locate)
            onDispose { MirrorOrigins.unregisterButton(serial, locate) }
        }
    }
    return onGloballyPositioned { tracker.coordinates = it }
        .pointerInput(tracker) {
            awaitPointerEventScope {
                while (true) {
                    // Initial pass, nothing consumed: the button still gets its click.
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                        tracker.screenBounds()?.let(MirrorOrigins::record)
                    }
                }
            }
        }
}

/** Linear interpolation between two window bounds; [t] 0 gives [from], 1 gives [to]. */
internal fun lerpBounds(from: Rectangle, to: Rectangle, t: Float): Rectangle {
    fun lerp(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
    return Rectangle(lerp(from.x, to.x), lerp(from.y, to.y), lerp(from.width, to.width), lerp(from.height, to.height))
}

/**
 * Where a window growing out of a Mirror button ends: [width] by [height] (no larger than [screen], the usable area of
 * the screen the button is on), at [x] / [y] when the options set them, else centered on [screen].
 */
internal fun zoomTarget(x: Int?, y: Int?, width: Int, height: Int, screen: Rectangle): Rectangle {
    val w = width.coerceAtMost(screen.width)
    val h = height.coerceAtMost(screen.height)
    return Rectangle(
        x ?: (screen.x + (screen.width - w) / 2),
        y ?: (screen.y + (screen.height - h) / 2),
        w, h,
    )
}

/** The usable bounds (less menu bar, Dock or taskbar) of the screen holding [origin]'s center; the nearest screen's if none, else a default. */
internal fun usableScreenBounds(origin: Rectangle, screens: List<ScreenArea>): Rectangle {
    val center = Rectangle(origin.centerX.roundToInt(), origin.centerY.roundToInt(), 1, 1)
    return screenFor(center, screens)?.workArea ?: Rectangle(0, 0, 1280, 800)
}

/** The device screen at a point of its wake: scales of the stage and how bright the white flash over it is. */
internal data class WakeFrame(val scaleX: Float, val scaleY: Float, val flash: Float, val visible: Boolean)

/** A line's height as a fraction of the stage: the screen is this thin while it is a bright line. */
internal const val WAKE_LINE = 0.004f

/** The fraction of the wake spent widening the line before it opens into the screen. */
private const val WAKE_LINE_PART = 0.35f

/**
 * [progress] 0 is off (hidden), 1 is fully on. The screen first shows as a bright line that widens from 60%
 * to the full width, then opens to its full height while the brightness fades. Closing runs it backwards.
 */
internal fun wakeFrame(progress: Float): WakeFrame {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return WakeFrame(0.6f, WAKE_LINE, 1f, visible = false)
    if (p < WAKE_LINE_PART) {
        val t = p / WAKE_LINE_PART
        return WakeFrame(0.6f + 0.4f * t, WAKE_LINE, 1f, visible = true)
    }
    val t = (p - WAKE_LINE_PART) / (1f - WAKE_LINE_PART)
    return WakeFrame(1f, WAKE_LINE + (1f - WAKE_LINE) * t, 1f - t, visible = true)
}

/**
 * The stage while the window animates: black with a dim breathing line until the screen wakes, then [content]
 * scaled per [wakeFrame]. [wake] is read at draw time only. [standby] shows the line (false once disconnected).
 */
@Composable
internal fun WakeStage(wake: () -> Float, standby: Boolean, content: @Composable () -> Unit) {
    val asleep by remember { derivedStateOf { wake() <= 0f } }
    Box(Modifier.fillMaxSize().drawBehind { if (wake() < 1f) drawRect(Color.Black) }) {
        Box(
            Modifier.matchParentSize().graphicsLayer {
                val f = wakeFrame(wake())
                alpha = if (f.visible) 1f else 0f
                scaleX = f.scaleX
                scaleY = f.scaleY
            },
        ) {
            content()
            Box(Modifier.matchParentSize().graphicsLayer { alpha = wakeFrame(wake()).flash }.background(Color.White))
        }
        if (asleep && standby) StandbyLine()
    }
}

/** A dim line across the middle of the dark stage, breathing while the first frame is on its way. */
@Composable
private fun StandbyLine() {
    val breath = rememberInfiniteTransition(label = "standby")
    val t by breath.animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    Canvas(Modifier.fillMaxSize()) {
        val half = size.width * 0.3f * (1f - 0.3f * t)
        val y = size.height / 2
        drawLine(
            Color.White.copy(alpha = 0.35f - 0.2f * t), Offset(size.width / 2 - half, y), Offset(size.width / 2 + half, y),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/** Whether the OS asks for less motion: Reduce motion on macOS, animations off on Windows. False when unknown. */
fun systemReducesMotion(): Boolean = try {
    when {
        Os.isMac -> {
            val workspace = ObjC.msgPtr(ObjC.cls("NSWorkspace"), "sharedWorkspace")
            workspace != null && ObjC.msgBool(workspace, "accessibilityDisplayShouldReduceMotion")
        }
        Os.isWindows -> {
            val on = IntByReference(1)
            val spiGetClientAreaAnimation = 0x1042
            val ok = Function.getFunction("user32", "SystemParametersInfoW")
                .invokeInt(arrayOf(spiGetClientAreaAnimation, 0, on.pointer, 0)) != 0
            ok && on.value == 0
        }
        else -> false
    }
} catch (_: Throwable) {
    false
}
