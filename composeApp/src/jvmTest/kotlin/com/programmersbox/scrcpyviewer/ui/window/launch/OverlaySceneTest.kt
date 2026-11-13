package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlaySceneTest {
    private val w = 400
    private val h = 250

    private fun solid(color: Int, width: Int = w, height: Int = h): ImageBitmap {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL)) }
        Canvas(bitmap).clear(color)
        return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun render(frame: OverlayFrame, picture: ImageBitmap?): androidx.compose.ui.graphics.PixelMap {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            OverlayScene(frame, picture, surface = Color.DarkGray, titleBar = Color.Gray, accent = Color.Cyan)
        }
        try {
            return scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }

    private fun isRed(c: Color) = c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f && c.alpha > 0.9f

    @Test
    fun `screen on reveals the window above the scan line only`() {
        val px = render(LaunchMotion.frame(LaunchQuitAnimation.ScreenOn, LaunchPhase.Launch, 260), solid(0xFFFF0000.toInt()))
        assertTrue(isRed(px[w / 2, (h * 0.25f).toInt()]), "above the line: ${px[w / 2, (h * 0.25f).toInt()]}")
        assertEquals(0f, px[w / 2, (h * 0.95f).toInt()].alpha, "below the line stays see-through")
    }

    @Test
    fun `icon unfold shows the icon in the middle and nothing at the edges`() {
        val px = render(LaunchMotion.frame(LaunchQuitAnimation.IconUnfold, LaunchPhase.Launch, 260), picture = null)
        assertTrue(px[w / 2, h / 2].alpha > 0.9f, "icon at the centre: ${px[w / 2, h / 2]}")
        assertEquals(0f, px[(w * 0.05f).toInt(), (h * 0.05f).toInt()].alpha, "corner stays see-through")
    }

    @Test
    fun `a picture shorter than the window leaves room for the title bar`() {
        // Content is 200 px tall in a 250 px window: the top 50 px is the title bar band.
        val px = render(LaunchMotion.frame(LaunchQuitAnimation.IconUnfold, LaunchPhase.Quit, 0), solid(0xFFFF0000.toInt(), w, 200))
        assertEquals(Color.Gray, px[w / 2, 20])
        assertTrue(isRed(px[w / 2, 150]))
    }
}
