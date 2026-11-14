package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Marks, geometry constants, undo and the dirty flag. */
class MarkupDocumentTest {
    private val red = MarkupColor.Red

    private fun box(x2: Float = 50f, y2: Float = 40f) = Mark.Box(red.argb, MarkPoint(10f, 10f), MarkPoint(x2, y2))

    @Test
    fun colorsAndToolsMatchSpec() {
        assertEquals(
            listOf(0xFFE53935.toInt(), 0xFFFBC02D.toInt(), 0xFF1E88E5.toInt(), 0xFF111111.toInt()),
            MarkupColor.entries.map { it.argb },
        )
        assertEquals(listOf("Red", "Yellow", "Blue", "Black"), MarkupColor.entries.map { it.label })
        assertEquals(listOf("Pen", "Arrow", "Box", "Pixelate"), MarkupTool.entries.map { it.name })
    }

    @Test
    fun strokeWidthIsPointEightPercentOfShorterSideWithMinThree() {
        assertEquals(8f, strokeWidth(1000, 3000), 0.001f)
        assertEquals(8f, strokeWidth(3000, 1000), 0.001f)
        assertEquals(3f, strokeWidth(100, 200), 0.001f)
        assertEquals(11.52f, strokeWidth(1440, 3200), 0.001f)
    }

    @Test
    fun blockSizeIsTwoPointFivePercentWithMinEight() {
        assertEquals(25, blockSize(1000, 3000))
        assertEquals(8, blockSize(100, 200))
        assertEquals(36, blockSize(1440, 3200))
    }

    @Test
    fun arrowHeadIsFourStrokesLongAt26Degrees() {
        val stroke = 10f
        val head = arrowHead(MarkPoint(0f, 0f), MarkPoint(100f, 0f), stroke)
        assertEquals(MarkPoint(100f, 0f), head.tip)
        for (wing in listOf(head.left, head.right)) {
            assertEquals(4 * stroke, hypot(wing.x - head.tip.x, wing.y - head.tip.y), 0.01f)
            // Angle between the wing and the shaft (which points back toward the start, i.e. toward -x).
            val angle = Math.toDegrees(atan2(abs(wing.y - head.tip.y), head.tip.x - wing.x).toDouble())
            assertEquals(26.0, angle, 0.01)
        }
        assertEquals(-head.left.y, head.right.y, 0.001f)
    }

    @Test
    fun arrowHeadForDegenerateArrowDoesNotThrow() {
        arrowHead(MarkPoint(5f, 5f), MarkPoint(5f, 5f), 10f)
    }

    @Test
    fun dragShorterThanTwoPxAddsNothing() {
        val doc = MarkupDocument(200, 200)
        assertFalse(doc.add(Mark.Box(red.argb, MarkPoint(10f, 10f), MarkPoint(11f, 11f))))
        assertFalse(doc.add(Mark.Arrow(red.argb, MarkPoint(10f, 10f), MarkPoint(10f, 11.5f))))
        assertFalse(doc.add(Mark.Pixelate(MarkPoint(10f, 10f), MarkPoint(10f, 10f))))
        assertFalse(doc.add(Mark.Pen(red.argb, listOf(MarkPoint(10f, 10f), MarkPoint(10.5f, 10f)))))
        assertTrue(doc.marks.isEmpty())
        assertFalse(doc.canUndo)
        assertFalse(doc.dirty)
        assertTrue(doc.add(Mark.Box(red.argb, MarkPoint(10f, 10f), MarkPoint(12f, 10f))))
    }

    @Test
    fun addAndUndoAreAStack() {
        val doc = MarkupDocument(200, 200)
        val a = box()
        val b = box(60f, 60f)
        doc.add(a)
        doc.add(b)
        assertEquals(listOf(a, b), doc.marks.toList())
        assertEquals(b, doc.undo())
        assertEquals(listOf(a), doc.marks.toList())
        assertEquals(a, doc.undo())
        assertFalse(doc.canUndo)
        assertNull(doc.undo())
    }

    @Test
    fun dirtyTracksChangesSinceMarkClean() {
        val doc = MarkupDocument(200, 200)
        assertFalse(doc.dirty)
        doc.add(box())
        assertTrue(doc.dirty)
        doc.markClean()
        assertFalse(doc.dirty)
        doc.add(box(80f, 80f))
        assertTrue(doc.dirty)
        doc.undo()
        assertFalse(doc.dirty)
        doc.undo()
        assertTrue(doc.marks.isEmpty())
        assertFalse(doc.dirty)
    }
}
