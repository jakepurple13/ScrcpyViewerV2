package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    /**
     * A floating mirror window [base] wide (dp) whose stage is [baseStage] wide while the sheet is closed. Like the
     * window's effect: an open, close or tab switch resizes the window by the change in growth and the stage by the
     * change in what it gives up; a drag leaves the window alone and takes the sheet's change from the stage.
     */
    private class Window(val base: Dp, val baseStage: Dp, val room: Dp = 2000.dp) {
        var split = SheetSplit()
        var width = base
        var stage = baseStage

        fun show(width: Dp, tab: SheetTab) {
            val next = sheetSplit(width, tab, stage, split, room)
            this.width += next.growth - split.growth
            stage -= next.fromStage - split.fromStage
            split = next
        }

        fun close() = show(0.dp, SheetTab.Actions)

        fun drag(width: Dp) {
            stage -= width - (split.growth + split.fromStage)
            split = draggedSplit(split.growth, width)
        }

        fun assertClosed() {
            assertEquals(base, width, "the window is back to its width before opening")
            assertEquals(baseStage, stage, "the stage has its width back, so the image is not letterboxed")
        }
    }

    @Test
    fun `open, drag wider, close returns the window to its width before opening`() {
        val w = Window(base = 600.dp, baseStage = 552.dp)
        w.show(280.dp, SheetTab.Actions)
        assertEquals(880.dp, w.width)
        assertEquals(552.dp, w.stage, "opening grows the window; the stage keeps its width")
        w.drag(400.dp)
        assertEquals(880.dp, w.width, "a drag never resizes the window")
        assertEquals(432.dp, w.stage)
        w.close()
        w.assertClosed()
    }

    @Test
    fun `open, drag narrower, close returns the window to its width before opening`() {
        val w = Window(base = 600.dp, baseStage = 552.dp)
        w.show(500.dp, SheetTab.Actions)
        assertEquals(1100.dp, w.width)
        w.drag(300.dp)
        assertEquals(1100.dp, w.width)
        assertEquals(752.dp, w.stage)
        w.close()
        w.assertClosed()
    }

    @Test
    fun `a tab switch after a drag moves the window by the difference between the tab widths`() {
        val w = Window(base = 600.dp, baseStage = 552.dp)
        w.show(280.dp, SheetTab.Actions)
        w.drag(400.dp)
        w.show(520.dp, SheetTab.Shell)
        assertEquals(1000.dp, w.width, "Actions shows 400, Shell 520: the window grows by 120")
        assertEquals(432.dp, w.stage, "the drag stays taken from the stage")
        w.show(400.dp, SheetTab.Actions)
        assertEquals(880.dp, w.width)
        w.close()
        w.assertClosed()
    }

    @Test
    fun `repeated open, drag, switch and close cycles do not drift`() {
        val w = Window(base = 600.dp, baseStage = 552.dp)
        val drags = listOf(400.dp, 290.dp, 500.dp, 300.dp, 450.dp)
        for ((i, drag) in drags.withIndex()) {
            w.show(280.dp + (i * 40).dp, SheetTab.Actions)
            w.drag(drag)
            w.show(400.dp + (i * 30).dp, SheetTab.Shell)
            w.drag(drag + 150.dp)
            w.close()
            w.assertClosed()
        }
    }

    @Test
    fun `opening grows the window no further than the screen allows, never under the tab minimum`() {
        val w = Window(base = 1500.dp, baseStage = 1452.dp, room = 450.dp)
        w.show(520.dp, SheetTab.Shell)
        assertEquals(1950.dp, w.width, "grown by the 450 dp the screen has room for")
        assertEquals(1382.dp, w.stage, "the stage gives up the other 70 dp")
        w.close()
        w.assertClosed()

        val full = Window(base = 1900.dp, baseStage = 1852.dp, room = 20.dp)
        full.show(500.dp, SheetTab.Actions)
        assertEquals(2180.dp, full.width, "grown by the Actions minimum, 280 dp")
        full.close()
        full.assertClosed()
    }

    @Test
    fun `in a window too narrow for the stage minimum the window grows by the width shown`() {
        // A 120 dp stage: the sheet shows its tab minimum, not the stored 500 dp.
        val w = Window(base = 168.dp, baseStage = 120.dp)
        w.show(500.dp, SheetTab.Actions)
        assertEquals(SheetSplit(growth = 280.dp, fromStage = 0.dp), w.split)
        assertEquals(120.dp, w.stage, "the stage keeps its width: no jump when the window stops growing")
        w.close()
        w.assertClosed()
    }

    @Test
    fun `each frame keeps the window's top from before the animation`() {
        val base = Rectangle(624, 125, 424, 853)
        // macOS reported the window at (0, 0) mid-resize; the frame never reads that back.
        assertEquals(Rectangle(624, 125, 828, 853), sheetFrameBounds(base, 404, screenRight, height = 853))
        // Near the edge it still only moves left, and takes the height the window has now.
        assertEquals(Rectangle(1520, 125, 400, 900), sheetFrameBounds(Rectangle(1700, 125, 200, 853), 200, screenRight, 900))
    }

    @Test
    fun `files tab grows the window by at least 440 dp`() {
        assertEquals(SheetSplit(440.dp, 0.dp), sheetSplit(440.dp, SheetTab.Files, 400.dp, SheetSplit(), room = 100.dp))
    }
}
