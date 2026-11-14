package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.Os
import java.net.URI

private val log = System.getLogger("scrcpyviewer.OpenExternally")

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
 * The argv that opens [target] with the computer's default handler. Never a shell string, so a target with spaces or
 * quotes stays one argument.
 *
 * Windows: a [url] goes to `rundll32 url.dll,FileProtocolHandler`, which hands it to the default browser with no shell
 * (so `&` in a query string is not a command separator, as it would be through `cmd /c start`). A file goes to
 * `explorer.exe`, because `FileProtocolHandler` parses a path as a URL (a `#` or `%` in a name misreads).
 */
internal fun openCommand(target: String, platform: OpenPlatform, url: Boolean = false): List<String> =
    when (platform) {
        OpenPlatform.Mac -> listOf("open", target)
        OpenPlatform.Windows ->
            if (url) listOf("rundll32", "url.dll,FileProtocolHandler", target) else listOf("explorer.exe", target)
        OpenPlatform.Linux -> listOf("xdg-open", target)
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

internal fun openExternally(
    target: String,
    platform: OpenPlatform,
    url: Boolean = false,
    run: (List<String>) -> Process? = ::startDetached,
): Boolean =
    runCatching { run(openCommand(target, platform, url)) != null }
        .onFailure { log.log(System.Logger.Level.WARNING, "Could not open $target", it) }
        .getOrDefault(false)

/**
 * Opens a file path with the computer's default app by launching the platform opener (the Tao window has no AWT
 * toolkit and no `LocalUriHandler`). False, logged, when the opener cannot start; never throws.
 */
fun openExternally(target: String, run: (List<String>) -> Process? = ::startDetached): Boolean =
    openExternally(target, OpenPlatform.current(), url = false, run = run)

/** True for an absolute `http` or `https` URL with a host; anything else is not safe to hand to the OS. */
internal fun isWebUrl(url: String): Boolean =
    runCatching { URI(url) }.getOrNull()
        ?.let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() } == true

internal fun openWebUrl(
    url: String,
    platform: OpenPlatform,
    run: (List<String>) -> Process? = ::startDetached,
): Boolean {
    if (!isWebUrl(url)) {
        log.log(System.Logger.Level.WARNING, "Refusing to open non-http(s) URL: $url")
        return false
    }
    return openExternally(url, platform, url = true, run = run)
}

/**
 * Opens an http(s) [url] in the default browser. Anything else is refused and logged (release URLs come from GitHub
 * data). False when refused or the opener cannot start; never throws.
 */
fun openWebUrl(url: String, run: (List<String>) -> Process? = ::startDetached): Boolean =
    openWebUrl(url, OpenPlatform.current(), run)

/**
 * The argv that shows [file] in the file manager: selected in Finder / Explorer; on Linux there is no portable select,
 * so the parent folder opens. Never a shell string.
 */
internal fun revealCommand(file: String, platform: OpenPlatform): List<String> = when (platform) {
    OpenPlatform.Mac -> listOf("open", "-R", file)
    OpenPlatform.Windows -> listOf("explorer.exe", "/select,$file")
    OpenPlatform.Linux -> listOf("xdg-open", java.nio.file.Paths.get(file).parent?.toString() ?: ".")
}

internal fun revealInFolder(
    file: java.nio.file.Path,
    platform: OpenPlatform,
    run: (List<String>) -> Process? = ::startDetached,
): Boolean =
    runCatching { run(revealCommand(file.toString(), platform)) != null }
        .onFailure { log.log(System.Logger.Level.WARNING, "Could not reveal $file", it) }
        .getOrDefault(false)

/** Shows [file] in Finder / Explorer (selected) or opens its folder (Linux). False, logged, on failure; never throws. */
fun revealInFolder(file: java.nio.file.Path, run: (List<String>) -> Process? = ::startDetached): Boolean =
    revealInFolder(file, OpenPlatform.current(), run)
