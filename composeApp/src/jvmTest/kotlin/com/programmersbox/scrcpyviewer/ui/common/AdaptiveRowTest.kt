package com.programmersbox.scrcpyviewer.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveRowTest {
    @Test
    fun `stacks at full size when the text would get less than its minimum`() {
        // 400 - 300 - 16 = 84 < 160
        assertEquals(RowFit(stacked = true, size = ControlSize.Full), rowFit(400, 160, 16, full = 300))
    }

    @Test
    fun `stays inline when the text keeps its minimum`() {
        assertEquals(RowFit(stacked = false, size = ControlSize.Full), rowFit(500, 160, 16, 300))
    }

    @Test
    fun `stacked control drops to icons when full does not fit`() {
        assertEquals(RowFit(stacked = true, size = ControlSize.Icons), rowFit(250, 160, 16, full = 300, icons = 140))
    }

    @Test
    fun `stacked control drops to the menu when icons do not fit`() {
        assertEquals(
            RowFit(stacked = true, size = ControlSize.Menu),
            rowFit(120, 160, 16, full = 300, icons = 140, menu = 110),
        )
    }
}
