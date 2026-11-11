package com.programmersbox.scrcpyviewer.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared-axis switch moves content the same way the rail pill travels. */
class RailDirectionTest {
    @Test
    fun `moving down the rail plays forward and moving up plays reverse`() {
        assertEquals(1, railDirection(0, 3))
        assertEquals(1, railDirection(1, 2))
        assertEquals(-1, railDirection(3, 0))
        assertEquals(-1, railDirection(2, 1))
    }

    @Test
    fun `no move or an unknown position has no direction`() {
        assertEquals(0, railDirection(2, 2))
        assertEquals(0, railDirection(-1, 2))
        assertEquals(0, railDirection(1, -1))
    }

    @Test
    fun `scene keys map back to rail positions`() {
        TopLevelRoute.all.forEachIndexed { i, route -> assertEquals(i, railIndexOf(railContentKey(route))) }
        assertEquals(-1, railIndexOf("not a route"))
    }
}
