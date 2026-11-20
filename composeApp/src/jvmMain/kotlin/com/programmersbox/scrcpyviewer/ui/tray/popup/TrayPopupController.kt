package com.programmersbox.scrcpyviewer.ui.tray.popup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long after the popup closes an icon click is taken as the same click that closed it. */
private const val REOPEN_GUARD_MS = 300L

/**
 * Whether the menu bar popup is open, and the pointer location ([anchor], window units) of the click that opened it.
 * [iconClicked] runs on the tray thread and [dismissed] on the UI thread; both only write state.
 */
class TrayPopupController(private val now: () -> Long = System::currentTimeMillis) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _anchor = MutableStateFlow<IntArray?>(null)
    val anchor: StateFlow<IntArray?> = _anchor.asStateFlow()

    private var dismissedAt: Long? = null

    /**
     * The tray icon was clicked at [pointer] (null when unknown). Toggles the popup. A click within 300 ms of a
     * [dismissed] stays closed: clicking the icon takes focus from the popup first, so that click already closed it.
     */
    @Synchronized
    fun iconClicked(pointer: IntArray?) {
        if (_visible.value) {
            close()
            return
        }
        val last = dismissedAt
        if (last != null && now() - last < REOPEN_GUARD_MS) return
        _anchor.value = pointer
        _visible.value = true
    }

    /** The popup lost focus, got Esc, or ran an action that brings a window forward. */
    @Synchronized
    fun dismissed() {
        if (_visible.value) close()
    }

    private fun close() {
        dismissedAt = now()
        _visible.value = false
    }
}
