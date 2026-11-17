package com.programmersbox.scrcpyviewer.telemetry

/**
 * Marks an exception whose message is fixed text the app wrote: no paths, serials, commands, adb or server output.
 * Crash reports send the message only for these types; every other exception, the app's own included, sends an empty
 * one. Anything that varies goes in a field kept out of the message (see `MirrorStageException.detail`).
 */
interface SafeMessage
