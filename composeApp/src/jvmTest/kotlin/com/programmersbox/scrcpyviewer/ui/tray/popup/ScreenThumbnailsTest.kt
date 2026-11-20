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
    fun `nothing is copied without a capture`() = runTest {
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
    fun `a capture copies each device once and never refreshes`() = runTest {
        var rounds = 0
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { rounds++; mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.capture()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, rounds)
        assertEquals(setOf("a"), thumbs.images.value.keys)
    }

    @Test
    fun `each open captures again`() = runTest {
        var rounds = 0
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { rounds++; mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.capture()
        runCurrent()
        thumbs.clear()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, rounds)
        thumbs.capture()
        runCurrent()
        assertEquals(2, rounds)
        assertEquals(setOf("a"), thumbs.images.value.keys)
    }

    @Test
    fun `one device failing skips only that device`() = runTest {
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { mapOf("bad" to { error("skia copy failed") }, "a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.capture()
        runCurrent()
        assertEquals(setOf("a"), thumbs.images.value.keys)
    }

    @Test
    fun `clearing drops images`() = runTest {
        val thumbs = ScreenThumbnails(
            backgroundScope,
            frames = { mapOf("a" to { frame }) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        thumbs.capture()
        runCurrent()
        assertTrue(thumbs.images.value.isNotEmpty())
        thumbs.clear()
        runCurrent()
        assertTrue(thumbs.images.value.isEmpty())
    }
}
