package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * The one way the app quits: the main window's close button and the macOS Quit command (Cmd+Q, Dock, menu) both
 * call [request]. [run] then closes every mirror session, which waits for their recorders to write the trailer
 * (bounded by [timeoutMs]), plays the main window's quit animation ([animateOut], bounded by [animateTimeoutMs]), then
 * runs [install] on [installDispatcher] and only then calls [exit]. While it waits,
 * [state] says whether a recording is being finished, so the UI can say so instead of freezing silently.
 *
 * [install] starts a downloaded update's installer, which blocks on `adb kill-server` and ends the process with
 * `System.exit`. [run] is called on the UI thread (the Tao main thread), so [install] must not run there: blocking the
 * event loop, or exiting from inside one of its callbacks, can hang shutdown hooks that need that thread.
 */
class AppShutdown(
    private val controller: MirrorController,
    private val exit: () -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val quitHook: QuitHook = MacQuitHook,
    private val install: () -> Unit = {},
    private val installDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The main window's quit animation; runs on the calling (UI) thread after the sessions close, before [install]. */
    private val animateOut: suspend () -> Unit = {},
    /** [animateOut] is cut off after this long, and a failing one is skipped: the exit never waits on it. */
    private val animateTimeoutMs: Long = DEFAULT_ANIMATE_TIMEOUT_MS,
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

    /** Once [request] was called: closes the sessions (at most [timeoutMs]), installs a pending update and exits. */
    suspend fun run() {
        state.value as? State.ShuttingDown ?: return
        if (withTimeoutOrNull(timeoutMs) { controller.closeAll() } == null) {
            log.log(System.Logger.Level.WARNING, "Sessions still closing after $timeoutMs ms, exiting anyway")
        }
        // Before the quit hook lets go, so a second Cmd+Q during the animation is still caught (and ignored).
        try {
            if (withTimeoutOrNull(animateTimeoutMs) { animateOut() } == null) {
                log.log(System.Logger.Level.WARNING, "Quit animation still running after $animateTimeoutMs ms, exiting anyway")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.log(System.Logger.Level.WARNING, "Quit animation failed, exiting anyway", t)
        }
        quitHook.release()
        // Ends the process when an update is pending; otherwise returns and the app exits normally.
        withContext(installDispatcher) { install() }
        exit()
    }

    /**
     * Routes the macOS Quit command to [onQuit] instead of the default, which ends the process at once and skips the
     * session teardown. No-op where the platform has no quit handler.
     */
    fun installQuitHandler(onQuit: () -> Unit) = quitHook.install(onQuit)

    companion object {
        /** Generous: each session's teardown may wait up to 10 s for its recorder. */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** The longest quit animation is 420 ms; this is only a backstop. */
        const val DEFAULT_ANIMATE_TIMEOUT_MS = 1_000L
        private val log = System.getLogger("scrcpyviewer.AppShutdown")
    }
}

/**
 * Platform seam for the OS Quit command. [install] routes it to [onQuit]; [onQuit] may be called from any thread.
 * [release] lets the next Quit through: called once the app has shut down, right before it exits.
 */
fun interface QuitHook {
    fun install(onQuit: () -> Unit)

    fun release() {}
}

/**
 * The macOS Quit command (Cmd+Q, the app menu, the Dock, logout) on the Tao backend. Tao's app menu sends `terminate:`
 * and its application delegate has no `applicationShouldTerminate:`, so AppKit would end the process at once. This adds
 * that method to the delegate's class: it answers NSTerminateCancel and calls [install]'s `onQuit` until [release].
 * No-op off macOS (Windows and Linux have no app-wide Quit; their windows' close buttons go through `onCloseRequest`).
 */
object MacQuitHook : QuitHook {
    private const val NS_TERMINATE_CANCEL = 0L
    private const val NS_TERMINATE_NOW = 1L

    @Volatile
    private var onQuit: (() -> Unit)? = null

    @Volatile
    private var released = false

    fun interface ShouldTerminate : Callback {
        fun invoke(self: Pointer?, selector: Pointer?, sender: Pointer?): Long
    }

    // Held for the life of the process: AppKit keeps calling it.
    private val shouldTerminate = ShouldTerminate { _, _, _ ->
        val quit = onQuit
        if (released || quit == null) {
            NS_TERMINATE_NOW
        } else {
            runCatching(quit).onFailure { log.log(System.Logger.Level.WARNING, "Quit handler failed", it) }
            NS_TERMINATE_CANCEL
        }
    }

    override fun install(onQuit: () -> Unit) {
        this.onQuit = onQuit
        released = false
        if (!Os.isMac) return
        ObjC.onMain {
            val app = ObjC.msgPtr(ObjC.cls("NSApplication"), "sharedApplication") ?: return@onMain
            val delegate = ObjC.msgPtr(app, "delegate")
            if (delegate == null) {
                log.log(System.Logger.Level.WARNING, "No NSApplication delegate: Quit is not routed to the shutdown")
                return@onMain
            }
            val objc = NativeLibrary.getInstance("objc")
            val cls = objc.getFunction("object_getClass").invokePointer(arrayOf(delegate))
            val selector = ObjC.sel("applicationShouldTerminate:")
            // NSUInteger (id self, SEL _cmd, NSApplication *sender)
            val added = objc.getFunction("class_addMethod").invokeInt(arrayOf(cls, selector, shouldTerminate, "Q@:@")) and 0xFF != 0
            if (!added) {
                // The delegate answers it already: replace its implementation.
                val method = objc.getFunction("class_getInstanceMethod").invokePointer(arrayOf(cls, selector))
                objc.getFunction("method_setImplementation").invokePointer(arrayOf(method, shouldTerminate))
            }
        }
    }

    override fun release() {
        released = true
    }

    private val log = System.getLogger("scrcpyviewer.MacQuitHook")
}
