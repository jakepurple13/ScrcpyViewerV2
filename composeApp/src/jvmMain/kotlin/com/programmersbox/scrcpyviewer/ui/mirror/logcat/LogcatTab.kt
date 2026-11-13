package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** The sheet's Logcat tab for [controller], in a sheet [sheetWidth] wide. */
@Composable
fun LogcatTab(controller: LogcatController, sheetWidth: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Logcat", style = MaterialTheme.typography.bodyMedium)
    }
}
