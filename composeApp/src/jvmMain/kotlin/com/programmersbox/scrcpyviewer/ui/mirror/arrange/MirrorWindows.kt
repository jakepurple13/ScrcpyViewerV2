package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.runtime.MutableState
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Rectangle
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * One open mirror window as the arranger sees it. The lambdas read the window's live state.
 *
 * [info] is its frame and chrome now; [floating] false while maximized or fullscreen; [showing] false while it is not
 * on screen yet, or grows or shrinks (open and close animations). [arranging] is set by the arranger while it moves the
 * window: the window's own sizing and aspect effects wait meanwhile. [appSizing] is true while the app itself moves or
 * resizes the window (the quick actions sheet growing it, the size following a new frame size or Fit): such changes are
 * never the user's. [uiContext] is where the window's native calls and animation frames run (its composition's
 * context; any Job in it is ignored).
 */
class WindowEntry(
    val handle: MirrorWindowHandle,
    val info: () -> ArrangeWindow,
    val floating: () -> Boolean,
    val minimized: () -> Boolean,
    val showing: () -> Boolean,
    val arranging: MutableState<Boolean>,
    val appSizing: StateFlow<Boolean> = MutableStateFlow(false),
    val uiContext: CoroutineContext = EmptyCoroutineContext,
)

/** The open mirror windows, by serial. A window registers once it has its first frame and size, and unregisters as it goes. */
class MirrorWindows {
    private val _entries = MutableStateFlow<Map<String, WindowEntry>>(emptyMap())
    val entries: StateFlow<Map<String, WindowEntry>> = _entries.asStateFlow()

    /** The main window's bounds (window units), or null while it has none; set by the main window. */
    @Volatile
    var mainWindowBounds: () -> Rectangle? = { null }

    fun register(serial: String, entry: WindowEntry) {
        _entries.update { it + (serial to entry) }
    }

    /** Removes [serial] if [entry] is still its entry (a reopened window may have registered meanwhile). */
    fun unregister(serial: String, entry: WindowEntry) {
        _entries.update { if (it[serial] === entry) it - serial else it }
    }
}
