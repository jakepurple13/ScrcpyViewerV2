package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageMotionTest {
    private val portrait = IntSize(1080, 2400)
    private val landscape = IntSize(2400, 1080)

    @Test
    fun `portrait to landscape starts a quarter turn clockwise, back the other way`() {
        assertEquals(90f, turnStartDegrees(portrait, landscape))
        assertEquals(-90f, turnStartDegrees(landscape, portrait))
    }

    @Test
    fun `a flipped display turns the other way`() {
        assertEquals(-90f, turnStartDegrees(portrait, landscape, flip = true))
        assertEquals(90f, turnStartDegrees(landscape, portrait, flip = true))
    }

    @Test
    fun `no turn without a portrait-landscape swap`() {
        assertNull(turnStartDegrees(null, landscape), "the first frame")
        assertNull(turnStartDegrees(portrait, IntSize(720, 1600)), "a new resolution, same shape")
        assertNull(turnStartDegrees(portrait, portrait))
        assertNull(turnStartDegrees(IntSize(1000, 1000), landscape), "square frames have no orientation")
        assertNull(turnStartDegrees(portrait, IntSize(0, 0)))
    }

    @Test
    fun `the turned image starts covering the old one`() {
        // A 300x667 portrait image; the landscape one letterboxed into the same stage is 300x135. A quarter turn
        // around it is 135x300, so it starts at 667 / 300 = 2.22x (the height) or 300 / 135 = 2.22x (the width).
        val scale = turnStartScale(oldImage = Size(300f, 667f), newImage = Size(300f, 135f))
        assertEquals(2.222f, scale, 0.01f)
        // Once the window is landscape the new image is large: it starts smaller than its settled size.
        assertTrue(turnStartScale(oldImage = Size(300f, 667f), newImage = Size(1200f, 540f)) < 1f)
        assertEquals(1f, turnStartScale(Size.Zero, Size(10f, 10f)), "no old image: no scale")
    }

    @Test
    fun `a turning image is scaled down to stay inside the stage`() {
        val stage = Size(772f, 612f)
        val image = Size(772f, 347f) // a landscape frame fitted to the stage
        assertEquals(1f, turnFitScale(image, stage, 0f), "upright it fits as it is")
        assertEquals(612f / 772f, turnFitScale(image, stage, 90f), 0.001f, "a quarter turn round, its width is the height")
        val diagonal = turnFitScale(image, stage, 45f)
        assertTrue(diagonal < 612f / 772f, "half way round the corners reach furthest")
        assertEquals(1f, turnFitScale(Size(10f, 10f), Size(1000f, 1000f), 45f), "never scaled up")
    }

    @Test
    fun `the grey filter is off on a live stage`() {
        assertNull(greyColorFilter(0f))
        assertNotNull(greyColorFilter(0.01f))
        assertNotNull(greyColorFilter(1f))
    }

    @Test
    fun `the skeleton is a phone until the frame size is known, and fits the stage`() {
        val (w, h) = skeletonSize(800.dp, 800.dp, null)
        assertEquals(9f / 19.5f, w.value / h.value, 0.01f)
        assertTrue(h <= 800.dp * 0.7f)
        val (lw, lh) = skeletonSize(800.dp, 800.dp, IntSize(2400, 1080))
        assertTrue(lw > lh, "a landscape frame gives a landscape skeleton")
        assertTrue(lw <= 800.dp - 64.dp)
        val (nw, _) = skeletonSize(120.dp, 800.dp, null)
        assertTrue(nw <= 120.dp, "never wider than the stage")
    }
}
