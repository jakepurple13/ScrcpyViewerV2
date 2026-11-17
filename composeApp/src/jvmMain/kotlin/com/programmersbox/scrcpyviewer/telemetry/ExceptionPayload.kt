package com.programmersbox.scrcpyviewer.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Collections
import java.util.IdentityHashMap

private const val OWN_TYPE_PREFIX = "com.programmersbox.scrcpyviewer"
private const val IN_APP_PREFIX = "com.programmersbox"

/**
 * Builds the PostHog `$exception_list` properties for [t]: the outer throwable first, then its causes.
 * The message (`value`) is sent only for our own exception types, since we write those messages; every other type
 * gets an empty value. Every string is run through [scrubber]. Frames are ordered oldest call first, as PostHog
 * expects. `$exception_type` and `$exception_message` mirror the first entry.
 */
fun exceptionProperties(t: Throwable, handled: Boolean, scrubber: Scrubber): JsonObject {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val chain = generateSequence(t) { it.cause }.takeWhile { seen.add(it) }.toList()
    val entries = chain.map { entry(it, handled, scrubber) }
    val first = entries.first()
    return buildJsonObject {
        put("\$exception_list", JsonArray(entries))
        put("\$exception_type", first.getValue("type"))
        put("\$exception_message", first.getValue("value"))
    }
}

private fun entry(t: Throwable, handled: Boolean, scrubber: Scrubber): JsonObject {
    val typeName = t.javaClass.name
    val value = if (typeName.startsWith(OWN_TYPE_PREFIX)) t.message.orEmpty() else ""
    return buildJsonObject {
        put("type", scrubber.scrub(typeName))
        put("value", scrubber.scrub(value))
        putJsonObject("mechanism") {
            put("handled", handled)
            put("type", "generic")
        }
        putJsonObject("stacktrace") {
            put("type", "raw")
            putJsonArray("frames") {
                t.stackTrace.reversed().forEach { f ->
                    add(buildJsonObject {
                        put("function", scrubber.scrub(f.methodName))
                        put("module", scrubber.scrub(f.className))
                        put("filename", scrubber.scrub(f.fileName.orEmpty()))
                        put("lineno", JsonPrimitive(f.lineNumber))
                        put("in_app", f.className.startsWith(IN_APP_PREFIX))
                        put("platform", "java")
                    })
                }
            }
        }
    }
}

/**
 * The `$exception` properties for a native crash found by [NativeCrashScanner]: the signal name as the message and
 * the frames' function names (scrubbed), oldest call first. Nothing else from the crash log is sent.
 */
fun nativeCrashProperties(c: NativeCrash, scrubber: Scrubber): JsonObject {
    val signal = scrubber.scrub(c.signal)
    val entry = buildJsonObject {
        put("type", NATIVE_CRASH)
        put("value", signal)
        putJsonObject("mechanism") {
            put("handled", false)
            put("type", "native")
        }
        putJsonObject("stacktrace") {
            put("type", "raw")
            putJsonArray("frames") {
                c.frames.reversed().forEach { f ->
                    add(buildJsonObject {
                        put("function", scrubber.scrub(f))
                        put("in_app", OWN_TYPE_PREFIX in f)
                    })
                }
            }
        }
    }
    return buildJsonObject {
        put("\$exception_list", JsonArray(listOf(entry)))
        put("\$exception_type", NATIVE_CRASH)
        put("\$exception_message", signal)
    }
}

private const val NATIVE_CRASH = "native_crash"
