package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState
import com.programmersbox.scrcpyviewer.ui.tray.TrayAction
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayPopupHostTest {
    private val mac = ScreenArea(Rectangle(0, 0, 1512, 982), Rectangle(0, 33, 1512, 949))

    @Test
    fun screenshotNoteFollowsAfterScreenshot() {
        assertEquals("Saved to Screenshots", screenshotNote(AfterScreenshot.Thumbnail))
        assertEquals("Copied", screenshotNote(AfterScreenshot.Copy))
        // The editor window comes forward instead, and the popup closes.
        assertNull(screenshotNote(AfterScreenshot.OpenEditor))
    }

    @Test
    fun windowActionsClosePopupTogglesDoNot() {
        listOf(TrayAction.Focus, TrayAction.Mirror, TrayAction.OpenMain, TrayAction.Settings).forEach {
            assertTrue(closesPopup(it), "$it")
        }
        assertFalse(closesPopup(TrayAction.Arrange(ArrangeLayout.Grid)))
        assertFalse(closesPopup(TrayAction.KeepTiled(true)))
    }

    @Test
    fun stopFollowingKeepsTheLeadersOtherFollowers() {
        val follow = FollowState("lead", setOf("a", "b"))
        assertEquals("lead" to setOf("b"), stopFollowing("a", follow))
        assertEquals("lead" to emptySet(), stopFollowing("a", FollowState("lead", setOf("a"))))
        assertNull(stopFollowing("lead", follow))
        assertNull(stopFollowing("a", FollowState()))
    }

    @Test
    fun noClickHangsFromTheFirstScreensTopRight() {
        val point = assertNotNull(popupAnchorPoint(null, listOf(mac)))
        val r = assertNotNull(popupWindowBounds(null, 400, listOf(mac)))
        assertTrue(mac.workArea.contains(point[0], point[1]))
        assertEquals(1512 - r.width, r.x)
        assertEquals(33, r.y)
        assertEquals(POPUP_WIDTH + 20, r.width)
        assertEquals(420, r.height)
    }

    @Test
    fun clickIsTheAnchor() {
        assertContentEquals(intArrayOf(1300, 12), popupAnchorPoint(intArrayOf(1300, 12), listOf(mac)))
    }

    @Test
    fun noScreensGivesNoBounds() {
        assertNull(popupWindowBounds(intArrayOf(10, 10), 400, emptyList()))
        assertNull(popupMaxPaneHeight(null, emptyList()))
    }

    @Test
    fun paneCapIsTheWorkAreaLessMargins() {
        assertEquals(949 - 20, popupMaxPaneHeight(intArrayOf(1300, 12), listOf(mac)))
    }
}
