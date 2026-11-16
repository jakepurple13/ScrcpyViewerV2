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
import com.programmersbox.scrcpyviewer.state.afterShot
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
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.MirrorWindows
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.WindowArranger
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
import dev.nucleusframework.application.NucleusWindow
import androidx.compose.runtime.SideEffect
import com.programmersbox.scrcpyviewer.ui.window.launch.QuitAnimator
import com.programmersbox.scrcpyviewer.ui.window.launch.LAUNCH_FADE_MILLIS
import com.programmersbox.scrcpyviewer.ui.window.launch.LAUNCH_WATCHDOG_SLACK_MILLIS
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchMotion
import com.programmersbox.scrcpyviewer.ui.window.launch.launchWatchdog
import com.programmersbox.scrcpyviewer.ui.window.launch.setWindowAlpha
import com.programmersbox.scrcpyviewer.ui.window.launch.QuitScrim
import com.programmersbox.scrcpyviewer.di.DataDir
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.ui.devices.DeviceSelection
import com.programmersbox.scrcpyviewer.ui.devices.canMirror
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorRequests
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequests
import com.programmersbox.scrcpyviewer.ui.tray.ScrcpyTray
import com.programmersbox.scrcpyviewer.ui.tray.TrayCommands
import com.programmersbox.scrcpyviewer.ui.tray.trayIconFiles

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
    val mainWindowRequests = koin.get<MainWindowRequests>()
    val mirrorRequests = koin.get<MirrorRequests>()
    val deviceOptions = koin.get<DeviceOptionsStore>()
    val deviceSelection = koin.get<DeviceSelection>()
    val mirrorWindows = koin.get<MirrorWindows>()
    val arranger = koin.get<WindowArranger>()
    // Fixed files the native tray reads (see trayIconFiles); without them the app runs with no tray icon.
    val trayIcons = runCatching { trayIconFiles(koin.get<DataDir>().path.resolve("tray")) }
        .onFailure { System.getLogger("scrcpyviewer.Tray").log(System.Logger.Level.WARNING, "No tray icon files", it) }
        .getOrNull()
    // Single instance off: a dev run next to the installed app must not hand off to it and exit.
    nucleusApplication(enableSingleInstance = false) {
        val settings by appSettings.settings.collectAsState()
        val mirrors by mirrorController.open.collectAsState()
        // Launch: the settings saved by the last run pick the effect, once.
        // A fade launch stays hidden until its opacity is 0 (macOS), so it never shows one opaque frame first.
        val launch = remember { LaunchHandoff(launchEffectNow(settings, fullscreen = false), hideForFade = Os.isMac) }
        val picture = remember { WindowPicture() }
        var mainWindow by remember { mutableStateOf<NucleusWindow?>(null) }
        // Quit: the chosen animation plays after the sessions close (AppShutdown's animateOut).
        val quit = remember { QuitAnimator(picture) { mainWindow?.boundsOnScreen() } }
        // Icon unfold's launch fades the device rows up in a stagger once the real window shows.
        val entrance = remember {
            (launch.effect as? LaunchEffect.Overlay)?.takeIf { it.anim == LaunchQuitAnimation.IconUnfold }
                ?.let { LaunchEntrance(launch.realVisible) }
        }
        // A downloaded update installs after the sessions close, off the UI thread, before the app exits.
        val shutdown = remember {
            AppShutdown(
                mirrorController, exit = ::exitApplication, install = updateManager::installPending,
                animateOut = quit::play,
            )
        }
        val shutdownState by shutdown.state.collectAsState()
        // The tray menu: in the application scope, so it lives as long as the app (closing the main window still quits).
        // It goes once quitting starts, so nothing opens a mirror while the sessions close.
        if (settings.showTrayIcon && trayIcons != null && shutdownState == AppShutdown.State.Running) {
            val devices by mirrorController.devices.collectAsState()
            val trayCommands = remember {
                TrayCommands(
                    mirrorRequests = mirrorRequests,
                    mainWindow = mainWindowRequests,
                    canMirror = { serial ->
                        mirrorController.devices.value.firstOrNull { it.serial == serial }?.let(deviceOptions::canMirror) == true
                    },
                    focus = mirrorController::focus,
                    select = deviceSelection::select,
                    quit = shutdown::request,
                    // Focus from the menu bar arranges around the mirror window focused last.
                    arrange = { layout -> arranger.apply(layout, focus = mirrorWindows.lastFocused.value) },
                    keepTiled = arranger::setKeepTiled,
                )
            }
            val arrangeState by arranger.state.collectAsState()
            ScrcpyTray(trayIcons, devices, mirrors.keys, arrangeState, trayCommands::dispatch)
        }
        // Cmd+Q, the app menu's Quit and the Dock's go through the shutdown too (MacQuitHook).
        DisposableEffect(Unit) {
            shutdown.installQuitHandler(shutdown::request)
            onDispose { }
        }
        LaunchedEffect(shutdownState) {
            if (shutdownState is AppShutdown.State.ShuttingDown) {
                // A quit during the launch animation: the real window takes over at once, with all its rows.
                launch.cancel()
                entrance?.skip()
                quit.begin(
                    launchEffectNow(
                        appSettings.settings.value,
                        fullscreen = mainWindow?.isFullscreen == true,
                        minimized = mainWindow?.isMinimized == true,
                    ),
                    sessionsOpen = mirrorController.open.value.isNotEmpty(),
                )
            }
            shutdown.run()
        }
        // Checks on launch, then every 6 hours while the app runs.
        LaunchedEffect(Unit) { updateManager.checkPeriodically() }
        // A downloaded update installs once the app has shut down (installPending in the shutdown above).
        LaunchedEffect(Unit) {
            updateManager.state.first { it is UpdateState.Installing }
            shutdown.request()
        }
        val shuttingDown = shutdownState as? AppShutdown.State.ShuttingDown
        val launchEffect = launch.effect
        val realVisible by launch.realVisible.collectAsState()
        val overlayOpen by launch.overlayOpen.collectAsState()
        var launchPicture by remember { mutableStateOf<ImageBitmap?>(null) }
        var mainBounds by remember { mutableStateOf<NucleusWindowBounds?>(null) }
        // Whatever stalls the overlay or the fade, the window shows in the end.
        LaunchedEffect(Unit) {
            val animMs = when (launchEffect) {
                is LaunchEffect.Overlay -> LaunchMotion.durationMs(launchEffect.anim, LaunchPhase.Launch)
                LaunchEffect.Fade -> LAUNCH_FADE_MILLIS
                LaunchEffect.None -> return@LaunchedEffect
            }
            launchWatchdog(launch, animMs + LAUNCH_WATCHDOG_SLACK_MILLIS)
            if (launchEffect == LaunchEffect.Fade && shutdown.state.value == AppShutdown.State.Running) {
                // A fade that never ran would leave the window shown but see-through.
                launch.fadeReady()
                mainWindow?.let { setWindowAlpha(it, 1f) }
            }
        }
        AppWindow(
            onCloseRequest = shutdown::request,
            title = appTitle(),
            theme = settings.appTheme,
            // Quitting with the overlay keeps the window up (dimmed while sessions close) until the overlay covers it;
            // the fade keeps it until it has faded; otherwise it goes at once, as it always has.
            visible = realVisible && (shuttingDown == null || (quit.keepsWindow && !quit.hidden) || !quit.faded),
            titleBar = { MainTitle() },
        ) {
            SideEffect { mainWindow = nucleusWindow }
            if (quit.effect == LaunchEffect.Fade) {
                LaunchedEffect(Unit) {
                    fadeWindow(nucleusWindow, from = 1f, to = 0f)
                    quit.fadeFinished()
                }
            }
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
                // The window exists (hidden): make it see-through, show it, then fade it in.
                LaunchedEffect(Unit) {
                    setWindowAlpha(nucleusWindow, 0f)
                    launch.fadeReady()
                    withFrameNanos { }
                    fadeWindow(nucleusWindow, from = 0f, to = 1f)
                }
            }
            // A mirror window asked for the Actions screen: App selects the tab, this brings the window forward.
            LaunchedEffect(actionsNavigation) {
                actionsNavigation.requests.collect { bringToFront(nucleusWindow) }
            }
            // The tray menu's Open / Settings…: App selects the destination, this brings the window forward.
            LaunchedEffect(mainWindowRequests) {
                mainWindowRequests.requests.collect { bringToFront(nucleusWindow) }
            }
            // Device clipboard sync and the mirror paste go through Compose's clipboard, bound here.
            val clipboard = LocalClipboard.current
            val clipboardScope = rememberCoroutineScope()
            val pcClipboard = remember { koin.get<PcClipboard>() }
            LaunchedEffect(clipboard) { pcClipboard.bind(clipboard, clipboardScope) }
            // The Mirror buttons' screen bounds (mirrorOrigin) are read through it.
            val handle = remember(nucleusWindow) { TaoMirrorWindowHandle(nucleusWindow, koin.get<ScreenGeometry>()) }
            // Arrange mirror windows lays them out on the screen the main window is on.
            DisposableEffect(handle) {
                val windows = koin.get<MirrorWindows>()
                val bounds: () -> java.awt.Rectangle? = { handle.bounds.takeIf { it.width > 0 && it.height > 0 } }
                windows.mainWindowBounds = bounds
                onDispose { if (windows.mainWindowBounds === bounds) windows.mainWindowBounds = { null } }
            }
            CompositionLocalProvider(LocalMirrorWindowHandle provides handle, LocalLaunchEntrance provides entrance) {
                Box(Modifier.fillMaxSize().recordInto(picture)) { App() }
            }
            QuitScrim(visible = quit.dimmed, finishingRecording = shuttingDown?.finishingRecording == true)
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
        quit.playing?.let { play ->
            LaunchOverlayWindow(
                position = WindowPosition(play.bounds.x.dp, play.bounds.y.dp),
                size = DpSize(play.bounds.width.dp, play.bounds.height.dp),
                anim = play.anim,
                phase = LaunchPhase.Quit,
                picture = { play.picture },
                theme = settings.appTheme,
                onFirstFrame = quit::overlayFirstFrame,
                onEnd = quit::overlayEnded,
            )
        }
        for ((serial, session) in mirrors) {
            key(serial) {
                MirrorWindow(serial, session, mirrorController, settings.appTheme, settings.captureDir, afterScreenshot = settings.afterShot, animate = settings.animateMirrorWindows, adb = adbClient)
            }
        }
        if (shuttingDown?.finishingRecording == true && !quit.keepsWindow) FinishingRecordingWindow(settings.appTheme)
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
