package com.programmersbox.scrcpyviewer.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder
import dev.nucleusframework.composenativetray.tray.api.NativeTray
import dev.nucleusframework.composenativetray.utils.MenuContentHash
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            if (Os.isMac) awaitMainQueue()
        }
    }

    DisposableEffect(tray) {
        onDispose { trayExecutor.execute { tray.dispose() } }
    }
}

/**
 * Blocks until the AppKit main queue has run everything queued on it before this call, or [timeoutMillis] passes.
 * Returns whether it drained in time.
 *
 * Off the main thread, the native `tray_update` only queues the menu rebuild on the main queue, and that rebuild reads
 * the C menu item strings later. The next update frees those strings first (`MacTrayManager.recreateMenu`), so two
 * updates close together (a device plugged in and out quickly) let the queued rebuild read freed memory: a SIGSEGV in
 * `nativeMenu(from:statusItem:)`. Waiting here after each update keeps the next free behind that rebuild. The main
 * queue runs blocks in order, so our marker runs only after the rebuild did.
 */
internal fun awaitMainQueue(timeoutMillis: Long = 2_000): Boolean {
    val drained = CountDownLatch(1)
    ObjC.onMain { drained.countDown() }
    return drained.await(timeoutMillis, TimeUnit.MILLISECONDS)
}
