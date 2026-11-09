package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SheetMotionTest {
    private val tile = Size(160f, 84f)

    @Test
    fun `fill radius reaches the furthest corner from the press`() {
        assertEquals(hypot(80f, 42f), tileFillRadius(Offset(80f, 42f), tile), 0.001f)
        assertEquals(hypot(160f, 84f), tileFillRadius(Offset(0f, 0f), tile), 0.001f)
        assertEquals(hypot(150f, 64f), tileFillRadius(Offset(10f, 20f), tile), 0.001f)
        assertEquals(hypot(150f, 74f), tileFillRadius(Offset(150f, 74f), tile), 0.001f)
    }

    @Test
    fun `a press outside the tile is clamped to its edge`() {
        assertEquals(hypot(160f, 84f), tileFillRadius(Offset(-30f, 200f), tile), 0.001f)
    }

    @Test
    fun `fill grows from the centre without a press`() {
        assertEquals(Offset(80f, 42f), tileFillOrigin(null, tile))
        assertEquals(Offset(5f, 6f), tileFillOrigin(Offset(5f, 6f), tile))
    }

    @Test
    fun `the fill grows from the last press`() {
        val fill = TileFillState(on = false)
        assertNull(fill.origin)
        fill.pressed(Offset(10f, 20f))
        assertEquals(Offset(10f, 20f), fill.origin)
    }

    @Test
    fun `a tile starts filled or empty without animating`() {
        assertEquals(1f, TileFillState(on = true).progress.value)
        assertEquals(0f, TileFillState(on = false).progress.value)
    }

    @Test
    fun `history already shown does not animate`() {
        val a = LineArrivals()
        a.observe(120)
        assertTrue(a.pendingSeqs().isEmpty())
        a.observe(120)
        assertTrue(a.pendingSeqs().isEmpty())
    }

    @Test
    fun `appended lines animate once each`() {
        val a = LineArrivals()
        a.observe(10)
        a.observe(13)
        assertEquals(setOf(10L, 11L, 12L), a.pendingSeqs())
        assertTrue(a.claim(11))
        assertFalse(a.claim(11)) // scrolled away and back
        assertFalse(a.claim(9))
    }

    @Test
    fun `a huge batch animates only its newest lines`() {
        val a = LineArrivals(cap = 50)
        a.observe(0)
        a.observe(5_000)
        assertEquals((4_950L until 5_000L).toSet(), a.pendingSeqs())
    }

    @Test
    fun `a newer batch drops unclaimed lines of the last`() {
        val a = LineArrivals()
        a.observe(0)
        a.observe(3)
        a.observe(5)
        assertEquals(setOf(3L, 4L), a.pendingSeqs())
    }

    @Test
    fun `changing the filter does not animate lines it brings back`() {
        val a = LineArrivals()
        a.observe(0)
        a.observe(4)
        a.observe(4, "err")
        assertTrue(a.pendingSeqs().isEmpty())
        a.observe(6, "err")
        assertEquals(setOf(4L, 5L), a.pendingSeqs())
    }

    @Test
    fun `shake ends at rest`() {
        assertEquals(listOf(-6f, 5f, -3f, 2f, 0f), DropShakeOffsets)
    }

    @Test
    fun `exit pill colors`() {
        val colors = lightColorScheme()
        assertEquals(colors.errorContainer to colors.onErrorContainer, exitPillColors(1, dark = false, colors))
        assertEquals(colors.surfaceContainerHighest to colors.onSurfaceVariant, exitPillColors(null, dark = false, colors))
        assertNotEquals(exitPillColors(0, dark = false, colors), exitPillColors(0, dark = true, colors))
    }
}
