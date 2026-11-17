package com.programmersbox.scrcpyviewer.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class ExceptionPayloadTest {
    private fun JsonObject.list(i: Int): JsonObject = getValue("\$exception_list").jsonArray[i].jsonObject
    private fun JsonObject.str(k: String): String = getValue(k).jsonPrimitive.content
    private fun JsonObject.types(): List<String> =
        (getValue("\$exception_list") as JsonArray).map { it.jsonObject.str("type") }
    private fun JsonObject.frames(i: Int) =
        list(i).getValue("stacktrace").jsonObject.getValue("frames").jsonArray.map { it.jsonObject }

    @Test fun foreignExceptionMessageIsDropped() {
        val p = exceptionProperties(IllegalStateException("secret /sdcard/x com.foo.app"), handled = true, Scrubber("/h"))
        assertEquals("", p.list(0).str("value"))
        assertEquals("", p.str("\$exception_message"))
    }

    @Test fun ownExceptionMessageIsKept() {
        val p = exceptionProperties(TestOwnException("decoder stalled"), handled = false, Scrubber("/h"))
        assertEquals("decoder stalled", p.list(0).str("value"))
        assertEquals("decoder stalled", p.str("\$exception_message"))
        assertEquals("com.programmersbox.scrcpyviewer.telemetry.TestOwnException", p.str("\$exception_type"))
    }

    @Test fun ownMessageIsScrubbed() {
        val scrubber = Scrubber("/h").also { it.rememberSerial("R5CX12GDWPZ") }
        val p = exceptionProperties(TestOwnException("dev R5CX12GDWPZ at 10.0.0.5"), true, scrubber)
        assertEquals("dev <device> at <ip>", p.list(0).str("value"))
    }

    @Test fun causeChainIsListed() {
        val p = exceptionProperties(RuntimeException(IOException()), true, Scrubber("/h"))
        assertEquals(listOf("java.lang.RuntimeException", "java.io.IOException"), p.types())
    }

    @Test fun mechanismCarriesHandledFlag() {
        val m = exceptionProperties(RuntimeException(), handled = false, Scrubber("/h"))
            .list(0).getValue("mechanism").jsonObject
        assertEquals(mapOf("handled" to false, "synthetic" to false), m.mapValues { it.value.jsonPrimitive.boolean })
    }

    @Test fun framesMatchPostHogCustomFormat() {
        val t = RuntimeException("x")
        t.stackTrace = arrayOf(
            StackTraceElement("com.programmersbox.scrcpyviewer.Foo", "bar", "Foo.kt", 12),
            StackTraceElement("jdk.internal.Native", "call", null, -2),
        )
        val entry = exceptionProperties(t, true, Scrubber("/h")).list(0)
        assertEquals("raw", entry.getValue("stacktrace").jsonObject.str("type"))
        val frames = exceptionProperties(t, true, Scrubber("/h")).frames(0)
        assertEquals(
            JsonObject(
                mapOf(
                    "platform" to JsonPrimitive("custom"),
                    "lang" to JsonPrimitive("java"),
                    "function" to JsonPrimitive("call"),
                    "module" to JsonPrimitive("jdk.internal.Native"),
                    "resolved" to JsonPrimitive(true),
                    "in_app" to JsonPrimitive(false),
                ),
            ),
            frames[0],
        )
        assertEquals(
            JsonObject(
                mapOf(
                    "platform" to JsonPrimitive("custom"),
                    "lang" to JsonPrimitive("java"),
                    "function" to JsonPrimitive("bar"),
                    "module" to JsonPrimitive("com.programmersbox.scrcpyviewer.Foo"),
                    "filename" to JsonPrimitive("Foo.kt"),
                    "lineno" to JsonPrimitive(12),
                    "resolved" to JsonPrimitive(true),
                    "in_app" to JsonPrimitive(true),
                ),
            ),
            frames[1],
        )
    }

    @Test fun inAppFlagFollowsPackage() {
        val t = RuntimeException("x")
        t.stackTrace = arrayOf(
            StackTraceElement("com.programmersbox.scrcpyviewer.Foo", "bar", "Foo.kt", 12),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1583),
        )
        val frames = exceptionProperties(t, true, Scrubber("/h")).frames(0)
        // Oldest call first: the java.* frame, then ours.
        assertEquals(listOf("java.lang.Thread", "com.programmersbox.scrcpyviewer.Foo"), frames.map { it.str("module") })
        assertEquals(listOf(false, true), frames.map { it.getValue("in_app").jsonPrimitive.boolean })
        assertEquals("custom", frames[0].str("platform"))
        assertEquals("12", frames[1].str("lineno"))
    }
}
