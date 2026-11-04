package com.programmersbox.scrcpyviewer.ui.mirror

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
