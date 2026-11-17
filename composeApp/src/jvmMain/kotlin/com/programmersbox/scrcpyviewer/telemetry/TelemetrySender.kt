package com.programmersbox.scrcpyviewer.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class FlushResult { Empty, Sent, RetryLater, Dropped }

/**
 * Posts queued events to PostHog's `/batch/` endpoint in batches of at most 100. The queue lock is never held during
 * the HTTP call: a batch is peeked, sent, and only the uuids that were sent are removed, so events removed meanwhile
 * (for example by a consent change) never come back. Failures never throw; they back off through [nextDelayMs].
 * The API key is never logged.
 */
class TelemetrySender(
    private val config: TelemetryConfig,
    private val queue: TelemetryQueue,
    engine: HttpClientEngine = CIO.create(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    @Volatile private var failures = 0
    @Volatile private var retryAfterUntil = 0L

    /** Sends batches until the queue is empty or a batch fails. */
    suspend fun flush(): FlushResult {
        var sentAny = false
        while (true) {
            val batch = queue.peek(BATCH_SIZE)
            if (batch.isEmpty()) return if (sentAny) FlushResult.Sent else FlushResult.Empty
            val response = try {
                post(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return fail()
            }
            val status = response.status
            when {
                status.value in 200..299 -> {
                    queue.remove(batch.mapTo(mutableSetOf()) { it.uuid })
                    failures = 0
                    retryAfterUntil = 0L
                    sentAny = true
                }

                status == HttpStatusCode.TooManyRequests -> {
                    response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let {
                        retryAfterUntil = clock() + it * 1000
                    }
                    return fail()
                }

                status.value in 400..499 -> {
                    queue.remove(batch.mapTo(mutableSetOf()) { it.uuid })
                    failures = 0
                    return FlushResult.Dropped
                }

                else -> return fail()
            }
        }
    }

    /** 30 s normally; after n consecutive failures min(30 s * 2^n, 10 min), never below a `Retry-After` floor. */
    fun nextDelayMs(): Long {
        val base = if (failures == 0) BASE_DELAY_MS else minOf(BASE_DELAY_MS shl failures.coerceAtMost(10), MAX_DELAY_MS)
        val floor = (retryAfterUntil - clock()).coerceAtLeast(0)
        return maxOf(base, minOf(floor, MAX_DELAY_MS))
    }

    fun close() = client.close()

    private fun fail(): FlushResult {
        failures++
        return FlushResult.RetryLater
    }

    private suspend fun post(batch: List<QueuedEvent>): HttpResponse =
        client.post("${config.host.trimEnd('/')}/batch/") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("api_key", JsonPrimitive(config.key))
                put("batch", buildJsonArray {
                    batch.forEach { e ->
                        add(buildJsonObject {
                            put("event", JsonPrimitive(e.event))
                            put("properties", e.properties)
                            put("timestamp", JsonPrimitive(e.timestamp))
                            put("uuid", JsonPrimitive(e.uuid))
                        })
                    }
                })
            })
        }

    private companion object {
        const val BATCH_SIZE = 100
        const val BASE_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 600_000L
    }
}
