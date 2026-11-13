package com.programmersbox.scrcpyviewer.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder
import dev.nucleusframework.composenativetray.tray.api.NativeTray
import dev.nucleusframework.composenativetray.utils.MenuContentHash
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The one thread that talks to the native tray. A single thread keeps updates in order. */
private val trayExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "scrcpyviewer-tray").apply { isDaemon = true }
}
private val trayDispatcher = trayExecutor.asCoroutineDispatcher()

/**
 * Same as the library's `Tray(...)`, but every call into [NativeTray] runs on [trayDispatcher].
 *
 * On macOS, `MacTrayInitializer.initialize` and `update` are both `synchronized`. Initialization holds the
 * lock while native code waits for the AppKit main thread, so an update from the main thread during startup
 * blocks it on that lock and the app hangs. Keeping the main thread out of the tray code avoids the deadlock.
 */
@Composable
fun OffMainTray(
    iconPath: String,
    windowsIconPath: String,
    tooltip: String,
    /** Changes whenever the menu's click targets change, since the library's hash ignores them. */
    key: Any?,
    menuContent: TrayMenuBuilder.() -> Unit,
) {
    val tray = remember { NativeTray() }
    // The library's own change key for menu content: a lambda is new on every recomposition.
    val menuHash = MenuContentHash.calculateMenuHash(menuContent)

    LaunchedEffect(tray, iconPath, windowsIconPath, tooltip, menuHash, key) {
        withContext(trayDispatcher) {
            tray.update(iconPath, windowsIconPath, tooltip, null, menuContent, null)
        }
    }

    DisposableEffect(tray) {
        onDispose { trayExecutor.execute { tray.dispose() } }
    }
}
