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
        listOf(TrayAction.Focus, TrayAction.Mirror, TrayAction.OpenMain, TrayAction.Settings, TrayAction.Quit).forEach {
            assertTrue(closesPopup(it), "$it")
        }
        assertFalse(closesPopup(TrayAction.ShowPopup))
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

    @Test
    fun windowRoomGrowsAheadOfTheAnimationAndShrinksOnlyWhenSettled() {
        // Growing: jumps ahead once, then holds while the pane animates up to it.
        assertEquals(400 + 240, popupRoomHeight(room = 300, pane = 400, cap = 900, settled = false))
        assertEquals(640, popupRoomHeight(room = 640, pane = 500, cap = 900, settled = false))
        // Never past the cap.
        assertEquals(900, popupRoomHeight(room = 300, pane = 800, cap = 900, settled = false))
        assertEquals(900, popupRoomHeight(room = 900, pane = 1200, cap = 900, settled = false))
        // Shrinking: keeps the old room while the pane animates down, then fits the settled pane.
        assertEquals(640, popupRoomHeight(room = 640, pane = 350, cap = 900, settled = false))
        assertEquals(350, popupRoomHeight(room = 640, pane = 350, cap = 900, settled = true))
        assertEquals(900, popupRoomHeight(room = 640, pane = 1200, cap = 900, settled = true))
    }

    @Test
    fun popupHangsUpwardOnlyFromABottomBar() {
        val win = ScreenArea(Rectangle(0, 0, 1920, 1080), Rectangle(0, 0, 1920, 1032))
        assertTrue(popupHangsUpward(intArrayOf(1800, 1050), listOf(win)))
        assertFalse(popupHangsUpward(intArrayOf(1300, 12), listOf(mac)))
        assertFalse(popupHangsUpward(null, listOf(mac)))
        // Windows 11 tray overflow flyout: a click inside the work area, in its lower half.
        assertTrue(popupHangsUpward(intArrayOf(1700, 950), listOf(win)))
        assertFalse(popupHangsUpward(intArrayOf(1700, 200), listOf(win)))
        assertFalse(popupHangsUpward(intArrayOf(1, 1), emptyList()))
    }

    @Test
    fun repeatScreenshotsGetDistinctNotes() {
        val first = nextScreenshotNote(null, "Copied")
        val second = nextScreenshotNote(first, "Copied")
        assertEquals("Copied", second.text)
        assertTrue(first != second && first.id != second.id)
    }

    @Test
    fun overflowFlyoutClickHangsTheWindowAboveTheClick() {
        val win = ScreenArea(Rectangle(0, 0, 1920, 1080), Rectangle(0, 0, 1920, 1032))
        val r = assertNotNull(popupWindowBounds(intArrayOf(1700, 950), 400, listOf(win)))
        assertEquals(950 - 420, r.y)
        assertEquals(1700 + 18 - (POPUP_WIDTH + 20), r.x)
    }

    @Test
    fun outsidePressStartsOnlyOnANewPressOutsideTheBounds() {
        val bounds = Rectangle(100, 100, 380, 400)
        assertTrue(outsideClickStarted(wasDown = false, down = true, pointer = intArrayOf(10, 10), bounds = bounds))
        // Inside the popup, a held button (the click that opened it), or no button.
        assertFalse(outsideClickStarted(false, true, intArrayOf(200, 200), bounds))
        assertFalse(outsideClickStarted(true, true, intArrayOf(10, 10), bounds))
        assertFalse(outsideClickStarted(false, false, intArrayOf(10, 10), bounds))
        // Unknown pointer or bounds never close it.
        assertFalse(outsideClickStarted(false, true, null, bounds))
        assertFalse(outsideClickStarted(false, true, intArrayOf(10, 10), null))
    }

    @Test
    fun glassPaneIsSeeThroughOtherStylesAreOpaque() {
        val scheme = androidx.compose.material3.lightColorScheme()
        assertEquals(scheme.surfaceContainerHigh, paneColor(com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle.Glass, scheme))
        assertEquals(scheme.surface, paneColor(com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle.Material, scheme))
    }
}
