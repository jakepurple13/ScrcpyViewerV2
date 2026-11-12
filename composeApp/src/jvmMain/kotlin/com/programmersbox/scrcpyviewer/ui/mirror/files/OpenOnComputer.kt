package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.platform.UriHandler
import java.nio.file.Path

private val log = System.getLogger("scrcpyviewer.Files")

/**
 * Opens [path] with the computer's default app through the URI handler (the Tao window has no AWT toolkit, so no
 * `Desktop`). The `file:` URI comes from [Path.toUri], which encodes spaces and Windows drive paths. A failure is
 * logged, never thrown; false then.
 */
fun UriHandler.openFileSafely(path: Path): Boolean =
    runCatching { openUri(path.toUri().toString()) }
        .onFailure { log.log(System.Logger.Level.WARNING, "Could not open $path", it) }
        .isSuccess
