package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope

/**
 * Pictures of the main window's content for the launch and quit overlay. [recordInto] on the content keeps the last
 * drawn frame in a graphics layer of that window; [capture] turns it into a bitmap. A hidden window still draws, so a
 * picture is ready before the window is first shown.
 */
class WindowPicture {
    internal var layer: GraphicsLayer? = null

    /** The content's last drawn frame, or null before it has drawn (or if the layer can't be read). */
    suspend fun capture(): ImageBitmap? = runCatching { layer?.toImageBitmap() }.getOrNull()
}

/**
 * Records what this content draws into [picture] (and draws it as usual). Must be applied inside the window whose
 * content it records: the layer belongs to that window's graphics context.
 */
fun Modifier.recordInto(picture: WindowPicture): Modifier = composed {
    val layer = rememberGraphicsLayer()
    SideEffect { picture.layer = layer }
    drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/**
 * The see-through overlay that plays [anim]'s [phase] over the main window, which sits at [position] with [size]
 * (the main window's own window state values). It never takes focus or clicks and has no frame. Plays from the first
 * frame; [onFirstFrame] runs once that frame is drawn (the quit hides the real window then), [onEnd] after the last.
 * [picture] is read on every frame, so a picture that arrives during the launch is used from then on.
 *
 * Linux Wayland gives the overlay an XWayland surface ([forceX11]): Wayland clients can't place their own windows.
 */
@Composable
fun NucleusApplicationScope.LaunchOverlayWindow(
    position: WindowPosition,
    size: DpSize,
    anim: LaunchQuitAnimation,
    phase: LaunchPhase,
    picture: () -> ImageBitmap?,
    theme: AppTheme,
    onFirstFrame: () -> Unit = {},
    onEnd: () -> Unit,
    forceX11: Boolean = OverlayPlatform.current() == OverlayPlatform.LinuxWayland,
) {
    val state = rememberWindowState(position = position, size = size)
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val end by rememberUpdatedState(onEnd)
    DecoratedWindow(
        onCloseRequest = {},
        state = state,
        title = "",
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
        clickThrough = true,
        forceX11 = forceX11,
    ) {
        val duration = LaunchMotion.durationMs(anim, phase)
        var t by remember { mutableStateOf(0) }
        LaunchedEffect(anim, phase) {
            val start = withFrameNanos { it }
            firstFrame()
            while (t < duration) {
                val now = withFrameNanos { it }
                t = ((now - start) / 1_000_000).toInt().coerceAtMost(duration)
            }
            // One more frame so the last one is on screen before the hand-off.
            withFrameNanos { }
            end()
        }
        ScrcpyViewerTheme(theme) {
            val colors = MaterialTheme.colorScheme
            OverlayScene(
                LaunchMotion.frame(anim, phase, t), picture(),
                surface = colors.surface, titleBar = colors.surfaceContainer, accent = colors.primary,
            )
        }
    }
}
