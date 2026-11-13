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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import com.programmersbox.scrcpyviewer.ui.common.PcClipboard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.neonText
import com.programmersbox.scrcpyviewer.ui.window.AppWindow
import com.programmersbox.scrcpyviewer.ui.window.bringToFront
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import com.programmersbox.scrcpyviewer.adb.AdbClient
import androidx.compose.runtime.CompositionLocalProvider
import com.programmersbox.scrcpyviewer.ui.mirror.TaoMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.LocalMirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindow
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.App
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.actions.ActionInbox
import com.programmersbox.scrcpyviewer.di.AppScope
import com.programmersbox.scrcpyviewer.di.startAppKoin
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.flow.first
import com.programmersbox.scrcpyviewer.files.DragCache
import kotlin.concurrent.thread
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchEffect
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchEntrance
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchHandoff
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchOverlayWindow
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchPhase
import com.programmersbox.scrcpyviewer.ui.window.launch.LocalLaunchEntrance
import com.programmersbox.scrcpyviewer.ui.window.launch.WindowPicture
import com.programmersbox.scrcpyviewer.ui.window.launch.fadeWindow
import com.programmersbox.scrcpyviewer.ui.window.launch.launchEffectNow
import com.programmersbox.scrcpyviewer.ui.window.launch.recordInto
import dev.nucleusframework.application.NucleusWindowBounds

fun main() {
    // Files a crashed or killed run left in the Files tab's cache (drags, opened files, APKs being installed).
    thread(name = "drag-cache-sweep", isDaemon = true) { DragCache().sweep() }
    val koin = startAppKoin()
    koin.get<ActionInbox>().start(koin.get<AppScope>())
    val appSettings = koin.get<AppSettings>()
    val mirrorController = koin.get<MirrorController>()
    val adbClient = koin.get<AdbClient>()
    val updateManager = koin.get<UpdateManager>()
    val actionsNavigation = koin.get<ActionsNavigation>()
    // Single instance off: a dev run next to the installed app must not hand off to it and exit.
    nucleusApplication(enableSingleInstance = false) {
        val settings by appSettings.settings.collectAsState()
        val mirrors by mirrorController.open.collectAsState()
        // A downloaded update installs after the sessions close, off the UI thread, before the app exits.
        val shutdown = remember {
            AppShutdown(mirrorController, exit = ::exitApplication, install = updateManager::installPending)
        }
        val shutdownState by shutdown.state.collectAsState()
        // Cmd+Q, the app menu's Quit and the Dock's go through the shutdown too (MacQuitHook).
        DisposableEffect(Unit) {
            shutdown.installQuitHandler(shutdown::request)
            onDispose { }
        }
        LaunchedEffect(shutdownState) { shutdown.run() }
        // Checks on launch, then every 6 hours while the app runs.
        LaunchedEffect(Unit) { updateManager.checkPeriodically() }
        // A downloaded update installs once the app has shut down (installPending in the shutdown above).
        LaunchedEffect(Unit) {
            updateManager.state.first { it is UpdateState.Installing }
            shutdown.request()
        }
        val shuttingDown = shutdownState as? AppShutdown.State.ShuttingDown
        // Launch: the settings saved by the last run pick the effect, once.
        val launch = remember { LaunchHandoff(launchEffectNow(settings, fullscreen = false)) }
        val launchEffect = remember { launch.effect }
        val realVisible by launch.realVisible.collectAsState()
        val overlayOpen by launch.overlayOpen.collectAsState()
        val picture = remember { WindowPicture() }
        var launchPicture by remember { mutableStateOf<ImageBitmap?>(null) }
        var mainBounds by remember { mutableStateOf<NucleusWindowBounds?>(null) }
        val entrance = remember {
            (launchEffect as? LaunchEffect.Overlay)?.takeIf { it.anim == LaunchQuitAnimation.IconUnfold }
                ?.let { LaunchEntrance(launch.realVisible) }
        }
        AppWindow(
            onCloseRequest = shutdown::request,
            title = appTitle(),
            theme = settings.appTheme,
            visible = realVisible && shuttingDown == null,
            titleBar = { MainTitle() },
        ) {
            // Where the overlay goes, once the (still hidden) window exists; then a picture of its first frames.
            LaunchedEffect(Unit) {
                if (!overlayOpen) return@LaunchedEffect
                mainBounds = nucleusWindow.boundsOnScreen()
                withFrameNanos { }
                launchPicture = picture.capture()
                if (mainBounds == null) launch.cancel() // nowhere to draw: show the window as before
            }
            // The overlay closes once the real window has drawn since it was shown.
            LaunchedEffect(realVisible) {
                if (!realVisible) return@LaunchedEffect
                withFrameNanos { }
                withFrameNanos { }
                launch.realWindowDrew()
            }
            if (launchEffect == LaunchEffect.Fade) {
                LaunchedEffect(Unit) { fadeWindow(nucleusWindow, from = 0f, to = 1f) }
            }
            // A mirror window asked for the Actions screen: App selects the tab, this brings the window forward.
            LaunchedEffect(actionsNavigation) {
                actionsNavigation.requests.collect { bringToFront(nucleusWindow) }
            }
            // Device clipboard sync and the mirror paste go through Compose's clipboard, bound here.
            val clipboard = LocalClipboard.current
            val clipboardScope = rememberCoroutineScope()
            val pcClipboard = remember { koin.get<PcClipboard>() }
            LaunchedEffect(clipboard) { pcClipboard.bind(clipboard, clipboardScope) }
            // The Mirror buttons' screen bounds (mirrorOrigin) are read through it.
            val handle = remember(nucleusWindow) { TaoMirrorWindowHandle(nucleusWindow, koin.get<ScreenGeometry>()) }
            CompositionLocalProvider(LocalMirrorWindowHandle provides handle, LocalLaunchEntrance provides entrance) {
                Box(Modifier.fillMaxSize().recordInto(picture)) { App() }
            }
        }
        val bounds = mainBounds
        if (overlayOpen && launchEffect is LaunchEffect.Overlay && bounds != null) {
            LaunchOverlayWindow(
                position = WindowPosition(bounds.x.dp, bounds.y.dp),
                size = DpSize(bounds.width.dp, bounds.height.dp),
                anim = launchEffect.anim,
                phase = LaunchPhase.Launch,
                picture = { launchPicture },
                theme = settings.appTheme,
                onEnd = launch::overlayEnded,
            )
        }
        for ((serial, session) in mirrors) {
            key(serial) {
                MirrorWindow(serial, session, mirrorController, settings.appTheme, settings.captureDir, animate = settings.animateMirrorWindows, adb = adbClient)
            }
        }
        if (shuttingDown?.finishingRecording == true) FinishingRecordingWindow(settings.appTheme)
    }
    // nucleusApplication never returns: the Tao backend calls System.exit once its event loop ends. Shutdown work
    // belongs in AppShutdown, not here.
}

/** The main window's custom title bar: app icon on Windows (macOS and Linux show none), then the name. */
@Composable
private fun MainTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (Os.isWindows) {
            Image(AppIcon.painter, contentDescription = null, Modifier.padding(start = 12.dp, end = 8.dp).size(16.dp))
        }
        // Neon themes light the app name up in the glow color.
        Text(appTitle(), style = neonText(MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.primary))
    }
}

/** Shown while quitting waits for recorders to write their trailers. It cannot be closed. */
@Composable
private fun NucleusApplicationScope.FinishingRecordingWindow(theme: AppTheme) = ScrcpyViewerTheme(theme) {
    MaterialDecoratedWindow(
        onCloseRequest = {},
        title = "Scrcpy Vwr",
        icon = AppIcon.painter,
        state = rememberWindowState(size = DpSize(340.dp, 120.dp), position = WindowPosition(Alignment.Center)),
        resizable = false,
    ) {
        MaterialTitleBar()
        // Tao windows don't inherit composition locals: the theme is provided again.
        ScrcpyViewerTheme(theme) {
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
