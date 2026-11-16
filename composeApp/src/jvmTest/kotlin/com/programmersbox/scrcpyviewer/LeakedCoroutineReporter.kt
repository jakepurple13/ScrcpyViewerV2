package com.programmersbox.scrcpyviewer

import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Registered as a service (META-INF/services), so it sees every coroutine exception nothing handled: one thrown in a
 * scope a test made and never cancelled, after that test has passed. runTest reports such an exception only at the
 * start of the next runTest, as UncaughtExceptionsBeforeTest on an innocent test (LogcatControllerTest and
 * LinksStateTest failed that way on CI). This writes it to stderr in one tagged chunk, and the jvmTest output
 * listener in build.gradle.kts prints it with the test that was running. It reports only; handling stays the same.
 */
class LeakedCoroutineReporter : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val trace = StringWriter().also { exception.printStackTrace(PrintWriter(it)) }
        // One write, so the output listener gets the tag and the trace as one event.
        System.err.print("$TAG thread ${Thread.currentThread().name}, $context\n$trace")
        System.err.flush()
    }

    private companion object {
        const val TAG = "[leaked-coroutine-exception]"
    }
}
