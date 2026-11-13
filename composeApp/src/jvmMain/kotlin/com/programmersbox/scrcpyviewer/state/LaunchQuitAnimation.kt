package com.programmersbox.scrcpyviewer.state

/** How the main window appears on launch and disappears on quit (`Settings.launchQuitAnimation`). */
enum class LaunchQuitAnimation(val label: String) {
    /** The app icon unfolds and the window grows out of its phone screen; quit folds it back. */
    IconUnfold("Icon unfold"),

    /** A scan line sweeps the window in; quit squashes it to a line, then a dot, like an old TV. */
    ScreenOn("Screen on"),

    /** No animation. */
    Off("Off"),
    ;

    companion object {
        /** Unknown or missing names fall back to [IconUnfold], so a removed value never breaks the settings file. */
        fun fromName(name: String?): LaunchQuitAnimation = entries.firstOrNull { it.name == name } ?: IconUnfold
    }
}

/** [Settings.launchQuitAnimation] as the enum. */
val Settings.launchQuit: LaunchQuitAnimation get() = LaunchQuitAnimation.fromName(launchQuitAnimation)
