package com.programmersbox.scrcpyviewer.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bring the main window to the front, on [route] when it is not null (null keeps the current screen). */
data class MainWindowRequest(val route: TopLevelRoute? = null)

/**
 * Asks for the main window from outside it (the tray menu). Main.kt brings the window forward; App selects the route.
 * Safe from any thread.
 */
class MainWindowRequests {
    private val _requests = MutableSharedFlow<MainWindowRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<MainWindowRequest> = _requests.asSharedFlow()

    fun show(route: TopLevelRoute? = null) {
        _requests.tryEmit(MainWindowRequest(route))
    }
}
