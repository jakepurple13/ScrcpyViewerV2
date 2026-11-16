package com.programmersbox.scrcpyviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes

/** Pane state for screenshots: an optional dragged list width (px) and the single-pane destination. */
@Composable
fun screenshotPanes(listWidthPx: Int? = null, detailOpen: Boolean = false): ListDetailPanes {
    val panes = rememberListDetailPanes()
    remember(panes) {
        if (listWidthPx != null) {
            panes.expansion.setFirstPaneWidth(listWidthPx)
            // AdaptiveListDetail (Devices) reads the dragged width in dp; screenshots render at 1 px = 1 dp.
            panes.listWidth = listWidthPx.dp
        }
        if (detailOpen) panes.navigation.openDetail()
    }
    return panes
}
