package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws one [frame] of a launch or quit animation over a see-through background, sized to the main window: the window
 * [picture] (its content below the title bar; the title bar is a band of [titleBar]) clipped, scaled and brightened as
 * the frame says, the Screen on scan line in [accent], and the app icon. Without a picture the window is a plain
 * [surface] shape with the title bar band.
 */
@Composable
fun OverlayScene(
    frame: OverlayFrame,
    picture: ImageBitmap?,
    surface: Color,
    titleBar: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    /** The icon's size as a fraction of the window's shorter side; the Settings previews draw it larger. */
    iconFraction: Float = 0.22f,
    /** A hairline around the window, so a small preview's window stands out from its desktop. */
    windowOutline: Color? = null,
) {
    Canvas(modifier.fillMaxSize()) {
        drawWindow(frame, picture, surface, titleBar, windowOutline)
        drawBeam(frame, accent)
        drawIcon(frame, accent, iconFraction)
    }
}

/** Title bar height when there is no picture to measure it from. */
private val FALLBACK_TITLE_BAR = 38.dp

private fun DrawScope.drawWindow(frame: OverlayFrame, picture: ImageBitmap?, surface: Color, titleBar: Color, outline: Color?) {
    if (frame.windowAlpha <= 0f) return
    val clip = frame.windowClip
    val rect = RoundRect(
        clip.left * size.width, clip.top * size.height, clip.right * size.width, clip.bottom * size.height,
        CornerRadius(frame.windowCorner.dp.toPx()),
    )
    withTransform({ scale(frame.windowScaleX, frame.windowScaleY, center) }) {
        clipPath(Path().apply { addRoundRect(rect) }) {
            val contentHeight = picture?.let { it.height * size.width / it.width } ?: (size.height - FALLBACK_TITLE_BAR.toPx())
            val bar = (size.height - contentHeight).coerceAtLeast(0f)
            drawRect(titleBar, size = Size(size.width, bar), alpha = frame.windowAlpha)
            if (picture != null) {
                drawImage(
                    picture,
                    dstOffset = IntOffset(0, bar.roundToInt()),
                    dstSize = IntSize(size.width.roundToInt(), contentHeight.roundToInt()),
                    alpha = frame.windowAlpha,
                )
            } else {
                drawRect(surface, topLeft = Offset(0f, bar), alpha = frame.windowAlpha)
            }
            // Brightness above 1 washes toward white: 2.5 is nearly white, 1.4 a light flash.
            val wash = ((frame.windowBrightness - 1f) / 1.8f).coerceIn(0f, 0.9f)
            if (wash > 0f) drawRect(Color.White, alpha = wash * frame.windowAlpha)
        }
        if (outline != null) {
            drawPath(Path().apply { addRoundRect(rect) }, outline, alpha = frame.windowAlpha, style = Stroke(1.dp.toPx()))
        }
    }
}

private fun DrawScope.drawBeam(frame: OverlayFrame, accent: Color) {
    val y = frame.beamY ?: return
    if (frame.beamAlpha <= 0f) return
    val py = y * size.height
    // A soft glow under a 3 dp line.
    drawLine(accent.copy(alpha = 0.25f * frame.beamAlpha), Offset(0f, py), Offset(size.width, py), strokeWidth = 14.dp.toPx())
    drawLine(accent.copy(alpha = frame.beamAlpha), Offset(0f, py), Offset(size.width, py), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Butt)
}

/**
 * The app icon (composeApp/icons/app-icon.svg, a 512 unit square) centred on the window: a dark rounded tile, the
 * back phone's outline, the front phone, and its screen, which flashes [accent].
 */
private fun DrawScope.drawIcon(frame: OverlayFrame, accent: Color, fraction: Float) {
    if (frame.iconAlpha <= 0f || frame.iconScale <= 0f) return
    val side = fraction * min(size.width, size.height) * frame.iconScale
    val u = side / 512f
    val origin = Offset(center.x - side / 2, center.y - side / 2 + frame.iconOffsetY * size.height)
    val a = frame.iconAlpha
    fun r(x: Float, y: Float) = Offset(origin.x + x * u, origin.y + y * u)
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF1D2A2A), Color(0xFF0B1111)), r(16f, 16f), r(496f, 496f)),
        topLeft = r(16f, 16f), size = Size(480 * u, 480 * u), cornerRadius = CornerRadius(112 * u), alpha = a,
    )
    val back = frame.backPhoneProgress
    translate(-46f * u * (1f - back), -24f * u * (1f - back)) {
        drawRoundRect(
            Color(0xFF4CDADA), topLeft = r(204f, 128f), size = Size(150 * u, 270 * u), cornerRadius = CornerRadius(30 * u),
            style = Stroke(14 * u), alpha = 0.55f * a * back,
        )
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF9CF1F0), Color(0xFF4CDADA)), origin.y + 104 * u, origin.y + 374 * u),
        topLeft = r(158f, 104f), size = Size(150 * u, 270 * u), cornerRadius = CornerRadius(30 * u), alpha = a,
    )
    drawRoundRect(
        lerp(Color(0xFF004F4F), accent, frame.screenFlash),
        topLeft = r(176f, 128f), size = Size(114 * u, 210 * u), cornerRadius = CornerRadius(14 * u), alpha = a,
    )
}
