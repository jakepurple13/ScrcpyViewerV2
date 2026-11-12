package com.programmersbox.scrcpyviewer

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The app icon (composeApp/icons/app-icon.svg), used for every window. The Dock / taskbar icon comes from the
 * packaging (.icns, .ico, .png); dev runs show the JVM's.
 */
object AppIcon {
    val painter: Painter by lazy {
        val bytes = AppIcon::class.java.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
}
