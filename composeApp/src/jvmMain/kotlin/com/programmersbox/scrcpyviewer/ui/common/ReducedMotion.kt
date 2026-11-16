package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Function
import com.sun.jna.ptr.IntByReference

/**
 * Whether the OS asks for less motion: Reduce motion on macOS, animations off on Windows, GNOME's animations switch on
 * Linux. False when unknown.
 */
fun systemReducesMotion(): Boolean = try {
    when {
        Os.isMac -> {
            val workspace = ObjC.msgPtr(ObjC.cls("NSWorkspace"), "sharedWorkspace")
            workspace != null && ObjC.msgBool(workspace, "accessibilityDisplayShouldReduceMotion")
        }
        Os.isWindows -> {
            val on = IntByReference(1)
            val spiGetClientAreaAnimation = 0x1042
            val ok = Function.getFunction("user32", "SystemParametersInfoW")
                .invokeInt(arrayOf(spiGetClientAreaAnimation, 0, on.pointer, 0)) != 0
            ok && on.value == 0
        }
        else -> gnomeReducesMotion.get()
    }
} catch (_: Throwable) {
    false
}

/**
 * GNOME's switch, read at most once a minute: it runs a process (up to 500 ms) and callers are on the UI thread (every
 * mirror window open, the main window's launch and quit).
 */
private val gnomeReducesMotion = com.programmersbox.scrcpyviewer.ui.window.launch.TimedCache(60_000) {
    runCatching { gnomeAnimationsOff(gsettingsEnableAnimations()) }.getOrDefault(false)
}

/** `gsettings get org.gnome.desktop.interface enable-animations` (null when it fails or takes over 500 ms). */
private fun gsettingsEnableAnimations(): String? {
    val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
        .redirectErrorStream(true).start()
    if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        return null
    }
    return process.inputStream.bufferedReader().use { it.readText() }
}

/** GNOME's animations switch is off: [output] of `gsettings get … enable-animations` is `false`. */
internal fun gnomeAnimationsOff(output: String?): Boolean = output?.trim() == "false"
