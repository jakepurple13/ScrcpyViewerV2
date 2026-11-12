package com.programmersbox.scrcpyviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Renders [content] offscreen at [width]x[height] px and writes a PNG to [out]. With [settleMillis],
 * a first frame runs effects and the PNG shows the frame [settleMillis] later (animations finished).
 */
fun renderToPng(width: Int, height: Int, out: Path, settleMillis: Long = 0, content: @Composable () -> Unit) {
    // Navigation 3 entries own lifecycles, which must change state on the main (Swing) thread.
    if (SwingUtilities.isEventDispatchThread()) return renderOnThisThread(width, height, out, settleMillis, content)
    try {
        SwingUtilities.invokeAndWait { renderOnThisThread(width, height, out, settleMillis, content) }
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun renderOnThisThread(width: Int, height: Int, out: Path, settleMillis: Long, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width, height, Density(1f), content = content)
    try {
        if (settleMillis > 0) scene.render(0)
        // A frame halfway lets content composed during layout (lazy items) start its animations before the last one.
        if (settleMillis > 0) scene.render(settleMillis * 500_000)
        val image = if (settleMillis > 0) scene.render(settleMillis * 1_000_000) else scene.render()
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }
        Files.createDirectories(out.parent)
        Files.write(out, data.bytes)
    } finally {
        scene.close()
    }
}
