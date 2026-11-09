package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The first frame fades, grows (from [REVEAL_START_SCALE]) and sharpens (from [REVEAL_BLUR]) in over this long. */
const val REVEAL_MILLIS = 420

/** A device rotation turns the new frame from ±90° to upright over this long. */
const val TURN_MILLIS = 460

/** A disconnected stage's last frame goes grey (and back, on reconnect) over this long. */
const val GREY_MILLIS = 500

private const val REVEAL_START_SCALE = 0.94f
private val REVEAL_BLUR = 10.dp
private val GREY_BLUR = 3.dp

/** Brightness of a fully grey (disconnected) frame. */
private const val GREY_BRIGHTNESS = 0.7f

/** How many frames a turn waits for the decoder to deliver a frame of the new shape before it gives up. */
private const val TURN_WAIT_FRAMES = 30

private fun Float.lerpTo(to: Float, t: Float) = this + (to - this) * t

private fun IntSize.isLandscape() = width > height

/**
 * Where a frame that turned from [old] to [new] (displayed sizes) starts its turn animation, in degrees clockwise
 * (it then turns to 0), or null when the frame did not turn between portrait and landscape.
 *
 * The frame sizes cannot tell which way the device turned, so the most common turn is assumed: portrait to
 * landscape is a counter-clockwise turn of the device (Android's ROTATION_90), so the new frame starts turned +90°
 * (lined up with the device as it was) and turns back counter-clockwise; landscape to portrait is the reverse. A
 * mirrored [flip] display (`flip*` display orientations) turns the other way.
 */
fun turnStartDegrees(old: IntSize?, new: IntSize, flip: Boolean = false): Float? {
    if (old == null || old.width <= 0 || old.height <= 0 || new.width <= 0 || new.height <= 0) return null
    if (old.isLandscape() == new.isLandscape() || old.width == old.height || new.width == new.height) return null
    val degrees = if (new.isLandscape()) 90f else -90f
    return if (flip) -degrees else degrees
}

/**
 * The scale a turned image of [newImage] size (its unrotated displayed size) starts at so that, a quarter turn
 * around, it covers what the old image of [oldImage] size covered: the turned image is [newImage] with width and
 * height swapped. The smaller ratio, so it never starts larger than the old image in either direction.
 */
fun turnStartScale(oldImage: Size, newImage: Size): Float {
    if (newImage.width <= 0f || newImage.height <= 0f || oldImage.width <= 0f || oldImage.height <= 0f) return 1f
    return minOf(oldImage.width / newImage.height, oldImage.height / newImage.width)
}

/**
 * The largest scale (at most 1) at which an [image] turned by [degrees] about its centre still fits a [stage]
 * centred on it: a turned rectangle's bounding box is wider and taller than the rectangle.
 */
fun turnFitScale(image: Size, stage: Size, degrees: Float): Float {
    if (image.width <= 0f || image.height <= 0f || stage.width <= 0f || stage.height <= 0f) return 1f
    val rad = Math.toRadians(degrees.toDouble())
    val cos = abs(cos(rad)).toFloat()
    val sin = abs(sin(rad)).toFloat()
    val boxW = image.width * cos + image.height * sin
    val boxH = image.width * sin + image.height * cos
    return minOf(1f, stage.width / boxW, stage.height / boxH)
}

/**
 * The color filter of a disconnected frame [progress] (0..1) of the way to grey: saturation from 1 to 0 and
 * brightness from 1 to [GREY_BRIGHTNESS]. Null at 0, so a live stage draws without a filter.
 */
fun greyColorFilter(progress: Float): ColorFilter? {
    if (progress <= 0f) return null
    val p = progress.coerceAtMost(1f)
    val matrix = ColorMatrix().apply { setToSaturation(1f - p) }
    val brightness = 1f.lerpTo(GREY_BRIGHTNESS, p)
    matrix.timesAssign(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
    return ColorFilter.colorMatrix(matrix)
}

/**
 * The stage image's motion: the first-frame [reveal], the rotation [turn] and the disconnect [grey], each a 0..1
 * progress. They are read only inside [stageMotionLayer] and the stage canvas's draw, so a running animation
 * redraws the stage without recomposing it, and a settled stage (reveal and turn at 1, grey at 0) costs nothing.
 */
class StageMotion(revealed: Boolean, disconnected: Boolean) {
    val reveal = Animatable(if (revealed) 1f else 0f)
    val turn = Animatable(1f)
    val grey = Animatable(if (disconnected) 1f else 0f)

    /** The running turn: where it started (degrees) and the size (px) of the image as it was before the turn. */
    internal var turnFrom = 0f
    internal var turnOldImage = Size.Zero

    /** The oriented frame size the turn effect last saw (a plain holder). */
    internal var lastFrame: IntSize? = null

    /** The size (px) of the stage canvas, kept by [stageMotionLayer] (a plain holder). */
    internal var canvasSize = IntSize.Zero
}

/**
 * The stage image's motion, driven by the session: the first frame is revealed once [revealed] (and hidden again,
 * at once, when a new session starts without one), the last frame greys out while [disconnected], and a frame of
 * [frameSize] (displayed size) that turned between portrait and landscape turns into place. [oldImageSize] gives the
 * size (px) the old frame was shown at, for the canvas size the stage had.
 */
@Composable
fun rememberStageMotion(
    revealed: Boolean,
    disconnected: Boolean,
    frameSize: IntSize?,
    flip: Boolean,
    latestFrame: () -> VideoFrame?,
    orientation: DisplayOrientation,
    oldImageSize: (old: IntSize, canvas: IntSize) -> Size,
): StageMotion {
    val motion = remember { StageMotion(revealed, disconnected) }
    LaunchedEffect(motion, revealed) {
        if (revealed) motion.reveal.animateTo(1f, tween(REVEAL_MILLIS, easing = EmphasizedDecelerate)) else motion.reveal.snapTo(0f)
    }
    LaunchedEffect(motion, disconnected) {
        motion.grey.animateTo(if (disconnected) 1f else 0f, tween(GREY_MILLIS, easing = EmphasizedDecelerate))
    }
    LaunchedEffect(motion, frameSize) {
        val old = motion.lastFrame
        motion.lastFrame = frameSize ?: old
        if (frameSize == null) return@LaunchedEffect
        val from = turnStartDegrees(old, frameSize, flip) ?: return@LaunchedEffect
        if (motion.reveal.value < 1f || old == null) return@LaunchedEffect // nothing on screen to turn from
        val oldImage = oldImageSize(old, motion.canvasSize)
        // The session announces the new size before the decoder delivers a frame of it: wait for that frame, so
        // the old frame is never drawn turned. Checked before the frame's draw, so the new frame's first draw turns.
        var waited = 0
        while (waited++ < TURN_WAIT_FRAMES) {
            val drawn = latestFrame()?.let { orientation.orientedSize(IntSize(it.width, it.height)) }
            if (drawn != null && drawn.isLandscape() == frameSize.isLandscape()) break
            withFrameNanos { }
        }
        motion.turnFrom = from
        motion.turnOldImage = oldImage
        motion.turn.snapTo(0f)
        motion.turn.animateTo(1f, tween(TURN_MILLIS, easing = EmphasizedDecelerate))
    }
    return motion
}

/**
 * Applies [motion] to the stage canvas: alpha, scale and blur of the reveal, rotation and scale of the turn, and the
 * blur of the grey (its color filter is applied where the frame is drawn, [greyColorFilter]). [image] gives the
 * displayed image's rectangle inside the canvas of a given size. All reads happen in the layer block.
 */
fun Modifier.stageMotionLayer(motion: StageMotion, image: (Size) -> Rect): Modifier = graphicsLayer {
    motion.canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    val reveal = motion.reveal.value
    val turn = motion.turn.value
    val grey = motion.grey.value
    var scale = 1f
    if (reveal < 1f) {
        alpha = reveal
        scale *= REVEAL_START_SCALE.lerpTo(1f, reveal)
    }
    if (turn < 1f) {
        val degrees = motion.turnFrom * (1f - turn)
        val shown = image(size).size
        rotationZ = degrees
        // From the old image's size to the new one, and never out of the stage while the corners swing round.
        scale *= minOf(turnStartScale(motion.turnOldImage, shown).lerpTo(1f, turn), turnFitScale(shown, size, degrees))
    }
    scaleX = scale
    scaleY = scale
    val blur = maxOf((1f - reveal) * REVEAL_BLUR.toPx(), grey * GREY_BLUR.toPx())
    renderEffect = if (blur >= 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
}
