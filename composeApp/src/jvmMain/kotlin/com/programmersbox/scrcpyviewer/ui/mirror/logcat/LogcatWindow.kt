package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.bringToFront
import com.programmersbox.scrcpyviewer.ui.window.launch.fadeWindow
import com.programmersbox.scrcpyviewer.ui.window.launch.setWindowAlpha
import dev.nucleusframework.application.LocalNucleusApplicationScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The own window's size, every time it opens. */
val LOGCAT_WINDOW_SIZE = DpSize(720.dp, 640.dp)

/**
 * Logcat's own window, "Logcat · [deviceName]", while [LogcatController.popped]: the same panel as the sheet's tab
 * ([LogcatTab]) at [LOGCAT_WINDOW_SIZE] each time it opens, with a pin in the title bar for always on top. Closing it
 * docks the panel ([LogcatController.dock]). [LogcatController.raise] brings it to the front.
 *
 * Composed inside the mirror window's composition, so it closes with that window. It fades in as it opens and out
 * after a dock (macOS: the window's native opacity, as the app's launch fade; elsewhere it opens and goes at once), and
 * stays composed until that fade has run. Keys go to the panel's own handlers; nothing here reaches the device.
 */
@Composable
fun LogcatWindow(controller: LogcatController, deviceName: String, theme: AppTheme) {
    val popped = controller.popped
    // A window is up: from its first composition until its closing fade has run.
    var up by remember { mutableStateOf(false) }
    if (!popped && !up) return
    val motion = remember { !systemReducesMotion() }
    // Fresh each time the window opens: the default size, never a remembered one.
    val state = rememberWindowState(size = LOGCAT_WINDOW_SIZE)
    // Hidden until it is see-through, so the fade-in starts from nothing.
    var shown by remember { mutableStateOf(!motion) }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    LocalNucleusApplicationScope.current.AppWindow(
        onCloseRequest = controller::dock,
        title = "Logcat · $deviceName",
        theme = theme,
        state = state,
        visible = shown,
        alwaysOnTop = controller.alwaysOnTop,
        titleBar = { LogcatTitleBar("Logcat · $deviceName", controller, motion) },
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
                    fadeWindow(window, from = 0f, to = 1f) // docked and popped out again mid-fade
                }
            } else {
                if (motion) fadeWindow(window, from = 1f, to = 0f)
                up = false
            }
        }
        LaunchedEffect(window) {
            snapshotFlow { controller.raiseRequests }.drop(1).collect { bringToFront(window) }
        }
        DisposableEffect(controller) {
            controller.windowToast = { message -> snackScope.launch { snackbar.showSnackbar(message) } }
            onDispose { controller.windowToast = null }
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(focus) { focus.requestFocus() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
                LogcatTab(controller, maxWidth, motion, inWindow = true, focusRequester = focus)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp))
            }
        }
    }
}

/** The window's title bar slot: [title], and at the end the always-on-top pin ([LogcatPinButton]). */
@Composable
fun LogcatTitleBar(title: String, controller: LogcatController, motion: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LogcatPinButton(controller, motion)
    }
}

private val PinShape = RoundedCornerShape(6.dp)

/**
 * "Always on top": pressed, the pin turns 45° and its container fades to primaryContainer; the window then stays above
 * the others, the mirror included.
 */
@Composable
fun LogcatPinButton(controller: LogcatController, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val on = controller.alwaysOnTop
    val colorSpec = if (motion) tween<Color>(200) else snap()
    val container by animateColorAsState(if (on) scheme.primaryContainer else scheme.primaryContainer.copy(alpha = 0f), colorSpec)
    val content by animateColorAsState(if (on) scheme.onPrimaryContainer else LocalContentColor.current.copy(alpha = 0.72f), colorSpec)
    val turn by animateFloatAsState(if (on) 45f else 0f, if (motion) tween(300, easing = EmphasizedDecelerate) else snap())
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        IconToggleButton(
            checked = on,
            onCheckedChange = { controller.toggleAlwaysOnTop() },
            modifier = Modifier.size(width = 28.dp, height = 24.dp).semantics { contentDescription = "Always on top" },
            shape = PinShape,
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = container, contentColor = content,
                checkedContainerColor = container, checkedContentColor = content,
            ),
        ) {
            Icon(Icons.Filled.PushPin, contentDescription = null, Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        }
    }
}
