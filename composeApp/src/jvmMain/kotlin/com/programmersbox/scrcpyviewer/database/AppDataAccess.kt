package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.files.shellQuote

/**
 * What the device grants this adb shell. [Available] means `su` exists but hasn't been run: running it shows the
 * root manager's prompt on the phone, so only a user action asks ([Asking], then [Rooted] or [Denied]).
 */
enum class RootState { Unknown, None, Available, Asking, Rooted, AdbRoot, Denied }

/** How this device's `su` takes a command: `su -c '<cmd>'` (Magisk, KernelSU) or `su 0 sh -c '<cmd>'` (AOSP). */
enum class SuStyle { DashC, Zero }

/** How the shell reaches one app's private files. [wrap] runs a command with the app's data folder as its cwd. */
sealed interface AppDataAccess {
    val pkg: String

    /** Short name for the Data tab's chip. */
    val label: String

    fun wrap(inner: String): String

    data class RunAs(override val pkg: String) : AppDataAccess {
        override val label get() = "run-as"
        override fun wrap(inner: String) = "run-as $pkg sh -c ${shellQuote(inner)}"
    }

    data class Su(override val pkg: String, val style: SuStyle) : AppDataAccess {
        override val label get() = "root · su"
        override fun wrap(inner: String): String {
            val script = shellQuote("cd ${dataDir(pkg)} && $inner")
            return when (style) {
                SuStyle.DashC -> "su -c $script"
                SuStyle.Zero -> "su 0 sh -c $script"
            }
        }
    }

    data class AdbRoot(override val pkg: String) : AppDataAccess {
        override val label get() = "adb root"
        override fun wrap(inner: String) = "cd ${dataDir(pkg)} && $inner"
    }
}

private fun dataDir(pkg: String) = "/data/data/$pkg"

/** The way into [app]'s data: run-as for a debug build, otherwise root when the device has it; null when locked. */
fun accessFor(app: DeviceApp, root: RootState, su: SuStyle?): AppDataAccess? = when {
    app.debuggable -> AppDataAccess.RunAs(app.packageName)
    root == RootState.AdbRoot -> AppDataAccess.AdbRoot(app.packageName)
    root == RootState.Rooted && su != null -> AppDataAccess.Su(app.packageName, su)
    else -> null
}
