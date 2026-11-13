package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.state.launchQuit
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle

/** Where the launch overlay would run. Linux is split by session: Wayland needs the overlay on an XWayland surface. */
enum class OverlayPlatform {
    MacOS, Windows, LinuxX11, LinuxWayland;

    companion object {
        fun current(
            env: (String) -> String? = System::getenv,
            isMac: Boolean = Os.isMac,
            isWindows: Boolean = Os.isWindows,
        ): OverlayPlatform = when {
            isMac -> MacOS
            isWindows -> Windows
            env("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) || !env("WAYLAND_DISPLAY").isNullOrEmpty() -> LinuxWayland
            else -> LinuxX11
        }
    }
}

/**
 * The platforms that draw the launch and quit overlay; the others fade. A platform is added once the user has run the
 * overlay spike there by hand (see docs/superpowers/notes/2026-11-13-launch-overlay-spike.md).
 */
val OverlayEnabled: Set<OverlayPlatform> = setOf(OverlayPlatform.MacOS)

/** What the main window does on a launch or a quit. */
sealed interface LaunchEffect {
    /** The overlay plays [anim]. */
    data class Overlay(val anim: LaunchQuitAnimation) : LaunchEffect

    /** A 150 ms fade of the real window. */
    data object Fade : LaunchEffect

    /** Today's behaviour: the window appears or disappears at once. */
    data object None : LaunchEffect
}

/** Everything [launchEffect] decides on. */
data class LaunchConditions(
    val setting: LaunchQuitAnimation,
    val platform: OverlayPlatform,
    /** The OS asks for less motion. */
    val reduceMotion: Boolean,
    /** Glass with desktop blur (macOS): a picture of the window can't include the blurred desktop. */
    val glassBlur: Boolean,
    /** An overlay can't sit over a full-screen window (a macOS full-screen Space). */
    val fullscreen: Boolean,
    val enabled: Set<OverlayPlatform> = OverlayEnabled,
)

/** Off is no animation; anything the overlay can't do well fades; otherwise the overlay plays the chosen animation. */
fun launchEffect(c: LaunchConditions): LaunchEffect = when {
    c.setting == LaunchQuitAnimation.Off -> LaunchEffect.None
    c.reduceMotion || c.glassBlur || c.fullscreen || c.platform !in c.enabled -> LaunchEffect.Fade
    else -> LaunchEffect.Overlay(c.setting)
}

/** The millis of the fade [LaunchEffect.Fade] plays. */
const val LAUNCH_FADE_MILLIS = 150

/** [launchEffect] for this machine now: the chosen setting, this platform, the OS's motion preference, Glass blur. */
fun launchEffectNow(settings: Settings, fullscreen: Boolean): LaunchEffect {
    val theme = settings.appTheme
    return launchEffect(
        LaunchConditions(
            setting = settings.launchQuit,
            platform = OverlayPlatform.current(),
            reduceMotion = systemReducesMotion(),
            glassBlur = Os.isMac && theme.style == ThemeStyle.Glass && theme.desktopBlur,
            fullscreen = fullscreen,
        ),
    )
}
