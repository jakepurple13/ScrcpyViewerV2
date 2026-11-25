package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.bringToFront
import com.programmersbox.scrcpyviewer.ui.window.launch.fadeWindow
import com.programmersbox.scrcpyviewer.ui.window.launch.setWindowAlpha
import dev.nucleusframework.application.LocalNucleusApplicationScope
import kotlinx.coroutines.flow.drop

/** The own window's size, every time it opens. */
val DATABASE_WINDOW_SIZE = DpSize(900.dp, 640.dp)

/** The smallest the own window can be dragged to; the panel goes list-first below two panes. */
val DatabaseWindowMinSize = DpSize(360.dp, 360.dp)

/**
 * Databases' own window, "Databases · [deviceName]", while [DatabaseController.popped]: the Data tab's panel at
 * [DATABASE_WINDOW_SIZE] each time it opens. Closing it docks the panel ([DatabaseController.dock]);
 * [DatabaseController.raise] brings it to the front. Composed inside the mirror window's composition, so it closes
 * with that window. It fades in and out like Logcat's window.
 */
@Composable
fun DatabaseWindow(controller: DatabaseController, deviceName: String, theme: AppTheme, onShowDebuggableApps: () -> Unit) {
    val popped = controller.popped
    var up by remember { mutableStateOf(false) }
    if (!popped && !up) return
    val motion = remember { !systemReducesMotion() }
    val state = rememberWindowState(size = DATABASE_WINDOW_SIZE)
    var shown by remember { mutableStateOf(!motion) }
    val title = "Databases · $deviceName"
    LocalNucleusApplicationScope.current.AppWindow(
        onCloseRequest = controller::dock,
        title = title,
        theme = theme,
        state = state,
        visible = shown,
        minSize = DatabaseWindowMinSize,
        reduceMotion = !motion,
        titleBar = {
            Text(title, Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) {
        val window = nucleusWindow
        LaunchedEffect(window, popped) {
            up = true
            if (popped) {
                if (!shown) {
                    setWindowAlpha(window, 0f)
                    shown = true
                    withFrameNanos { }
                    fadeWindow(window, from = 0f, to = 1f)
                } else if (motion) {
                    fadeWindow(window, from = 0f, to = 1f)
                }
            } else {
                if (motion) fadeWindow(window, from = 1f, to = 0f)
                up = false
            }
        }
        DatabaseWindowContent(controller, motion, shown, onRaise = { bringToFront(window) }, onShowDebuggableApps = onShowDebuggableApps)
    }
}

/** What [DatabaseWindow] shows: the panel without its pop-out button, and [onRaise] once per [DatabaseController.raise]. */
@Composable
fun DatabaseWindowContent(
    controller: DatabaseController,
    motion: Boolean,
    shown: Boolean,
    onRaise: () -> Unit,
    onShowDebuggableApps: () -> Unit = {},
) {
    val raise by rememberUpdatedState(onRaise)
    LaunchedEffect(controller) {
        // A count from before this composition is not a request to this window.
        snapshotFlow { controller.raiseRequests }.drop(1).collect { raise() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (shown) DatabaseTab(controller, motion, onShowDebuggableApps, onPopOut = null)
    }
}
