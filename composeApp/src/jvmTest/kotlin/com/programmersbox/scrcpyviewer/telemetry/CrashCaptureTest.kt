package com.programmersbox.scrcpyviewer.telemetry

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashCaptureTest {
    private val original: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    /** What happened, in order: "telemetry" when the fake got it, "previous" when the earlier handler ran. */
    private val calls = CopyOnWriteArrayList<String>()
    private val previousGot = CopyOnWriteArrayList<Throwable>()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            calls += "previous"
            previousGot += t
        }
    }

    @AfterTest
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun recording() = object : Telemetry by FakeTelemetry() {
        val got = CopyOnWriteArrayList<Throwable>()
        override fun captureExceptionBlocking(t: Throwable) {
            calls += "telemetry"
            got += t
        }
    }

    private fun crashOnNewThread(e: Throwable) = thread(name = "crasher") { throw e }.join()

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $message" }
            Thread.sleep(10)
        }
    }

    @Test fun uncaughtExceptionIsQueuedThenPreviousHandlerRuns() {
        val fake = recording()
        CrashCapture.install { fake }
        val e = IllegalStateException("boom")
        crashOnNewThread(e)
        assertEquals(listOf<Throwable>(e), fake.got)
        assertEquals(listOf<Throwable>(e), previousGot)
        assertEquals(listOf("telemetry", "previous"), calls)
    }

    @Test fun handlerSurvivesTelemetryThrowing() {
        CrashCapture.install { error("Koin is not up") }
        val e = IllegalStateException("boom")
        crashOnNewThread(e)
        assertEquals(listOf<Throwable>(e), previousGot)
    }

    @Test fun noTelemetryYetStillRunsPreviousHandler() {
        CrashCapture.install { null }
        val e = IllegalStateException("boom")
        crashOnNewThread(e)
        assertEquals(listOf<Throwable>(e), previousGot)
    }

    @Test fun coroutineFailureIsReportedOnceThenReachesTheThreadHandler() {
        val fake = FakeTelemetry()
        CrashCapture.install { fake }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashCapture.coroutineExceptionHandler)
        val e = IllegalStateException("boom")
        try {
            scope.launch { throw e }
            eventually("previous handler ran") { previousGot.isNotEmpty() }
        } finally {
            scope.cancel()
        }
        assertEquals(listOf<Pair<Throwable, Boolean>>(e to false), fake.exceptions)
        assertTrue(fake.blocking.isEmpty(), "reported twice: ${fake.blocking}")
        assertEquals(listOf<Throwable>(e), previousGot)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun windowExceptionIsReportedThenDefaultHandlingRuns() {
        val fake = FakeTelemetry()
        CrashCapture.install { fake }
        val e = IllegalStateException("boom")
        val handler = CrashCapture.reportingWindowHandler(WindowExceptionHandler { throw it })
        val thrown = assertFailsWith<IllegalStateException> { handler.onException(e) }
        assertSame(e, thrown)
        assertEquals(listOf<Throwable>(e), fake.blocking)
        // The rethrown exception reaching the uncaught handler is not sent again.
        crashOnNewThread(e)
        assertEquals(listOf<Throwable>(e), fake.blocking)
        assertEquals(listOf<Throwable>(e), previousGot)
    }
}
