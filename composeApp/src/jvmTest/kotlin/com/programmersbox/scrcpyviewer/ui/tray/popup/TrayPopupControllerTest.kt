package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.ui.window.PointerPoint
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayPopupControllerTest {
    private var time = 10_000L
    private val controller = TrayPopupController(now = { time })

    @Test
    fun iconClickOpensWithAnchor() {
        assertFalse(controller.visible.value)
        controller.iconClicked(PointerPoint(1200, 12))
        assertTrue(controller.visible.value)
        assertEquals(PointerPoint(1200, 12), controller.anchor.value)
    }

    @Test
    fun iconClickWithoutPointerOpensWithNoAnchor() {
        controller.iconClicked(PointerPoint(5, 5))
        time += 1_000
        controller.iconClicked(PointerPoint(5, 5))
        time += 1_000
        controller.iconClicked(null)
        assertTrue(controller.visible.value)
        assertNull(controller.anchor.value)
    }

    @Test
    fun secondIconClickCloses() {
        controller.iconClicked(PointerPoint(1200, 12))
        time += 1_000
        controller.iconClicked(PointerPoint(1200, 12))
        assertFalse(controller.visible.value)
    }

    @Test
    fun iconClickAfterFocusLossDoesNotReopen() {
        // The click on the icon that takes focus from the popup closes it; that same click must not reopen it.
        controller.iconClicked(PointerPoint(1200, 12))
        time += 2_000
        controller.dismissed()
        assertFalse(controller.visible.value)
        time += 100
        controller.iconClicked(PointerPoint(1200, 12))
        assertFalse(controller.visible.value)
        time += 300
        controller.iconClicked(PointerPoint(1200, 12))
        assertTrue(controller.visible.value)
    }

    @Test
    fun dismissWhileClosedDoesNotBlockTheNextClick() {
        controller.dismissed()
        controller.iconClicked(PointerPoint(1, 1))
        assertTrue(controller.visible.value)
    }

    @Test
    fun firstClickAtTimeZeroOpens() {
        val c = TrayPopupController(now = { 0L })
        c.iconClicked(null)
        assertTrue(c.visible.value)
    }

    @Test
    fun macPointerIsStoredRawAndFlippedByTheMainScreen() {
        // NSEvent.mouseLocation on the tray thread: bottom-left origin; the window flips it on the main thread.
        controller.iconClicked(PointerPoint(1300, 970, bottomLeft = true))
        val raw = controller.anchor.value!!
        assertTrue(raw.bottomLeft)
        val main = ScreenArea(Rectangle(0, 0, 1512, 982), Rectangle(0, 33, 1512, 949))
        val second = ScreenArea(Rectangle(1512, -200, 2560, 1440), Rectangle(1512, -175, 2560, 1415))
        assertContentEquals(intArrayOf(1300, 12), raw.inWindowUnits(listOf(main, second)))
        assertNull(raw.inWindowUnits(emptyList()))
        assertContentEquals(intArrayOf(5, 6), PointerPoint(5, 6).inWindowUnits(emptyList()))
    }
}
