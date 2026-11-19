package com.programmersbox.scrcpyviewer.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskbarUpdateIndicatorTest {
    private class FakeSink : TaskbarSink {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun attention() { calls += "attention" }
        override fun progress(fraction: Double) { calls += "progress:$fraction" }
        override fun indeterminate() { calls += "indeterminate" }
        override fun error(fraction: Double) { calls += "error:$fraction" }
        override fun hide() { calls += "hide" }
    }

    private val sink = FakeSink()
    private val ind = TaskbarUpdateIndicator(sink)
    private val v160 = Release("1.6.0", "u")
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun `an announced release asks for attention once`() {
        ind.onAnnounced(v160, popupDismissed = false)
        ind.onAnnounced(v160, popupDismissed = false)
        assertEquals(listOf("attention"), sink.calls)
    }

    @Test
    fun `nothing while the popup is dismissed or nothing is announced`() {
        ind.onAnnounced(null, popupDismissed = false)
        ind.onAnnounced(v160, popupDismissed = true)
        assertEquals(emptyList(), sink.calls)
    }

    @Test
    fun `a newer release asks again`() {
        ind.onAnnounced(v160, popupDismissed = false)
        ind.onAnnounced(v160.copy(version = "1.7.0"), popupDismissed = false)
        assertEquals(listOf("attention", "attention"), sink.calls)
    }

    @Test
    fun `download progress is sent per whole percent`() {
        listOf(0L, 5L, 10L, 15L, 40L).forEach { ind.onState(UpdateState.Downloading(v160, it, 1000)) }
        assertEquals(listOf("progress:0.0", "progress:0.01", "progress:0.04"), sink.calls)
    }

    @Test
    fun `unknown size shows indeterminate once`() {
        ind.onState(UpdateState.Downloading(v160, 10, 0))
        ind.onState(UpdateState.Downloading(v160, 20, -1))
        assertEquals(listOf("indeterminate"), sink.calls)
    }

    @Test
    fun `a failed download turns the bar red at its last fraction`() {
        ind.onState(UpdateState.Downloading(v160, 62, 100))
        ind.onState(UpdateState.Failed("boom", v160))
        assertEquals(listOf("progress:0.62", "error:0.62"), sink.calls)
    }

    @Test
    fun `a failed check hides`() {
        ind.onState(UpdateState.Checking)
        ind.onState(UpdateState.Failed("offline", null))
        assertEquals(listOf("hide", "hide"), sink.calls)
    }

    @Test
    fun `ready, cancel and idle hide the bar but installing leaves it`() {
        ind.onState(UpdateState.Downloading(v160, 50, 100))
        ind.onState(UpdateState.Ready(v160))
        ind.onState(UpdateState.Available(v160))
        ind.onState(UpdateState.Installing(v160))
        assertEquals(listOf("progress:0.5", "hide", "hide"), sink.calls)
    }

    @Test
    fun `a retry after failure starts the bar again`() {
        ind.onState(UpdateState.Downloading(v160, 50, 100))
        ind.onState(UpdateState.Failed("x", v160))
        ind.onState(UpdateState.Downloading(v160, 50, 100))
        assertEquals(listOf("progress:0.5", "error:0.5", "progress:0.5"), sink.calls)
    }

    @Test
    fun `run alerts for a release announced before it started`() = runBlocking {
        val manager = UpdateManager(
            backend = FakeUpdateBackend { FakeUpdateBackend.available("1.6.0") },
            scope = scope,
            currentVersion = "1.5.0",
            bundledAdbInUse = { null },
            macNotInApplications = false,
            repo = "jakepurple13/ScrcpyViewerV2",
            notesTimeoutMs = 200,
        )
        manager.check(userInitiated = false)
        withTimeout(5_000) { manager.announced.first { it != null } }
        val job = launch { ind.run(manager) } // one thread, like the UI dispatcher
        withTimeout(5_000) { while (sink.calls.isEmpty() || "attention" !in sink.calls) delay(10) }
        job.cancel()
        assertEquals(1, sink.calls.count { it == "attention" })
    }
}
