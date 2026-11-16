package com.programmersbox.scrcpyviewer.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import javax.swing.SwingUtilities

/** Runs [block] on the Swing thread (Navigation 3 lifecycles require it) and rethrows its failure. */
fun onEdt(block: () -> Unit) {
    var error: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (t: Throwable) {
            error = t
        }
    }
    error?.let { throw it }
}

/** This rendered image's pixels, read onto the heap; the image and the bitmap copied from it are closed. */
fun org.jetbrains.skia.Image.pixelsAndClose(): androidx.compose.ui.graphics.PixelMap = use { image ->
    val bitmap = image.toComposeImageBitmap()
    try {
        bitmap.toPixelMap()
    } finally {
        bitmap.asSkiaBitmap().close()
    }
}

/** Drives an offscreen scene with a virtual clock: frames, mouse drags. */
@OptIn(ExperimentalComposeUiApi::class)
class SceneDriver(val scene: ImageComposeScene) {
    private var time = 0L

    fun frame() {
        time += 16_000_000
        // Each render snapshots the scene into a new native image. Closed at once, not left to the GC: with CI's
        // 2 GB heap the GC seldom runs, and these piled up by gigabytes of native memory.
        scene.render(time).close()
    }

    fun frames(count: Int) = repeat(count) { frame() }

    /** The next frame's pixels. */
    fun snapshot(): androidx.compose.ui.graphics.PixelMap {
        time += 16_000_000
        return scene.render(time).pixelsAndClose()
    }

    fun drag(fromX: Float, toX: Float, y: Float = 300f) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(fromX, y), type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, Offset(fromX, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        val steps = 20
        for (i in 1..steps) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(fromX + (toX - fromX) * i / steps, y), type = PointerType.Mouse)
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(toX, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frames(60)
    }
}
