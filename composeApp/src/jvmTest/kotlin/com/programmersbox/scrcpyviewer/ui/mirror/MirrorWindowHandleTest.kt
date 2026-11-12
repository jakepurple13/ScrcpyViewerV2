package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A window that only records what is done to it. */
private class FakeMirrorWindowHandle(initial: Rectangle) : MirrorWindowHandle {
    val sets = mutableListOf<Rectangle>()
    private var current = Rectangle(initial)
    override var bounds: Rectangle
        get() = Rectangle(current)
        set(value) {
            sets += Rectangle(value)
            current = Rectangle(value)
        }
    override val size: IntSize get() = IntSize(current.width, current.height)
    override val isShowing = true
    override val focused: StateFlow<Boolean> = MutableStateFlow(true)
    override val focusEvents: SharedFlow<Boolean> = MutableSharedFlow()
    override val nsWindow: Pointer? = null
    override fun toFront() = Unit
    override fun screen(): ScreenArea? = null
}

/** A virtual clock: 16 ms a frame. */
private class Frames : MonotonicFrameClock {
    var time = 0L
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        time += 16_000_000
        return onFrame(time)
    }
}

class MirrorWindowHandleTest {
    @Test
    fun sheetGrowth_setsBoundsPerFrame() = runBlocking {
        val screenRight = 1920
        val base = Rectangle(1200, 40, 600, 800)
        // The user dragged the height meanwhile: every frame keeps the window's current height, not the base's.
        val handle = FakeMirrorWindowHandle(Rectangle(1200, 40, 600, 830))
        val growth = Animatable(0f)
        val values = mutableListOf<Float>()
        val shifts = mutableListOf<Int>()
        val progress = mutableListOf<Float>()
        withContext(Frames()) {
            growForSheet(handle, growth, base, 280f, tween(160), screenRight) { shift, p ->
                values += growth.value
                shifts += shift
                progress += p
            }
        }
        assertTrue(handle.sets.size > 3, "one setBounds per frame, got ${handle.sets.size}")
        assertEquals(values.map { sheetFrameBounds(base, it.roundToInt(), screenRight, 830) }, handle.sets)
        assertEquals(handle.sets.map { base.x - it.x }, shifts)
        assertEquals(Rectangle(1040, 40, 880, 830), handle.sets.last())
        assertEquals(1f, progress.last())
        assertEquals(280f, growth.value)
    }

    @Test
    fun contentOrigin_fullSizeContent_isTheWindowCorner() {
        assertEquals(Point(100, 50), contentOrigin(Rectangle(100, 50, 600, 800), 600f, 800f))
    }

    @Test
    fun contentOrigin_systemTitleBar_isBelowIt() {
        // macOS: a 28 pt title bar, no side or bottom border.
        assertEquals(Point(100, 78), contentOrigin(Rectangle(100, 50, 600, 800), 600f, 772f))
        // Windows: 8 px side and bottom borders, a 31 px top.
        assertEquals(Point(108, 81), contentOrigin(Rectangle(100, 50, 616, 839), 600f, 800f))
    }
}
