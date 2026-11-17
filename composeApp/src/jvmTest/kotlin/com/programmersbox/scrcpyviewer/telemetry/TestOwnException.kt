package com.programmersbox.scrcpyviewer.telemetry

/** Stands in for one of our own exception types marked [SafeMessage], so its message is kept. */
class TestOwnException(message: String) : RuntimeException(message), SafeMessage
