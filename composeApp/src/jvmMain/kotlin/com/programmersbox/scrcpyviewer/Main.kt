package com.programmersbox.scrcpyviewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import com.programmersbox.scrcpyviewer.ui.window.windowChrome
import javax.swing.SwingUtilities
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindow
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.App
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.di.startAppKoin
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.flow.first
import kotlin.system.exitProcess

fun main() {
    AppIcon.installTaskbarIcon()
    val koin = startAppKoin()
    val appSettings = koin.get<AppSettings>()
    val mirrorController = koin.get<MirrorController>()
    val adbClient = koin.get<AdbClient>()
    val updateManager = koin.get<UpdateManager>()
    val actionsNavigation = koin.get<ActionsNavigation>()
    // exitProcessOnExit = false so the update install below runs once the windows are gone.
    application(exitProcessOnExit = false) {
        val settings by appSettings.settings.collectAsState()
        val mirrors by mirrorController.open.collectAsState()
        val shutdown = remember { AppShutdown(mirrorController, exit = ::exitApplication) }
        val shutdownState by shutdown.state.collectAsState()
        DisposableEffect(Unit) {
            shutdown.installQuitHandler { SwingUtilities.invokeLater(shutdown::request) }
            onDispose { }
        }
        LaunchedEffect(shutdownState) { shutdown.run() }
        LaunchedEffect(Unit) { updateManager.check(userInitiated = false) }
        // A downloaded update installs once the app has shut down (installPending below).
        LaunchedEffect(Unit) {
            updateManager.state.first { it is UpdateState.Installing }
            shutdown.request()
        }
        val shuttingDown = shutdownState as? AppShutdown.State.ShuttingDown
        // Read once: a settings change applies to windows opened later, not this one.
        val mainChrome = remember { windowChrome(settings.useSystemTitleBar) }
        AppWindow(
            onCloseRequest = shutdown::request,
            title = appTitle(),
            themeMode = settings.themeMode,
            chrome = mainChrome,
            visible = shuttingDown == null,
            titleBar = { if (chrome == WindowChrome.Custom) MainTitle() },
        ) {
            // A mirror window asked for the Actions screen: App selects the tab, this brings the window forward.
            LaunchedEffect(actionsNavigation) {
                actionsNavigation.requests.collect { bringToFront(window) }
            }
            App()
        }
        for ((serial, session) in mirrors) {
            key(serial) {
                MirrorWindow(serial, session, mirrorController, settings.themeMode, settings.useSystemTitleBar, settings.captureDir, adb = adbClient)
            }
        }
        if (shuttingDown?.finishingRecording == true) FinishingRecordingWindow(settings.themeMode)
    }
    // Sessions are closed and recordings finished. With a downloaded update, Nucleus starts its install script and
    // exits the JVM here; otherwise (or if it could not start) the app exits normally.
    updateManager.installPending()
    exitProcess(0)
}

/** Restores [window] when minimized, then raises and focuses it. */
private fun bringToFront(window: java.awt.Frame) {
    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    window.toFront()
    window.requestFocus()
}

/** The main window's custom title bar: app icon on Windows (macOS and Linux show none), then the name. */
@Composable
private fun MainTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (Os.isWindows) {
            Image(AppIcon.painter, contentDescription = null, Modifier.padding(start = 12.dp, end = 8.dp).size(16.dp))
        }
        Text(appTitle(), style = MaterialTheme.typography.titleSmall)
    }
}

/** Shown while quitting waits for recorders to write their trailers. It cannot be closed. */
@Composable
private fun FinishingRecordingWindow(themeMode: ThemeMode) {
    Window(
        onCloseRequest = {},
        title = "Scrcpy Vwr",
        icon = AppIcon.painter,
        state = rememberWindowState(size = DpSize(340.dp, 120.dp), position = WindowPosition(Alignment.Center)),
        resizable = false,
    ) {
        ScrcpyViewerTheme(themeMode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Finishing recording…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
