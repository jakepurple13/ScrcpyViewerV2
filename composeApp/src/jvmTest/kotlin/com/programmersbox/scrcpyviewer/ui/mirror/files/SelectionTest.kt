package com.programmersbox.scrcpyviewer.ui.mirror.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun clickSelectsOneAndSetsAnchor() {
        val s = Selection(setOf("a", "b"), "a", "b").click(order, "c")
        assertEquals(Selection(setOf("c"), "c", "c"), s)
    }

    @Test
    fun shiftClickExtendsFromAnchorBothWays() {
        val start = Selection().click(order, "b")
        assertEquals(setOf("b", "c", "d"), start.shiftClick(order, "d").names)
        val back = start.shiftClick(order, "d").shiftClick(order, "a")
        assertEquals(setOf("a", "b"), back.names, "the range follows the anchor, not the last click")
        assertEquals("b", back.anchor)
        assertEquals("a", back.cursor)
        assertEquals(setOf("a", "b", "c"), Selection().shiftClick(order, "c").let { it.shiftClick(order, "a") }.names)
    }

    @Test
    fun toggleAddsAndRemoves() {
        val s = Selection().click(order, "a").toggle("c")
        assertEquals(setOf("a", "c"), s.names)
        assertEquals("c", s.anchor)
        assertEquals(setOf("c"), s.toggle("a").names)
    }

    @Test
    fun allAndClear() {
        assertEquals(order.toSet(), Selection().all(order).names)
        assertEquals(Selection(), Selection().all(order).clear())
    }

    @Test
    fun arrowsMoveAndExtend() {
        assertEquals(Selection(setOf("a"), "a", "a"), Selection().move(order, 1, extend = false))
        assertEquals(Selection(setOf("e"), "e", "e"), Selection().move(order, -1, extend = false))
        val c = Selection().click(order, "c")
        assertEquals(Selection(setOf("d"), "d", "d"), c.move(order, 1, extend = false))
        assertEquals(Selection(setOf("e"), "e", "e"), c.move(order, 1, false).move(order, 1, false).move(order, 1, false))
        val extended = c.move(order, 1, extend = true).move(order, 1, extend = true)
        assertEquals(setOf("c", "d", "e"), extended.names)
        assertEquals("c", extended.anchor)
        assertEquals("e", extended.cursor)
        assertEquals(setOf("b", "c"), c.move(order, -1, extend = true).names)
        assertEquals(Selection(), Selection().move(emptyList(), 1, false))
    }

    @Test
    fun retainDropsVanishedNames() {
        val s = Selection(setOf("a", "x", "c"), "x", "c").retain(order)
        assertEquals(setOf("a", "c"), s.names)
        assertNull(s.anchor)
        assertEquals("c", s.cursor)
    }
}
