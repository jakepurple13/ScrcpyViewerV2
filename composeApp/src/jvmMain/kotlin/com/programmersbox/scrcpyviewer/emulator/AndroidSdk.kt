package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal data class AndroidSdk(val root: Path, val emulator: Path, val emulatorVersion: String?)

internal sealed interface SdkLookup {
    data class Found(val sdk: AndroidSdk) : SdkLookup

    /** The SDK folder exists but holds no emulator binary. */
    data class NoEmulator(val root: Path) : SdkLookup

    /** No candidate folder exists; [defaultPath] is the platform default, as shown to the user. */
    data class NotFound(val defaultPath: String) : SdkLookup
}

private fun defaultDisplay(os: OpenPlatform) = when (os) {
    OpenPlatform.Mac -> "~/Library/Android/sdk"
    OpenPlatform.Windows -> "%LOCALAPPDATA%\\Android\\Sdk"
    OpenPlatform.Linux -> "~/Android/Sdk"
}

private fun defaultSdk(env: (String) -> String?, home: Path, os: OpenPlatform): Path? = when (os) {
    OpenPlatform.Mac -> home.resolve("Library").resolve("Android").resolve("sdk")
    OpenPlatform.Windows -> env("LOCALAPPDATA")?.let(::asPath)?.resolve("Android")?.resolve("Sdk")
    OpenPlatform.Linux -> home.resolve("Android").resolve("Sdk")
}

private fun asPath(s: String): Path? =
    s.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it.trim()) }.getOrNull() }

/**
 * First existing folder of: [override] (the saved setting), `ANDROID_HOME`, `ANDROID_SDK_ROOT`, the platform default.
 * [env], [home] and [os] are injected so tests never read the real machine.
 */
internal fun findSdk(override: String?, env: (String) -> String?, home: Path, os: OpenPlatform): SdkLookup {
    val candidates = listOfNotNull(
        override?.let(::asPath),
        env("ANDROID_HOME")?.let(::asPath),
        env("ANDROID_SDK_ROOT")?.let(::asPath),
        defaultSdk(env, home, os),
    )
    val root = candidates.firstOrNull { Files.isDirectory(it) } ?: return SdkLookup.NotFound(defaultDisplay(os))
    val binary = root.resolve("emulator").resolve(if (os == OpenPlatform.Windows) "emulator.exe" else "emulator")
    if (!Files.isRegularFile(binary)) return SdkLookup.NoEmulator(root)
    return SdkLookup.Found(AndroidSdk(root, binary, emulatorVersion(root)))
}

/** `Pkg.Revision` from `<sdk>/emulator/source.properties`; no process is run. */
private fun emulatorVersion(root: Path): String? = runCatching {
    Files.readAllLines(root.resolve("emulator").resolve("source.properties"))
        .firstNotNullOfOrNull { line ->
            line.substringAfter('=', "").takeIf { line.substringBefore('=').trim() == "Pkg.Revision" }?.trim()
        }?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** `ANDROID_AVD_HOME`, else `$ANDROID_USER_HOME/avd`, else `~/.android/avd`. */
internal fun avdHome(env: (String) -> String?, home: Path): Path {
    env("ANDROID_AVD_HOME")?.let(::asPath)?.let { return it }
    env("ANDROID_USER_HOME")?.let(::asPath)?.let { return it.resolve("avd") }
    return home.resolve(".android").resolve("avd")
}
