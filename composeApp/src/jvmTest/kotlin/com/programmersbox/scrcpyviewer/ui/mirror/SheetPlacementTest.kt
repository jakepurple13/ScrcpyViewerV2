package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.dp
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the quick actions sheet goes for a window width, and the compact tab control's breakpoint. */
class SheetPlacementTest {
    @Test
    fun `Actions docks from toolbar plus stage minimum plus sheet, floats below, fills under toolbar plus minimum`() {
        assertEquals(SheetPlacement.Docked, sheetPlacement(600.dp, 64.dp, 280.dp, 280.dp)) // 504 <= 600
        assertEquals(SheetPlacement.Docked, sheetPlacement(504.dp, 64.dp, 280.dp, 280.dp))
        assertEquals(SheetPlacement.Floating, sheetPlacement(503.dp, 64.dp, 280.dp, 280.dp))
        assertEquals(SheetPlacement.Floating, sheetPlacement(344.dp, 64.dp, 280.dp, 280.dp))
        assertEquals(SheetPlacement.Full, sheetPlacement(343.dp, 64.dp, 280.dp, 280.dp))
    }

    @Test
    fun `each tab places by its own minimum and saved width`() {
        assertEquals(SheetPlacement.Floating, sheetPlacement(620.dp, 64.dp, 400.dp, 400.dp)) // Shell
        assertEquals(SheetPlacement.Full, sheetPlacement(503.dp, 64.dp, 440.dp, 440.dp)) // Files
        assertEquals(SheetPlacement.Floating, sheetPlacement(663.dp, 64.dp, 440.dp, 440.dp))
        assertEquals(SheetPlacement.Docked, sheetPlacement(704.dp, 64.dp, 480.dp, 480.dp)) // Apps
        // A sheet dragged wider than its minimum floats sooner.
        assertEquals(SheetPlacement.Floating, sheetPlacement(600.dp, 64.dp, 400.dp, 280.dp))
    }

    @Test
    fun `the floating sheet keeps its saved width up to the space beside the toolbar, the full one takes all of it`() {
        assertEquals(280.dp, fitSheetWidth(280.dp, SheetTab.Actions, 450.dp, 64.dp))
        assertEquals(386.dp, fitSheetWidth(500.dp, SheetTab.Actions, 450.dp, 64.dp))
        assertEquals(280.dp, fitSheetWidth(120.dp, SheetTab.Actions, 450.dp, 64.dp))
        assertEquals(280.dp, fitSheetWidth(440.dp, SheetTab.Files, 344.dp, 64.dp)) // Full
        // Docked: as before, the stage keeps its minimum.
        assertEquals(clampSheetWidth(300.dp, SheetTab.Actions, 1280.dp, 64.dp), fitSheetWidth(300.dp, SheetTab.Actions, 1280.dp, 64.dp))
        assertEquals(SheetWidths.default(SheetTab.Actions), fitSheetWidth(10.dp, SheetTab.Actions, null, 64.dp))
    }

    @Test
    fun `sheet tabs turn compact under 340 dp`() {
        assertTrue(sheetTabsCompact(339.dp))
        assertFalse(sheetTabsCompact(340.dp))
    }

    @Test
    fun `while the sheet covers the stage, keys stay off the device and Esc closes it`() {
        listOf(AwtKeyEvent.VK_A, AwtKeyEvent.VK_ENTER, AwtKeyEvent.VK_V).forEach {
            assertEquals(KeyTarget.Sheet, keyTarget(sheetOpen = true, sheetFocused = false, keyCode = it, stageCovered = true), "key $it")
        }
        assertEquals(KeyTarget.Window, keyTarget(sheetOpen = true, sheetFocused = false, keyCode = AwtKeyEvent.VK_ESCAPE, stageCovered = true))
        assertEquals(KeyTarget.Sheet, keyTarget(sheetOpen = true, sheetFocused = true, keyCode = AwtKeyEvent.VK_ESCAPE, stageCovered = true))
        assertEquals(KeyTarget.Device, keyTarget(sheetOpen = true, sheetFocused = false, keyCode = AwtKeyEvent.VK_A, stageCovered = false))
    }
}
