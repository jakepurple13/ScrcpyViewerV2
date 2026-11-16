package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.mirror.AspectLock
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutsTest {
    private val work = Rectangle(0, 0, 1600, 1000)
    private val gap = 12
    private fun phone(s: String) = ArrangeWindow(s, IntSize(1080, 2400), 56, 40)
    private fun tablet(s: String) = ArrangeWindow(s, IntSize(2560, 1600), 56, 40)

    private fun assertAspect(w: ArrangeWindow, r: Rectangle) {
        val expected = AspectLock(w.frame.width, w.frame.height, w.chromeW.toDouble(), w.chromeH.toDouble()).outerHeight(r.width.toDouble())
        assertTrue(abs(expected - r.height) <= 1.0, "${w.serial}: height ${r.height} vs $expected")
    }

    private fun assertSane(ws: List<ArrangeWindow>, out: Map<String, Rectangle>, area: Rectangle = work) {
        assertEquals(ws.map { it.serial }.toSet(), out.keys)
        ws.forEach { w ->
            val r = out.getValue(w.serial)
            assertAspect(w, r)
            assertTrue(area.contains(r), "${w.serial} $r outside $area")
        }
        val l = out.values.toList()
        for (i in l.indices) for (j in i + 1 until l.size) {
            val a = l[i]
            val b = l[j]
            val hGap = maxOf(b.x - (a.x + a.width), a.x - (b.x + b.width))
            val vGap = maxOf(b.y - (a.y + a.height), a.y - (b.y + b.height))
            assertTrue(maxOf(hGap, vGap) >= gap, "gap < $gap between $a and $b")
        }
    }

    @Test fun zeroWindowsEmpty() {
        assertTrue(layout(ArrangeLayout.Grid, emptyList(), work, null, gap).isEmpty())
    }

    @Test fun asPlacedIsEmpty() {
        assertTrue(layout(ArrangeLayout.AsPlaced, listOf(phone("a")), work, null, gap).isEmpty())
    }

    @Test fun oneWindowCentredMaxFit() {
        val w = phone("a")
        val r = layout(ArrangeLayout.Grid, listOf(w), work, null, gap).getValue("a")
        assertAspect(w, r)
        assertTrue(abs(r.height - (1000 - 2 * gap)) <= 1)
        assertTrue(abs((r.x + r.width / 2) - 800) <= 1)
    }

    @Test fun fourPhonesPickFourColumns() {
        val ws = listOf("a", "b", "c", "d").map(::phone)
        val out = layout(ArrangeLayout.Grid, ws, work, null, gap)
        assertSane(ws, out)
        assertEquals(1, out.values.map { it.y }.distinct().size)
    }

    @Test fun mixedTabletAndPhones() {
        val ws = listOf(tablet("t"), phone("a"), phone("b"))
        assertSane(ws, layout(ArrangeLayout.Grid, ws, work, null, gap))
    }

    @Test fun sideBySideSingleRow() {
        val ws = listOf(tablet("t"), phone("a"), phone("b"))
        val out = layout(ArrangeLayout.SideBySide, ws, work, null, gap)
        assertSane(ws, out)
        assertTrue(out.values.sortedBy { it.x }.zipWithNext().all { (a, b) -> a.x + a.width <= b.x })
        assertTrue(out.values.all { abs((it.y + it.height / 2) - 500) <= 1 })
    }

    @Test fun focusTakesLeft62AndOthersStackRight() {
        val ws = listOf("a", "b", "c").map(::tablet)
        val out = layout(ArrangeLayout.Focus, ws, work, "b", gap)
        assertSane(ws, out)
        val f = out.getValue("b")
        assertTrue(f.x + f.width <= (1600 * 0.62).toInt())
        assertTrue(f.width > out.getValue("a").width)
        val a = out.getValue("a")
        val c = out.getValue("c")
        assertTrue(a.x >= (1600 * 0.62).toInt() && c.x >= (1600 * 0.62).toInt())
        assertTrue(a.y + a.height <= c.y || c.y + c.height <= a.y)
    }

    @Test fun focusAloneUsesFullArea() {
        val w = phone("a")
        val r = layout(ArrangeLayout.Focus, listOf(w), work, "a", gap).getValue("a")
        assertAspect(w, r)
        assertTrue(abs(r.height - (1000 - 2 * gap)) <= 1)
    }

    @Test fun cascadeOffsets() {
        val ws = listOf("a", "b", "c").map(::phone)
        val out = layout(ArrangeLayout.Cascade, ws, work, null, gap)
        ws.forEach {
            assertAspect(it, out.getValue(it.serial))
            assertTrue(work.contains(out.getValue(it.serial)))
        }
        assertEquals(34, out.getValue("b").x - out.getValue("a").x)
        assertEquals(30, out.getValue("b").y - out.getValue("a").y)
        assertEquals(68, out.getValue("c").x - out.getValue("a").x)
        assertTrue(out.getValue("a").width <= (1600 * 0.32).toInt() && out.getValue("a").height <= (1000 * 0.62).toInt())
    }

    @Test fun offsetWorkAreaRespected() {
        val area = Rectangle(100, 50, 800, 600)
        val ws = listOf("a", "b", "c", "d", "e").map(::phone)
        ArrangeLayout.entries.filter { it != ArrangeLayout.AsPlaced && it != ArrangeLayout.Cascade }.forEach {
            assertSane(ws, layout(it, ws, area, "c", gap), area)
        }
    }

    @Test fun degenerateFrameSizesStaySane() {
        val ws = listOf(ArrangeWindow("z", IntSize(0, 0), 56, 40), ArrangeWindow("n", IntSize(-5, 100), 56, 40))
        ArrangeLayout.entries.filter { it != ArrangeLayout.AsPlaced }.forEach { l ->
            val out = layout(l, ws, work, "z", gap)
            assertEquals(setOf("z", "n"), out.keys)
            out.values.forEach { assertTrue(it.width >= 1 && it.height >= 1 && work.contains(it), "$l $it") }
        }
    }

    @Test fun chromeLargerThanCellStaysInside() {
        val area = Rectangle(10, 20, 100, 100)
        val ws = listOf(ArrangeWindow("a", IntSize(1080, 2400), 200, 150), ArrangeWindow("b", IntSize(1080, 2400), 200, 150))
        ArrangeLayout.entries.filter { it != ArrangeLayout.AsPlaced }.forEach { l ->
            layout(l, ws, area, "a", gap).values.forEach { assertTrue(area.contains(it), "$l $it") }
        }
    }

    @Test fun twelveWindowsInSmallAreaStayInside() {
        val area = Rectangle(0, 0, 800, 600)
        val ws = (1..12).map { phone("p$it") }
        ArrangeLayout.entries.filter { it != ArrangeLayout.AsPlaced }.forEach { l ->
            val out = layout(l, ws, area, "p3", gap)
            assertEquals(12, out.size)
            out.values.forEach { assertTrue(area.contains(it), "$l $it") }
        }
    }

    @Test fun cascadeWrapsInsteadOfStacking() {
        val ws = (1..15).map { phone("p$it") }
        val out = layout(ArrangeLayout.Cascade, ws, work, null, gap)
        out.values.forEach { assertTrue(work.contains(it), "$it") }
        assertTrue(out.values.map { it.x to it.y }.distinct().size == 15, "positions must be distinct")
    }

    @Test fun unknownFocusFallsBackToFirst() {
        val ws = listOf(tablet("a"), tablet("b"))
        val out = layout(ArrangeLayout.Focus, ws, work, "nope", gap)
        assertTrue(out.getValue("a").width > out.getValue("b").width)
    }
}
