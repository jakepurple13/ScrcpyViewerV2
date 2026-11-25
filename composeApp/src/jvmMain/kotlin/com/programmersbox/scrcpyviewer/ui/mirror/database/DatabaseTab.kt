package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** The Data tab in the sheet: the tab itself, or a card while Databases is in its own window. */
@Composable
fun DatabaseSheetTab(controller: DatabaseController, sheetWidth: Dp, motion: Boolean, onShowDebuggableApps: () -> Unit) {
    Text("Data")
}
