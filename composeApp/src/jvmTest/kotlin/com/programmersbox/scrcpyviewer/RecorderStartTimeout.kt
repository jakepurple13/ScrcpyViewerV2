package com.programmersbox.scrcpyviewer

/**
 * How long a test waits for a session started with `record` to reach Running. The first Recorder in a test JVM loads
 * JavaCPP's FFmpeg avformat natives, which a cold Windows CI runner has taken over 5 s to extract and load
 * (AppShutdownTest flaked on that, failing the v1.14.1 and v1.17.1 release runs). A deadline only matters when
 * something hangs, so a generous one costs passing runs nothing.
 */
const val RECORDER_START_MILLIS = 30_000L
