package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Launch
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase.Quit

/** The Settings tiles' preview: a loop of the launch, the window held open, the quit, and a pause. */
object LaunchPreview {
    /** The tiles play at this fraction of real speed: at thumbnail size the real timing is over before it reads. */
    const val SPEED = 0.6f
    const val HOLD_MS = 700
    const val GAP_MS = 450

    /** Off: the window shows for this long, then is gone for [OFF_GONE_MS] (it just appears and disappears). */
    const val OFF_SHOWN_MS = 600
    const val OFF_GONE_MS = 250

    /** A tile loops its preview while hovered, unless the OS asks for less motion (then it plays once when picked). */
    fun loops(hovered: Boolean, reduceMotion: Boolean): Boolean = hovered && !reduceMotion

    fun loopMs(anim: LaunchQuitAnimation): Int =
        if (anim == LaunchQuitAnimation.Off) OFF_SHOWN_MS + OFF_GONE_MS
        else LaunchMotion.durationMs(anim, Launch) + HOLD_MS + LaunchMotion.durationMs(anim, Quit) + GAP_MS

    /** The open window, as a tile shows it when it isn't playing. */
    fun resting(anim: LaunchQuitAnimation): OverlayFrame {
        val shown = if (anim == LaunchQuitAnimation.Off) LaunchQuitAnimation.IconUnfold else anim
        return LaunchMotion.frame(shown, Launch, LaunchMotion.durationMs(shown, Launch))
    }

    /** The frame [tMs] into the loop (repeating). */
    fun frame(anim: LaunchQuitAnimation, tMs: Int): OverlayFrame {
        val t = tMs.mod(loopMs(anim))
        if (anim == LaunchQuitAnimation.Off) return resting(anim).copy(windowAlpha = if (t < OFF_SHOWN_MS) 1f else 0f)
        val launch = LaunchMotion.durationMs(anim, Launch)
        return when {
            t < launch -> LaunchMotion.frame(anim, Launch, t)
            t < launch + HOLD_MS -> resting(anim)
            else -> LaunchMotion.frame(anim, Quit, t - launch - HOLD_MS) // clamps to the last frame in the gap
        }
    }
}

/**
 * A small desktop with the app window on it, playing [anim]: looping while [looping], once each time [playOnce]
 * changes, otherwise still on the open window.
 */
@Composable
fun LaunchQuitPreview(anim: LaunchQuitAnimation, looping: Boolean, playOnce: Int, modifier: Modifier = Modifier) {
    var t by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(anim, looping, playOnce) {
        if (!looping && playOnce == 0) {
            t = null
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        val end = if (looping) Int.MAX_VALUE else LaunchPreview.loopMs(anim)
        while (true) {
            val elapsed = ((withFrameNanos { it } - start) / 1_000_000 * LaunchPreview.SPEED).toInt()
            if (elapsed >= end) break
            t = elapsed
        }
        t = null
    }
    val colors = MaterialTheme.colorScheme
    val picture = remember(colors.surface, colors.primaryContainer, colors.surfaceContainerHigh, colors.outlineVariant) {
        miniWindowPicture(colors.surface, colors.primaryContainer, colors.surfaceContainerHigh, colors.outlineVariant)
    }
    val frame = t?.let { LaunchPreview.frame(anim, it) } ?: LaunchPreview.resting(anim)
    // A wallpaper in the palette's container colors, so the window (surface) and the teal beam stand out against it.
    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.74f)) {
            OverlayScene(
                frame, picture, surface = colors.surface, titleBar = colors.surfaceContainerHighest, accent = colors.primary,
                iconFraction = 0.5f, windowOutline = colors.outline,
            )
        }
    }
}

/** A stand-in for the main window's content: a nav rail and three device rows, the first one selected. */
private fun miniWindowPicture(surface: Color, selected: Color, row: Color, line: Color): ImageBitmap {
    val w = 180
    val h = 92
    val image = ImageBitmap(w, h)
    val canvas = Canvas(image)
    val paint = Paint()
    fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Color, r: Float = 3f) {
        paint.color = color
        canvas.drawRoundRect(x, y, x + rw, y + rh, r, r, paint)
    }
    rect(0f, 0f, w.toFloat(), h.toFloat(), surface, 0f)
    rect(32f, 0f, 1f, h.toFloat(), line, 0f)
    for (i in 0 until 3) rect(9f, 10f + i * 18f, 14f, 12f, if (i == 0) selected else row, 4f)
    for (i in 0 until 3) rect(44f, 12f + i * 24f, 124f, 16f, if (i == 0) selected else row, 4f)
    return image
}
