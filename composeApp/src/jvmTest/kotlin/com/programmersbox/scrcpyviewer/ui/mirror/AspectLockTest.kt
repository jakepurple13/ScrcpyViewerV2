package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AspectLockTest {
    private val portrait = IntSize(1080, 2400)
    private val landscape = IntSize(2400, 1080)

    @Test
    fun `a user window resize is corrected`() {
        assertTrue(aspectLockNeeded(windowResized = true, lastFrame = portrait, frame = portrait, wasFit = true, fit = true))
    }

    @Test
    fun `a rotation is corrected though the window kept its size`() {
        assertTrue(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = landscape, wasFit = true, fit = true))
    }

    @Test
    fun `a new frame size is corrected`() {
        assertTrue(aspectLockNeeded(false, lastFrame = portrait, frame = IntSize(720, 1600), wasFit = true, fit = true))
        assertTrue(aspectLockNeeded(false, lastFrame = null, frame = portrait, wasFit = true, fit = true))
    }

    @Test
    fun `turning Fit back on is corrected`() {
        assertTrue(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = portrait, wasFit = false, fit = true))
    }

    @Test
    fun `a stage resized only by the sheet's drag bar is left alone`() {
        assertFalse(aspectLockNeeded(windowResized = false, lastFrame = portrait, frame = portrait, wasFit = true, fit = true))
    }

    @Test
    fun `a window resize after the sheet's bar was dragged changes the height by the user's change only`() {
        // Stage at the frame's aspect (0.45): 450 x 1000. The sheet opens (the window grows, the stage keeps its
        // width), then its bar is dragged narrower: the stage takes 200 px back and is 650 x 1000, wider than the frame.
        val afterDrag = IntSize(650, 1000)
        // The user widens the window by 6 px.
        val locked = aspectLockedStage(afterDrag, IntSize(656, 1000), portrait, portrait)
        assertEquals(656, locked.width)
        assertTrue(locked.height - afterDrag.height in 12..14, "grows by 6 / 0.45, not to 656 / 0.45: $locked")
    }

    @Test
    fun `a resize from a stage at the frame's aspect keeps that aspect`() {
        assertEquals(IntSize(540, 1200), aspectLockedStage(IntSize(450, 1000), IntSize(540, 1010), portrait, portrait))
        assertEquals(IntSize(495, 1100), aspectLockedStage(IntSize(450, 1000), IntSize(455, 1100), portrait, portrait))
    }

    @Test
    fun `a resize that would be taller than the screen keeps the user's height and narrows the stage`() {
        val locked = aspectLockedStage(IntSize(450, 1000), IntSize(480, 1000), portrait, portrait, maxHeight = 1020)
        assertEquals(IntSize(450, 1000), locked, "not 480 x 1067: the width goes back to the user's height")
        // Off the frame's aspect after a drag: the stage keeps its difference, at the user's height.
        assertEquals(IntSize(650, 1000), aspectLockedStage(IntSize(650, 1000), IntSize(700, 1000), portrait, portrait, 1050))
    }

    @Test
    fun `a rotation keeps the longer side, within the screen's height`() {
        assertEquals(IntSize(1000, 450), aspectLockedStage(IntSize(450, 1000), IntSize(450, 1000), portrait, landscape))
        assertEquals(IntSize(450, 1000), aspectLockedStage(IntSize(1000, 450), IntSize(1000, 450), landscape, portrait))
        assertEquals(IntSize(360, 800), aspectLockedStage(IntSize(1000, 450), IntSize(1000, 450), landscape, portrait, 800))
    }

    @Test
    fun `a new frame size or Fit back on fits the stage to the frame from its width`() {
        val offAspect = IntSize(650, 1000)
        assertEquals(IntSize(650, 1444), aspectLockedStage(offAspect, offAspect, null, portrait))
        assertEquals(IntSize(650, 1444), aspectLockedStage(offAspect, offAspect, IntSize(720, 1500), portrait))
        assertEquals(IntSize(650, 1444), aspectLockedStage(offAspect, offAspect, portrait, portrait, refit = true))
    }

    @Test
    fun `the lock's own correction is not corrected again`() {
        // The run after a correction sees the window resized (by the lock) and the stage it asked for.
        val corrected = IntSize(656, 1013)
        assertEquals(corrected, aspectLockedStage(corrected, corrected, portrait, portrait))
    }

    @Test
    fun `a stage change while the sheet pins the stage or keeps the window's size is the sheet's`() {
        assertTrue(stageChangeIsSheets(pinned = true, windowResized = true, portrait, portrait, wasFit = true, fit = true))
        assertTrue(stageChangeIsSheets(pinned = false, windowResized = false, portrait, portrait, wasFit = true, fit = true))
        assertFalse(stageChangeIsSheets(pinned = false, windowResized = true, portrait, portrait, wasFit = true, fit = true))
        assertFalse(stageChangeIsSheets(pinned = false, windowResized = false, portrait, landscape, wasFit = true, fit = true))
    }

    /**
     * A floating mirror window (px) whose stage shows [frame] with Fit on, run like the window's aspect lock effect:
     * each stage change [look]s; a change the sheet made becomes the base at once, any other waits for the size to
     * settle, then the lock corrects the window (which changes the stage again and looks once more).
     */
    private class LockedWindow(var stage: IntSize, val frame: IntSize = IntSize(1080, 2400), val maxHeight: Int = 1400) {
        var window = IntSize(stage.width, stage.height + 56)
        var lastWindow = window
        var lastStage = stage
        var pinned = false

        fun look(settled: Boolean = true) {
            if (stageChangeIsSheets(pinned, window != lastWindow, frame, frame, true, true)) {
                lastStage = stage
                return
            }
            if (!settled) return // restarted by the next stage change before the settle delay ran out
            val needed = aspectLockNeeded(window != lastWindow, frame, frame, true, true)
            lastWindow = window
            val target = if (needed) aspectLockedStage(lastStage, stage, frame, frame, maxHeight) else null
            lastStage = target ?: stage
            if (target != null && target != stage) {
                window = IntSize(window.width + target.width - stage.width, window.height + target.height - stage.height)
                stage = target
                look()
            }
        }

        /** The sheet's bar moved by [by] px (right: positive); the window keeps its size. */
        fun dragBar(by: Int) {
            stage = IntSize(stage.width + by, stage.height)
            look(settled = false) // the user goes straight on to resize the window
        }

        /** The user resized the window by [dw] x [dh] px. */
        fun resize(dw: Int, dh: Int) {
            window = IntSize(window.width + dw, window.height + dh)
            stage = IntSize(stage.width + dw, stage.height + dh)
            look()
        }

        /** An open, close or tab switch: the window grows by [grow], the stage (pinned) changes by [stageBy]. */
        fun sheet(grow: Int, stageBy: Int) {
            pinned = true
            window = IntSize(window.width + grow, window.height)
            stage = IntSize(stage.width + stageBy, stage.height)
            look(settled = false)
            lastWindow = window // the sheet effect, once the native window has its new size
            pinned = false
            look()
        }
    }

    @Test
    fun `dragging the bar then resizing the window never grows it toward the screen's height`() {
        val w = LockedWindow(IntSize(450, 1000))
        w.sheet(grow = 560, stageBy = 0)
        w.dragBar(200)
        w.resize(6, 0)
        assertTrue(w.stage.height in 1012..1014, "grows by the user's 6 px / 0.45 only: ${w.stage}")
        w.dragBar(-150)
        w.resize(-10, 0)
        assertTrue(w.stage.height in 989..991, "measured from the stage after the second drag: ${w.stage}")
        repeat(20) { w.resize(4, 0) }
        assertTrue(w.stage.height <= w.maxHeight, "never taller than the screen: ${w.stage}")
    }

    @Test
    fun `the sheet's open, drag, tab switch and close never change the window's height`() {
        val w = LockedWindow(IntSize(450, 1000))
        val height = w.window.height
        w.sheet(grow = 280, stageBy = 0)
        w.dragBar(-120)
        w.sheet(grow = 120, stageBy = 0)
        w.dragBar(200)
        w.sheet(grow = -400, stageBy = -80)
        assertEquals(height, w.window.height)
        assertEquals(IntSize(450, 1000), w.stage)
    }
}
