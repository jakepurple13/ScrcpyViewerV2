package com.programmersbox.scrcpyviewer.telemetry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetrySenderTest {
    private val config = TelemetryConfig(key = "phc_test", host = "https://us.i.posthog.com", channel = "dev")
    private fun queue() = TelemetryQueue(Files.createTempDirectory("ts").resolve("queue.jsonl"))
    private fun ev(name: String, kind: TelemetryKind = TelemetryKind.Usage) =
        QueuedEvent(name, name, kind, "2026-11-17T00:00:00Z", JsonObject(emptyMap()))

    private fun sender(q: TelemetryQueue, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        TelemetrySender(config, q, MockEngine(handler))

    private fun status(code: HttpStatusCode): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond("", code) }

    @Test fun postsBatchToHostWithApiKey() = runBlocking {
        val q = queue(); listOf("a", "b", "c").forEach { q.append(ev(it)) }
        var url = ""; var body = JsonObject(emptyMap())
        val s = sender(q) { req ->
            url = req.url.toString()
            body = Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject
            respond("{}", HttpStatusCode.OK)
        }
        assertEquals(FlushResult.Sent, s.flush())
        assertEquals("https://us.i.posthog.com/batch/", url)
        assertEquals("phc_test", body["api_key"]!!.jsonPrimitive.content)
        val batch = body["batch"]!!.jsonArray
        assertEquals(3, batch.size)
        assertEquals(setOf("event", "properties", "timestamp", "uuid"), batch[0].jsonObject.keys)
        assertEquals(0, q.size())
        assertEquals(FlushResult.Empty, s.flush())
    }

    @Test fun splitsIntoBatchesOf100() = runBlocking {
        val q = queue(); (1..250).forEach { q.append(ev("e$it")) }
        val sizes = mutableListOf<Int>()
        val s = sender(q) { req ->
            sizes += Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject["batch"]!!.jsonArray.size
            respond("{}", HttpStatusCode.OK)
        }
        assertEquals(FlushResult.Sent, s.flush())
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(0, q.size())
    }

    @Test fun serverErrorKeepsQueueAndBacksOff() = runBlocking {
        val q = queue(); q.append(ev("a"))
        val s = sender(q, status(HttpStatusCode.InternalServerError))
        assertEquals(30_000, s.nextDelayMs())
        assertEquals(FlushResult.RetryLater, s.flush())
        assertEquals(1, q.size())
        assertEquals(60_000, s.nextDelayMs())
        s.flush()
        assertEquals(120_000, s.nextDelayMs())
        repeat(10) { s.flush() }
        assertEquals(600_000, s.nextDelayMs())
    }

    @Test fun successResetsBackoff() = runBlocking {
        val q = queue(); q.append(ev("a"))
        var code = HttpStatusCode.InternalServerError
        val s = sender(q) { respond("", code) }
        s.flush()
        code = HttpStatusCode.OK
        s.flush()
        assertEquals(30_000, s.nextDelayMs())
    }

    @Test fun tooManyRequestsIsRetried() = runBlocking {
        val q = queue(); q.append(ev("a"))
        val s = sender(q, status(HttpStatusCode.TooManyRequests))
        assertEquals(FlushResult.RetryLater, s.flush())
        assertEquals(1, q.size())
    }

    @Test fun retryAfterIsAFloor() = runBlocking {
        val q = queue(); q.append(ev("a"))
        val s = TelemetrySender(config, q, MockEngine {
            respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "300"))
        }, clock = { 1_000L })
        s.flush()
        assertEquals(300_000, s.nextDelayMs())
    }

    @Test fun badRequestDropsBatch() = runBlocking {
        val q = queue(); q.append(ev("a")); q.append(ev("b"))
        val s = sender(q, status(HttpStatusCode.BadRequest))
        assertEquals(FlushResult.Dropped, s.flush())
        assertEquals(0, q.size())
        assertEquals(30_000, s.nextDelayMs())
    }

    @Test fun offlineDoesNotThrow() = runBlocking {
        val q = queue(); q.append(ev("a"))
        val s = sender(q) { throw IOException("offline") }
        assertEquals(FlushResult.RetryLater, s.flush())
        assertEquals(1, q.size())
    }

    @Test fun queueStaysCappedWhileOffline() = runBlocking {
        val q = queue()
        val s = sender(q) { throw IOException("offline") }
        (1..1_200).forEach { q.append(ev("e$it")) }
        s.flush()
        assertEquals(1000, q.size())
    }

    @Test fun eventsRemovedByConsentAreNotRequeued() = runBlocking {
        val q = queue(); q.append(ev("u1")); q.append(ev("c1", TelemetryKind.Crash))
        val s = sender(q) {
            q.removeKind(TelemetryKind.Usage)
            respond("", HttpStatusCode.InternalServerError)
        }
        assertEquals(FlushResult.RetryLater, s.flush())
        assertEquals(listOf("c1"), q.peek(10).map { it.uuid })
        assertTrue(q.peek(10).none { it.kind == TelemetryKind.Usage })
    }

    @Test fun sentEventsRemovedByConsentDoNotReappear() = runBlocking {
        val q = queue(); q.append(ev("u1"))
        val s = sender(q) { q.removeKind(TelemetryKind.Usage); respond("{}", HttpStatusCode.OK) }
        s.flush()
        assertEquals(0, q.size())
    }
}
