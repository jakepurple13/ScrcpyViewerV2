package com.programmersbox.scrcpyviewer.ui.tray.popup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayPopupControllerTest {
    private var time = 10_000L
    private val controller = TrayPopupController(now = { time })

    @Test
    fun iconClickOpensWithAnchor() {
        assertFalse(controller.visible.value)
        controller.iconClicked(intArrayOf(1200, 12))
        assertTrue(controller.visible.value)
        assertContentEquals(intArrayOf(1200, 12), controller.anchor.value)
    }

    @Test
    fun iconClickWithoutPointerOpensWithNoAnchor() {
        controller.iconClicked(intArrayOf(5, 5))
        time += 1_000
        controller.iconClicked(intArrayOf(5, 5))
        time += 1_000
        controller.iconClicked(null)
        assertTrue(controller.visible.value)
        assertNull(controller.anchor.value)
    }

    @Test
    fun secondIconClickCloses() {
        controller.iconClicked(intArrayOf(1200, 12))
        time += 1_000
        controller.iconClicked(intArrayOf(1200, 12))
        assertFalse(controller.visible.value)
    }

    @Test
    fun iconClickAfterFocusLossDoesNotReopen() {
        // The click on the icon that takes focus from the popup closes it; that same click must not reopen it.
        controller.iconClicked(intArrayOf(1200, 12))
        time += 2_000
        controller.dismissed()
        assertFalse(controller.visible.value)
        time += 100
        controller.iconClicked(intArrayOf(1200, 12))
        assertFalse(controller.visible.value)
        time += 300
        controller.iconClicked(intArrayOf(1200, 12))
        assertTrue(controller.visible.value)
    }

    @Test
    fun dismissWhileClosedDoesNotBlockTheNextClick() {
        controller.dismissed()
        controller.iconClicked(intArrayOf(1, 1))
        assertTrue(controller.visible.value)
    }

    @Test
    fun firstClickAtTimeZeroOpens() {
        val c = TrayPopupController(now = { 0L })
        c.iconClicked(null)
        assertTrue(c.visible.value)
    }
}
