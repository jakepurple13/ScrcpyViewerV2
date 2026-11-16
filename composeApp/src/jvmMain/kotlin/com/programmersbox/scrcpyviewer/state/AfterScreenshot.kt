package com.programmersbox.scrcpyviewer.state

/** What happens after a toolbar screenshot is saved (`Settings.afterScreenshot`). */
enum class AfterScreenshot(val label: String) {
    /** A thumbnail floats over the mirror window with copy and reveal actions. */
    Thumbnail("Show thumbnail"),

    /** The image is copied to the clipboard at once; no thumbnail. */
    Copy("Copy"),

    /** The markup editor opens on the screenshot at once; no thumbnail (until an edit is saved). */
    OpenEditor("Open editor"),
    ;

    companion object {
        /** Unknown or missing names fall back to [Thumbnail], so a removed value never breaks the settings file. */
        fun fromName(name: String?): AfterScreenshot = entries.firstOrNull { it.name == name } ?: Thumbnail
    }
}

/** [Settings.afterScreenshot] as the enum. */
val Settings.afterShot: AfterScreenshot get() = AfterScreenshot.fromName(afterScreenshot)
