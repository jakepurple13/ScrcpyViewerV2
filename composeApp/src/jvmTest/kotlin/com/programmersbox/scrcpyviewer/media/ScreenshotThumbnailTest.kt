package com.programmersbox.scrcpyviewer.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScreenshotThumbnailTest {
    private fun frame(w: Int, h: Int): VideoFrame {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
            erase(0xFF336699.toInt())
        }
        return VideoFrame.of(bitmap)
    }

    @Test
    fun `thumbnail fits inside the box keeping aspect`() {
        val thumb = assertNotNull(Screenshot.thumbnail(frame(1080, 2400), 380, 760))
        assertEquals(342, thumb.width)
        assertEquals(760, thumb.height)
    }

    @Test
    fun `thumbnail never upscales`() {
        val thumb = assertNotNull(Screenshot.thumbnail(frame(100, 200), 380, 760))
        assertEquals(100, thumb.width)
        assertEquals(200, thumb.height)
    }

    @Test
    fun `released frame gives null`() {
        val f = frame(100, 200)
        f.release()
        assertNull(Screenshot.thumbnail(f, 380, 760))
    }
}
