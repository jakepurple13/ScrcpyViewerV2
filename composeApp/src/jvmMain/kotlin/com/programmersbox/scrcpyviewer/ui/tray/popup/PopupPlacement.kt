package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import java.awt.Rectangle

/** How far right of the icon click the popup's right edge sits, so the popup hangs under the icon. */
private const val ICON_OVERHANG = 18

/**
 * Whether a popup for a click at ([clickX], [clickY]) hangs upward from it: the click is below [work] (a bottom bar), or
 * inside it in its lower half (Windows 11's tray overflow flyout over a bottom taskbar).
 */
internal fun hangsUpFrom(clickX: Int, clickY: Int, work: Rectangle): Boolean {
    if (clickY >= work.y + work.height) return true
    val inside = clickX >= work.x && clickX < work.x + work.width && clickY >= work.y
    return inside && clickY >= work.y + work.height / 2
}

/**
 * Where the popup goes for a tray click at ([clickX], [clickY]), in window units. Under a top bar, above a bottom bar,
 * beside a side bar. A click inside the work area (Windows 11's tray overflow flyout sits above the taskbar) hangs the
 * popup from the click: below it in the work area's upper half, above it in the lower half. Height is capped to the
 * work area. Null with no screens. With no click at all, see [popupTopRightBounds].
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
            x = (clickX + ICON_OVERHANG - width).coerceIn(minX, maxX)
            y = if (hangsUpFrom(clickX, clickY, work)) clickY - gap - height else clickY + gap
        }
    }
    return Rectangle(x, y.coerceIn(minY, maxY), width, height)
}

/**
 * Where the popup goes with no click location (Linux, the menu's "Show devices…"): the top-right of the first screen's
 * work area. Height is capped to the work area. Null with no screens.
 */
fun popupTopRightBounds(width: Int, contentHeight: Int, screens: List<ScreenArea>, gap: Int = 8): Rectangle? {
    val work = screens.firstOrNull()?.workArea ?: return null
    val height = minOf(contentHeight, work.height - 2 * gap).coerceAtLeast(0)
    val x = (work.x + work.width - gap - width).coerceAtLeast(work.x + gap)
    return Rectangle(x, work.y + gap, width, height)
}
