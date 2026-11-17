package com.programmersbox.scrcpyviewer.telemetry

import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.appendText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryQueueTest {
    private fun tmp(): Path = Files.createTempDirectory("tq").resolve("queue.jsonl")
    private fun ev(name: String, kind: TelemetryKind = TelemetryKind.Usage) =
        QueuedEvent(uuid = name, event = name, kind = kind, timestamp = "2026-11-17T00:00:00Z", properties = JsonObject(emptyMap()))

    @Test fun survivesRestart() {
        val f = tmp(); TelemetryQueue(f).append(ev("a"))
        assertEquals(listOf("a"), TelemetryQueue(f).peek(10).map { it.event })
    }

    @Test fun dropsOldestOverCap() {
        val q = TelemetryQueue(tmp(), maxEvents = 3); (1..5).forEach { q.append(ev("e$it")) }
        assertEquals(listOf("e3", "e4", "e5"), q.peek(10).map { it.event })
    }

    @Test fun dropsOldestOverByteCap() {
        val f = tmp(); val q = TelemetryQueue(f, maxBytes = 250)
        (1..5).forEach { q.append(ev("e$it")) }
        assertTrue(Files.size(f) <= 250)
        assertEquals("e5", q.peek(10).last().event)
        assertTrue(q.size() < 5)
    }

    @Test fun skipsTruncatedLastLine() {
        val f = tmp(); TelemetryQueue(f).append(ev("ok")); f.appendText("{\"uuid\":\"x\",\"ev")
        assertEquals(listOf("ok"), TelemetryQueue(f).peek(10).map { it.event })
    }

    @Test fun appendAfterTruncatedLineKeepsNewEvent() {
        val f = tmp(); TelemetryQueue(f).append(ev("ok")); f.appendText("{\"uuid\":\"x\",\"ev")
        val q = TelemetryQueue(f); q.append(ev("next"))
        assertEquals(listOf("ok", "next"), q.peek(10).map { it.event })
    }

    @Test fun removeKindDeletesOnlyThatKind() {
        val q = TelemetryQueue(tmp()); q.append(ev("u", TelemetryKind.Usage)); q.append(ev("c", TelemetryKind.Crash))
        q.removeKind(TelemetryKind.Usage)
        assertEquals(listOf("c"), q.peek(10).map { it.event })
    }

    @Test fun removeDeletesByUuid() {
        val q = TelemetryQueue(tmp()); listOf("a", "b", "c").forEach { q.append(ev(it)) }
        q.remove(setOf("a", "c"))
        assertEquals(listOf("b"), q.peek(10).map { it.event })
    }

    @Test fun concurrentAppendsLoseNothing() {
        val q = TelemetryQueue(tmp())
        (1..8).map { t -> thread { repeat(50) { q.append(ev("t$t-$it")) } } }.forEach { it.join() }
        assertEquals(400, q.size())
    }
}
