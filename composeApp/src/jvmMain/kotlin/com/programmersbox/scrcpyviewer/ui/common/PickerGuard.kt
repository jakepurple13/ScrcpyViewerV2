package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One file picker at a time on a screen. Off macOS the picker leaves the UI live ([onPickerThread] runs it in the
 * background, the Win32 dialog has no owner window, zenity / kdialog is a separate process), so a second click would
 * open a second picker and both results would be applied, an APK installed twice for example. [launch] ignores a click
 * while a pick is in progress.
 */
class PickerGuard {
    private val busy = AtomicBoolean(false)

    /** A pick launched here has not finished yet. */
    val inProgress: Boolean get() = busy.get()

    /** Launches [pick] in [scope], or returns null and does nothing while the last pick is still running. */
    fun launch(scope: CoroutineScope, pick: suspend CoroutineScope.() -> Unit): Job? {
        if (!busy.compareAndSet(false, true)) return null
        // On completion, not in a finally: a job cancelled before it starts never runs its body.
        return scope.launch(block = pick).also { job -> job.invokeOnCompletion { busy.set(false) } }
    }
}

/** A [PickerGuard] for the calling screen. */
@Composable
fun rememberPickerGuard(): PickerGuard = remember { PickerGuard() }
