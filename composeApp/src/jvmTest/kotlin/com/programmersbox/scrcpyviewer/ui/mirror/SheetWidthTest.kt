package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SheetWidthTest {
    @Test
    fun `closed is zero, Actions 280 and Shell 400`() {
        assertEquals(0.dp, sheetWidth(false, SheetTab.Shell))
        assertEquals(0.dp, sheetWidth(false, SheetTab.Actions))
        assertEquals(280.dp, sheetWidth(true, SheetTab.Actions))
        assertEquals(400.dp, sheetWidth(true, SheetTab.Shell))
    }

    @Test
    fun `growing takes 260 ms and shrinking 200 ms`() {
        assertEquals(260, sheetWidthSpec(280.dp, 400.dp).durationMillis)
        assertEquals(200, sheetWidthSpec(400.dp, 280.dp).durationMillis)
        assertEquals(260, sheetWidthSpec(0.dp, 400.dp).durationMillis)
        assertEquals(200, sheetWidthSpec(400.dp, 0.dp).durationMillis)
    }
}
