package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Rectangle

/**
 * Where the window would stand without the sheet, from its [current] bounds while grown by [growth] and moved left by
 * [shift] to keep the sheet on screen. Taken afresh each time the sheet's width changes, so a window the user moved
 * or resized while the sheet was open keeps its new place and size.
 */
internal fun sheetBaseBounds(current: Rectangle, growth: Int, shift: Int): Rectangle =
    Rectangle(current.x + shift, current.y, current.width - growth, current.height)

/**
 * [base] grown to the right by [growth], moved left only as far as needed to end by [screenRight]. The shift applied
 * is `base.x - result.x`; it goes back to 0 as the growth shrinks.
 */
internal fun grownBounds(base: Rectangle, growth: Int, screenRight: Int): Rectangle {
    val width = base.width + growth
    val overflow = (base.x + width - screenRight).coerceAtLeast(0)
    return Rectangle(base.x - overflow, base.y, width, base.height)
}

/**
 * One frame of the sheet's open, close or tab switch: [grownBounds] at [growth], with the window's [height] now (a
 * height the user drags meanwhile is kept). The top always comes from [base], never from the window as it is now:
 * mid-resize, macOS can report a window at y = 0 for an instant, and writing that back moves the window to the top
 * of the screen.
 */
internal fun sheetFrameBounds(base: Rectangle, growth: Int, screenRight: Int, height: Int): Rectangle =
    grownBounds(base, growth, screenRight).apply { this.height = height }

/**
 * How the open sheet's width is made up in a floating window: [growth], what the window grew by for it, plus
 * [fromStage], what the stage gave up (the drag bar's moves, or what the screen had no room for; negative when the
 * stage has more than before the sheet opened). Closing shrinks the window by [growth] only and gives the stage
 * [fromStage] back, so the window and the image return to their size before opening.
 */
internal data class SheetSplit(val growth: Dp = 0.dp, val fromStage: Dp = 0.dp)

/** After the drag bar set the sheet to [width]: the window keeps its [growth], the stage gives up the rest. */
internal fun draggedSplit(growth: Dp, width: Dp): SheetSplit = SheetSplit(growth, width - growth)

/**
 * Where an open, close or tab switch to a sheet [width] wide on [tab] (0 dp: closed) ends, from the [current] split
 * and the [stage]'s width now. What the stage gave up stays given up: a tab switch moves the window by the difference
 * between the two widths. The window grows no further than the screen has [room] for (its width less the window's
 * width without the sheet), but always by at least the tab's minimum; the stage gives up what is missing, down to
 * [STAGE_MIN_WIDTH]. A stage already under that minimum keeps its width, and the sheet shows the tab's minimum (what
 * [clampSheetWidth] shows there), so the window grows by the width the sheet actually shows.
 */
internal fun sheetSplit(width: Dp, tab: SheetTab, stage: Dp, current: SheetSplit, room: Dp): SheetSplit {
    if (width <= 0.dp) return SheetSplit()
    val min = SheetWidths.default(tab)
    val wanted = if (stage >= STAGE_MIN_WIDTH) width else min
    val growth = (wanted - current.fromStage).coerceAtMost(maxOf(room, min)).coerceAtLeast(0.dp)
    val stageWithoutSheet = stage + current.fromStage
    val shown = wanted.coerceAtMost(stageWithoutSheet + growth - STAGE_MIN_WIDTH).coerceAtLeast(min)
    return SheetSplit(growth, shown - growth)
}
