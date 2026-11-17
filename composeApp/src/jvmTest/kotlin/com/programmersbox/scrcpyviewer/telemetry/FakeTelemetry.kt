package com.programmersbox.scrcpyviewer.telemetry

import java.util.concurrent.CopyOnWriteArrayList

/** Records the exceptions and native crashes it is given; everything else is [Telemetry.Noop]. */
class FakeTelemetry : Telemetry by Telemetry.Noop {
    /** [captureException] calls, with their `handled` flag. */
    val exceptions = CopyOnWriteArrayList<Pair<Throwable, Boolean>>()
    val blocking = CopyOnWriteArrayList<Throwable>()
    val nativeCrashes = CopyOnWriteArrayList<NativeCrash>()

    override fun captureException(t: Throwable, handled: Boolean) {
        exceptions += t to handled
    }

    override fun captureExceptionBlocking(t: Throwable) {
        blocking += t
    }

    override fun captureNativeCrash(c: NativeCrash) {
        nativeCrashes += c
    }
}
