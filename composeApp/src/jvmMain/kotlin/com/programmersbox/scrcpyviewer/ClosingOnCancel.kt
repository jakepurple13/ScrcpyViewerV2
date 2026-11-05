package com.programmersbox.scrcpyviewer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [block] and calls [onCancel] on the cancelling thread the moment the caller is cancelled. Blocking socket
 * calls (ServerSocket.accept, Socket.connect, InputStream.read) ignore Thread.interrupt, so runInterruptible alone
 * never returns from them; closing the socket does. A launched coroutine that closes it may not get a thread before
 * the cancel (it is then never run), so the close is registered up front and needs no thread of its own.
 */
internal suspend fun <T> closingOnCancel(onCancel: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Nothing> { cont -> cont.invokeOnCancellation { if (!finished.get()) onCancel() } }
    }
    try {
        block()
    } catch (e: Throwable) {
        // The socket error caused by our own close: report the cancellation, not the error.
        ensureActive()
        throw e
    } finally {
        finished.set(true)
        guard.cancel()
    }
}
