package com.programmersbox.scrcpyviewer.telemetry

/** Stands in for one of our own exception types: it lives under the app package, so its message is kept. */
class TestOwnException(message: String) : RuntimeException(message)
