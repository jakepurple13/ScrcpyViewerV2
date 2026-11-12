package com.programmersbox.scrcpyviewer.ui.window

/**
 * Where a title-bar slot is drawn. Every window draws the Nucleus Material title bar ([Custom]); [System] is only
 * for a slot drawn as a plain strip inside the content (offscreen renders, tests). "Use system title bar" is
 * retired: `Settings.useSystemTitleBar` is still parsed from old settings files but nothing reads it.
 */
enum class WindowChrome { Custom, System }
