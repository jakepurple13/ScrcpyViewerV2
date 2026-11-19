package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import java.awt.Rectangle

/** How far right of the icon click the popup's right edge sits, so the popup hangs under the icon. */
private const val ICON_OVERHANG = 18

/**
 * Where the popup goes for a tray click at ([clickX], [clickY]), in window units. Under a top bar, above a bottom bar,
 * beside a side bar, top-right of the work area when the click is inside it. Height is capped to the work area. Null with no screens.
 */
fun popupBounds(
    clickX: Int,
    clickY: Int,
    width: Int,
    contentHeight: Int,
    screens: List<ScreenArea>,
    gap: Int = 8,
): Rectangle? {
    val work = screenFor(Rectangle(clickX, clickY, 1, 1), screens)?.workArea ?: return null
    val height = minOf(contentHeight, work.height - 2 * gap).coerceAtLeast(0)
    val minX = work.x + gap
    val maxX = (work.x + work.width - gap - width).coerceAtLeast(minX)
    val minY = work.y + gap
    val maxY = (work.y + work.height - gap - height).coerceAtLeast(minY)
    val right = work.x + work.width

    val x: Int
    val y: Int
    when {
        clickY < work.y -> {
            x = (clickX + ICON_OVERHANG - width).coerceIn(minX, maxX)
            y = minY
        }
        clickY >= work.y + work.height -> {
            x = (clickX + ICON_OVERHANG - width).coerceIn(minX, maxX)
            y = maxY
        }
        clickX < work.x -> {
            x = minX
            y = (clickY - height / 2).coerceIn(minY, maxY)
        }
        clickX >= right -> {
            x = maxX
            y = (clickY - height / 2).coerceIn(minY, maxY)
        }
        else -> {
            x = maxX
            y = minY
        }
    }
    return Rectangle(x, y, width, height)
}
