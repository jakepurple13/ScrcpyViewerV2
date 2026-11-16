package com.programmersbox.scrcpyviewer.emulator

import org.koin.core.annotation.Provided
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the Emulators section looks for the SDK and AVDs, and how it measures an AVD folder. Given to Koin at start:
 * the app uses [system]; tests give one that reads nothing on the machine and never runs `du`.
 */
@Provided
class EmulatorEnvironment(
    /** Environment variables (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_AVD_HOME`, …). */
    val env: (String) -> String?,
    /** The user's home folder: platform default SDK, `~/.android/avd`, and `~` in the SDK line. */
    val home: Path,
    /** An AVD folder's size on disk in bytes, or null. */
    val sizeOf: (Path) -> Long?,
) {
    companion object {
        fun system(): EmulatorEnvironment =
            EmulatorEnvironment(System::getenv, Paths.get(System.getProperty("user.home")), { avdSize(it) })
    }
}
