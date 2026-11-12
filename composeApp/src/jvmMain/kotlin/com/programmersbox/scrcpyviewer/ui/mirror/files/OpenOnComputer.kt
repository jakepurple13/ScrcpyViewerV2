package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.Os
import java.nio.file.Path

private val log = System.getLogger("scrcpyviewer.Files")

/** Which desktop the opener targets; [current] reads the host. */
internal enum class OpenPlatform {
    Mac, Windows, Linux;

    companion object {
        fun current() = when {
            Os.isMac -> Mac
            Os.isWindows -> Windows
            else -> Linux
        }
    }
}

/**
 * The argv that opens [path] with the computer's default app. Never a shell string, so a name with spaces or quotes
 * stays one argument. Windows uses `explorer.exe`: it takes the path as one argument and opens a file with its default
 * app, where `rundll32 url.dll,FileProtocolHandler` parses the path as a URL (a `#` or `%` in a name misreads).
 */
internal fun openCommand(path: Path, platform: OpenPlatform): List<String> {
    val target = path.toString()
    return when (platform) {
        OpenPlatform.Mac -> listOf("open", target)
        OpenPlatform.Windows -> listOf("explorer.exe", target)
        OpenPlatform.Linux -> listOf("xdg-open", target)
    }
}

/**
 * Starts [command] without waiting for it. Output is discarded so a chatty opener can never fill a pipe and block.
 * Null when the process cannot start.
 */
internal fun startDetached(command: List<String>): Process? =
    runCatching {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.onFailure { log.log(System.Logger.Level.WARNING, "Could not start $command", it) }.getOrNull()

/**
 * Opens [path] with the computer's default app by launching the platform opener (the Tao window has no AWT toolkit and
 * no `LocalUriHandler`). False, logged, when the opener cannot start; never throws.
 */
fun openOnComputer(path: Path, run: (List<String>) -> Process? = ::startDetached): Boolean =
    openOnComputer(path, OpenPlatform.current(), run)

internal fun openOnComputer(path: Path, platform: OpenPlatform, run: (List<String>) -> Process? = ::startDetached): Boolean =
    runCatching { run(openCommand(path, platform)) != null }
        .onFailure { log.log(System.Logger.Level.WARNING, "Could not open $path", it) }
        .getOrDefault(false)
