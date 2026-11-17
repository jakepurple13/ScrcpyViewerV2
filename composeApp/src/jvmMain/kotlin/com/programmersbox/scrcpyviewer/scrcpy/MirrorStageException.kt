package com.programmersbox.scrcpyviewer.scrcpy

import java.io.IOException

/** Where starting a mirror failed: pushing the server jar, starting or reaching the server, or decoding the video. */
enum class MirrorStage { Push, Server, Decode }

/**
 * Starting a mirror failed at [stage]. Being the app's own type, its [message] goes into crash reports as it is, so it
 * holds fixed text only. [detail] (adb's error line, the server's last output, a host and port) can name the device or
 * the computer: it is shown in the window ([displayMessage]) and never sent.
 */
class MirrorStageException(
    val stage: MirrorStage,
    message: String,
    cause: Throwable?,
    /** Appended to [message] in the window, separator included (": ..."). */
    val detail: String = "",
) : IOException(message, cause) {
    /** What the window shows: the message, then the [detail]. */
    val displayMessage: String get() = message.orEmpty() + detail
}
