package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.BuildInfo

/**
 * Where telemetry goes. [key] is the PostHog project key, [host] the PostHog host, [channel] is "dev" or "release".
 * A blank [key] means this build cannot send anything: [enabled] is false and callers create no client and write no files.
 */
data class TelemetryConfig(val key: String, val host: String, val channel: String) {
    val enabled: Boolean get() = key.isNotBlank()

    companion object {
        /** No key, so nothing is ever sent. Use in tests and test Koin graphs. */
        val Disabled = TelemetryConfig(key = "", host = "", channel = "dev")

        /** Reads the values Gradle generated into [BuildInfo]. */
        fun fromBuildInfo(): TelemetryConfig = TelemetryConfig(
            key = BuildInfo.POSTHOG_KEY,
            host = BuildInfo.POSTHOG_HOST,
            channel = BuildInfo.TELEMETRY_CHANNEL,
        )
    }
}
