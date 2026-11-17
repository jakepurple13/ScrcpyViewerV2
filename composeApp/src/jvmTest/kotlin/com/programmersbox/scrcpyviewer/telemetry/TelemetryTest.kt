package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.assumePosix
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelemetryTest {
    private val config = TelemetryConfig(key = "phc_test", host = "https://us.i.posthog.com", channel = "dev")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir: Path = Files.createTempDirectory("telemetry-test")
    private val settings = AppSettings(dataDir.resolve("settings.json"))
    private val queue = TelemetryQueue(dataDir.resolve("telemetry").resolve("queue.jsonl"))

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** A fake sender that never reaches the network; these tests never flush anyway. */
    private fun telemetry() = PostHogTelemetry(config, dataDir, settings, scope) {
        TelemetrySender(config, it, MockEngine { respond("", HttpStatusCode.OK) })
    }

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $message" }
            Thread.sleep(10)
        }
    }

    @Test fun usageBlockedByDefault() {
        val t = telemetry()
        t.track(TelemetryEvent.AppOpened(firstLaunch = true, themeStyle = "Material"))
        Thread.sleep(100)
        assertEquals(0, queue.size())
        val recent = t.recent.value.single()
        assertEquals("app_opened", recent.name)
        assertFalse(recent.sent)
    }

    @Test fun crashAllowedByDefault() {
        val t = telemetry()
        t.captureException(TestOwnException("boom"), handled = true)
        eventually("crash queued") { queue.size() == 1 }
        val e = queue.peek(1).single()
        assertEquals("\$exception", e.event)
        assertEquals(TelemetryKind.Crash, e.kind)
        assertEquals("boom", e.properties.getValue("\$exception_message").jsonPrimitive.content)
        assertTrue(t.recent.value.single().sent)
    }

    @Test fun commonPropertiesAdded() {
        settings.update { it.copy(usageStats = true) }
        val t = telemetry()
        t.track(TelemetryEvent.AppOpened(firstLaunch = true, themeStyle = "Material"))
        eventually("usage queued") { queue.size() == 1 }
        val e = queue.peek(1).single()
        assertEquals("app_opened", e.event)
        assertEquals(TelemetryKind.Usage, e.kind)
        val p = e.properties
        fun str(key: String) = p.getValue(key).jsonPrimitive.content
        assertEquals(t.installId(), str("distinct_id"))
        assertEquals(APP_VERSION, str("app_version"))
        assertTrue(str("os") in setOf("mac", "windows", "linux"), str("os"))
        assertEquals(System.getProperty("os.version"), str("os_version"))
        assertEquals(System.getProperty("os.arch"), str("arch"))
        assertEquals("dev", str("channel"))
        UUID.fromString(str("session_id"))
        assertEquals("scrcpy-vwr", str("\$lib"))
        assertFalse(p.getValue("\$process_person_profile").jsonPrimitive.boolean)
        assertTrue(p.getValue("first_launch").jsonPrimitive.boolean)
        assertEquals("material", str("theme_style"))
        assertTrue(t.recent.value.single().sent)
    }

    @Test fun resettingTheIdAlsoStartsANewSession() {
        val t = telemetry()
        fun ids(i: Int) = queue.peek(2)[i].properties.let {
            it.getValue("distinct_id").jsonPrimitive.content to it.getValue("session_id").jsonPrimitive.content
        }
        t.captureException(TestOwnException("before"), handled = true)
        eventually("first crash queued") { queue.size() == 1 }
        t.resetInstallId()
        t.captureException(TestOwnException("after"), handled = true)
        eventually("second crash queued") { queue.size() == 2 }
        val (idBefore, sessionBefore) = ids(0)
        val (idAfter, sessionAfter) = ids(1)
        assertTrue(idBefore != idAfter, "install ID rotated")
        // A session spanning the reset would link the old ID to the new one.
        assertTrue(sessionBefore != sessionAfter, "session ID rotated")
        UUID.fromString(sessionAfter)
    }

    @Test fun switchingUsageOffDeletesQueuedUsage() {
        settings.update { it.copy(usageStats = true) }
        val t = telemetry()
        t.track(TelemetryEvent.AdbRestarted(mirrorsOpen = 1))
        t.captureException(TestOwnException("boom"), handled = true)
        eventually("both queued") { queue.size() == 2 }
        settings.update { it.copy(usageStats = false) }
        eventually("usage removed") { queue.size() == 1 }
        assertEquals(TelemetryKind.Crash, queue.peek(10).single().kind)
    }

    @Test fun switchingCrashOffDeletesQueuedCrash() {
        settings.update { it.copy(usageStats = true) }
        val t = telemetry()
        t.track(TelemetryEvent.AdbRestarted(mirrorsOpen = 1))
        t.captureException(TestOwnException("boom"), handled = true)
        eventually("both queued") { queue.size() == 2 }
        settings.update { it.copy(crashReports = false) }
        eventually("crash removed") { queue.size() == 1 }
        assertEquals(TelemetryKind.Usage, queue.peek(10).single().kind)
    }

    @Test fun trackNeverThrowsOnUnwritableDir() {
        assumePosix()
        settings.update { it.copy(usageStats = true) }
        val dir = Files.createDirectories(dataDir.resolve("telemetry"))
        dir.toFile().setWritable(false)
        try {
            val t = telemetry()
            t.track(TelemetryEvent.AdbRestarted(mirrorsOpen = 1))
            t.captureException(TestOwnException("boom"), handled = true)
            t.captureExceptionBlocking(TestOwnException("fatal"))
            t.installId()
            t.resetInstallId()
            Thread.sleep(200)
            assertEquals(0, queue.size())
        } finally {
            dir.toFile().setWritable(true)
        }
    }

    @Test fun disabledConfigWritesNothing() {
        val dir = Files.createTempDirectory("telemetry-koin")
        withFakeAppKoin(dir) { koin ->
            val t = koin.get<Telemetry>()
            assertSame(Telemetry.Noop, t)
            t.track(TelemetryEvent.AdbRestarted(mirrorsOpen = 1))
            t.captureExceptionBlocking(TestOwnException("fatal"))
            t.start()
        }
        assertFalse(Files.exists(dir.resolve("telemetry")))
    }

    @Test fun flushRunsSenderWorkOffTheCallingThread() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }
        try {
            var senderThread: String? = null
            val t = PostHogTelemetry(config, dataDir, settings, scope) {
                senderThread = Thread.currentThread().name
                TelemetrySender(config, it, MockEngine { respond("", HttpStatusCode.OK) })
            }
            runBlocking(executor.asCoroutineDispatcher()) { t.flush() }
            // Coroutine debug mode appends " @coroutine#n" to the thread name.
            assertTrue(senderThread?.startsWith("fake-ui") == false, "sender built on $senderThread")
        } finally {
            executor.shutdown()
        }
    }

    @Test fun flushSendsAnEventTrackedJustBefore() = runBlocking {
        settings.update { it.copy(usageStats = true) }
        val bodies = java.util.Collections.synchronizedList(ArrayList<String>())
        val t = PostHogTelemetry(config, dataDir, settings, scope) {
            TelemetrySender(config, it, MockEngine { req ->
                bodies += String(req.body.toByteArray())
                respond("", HttpStatusCode.OK)
            })
        }
        t.flush() // builds the client now, so the flush below is fast enough to beat an unawaited write
        t.track(TelemetryEvent.AppClosed(sessionMinutes = 3, mirrorWindowsOpen = 0))
        t.flush()
        assertTrue(bodies.any { "app_closed" in it }, "sent: $bodies")
        assertEquals(0, queue.size())
    }

    @Test fun recentKeepsLast50() {
        val t = telemetry()
        repeat(59) { t.track(TelemetryEvent.AdbRestarted(mirrorsOpen = it)) }
        t.track(TelemetryEvent.AppOpened(firstLaunch = false, themeStyle = "Material"))
        val recent = t.recent.value
        assertEquals(50, recent.size)
        assertEquals("app_opened", recent.first().name, "newest first")
    }
}
