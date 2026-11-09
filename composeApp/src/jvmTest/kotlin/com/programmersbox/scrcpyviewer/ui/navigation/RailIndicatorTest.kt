package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RailIndicatorTest {
    @Test
    fun `moving down the bottom edge leads and the top trails`() {
        val (top, bottom) = indicatorEdgeTimings(down = true)
        assertEquals(LeadingEdge, bottom)
        assertEquals(TrailingEdge, top)
    }

    @Test
    fun `moving up the top edge leads and the bottom trails`() {
        val (top, bottom) = indicatorEdgeTimings(down = false)
        assertEquals(LeadingEdge, top)
        assertEquals(TrailingEdge, bottom)
    }

    @Test
    fun `the trailing edge starts later and takes longer`() {
        assertEquals(EdgeTiming(220, 0), LeadingEdge)
        assertEquals(EdgeTiming(380, 60), TrailingEdge)
    }

    /** A virtual clock (8 ms per call; both edges ask per frame) that records the pill's (top, bottom) after each call. */
    private class Frames(val indicator: RailIndicator) : MonotonicFrameClock {
        var time = 0L
        val edges = mutableListOf<Pair<Float, Float>>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            // Let the other edge's coroutine take its frame too, so both edges move in step.
            yield()
            time += 8_000_000
            return onFrame(time).also { edges += indicator.top.value to indicator.bottom.value }
        }
    }

    @Test
    fun `first placement snaps, a move stretches then settles`() = runBlocking {
        val indicator = RailIndicator()
        val frames = Frames(indicator)
        withContext(frames) {
            indicator.moveTo("devices", Offset(40f, 50f), 16f)
            assertEquals(34f to 66f, indicator.top.value to indicator.bottom.value)
            assertTrue(frames.edges.isEmpty(), "the first placement does not animate")

            indicator.moveTo("settings", Offset(40f, 250f), 16f)
        }
        assertEquals(234f to 266f, indicator.top.value to indicator.bottom.value)
        // The bottom moves first while the top waits out its delay: the pill stretches downward.
        val firstMove = frames.edges.first { it.second != 66f }
        assertEquals(34f, firstMove.first)
        assertTrue(frames.edges.any { (top, bottom) -> bottom - top > 100f }, "the pill should stretch mid-move")
    }

    @Test
    fun `moving up the top edge leads`() = runBlocking {
        val indicator = RailIndicator()
        val frames = Frames(indicator)
        withContext(frames) {
            indicator.moveTo("settings", Offset(40f, 250f), 16f)
            indicator.moveTo("devices", Offset(40f, 50f), 16f)
        }
        val firstMove = frames.edges.first { it.first != 234f }
        assertEquals(266f, firstMove.second)
        assertEquals(34f to 66f, indicator.top.value to indicator.bottom.value)
    }

    @Test
    fun `the same item moving snaps instead of travelling`() = runBlocking {
        val indicator = RailIndicator()
        val frames = Frames(indicator)
        withContext(frames) {
            indicator.moveTo("devices", Offset(40f, 50f), 16f)
            indicator.moveTo("devices", Offset(40f, 60f), 16f)
        }
        assertEquals(44f to 76f, indicator.top.value to indicator.bottom.value)
        assertTrue(frames.edges.isEmpty())
    }
}
