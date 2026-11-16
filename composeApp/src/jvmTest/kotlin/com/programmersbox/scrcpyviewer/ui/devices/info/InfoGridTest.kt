package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class InfoGridTest {
    @Test
    fun `columns fit 220 dp each and stop at three`() {
        assertEquals(1, gridColumns(300.dp))
        assertEquals(2, gridColumns(452.dp))
        assertEquals(3, gridColumns(684.dp))
        assertEquals(3, gridColumns(976.dp))
    }

    @Test
    fun `six cards in three columns go two per column in reading order`() {
        assertEquals(listOf(listOf(0, 3), listOf(1, 4), listOf(2, 5)), (0 until 3).map { masonryColumn(it, 3, 6) })
        assertEquals(listOf(listOf(0, 2, 4), listOf(1, 3, 5)), (0 until 2).map { masonryColumn(it, 2, 6) })
        assertEquals(listOf((0 until 6).toList()), listOf(masonryColumn(0, 1, 6)))
    }
}
