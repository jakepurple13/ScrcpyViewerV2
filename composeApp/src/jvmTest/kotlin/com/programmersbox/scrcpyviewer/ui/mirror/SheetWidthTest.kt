package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetWidthTest {
    @Test
    fun `closed is zero, Actions 280 and Shell 400`() {
        assertEquals(0.dp, sheetWidth(false, SheetTab.Shell))
        assertEquals(0.dp, sheetWidth(false, SheetTab.Actions))
        assertEquals(280.dp, sheetWidth(true, SheetTab.Actions))
        assertEquals(400.dp, sheetWidth(true, SheetTab.Shell))
    }

    @Test
    fun `each tab's own width drives sheetWidth`() {
        val widths = SheetWidths(actions = 520.dp, shell = 640.dp)
        assertEquals(520.dp, sheetWidth(true, SheetTab.Actions, widths))
        assertEquals(640.dp, sheetWidth(true, SheetTab.Shell, widths))
        assertEquals(0.dp, sheetWidth(false, SheetTab.Shell, widths))
        assertEquals(SheetWidths(actions = 300.dp, shell = 640.dp), widths.with(SheetTab.Actions, 300.dp))
        assertEquals(SheetWidths(actions = 520.dp, shell = 400.dp), widths.with(SheetTab.Shell, 400.dp))
    }

    @Test
    fun `growing takes 260 ms and shrinking 200 ms`() {
        assertEquals(260, sheetWidthSpec(280.dp, 400.dp).durationMillis)
        assertEquals(200, sheetWidthSpec(400.dp, 280.dp).durationMillis)
        assertEquals(260, sheetWidthSpec(0.dp, 400.dp).durationMillis)
        assertEquals(200, sheetWidthSpec(400.dp, 0.dp).durationMillis)
    }

    @Test
    fun `clamping keeps each tab's minimum and 160 dp for the device`() {
        // Window 1200 dp, toolbar 64 dp: the sheet may take up to 1200 - 64 - 160 = 976 dp.
        assertEquals(280.dp, clampSheetWidth(200.dp, SheetTab.Actions, 1200.dp, 64.dp))
        assertEquals(400.dp, clampSheetWidth(300.dp, SheetTab.Shell, 1200.dp, 64.dp))
        assertEquals(520.dp, clampSheetWidth(520.dp, SheetTab.Actions, 1200.dp, 64.dp))
        assertEquals(976.dp, clampSheetWidth(2000.dp, SheetTab.Actions, 1200.dp, 64.dp))
        assertEquals(976.dp, clampSheetWidth(2000.dp, SheetTab.Shell, 1200.dp, 64.dp))
    }

    @Test
    fun `a small window clamps the sheet down to what fits, never under the tab's minimum`() {
        // 744 - 64 - 160 = 520 dp fit.
        assertEquals(520.dp, clampSheetWidth(600.dp, SheetTab.Actions, 744.dp, 64.dp))
        // Too narrow even for the minimum: the sheet keeps its minimum.
        assertEquals(280.dp, clampSheetWidth(600.dp, SheetTab.Actions, 400.dp, 64.dp))
        assertEquals(400.dp, clampSheetWidth(600.dp, SheetTab.Shell, 400.dp, 64.dp))
    }

    @Test
    fun `tile columns follow the sheet width`() {
        assertEquals(2, tileColumns(280.dp))
        assertEquals(2, tileColumns(359.dp))
        assertEquals(3, tileColumns(360.dp))
        assertEquals(3, tileColumns(479.dp))
        assertEquals(4, tileColumns(480.dp))
        assertEquals(4, tileColumns(900.dp))
    }

    @Test
    fun `resizing changes only the current tab, saves only that tab on done, and resets to the default`() {
        val saved = mutableListOf<Pair<SheetTab, Dp>>()
        val qa = QuickActions(
            device = null, scope = CoroutineScope(Dispatchers.Unconfined),
            widths = SheetWidths(actions = 300.dp, shell = 500.dp), saveWidth = { tab, width -> saved += tab to width },
        ) {}
        assertEquals(SheetWidths(actions = 300.dp, shell = 500.dp), qa.uiState.widths)

        qa.onEvent(QuickActionEvent.Resize(360.dp))
        qa.onEvent(QuickActionEvent.Resize(420.dp))
        assertEquals(SheetWidths(actions = 420.dp, shell = 500.dp), qa.uiState.widths)
        assertTrue(saved.isEmpty(), "not saved while dragging")
        qa.onEvent(QuickActionEvent.ResizeDone(SheetTab.Actions))
        assertEquals(listOf(SheetTab.Actions to 420.dp), saved)

        qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Shell))
        qa.onEvent(QuickActionEvent.ResetWidth)
        assertEquals(SheetWidths(actions = 420.dp, shell = 400.dp), qa.uiState.widths)
        assertEquals(SheetTab.Shell to 400.dp, saved.last())
    }

    @Test
    fun `a save that lands after a tab switch saves the tab that was resized`() {
        val saved = mutableListOf<Pair<SheetTab, Dp>>()
        val qa = QuickActions(
            device = null, scope = CoroutineScope(Dispatchers.Unconfined), saveWidth = { tab, width -> saved += tab to width },
        ) {}
        qa.onEvent(QuickActionEvent.Resize(296.dp)) // Left on the bar, Actions tab
        qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Shell)) // switched before the delayed save
        qa.onEvent(QuickActionEvent.ResizeDone(SheetTab.Actions))
        assertEquals(listOf(SheetTab.Actions to 296.dp), saved)
    }

    @Test
    fun `saved widths load between each tab's minimum and 1200 dp`() {
        assertEquals(SheetWidths(actions = 520.dp, shell = 640.dp), SheetWidths.fromSettings(520, 640))
        assertEquals(SheetWidths(actions = 280.dp, shell = 400.dp), SheetWidths.fromSettings(0, -5))
        assertEquals(SheetWidths(actions = 1200.dp, shell = 1200.dp), SheetWidths.fromSettings(90_000, 5000))
    }
}
