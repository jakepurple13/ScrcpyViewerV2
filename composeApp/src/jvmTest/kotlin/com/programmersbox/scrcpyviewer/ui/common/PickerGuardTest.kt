package com.programmersbox.scrcpyviewer.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickerGuardTest {
    @Test
    fun aClickWhileAPickIsInProgressIsIgnored() = runBlocking<Unit> {
        val guard = PickerGuard()
        val picks = AtomicInteger()
        val close = CompletableDeferred<Unit>()
        val first = guard.launch(this) {
            picks.incrementAndGet()
            close.await()
        }
        assertNotNull(first)
        assertTrue(guard.inProgress)
        assertNull(guard.launch(this) { picks.incrementAndGet() })
        close.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertEquals(1, picks.get())
        assertFalse(guard.inProgress)
    }

    @Test
    fun aNewPickCanStartOnceTheLastOneEnded() = runBlocking<Unit> {
        val guard = PickerGuard()
        val picks = AtomicInteger()
        withTimeout(5_000) { guard.launch(this) { picks.incrementAndGet() }!!.join() }
        withTimeout(5_000) { guard.launch(this) { picks.incrementAndGet() }!!.join() }
        assertEquals(2, picks.get())
    }

    @Test
    fun aFailedOrCancelledPickFreesTheGuard() = runBlocking<Unit> {
        val guard = PickerGuard()
        val failing = CoroutineScope(Job())
        withTimeout(5_000) { guard.launch(failing) { error("picker failed") }!!.join() }
        assertFalse(guard.inProgress)
        // A scope already cancelled (the screen left) never runs the pick, and must not leave the guard stuck.
        val gone = CoroutineScope(Job()).apply { cancel() }
        withTimeout(5_000) { guard.launch(gone) { error("never runs") }!!.join() }
        assertFalse(guard.inProgress)
    }
}
