package com.programmersbox.scrcpyviewer.scrcpy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test

class ClosingOnCancelTest {
    /**
     * ServerSocket.accept ignores Thread.interrupt, so runInterruptible alone never returns on cancel. On a single
     * thread no other coroutine can run to close the socket either: the close has to happen on the cancelling thread.
     */
    @Test
    fun `cancel wakes a blocked accept with no free thread`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { single ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
                val accepting = CompletableDeferred<Unit>()
                val job = launch(single) {
                    closingOnCancel(onCancel = { ss.close() }) {
                        accepting.complete(Unit)
                        runInterruptible { ss.accept() }
                    }
                }
                accepting.await()
                delay(100) // let it block in accept
                withTimeout(5_000) { job.cancelAndJoin() }
            }
        }
    }
}
