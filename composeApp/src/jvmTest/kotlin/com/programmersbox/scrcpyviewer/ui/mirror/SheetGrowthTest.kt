package com.programmersbox.scrcpyviewer.ui.mirror

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class SheetGrowthTest {
    private val screenRight = 1920

    @Test
    fun `a window moved while the sheet is open grows from where it is now`() {
        val opened = grownBounds(Rectangle(100, 50, 600, 800), 280, screenRight)
        assertEquals(Rectangle(100, 50, 880, 800), opened)

        // The user drags the grown window to x = 300.
        val base = sheetBaseBounds(Rectangle(300, 80, 880, 800), growth = 280, shift = 0)
        assertEquals(Rectangle(300, 80, 600, 800), base)
        assertEquals(Rectangle(300, 80, 1000, 800), grownBounds(base, 400, screenRight))
    }

    @Test
    fun `a window resized while the sheet is open keeps its new stage width`() {
        // Aspect lock widened the grown window from 880 to 940: the stage part is now 660.
        val base = sheetBaseBounds(Rectangle(100, 50, 940, 860), growth = 280, shift = 0)
        assertEquals(Rectangle(100, 50, 660, 860), base)
        assertEquals(Rectangle(100, 50, 1060, 860), grownBounds(base, 400, screenRight))
        assertEquals(Rectangle(100, 50, 660, 860), grownBounds(base, 0, screenRight))
    }

    @Test
    fun `near the screen edge the window moves left and comes back as the sheet shrinks`() {
        val original = Rectangle(1200, 0, 600, 800) // ends at 1800, 120 short of the edge
        val actions = grownBounds(original, 280, screenRight)
        assertEquals(Rectangle(1040, 0, 880, 800), actions) // moved left 160
        val shift = original.x - actions.x

        // Switch to Shell: rebuilt base is the original window, then 120 more to the left.
        val base = sheetBaseBounds(actions, growth = 280, shift = shift)
        assertEquals(original, base)
        val shell = grownBounds(base, 400, screenRight)
        assertEquals(Rectangle(920, 0, 1000, 800), shell)

        // Close: back to the original place.
        val closing = sheetBaseBounds(shell, growth = 400, shift = base.x - shell.x)
        assertEquals(original, grownBounds(closing, 0, screenRight))
    }
}
