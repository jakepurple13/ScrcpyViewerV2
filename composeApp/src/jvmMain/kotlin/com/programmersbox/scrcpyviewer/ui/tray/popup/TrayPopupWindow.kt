package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.telemetry.ReportWindowCrashes
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import com.programmersbox.scrcpyviewer.ui.theme.stylePane
import com.programmersbox.scrcpyviewer.ui.window.NativeScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.launch.OverlayPlatform
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import java.awt.Rectangle
import kotlin.math.ceil

/** The popup pane's width in dp, as the scene tests draw it. */
const val POPUP_WIDTH = 360

/** Room around the pane inside the see-through window for its shadow. */
private const val SHADOW_MARGIN = 10

/** The window's width: the pane plus its shadow margin on both sides. */
private const val WINDOW_WIDTH = POPUP_WIDTH + 2 * SHADOW_MARGIN

/** Before anything was measured (the window is created hidden and draws once before it shows). */
private const val FIRST_HEIGHT = 240

private val PANE_SHAPE = RoundedCornerShape(14.dp)

/**
 * Where the popup hangs from: the click, or with no click location (Linux, the menu's "Show devices…") the middle of
 * the first screen's work area, which [popupBounds] turns into its top-right corner. Null with no screens.
 */
internal fun popupAnchorPoint(anchor: IntArray?, screens: List<ScreenArea>): IntArray? =
    anchor ?: screens.firstOrNull()?.workArea?.let { intArrayOf(it.x + it.width / 2, it.y + it.height / 2) }

/**
 * The window bounds for a pane [paneHeight] dp tall hanging from [anchor] (see [popupAnchorPoint]), shadow margin
 * included; null with no screens. The margin stands in for [popupBounds]' gap, so the pane sits a margin off the bars.
 */
internal fun popupWindowBounds(anchor: IntArray?, paneHeight: Int, screens: List<ScreenArea>): Rectangle? {
    val point = popupAnchorPoint(anchor, screens) ?: return null
    return popupBounds(point[0], point[1], WINDOW_WIDTH, paneHeight + 2 * SHADOW_MARGIN, screens, gap = 0)
}

/** The tallest the pane may be under [anchor]: its screen's work area less the margins. Null with no screens. */
internal fun popupMaxPaneHeight(anchor: IntArray?, screens: List<ScreenArea>): Int? {
    val point = popupAnchorPoint(anchor, screens) ?: return null
    val work = screenFor(Rectangle(point[0], point[1], 1, 1), screens)?.workArea ?: return null
    return (work.height - 2 * SHADOW_MARGIN).coerceAtLeast(0)
}

/** How far past the pane the window grows when the pane starts to grow, so most reflows need one resize. */
private const val GROW_HEADROOM = 240

/** How long the pane's height must hold still before the window shrinks to it. */
private const val SETTLE_MS = 150L

/**
 * The pane height the window makes room for, given the [room] it has now and the pane's current (possibly animating)
 * height [pane], both capped at [cap]. When the pane outgrows the room, the room jumps ahead by [GROW_HEADROOM] so the
 * grow animates inside a window that is already big enough. It shrinks only once the pane has [settled] (or the window
 * is hidden), so a shrink animates inside the old room; the spare room is see-through.
 */
internal fun popupRoomHeight(room: Int, pane: Int, cap: Int, settled: Boolean): Int {
    val target = minOf(pane, cap)
    return when {
        settled -> target
        target > room -> minOf(target + GROW_HEADROOM, cap)
        else -> room
    }
}

/**
 * Whether the popup hangs upward from [anchor] (a click below the work area: a bottom taskbar). The pane then sits at
 * the window's bottom edge, next to the taskbar, with any spare room above it.
 */
internal fun popupHangsUpward(anchor: IntArray?, screens: List<ScreenArea>): Boolean {
    val point = popupAnchorPoint(anchor, screens) ?: return false
    val work = screenFor(Rectangle(point[0], point[1], 1, 1), screens)?.workArea ?: return false
    return point[1] >= work.y + work.height
}

/**
 * The menu bar popup's window: see-through, frameless, on top and kept off the taskbar, created once and shown and
 * hidden with [TrayPopupController.visible], so it opens at once. It makes room for [content]'s height (at most the
 * work area, see [popupRoomHeight]; [content] gets that cap in dp as `maxHeight` and should scroll inside it) and hangs from the icon click.
 * It closes on focus loss and Esc. Opening scales up from about 0.9 from the corner nearest the icon, with a fade;
 * closing is the reverse, faster.
 */
@Composable
fun NucleusApplicationScope.TrayPopupWindow(
    controller: TrayPopupController,
    theme: AppTheme,
    content: @Composable (maxHeight: Int) -> Unit,
) {
    val visible by controller.visible.collectAsState()
    val anchor by controller.anchor.collectAsState()
    // Read again on each open: screens come and go, and the menu bar can move between them.
    // Kept while it closes, so the window doesn't move or resize under the close animation.
    val lastScreens = remember { arrayOf<List<ScreenArea>>(emptyList()) }
    val screens = remember(visible, anchor) {
        if (visible) NativeScreenGeometry.screens().also { lastScreens[0] = it } else lastScreens[0]
    }
    // Stays shown until the close animation ends.
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val windowVisible = shown.currentState || shown.targetState

    var paneHeight by remember { mutableIntStateOf(FIRST_HEIGHT) }
    val maxPane = popupMaxPaneHeight(anchor, screens) ?: 640
    // The window follows the pane's target, not every frame of its animation (see popupRoomHeight).
    var room by remember { mutableIntStateOf(FIRST_HEIGHT) }
    LaunchedEffect(paneHeight, maxPane, windowVisible) {
        if (!windowVisible) {
            room = popupRoomHeight(room, paneHeight, maxPane, settled = true)
            return@LaunchedEffect
        }
        room = popupRoomHeight(room, paneHeight, maxPane, settled = false)
        delay(SETTLE_MS)
        room = popupRoomHeight(room, paneHeight, maxPane, settled = true)
    }
    val bounds = popupWindowBounds(anchor, room, screens)
    val upward = popupHangsUpward(anchor, screens)

    val state = rememberWindowState(
        position = WindowPosition(Alignment.TopEnd),
        size = DpSize(WINDOW_WIDTH.dp, (FIRST_HEIGHT + 2 * SHADOW_MARGIN).dp),
    )
    // Position and size change in the same apply, so the window moves and resizes together.
    SideEffect {
        val size = DpSize(WINDOW_WIDTH.dp, (bounds?.height ?: (room + 2 * SHADOW_MARGIN)).dp)
        if (bounds != null) {
            val position = WindowPosition(bounds.x.dp, bounds.y.dp)
            if (state.position != position) state.position = position
        }
        // No screen geometry (some Linux desktops): the window manager places it, top right.
        if (state.size != size) state.size = size
    }

    // Read once, like every window root (AppWindow).
    val reduceMotion = remember { systemReducesMotion() }

    // Its Compose exceptions are reported (CrashCapture).
    ReportWindowCrashes {
        DecoratedWindow(
            onCloseRequest = controller::dismissed,
            state = state,
            visible = windowVisible,
            title = "Scrcpy Vwr",
            resizable = false,
            focusable = true,
            alwaysOnTop = true,
            undecorated = true,
            // macOS: hiddenFromDock switches the whole app to the accessory policy (no Dock icon, no menu bar), so it
            // stays off there; an extra window of the app adds no Dock tile anyway. Windows / Linux: per window.
            hiddenFromDock = !Os.isMac,
            onPreviewKeyEvent = { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    controller.dismissed()
                    true
                } else {
                    false
                }
            },
            transparent = true,
            forceX11 = OverlayPlatform.current() == OverlayPlatform.LinuxWayland,
        ) {
            // Bring it forward on open so losing focus can close it. (macOS non-activating behaviour comes later.)
            LaunchedEffect(visible) {
                if (visible) {
                    withFrameNanos { }
                    nucleusWindow.toFront()
                    nucleusWindow.requestFocus()
                }
            }
            val focused = LocalWindowInfo.current.isWindowFocused
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(visible, focused) {
                when {
                    !visible -> hadFocus = false
                    focused -> hadFocus = true
                    hadFocus -> {
                        hadFocus = false
                        controller.dismissed()
                    }
                }
            }
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                ScrcpyViewerTheme(theme) {
                    val transition = rememberTransition(shown, label = "tray popup")
                    val scale by transition.animateFloat(
                        transitionSpec = {
                            when {
                                reduceMotion -> snap<Float>()
                                targetState -> layoutSpring<Float>()
                                else -> tween<Float>(120)
                            }
                        },
                        label = "scale",
                    ) { if (it || reduceMotion) 1f else 0.9f }
                    val alpha by transition.animateFloat(
                        transitionSpec = { if (targetState) tween<Float>(150) else tween<Float>(100) },
                        label = "alpha",
                    ) { if (it) 1f else 0f }
                    val origin = originToward(anchor, bounds)
                    val density = LocalDensity.current
                    // The pane keeps to the anchor side; spare room (while the window waits for a shrink) is see-through.
                    Box(Modifier.fillMaxSize(), contentAlignment = if (upward) Alignment.BottomStart else Alignment.TopStart) {
                        Box(
                            Modifier
                                .padding(SHADOW_MARGIN.dp)
                                // Measured at its own height (up to the cap), whatever the window's height is now.
                                .wrapContentHeight(if (upward) Alignment.Bottom else Alignment.Top, unbounded = true)
                                .width(POPUP_WIDTH.dp)
                                .onSizeChanged { size ->
                                    paneHeight = ceil(size.height / density.density).toInt()
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                    transformOrigin = origin
                                }
                                .shadow(12.dp, PANE_SHAPE, clip = false)
                                .clip(PANE_SHAPE)
                                .styleBackdrop()
                                .background(MaterialTheme.colorScheme.surface)
                                .stylePane(PANE_SHAPE),
                        ) {
                            content(maxPane)
                        }
                    }
                }
            }
        }
    }
}

/** The pane corner nearest the icon click, so the popup grows out of the icon. Top-right without a click. */
private fun originToward(anchor: IntArray?, bounds: Rectangle?): TransformOrigin {
    if (anchor == null || bounds == null) return TransformOrigin(1f, 0f)
    val x = if (anchor[0] < bounds.x + bounds.width / 2) 0f else 1f
    val y = if (anchor[1] < bounds.y + bounds.height / 2) 0f else 1f
    return TransformOrigin(x, y)
}
