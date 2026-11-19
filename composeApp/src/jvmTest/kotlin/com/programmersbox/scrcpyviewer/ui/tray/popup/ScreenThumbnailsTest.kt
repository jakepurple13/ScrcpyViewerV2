package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.media.VideoFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenThumbnailsTest {
    private val frame = VideoFrame.of(Bitmap().apply {
        allocPixels(ImageInfo(40, 80, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        erase(0xFF336699.toInt())
    })

    @Test
    fun `inactive refresher copies nothing`() = runTest {
        var rounds = 0
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { rounds++; mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, rounds)
        assertTrue(thumbs.images.value.isEmpty())
    }

    @Test
    fun `active refresher copies once a second`() = runTest {
        var rounds = 0
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { rounds++; mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.setActive(true)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(rounds in 3..4, "rounds = $rounds")
        assertEquals(setOf("a"), thumbs.images.value.keys)
    }

    @Test
    fun `deactivating clears images`() = runTest {
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.setActive(true)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(thumbs.images.value.isNotEmpty())
        thumbs.setActive(false)
        runCurrent()
        assertTrue(thumbs.images.value.isEmpty())
    }
}
