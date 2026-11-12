package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import javax.swing.SwingUtilities

/**
 * The one way the app quits: the main window's close button and the macOS Quit command (Cmd+Q, Dock, menu) both
 * call [request]. [run] then closes every mirror session, which waits for their recorders to write the trailer
 * (bounded by [timeoutMs]), and only then calls [exit]. While it waits, [state] says whether a recording is being
 * finished, so the UI can say so instead of freezing silently.
 */
class AppShutdown(
    private val controller: MirrorController,
    private val exit: () -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val quitHook: QuitHook = AwtQuitHook,
) {
    sealed interface State {
        data object Running : State
        data class ShuttingDown(val finishingRecording: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Running)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts shutting down; later calls are ignored. Safe from any thread. */
    fun request() {
        val recording = controller.open.value.values.any { it.hasUnfinishedRecording }
        _state.compareAndSet(State.Running, State.ShuttingDown(recording))
    }

    /** Once [request] was called: closes the sessions (at most [timeoutMs]) and exits. */
    suspend fun run() {
        state.value as? State.ShuttingDown ?: return
        if (withTimeoutOrNull(timeoutMs) { controller.closeAll() } == null) {
            log.log(System.Logger.Level.WARNING, "Sessions still closing after $timeoutMs ms, exiting anyway")
        }
        exit()
    }

    /**
     * Routes the macOS Quit command to [onQuit] instead of AWT's default, which calls System.exit and skips the
     * session teardown. No-op where the platform has no quit handler.
     */
    fun installQuitHandler(onQuit: () -> Unit) = quitHook.install(onQuit)

    companion object {
        /** Generous: each session's teardown may wait up to 10 s for its recorder. */
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private val log = System.getLogger("scrcpyviewer.AppShutdown")
    }
}

/** Platform seam for the OS Quit command. [install] routes it to [onQuit]; [onQuit] may be called from any thread. */
fun interface QuitHook {
    fun install(onQuit: () -> Unit)
}

/** AWT's macOS quit handler, with the hop onto the EDT. No-op where the platform has none. */
object AwtQuitHook : QuitHook {
    override fun install(onQuit: () -> Unit) {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
        desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            SwingUtilities.invokeLater { onQuit() }
        }
    }
}
