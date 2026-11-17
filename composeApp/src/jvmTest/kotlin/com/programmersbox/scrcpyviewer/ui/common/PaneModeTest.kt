package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PaneModeTest {
    // 676 = list min 240 + divider 16 + detail min 420.
    @Test
    fun `two panes when the list min, divider and detail min fit, pushed or not`() {
        assertEquals(PaneMode.TwoPane, paneMode(676.dp, 240.dp, pushed = false, windowWidth = 1000.dp))
        assertEquals(PaneMode.TwoPane, paneMode(676.dp, 240.dp, pushed = true, windowWidth = 1000.dp))
    }

    @Test
    fun `one dp short shows the list alone when nothing is pushed`() {
        assertEquals(PaneMode.ListView, paneMode(675.dp, 240.dp, pushed = false, windowWidth = 1000.dp))
    }

    @Test
    fun `one dp short shows the pushed detail when an item is pushed`() {
        assertEquals(PaneMode.ItemView, paneMode(675.dp, 240.dp, pushed = true, windowWidth = 1000.dp))
    }

    @Test
    fun `a 840 dp window shows two panes, 839 does not, with content that fits`() {
        assertEquals(PaneMode.TwoPane, paneMode(800.dp, 240.dp, pushed = false, windowWidth = 840.dp))
        assertEquals(PaneMode.ListView, paneMode(800.dp, 240.dp, pushed = false, windowWidth = 839.dp))
        assertEquals(PaneMode.ItemView, paneMode(800.dp, 240.dp, pushed = true, windowWidth = 839.dp))
    }

    @Test
    fun `a wide window still needs the content to fit`() {
        assertEquals(PaneMode.ListView, paneMode(675.dp, 240.dp, pushed = false, windowWidth = 1400.dp))
    }
}
