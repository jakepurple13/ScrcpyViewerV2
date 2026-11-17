package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.state.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCrashScannerTest {
    private val dir: Path = Files.createTempDirectory("native-crash-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val sample: String =
        checkNotNull(javaClass.getResource("/telemetry/hs_err_sample.log")).readText()

    /** The same crash in another app: nothing in it mentions our package. */
    private val foreign: String = sample
        .replace("com.programmersbox.scrcpyviewer", "org.example.other")
        .replace("com/programmersbox/scrcpyviewer", "org/example/other")

    private fun write(pid: Long, text: String): Path = dir.resolve("hs_err_pid$pid.log").also { it.writeText(text) }

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $message" }
            Thread.sleep(10)
        }
    }

    @Test fun scannerFindsOurCrashOnly() {
        val ours = write(4242, sample)
        write(5151, foreign)
        val crashes = NativeCrashScanner(listOf(dir), currentPid = 1).scan()
        val c = crashes.single()
        assertEquals(ours, c.file)
        assertEquals("SIGSEGV", c.signal)
        assertEquals(20, c.frames.size)
        assertEquals("ff_h264_decode_mb_cabac", c.frames[0])
        // A frame with no symbol keeps only the library's file name.
        assertEquals("libavcodec.61.dylib", c.frames[1])
        assertEquals(
            "com.programmersbox.scrcpyviewer.media.VideoDecoder.onPacket(Lcom/programmersbox/scrcpyviewer/scrcpy/MediaPacket;)V",
            c.frames[12],
        )
        assertEquals("kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Ljava/lang/Object;)V", c.frames[15])
        assertTrue(c.frames.none { "/" in it.substringBefore('(') }, "frames: ${c.frames}")
    }

    @Test fun scannerSkipsCurrentPid() {
        write(4242, sample)
        assertTrue(NativeCrashScanner(listOf(dir), currentPid = 4242).scan().isEmpty())
    }

    @Test fun scannerLooksInEachDirOnce() {
        write(4242, sample)
        val crashes = NativeCrashScanner(listOf(dir, dir.resolve(".").normalize()), currentPid = 1).scan()
        assertEquals(1, crashes.size)
    }

    @Test fun nativeCrashFileDeletedEvenWhenCrashReportsOff() {
        val dataDir = Files.createTempDirectory("native-crash-data")
        val settings = AppSettings(dataDir.resolve("settings.json"))
        settings.update { it.copy(crashReports = false) }
        val config = TelemetryConfig(key = "phc_test", host = "https://us.i.posthog.com", channel = "dev")
        val telemetry = PostHogTelemetry(config, dataDir, settings, scope) { error("never sends") }
        val file = write(4242, sample)
        val foreignFile = write(5151, foreign)
        NativeCrashScanner(listOf(dir), currentPid = 1).reportAndDelete(telemetry)
        assertFalse(file.exists())
        assertTrue(foreignFile.exists(), "another app's crash file is left alone")
        Thread.sleep(100)
        assertEquals(0, TelemetryQueue(dataDir.resolve("telemetry").resolve("queue.jsonl")).size())
        assertFalse(telemetry.recent.value.single().sent)
    }

    @Test fun nativeCrashIsQueuedWhenCrashReportsOn() {
        val dataDir = Files.createTempDirectory("native-crash-data")
        val settings = AppSettings(dataDir.resolve("settings.json"))
        val config = TelemetryConfig(key = "phc_test", host = "https://us.i.posthog.com", channel = "dev")
        val telemetry = PostHogTelemetry(config, dataDir, settings, scope) { error("never sends") }
        val file = write(4242, sample)
        NativeCrashScanner(listOf(dir), currentPid = 1).reportAndDelete(telemetry)
        assertFalse(file.exists())
        val queue = TelemetryQueue(dataDir.resolve("telemetry").resolve("queue.jsonl"))
        eventually("crash queued") { queue.size() == 1 }
        val e = queue.peek(1).single()
        assertEquals("\$exception", e.event)
        assertEquals(TelemetryKind.Crash, e.kind)
        assertEquals("native_crash", e.properties.getValue("\$exception_type").jsonPrimitive.content)
        assertEquals("SIGSEGV", e.properties.getValue("\$exception_message").jsonPrimitive.content)
    }

    @Test fun nativeCrashPropsCarryNoPaths() {
        write(4242, sample)
        val crash = NativeCrashScanner(listOf(dir), currentPid = 1).scan().single()
        val scrubber = Scrubber(home = "/Users/x")
        val props = nativeCrashProperties(crash, scrubber)
        val text = props.toString()
        assertFalse("/Users/x" in text, text)
        assertFalse(dir.toString() in text, text)
        assertFalse("hs_err" in text, text)
        assertFalse("R5CX12GDWPZ" in text, text)

        assertEquals("native_crash", props.getValue("\$exception_type").jsonPrimitive.content)
        assertEquals("SIGSEGV", props.getValue("\$exception_message").jsonPrimitive.content)
        val entry = props.getValue("\$exception_list").jsonArray.single().jsonObject
        assertEquals("native_crash", entry.getValue("type").jsonPrimitive.content)
        assertEquals("SIGSEGV", entry.getValue("value").jsonPrimitive.content)
        val mechanism = entry.getValue("mechanism").jsonObject
        assertFalse(mechanism.getValue("handled").jsonPrimitive.boolean)
        assertEquals("native", mechanism.getValue("type").jsonPrimitive.content)
        val frames = entry.getValue("stacktrace").jsonObject.getValue("frames").jsonArray.map { it.jsonObject }
        assertEquals(20, frames.size)
        assertTrue(frames.all { it.keys == setOf("function", "in_app") }, "frames: $frames")
        // Oldest call first, as PostHog expects: the crashing frame is last.
        assertEquals("ff_h264_decode_mb_cabac", frames.last().getValue("function").jsonPrimitive.content)
        val inApp = frames.filter { it.getValue("in_app").jsonPrimitive.boolean }.map { it.getValue("function").jsonPrimitive.content }
        assertEquals(4, inApp.size, "in_app: $inApp")
        assertTrue(inApp.all { "com.programmersbox.scrcpyviewer" in it })
    }

    @Test fun nativeCrashFunctionsAreScrubbed() {
        val scrubber = Scrubber(home = "/Users/x").also { it.rememberSerial("R5CX12GDWPZ") }
        val crash = NativeCrash(dir.resolve("hs_err_pid1.log"), "SIGSEGV", listOf("/Users/x/lib/thing", "decode_R5CX12GDWPZ"))
        val text = nativeCrashProperties(crash, scrubber).toString()
        assertTrue("~/lib/thing" in text, text)
        assertTrue("decode_<device>" in text, text)
        assertFalse("/Users/x" in text || "R5CX12GDWPZ" in text, text)
    }
}
