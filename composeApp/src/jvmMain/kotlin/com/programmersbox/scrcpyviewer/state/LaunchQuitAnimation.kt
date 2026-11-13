package com.programmersbox.scrcpyviewer.state

/** How the main window appears on launch and disappears on quit (`Settings.launchQuitAnimation`). */
enum class LaunchQuitAnimation(val label: String, val description: String) {
    /** The app icon unfolds and the window grows out of its phone screen; quit folds it back. */
    IconUnfold("Icon unfold", "The window grows out of the app icon."),

    /** A scan line sweeps the window in; quit squashes it to a line, then a dot, like an old TV. */
    ScreenOn("Screen on", "A scan line sweeps the window in. Quit is a TV switching off."),

    /** No animation. */
    Off("Off", "The window appears and closes at once."),
    ;

    companion object {
        /** Unknown or missing names fall back to [IconUnfold], so a removed value never breaks the settings file. */
        fun fromName(name: String?): LaunchQuitAnimation = entries.firstOrNull { it.name == name } ?: IconUnfold
    }
}

/** [Settings.launchQuitAnimation] as the enum. */
val Settings.launchQuit: LaunchQuitAnimation get() = LaunchQuitAnimation.fromName(launchQuitAnimation)
