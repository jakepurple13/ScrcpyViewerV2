package com.programmersbox.scrcpyviewer.telemetry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.DefaultWindowExceptionHandlerFactory
import dev.nucleusframework.window.tao.LocalWindowExceptionHandlerFactory
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.WindowExceptionHandlerFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.Collections
import java.util.WeakHashMap

/**
 * Reports crashes: uncaught exceptions on any thread, failures in the app's own coroutine scopes
 * ([coroutineExceptionHandler]) and exceptions inside Compose windows ([ReportWindowCrashes]). Each is sent
 * once, even when it then travels on to another of these handlers, and the usual handling (the stack trace on stderr,
 * Nucleus' fatal path for a window) still runs afterwards. Reporting never throws.
 */
object CrashCapture {
    @Volatile
    private var telemetry: () -> Telemetry? = { null }

    /** Exceptions already sent; weak, so a reported exception can still be collected. */
    private val reported: MutableSet<Throwable> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * Installs the default uncaught exception handler; call it first thing in `main()`. [telemetry] is null until
     * Koin has built it, and crashes before that are only printed. The handler writes the exception to the queue
     * before returning, then runs the handler that was installed before (or prints it, as the JVM would).
     */
    fun install(telemetry: () -> Telemetry?) {
        this.telemetry = telemetry
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            report(t) { it.captureExceptionBlocking(t) }
            if (previous != null) {
                previous.uncaughtException(thread, t)
            } else {
                // What ThreadGroup.uncaughtException does when no default handler is set.
                System.err.print("Exception in thread \"${thread.name}\" ")
                t.printStackTrace(System.err)
            }
        }
    }

    /**
     * For the app's own coroutine scopes ([com.programmersbox.scrcpyviewer.di.AppScope]): reports the failure, then
     * hands it to the thread's uncaught exception handler, as coroutines do when a scope has no handler.
     */
    val coroutineExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        report(t) { it.captureException(t, handled = false) }
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, t)
    }

    /** Reports, then defers to Nucleus' default handler, which rethrows into its fatal path. */
    @OptIn(ExperimentalComposeUiApi::class)
    internal val windowExceptionHandlerFactory: WindowExceptionHandlerFactory = object : WindowExceptionHandlerFactory {
        override fun exceptionHandler(window: TaoWindow): WindowExceptionHandler =
            reportingWindowHandler(DefaultWindowExceptionHandlerFactory.exceptionHandler(window))
    }

    /** Reports an exception from a window's Compose scene, then hands it to [delegate]. */
    @OptIn(ExperimentalComposeUiApi::class)
    internal fun reportingWindowHandler(delegate: WindowExceptionHandler): WindowExceptionHandler =
        WindowExceptionHandler { t ->
            report(t) { it.captureExceptionBlocking(t) }
            delegate.onException(t)
        }

    private inline fun report(t: Throwable, send: (Telemetry) -> Unit) {
        try {
            if (!reported.add(t)) return
            telemetry()?.let(send)
        } catch (_: Throwable) {
            // Reporting must never replace the crash being handled.
        }
    }
}

/**
 * Windows opened in [content] report the exceptions of their Compose scene through [CrashCapture]. Nucleus' Tao
 * `DecoratedWindow` (behind `MaterialDecoratedWindow` and Nucleus' `DecoratedWindow`) reads
 * `LocalWindowExceptionHandlerFactory` where it is opened, so this goes around the window call, not inside it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReportWindowCrashes(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides CrashCapture.windowExceptionHandlerFactory, content = content)
}
