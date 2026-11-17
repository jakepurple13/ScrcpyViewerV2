package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a log row is laid out at the tab's width. Each shape keeps the same cells; only where they sit changes.
 */
enum class LogcatRowShape {
    /** One line: time, PID-TID, tag, package, level, message (600 dp and up). */
    Full,

    /** One line without PID-TID and Package (420 to 599 dp). */
    NoIds,

    /** Two lines: level, tag and HH:mm:ss, then the message under the tag; no header (under 420 dp). */
    Stacked,
}

/** The row shape for a tab [width] wide. */
fun logcatRowShape(width: Dp): LogcatRowShape = when {
    width >= 600.dp -> LogcatRowShape.Full
    width >= 420.dp -> LogcatRowShape.NoIds
    else -> LogcatRowShape.Stacked
}

/**
 * Under 420 dp the toolbar and status bar compact: Play/Pause is an icon, the picked app's label takes its own line
 * under the chips, the level mix becomes a strip on the status bar's top edge and Wrap and Compact fold into a ⋮ menu.
 */
fun logcatToolbarCompact(width: Dp): Boolean = width < 420.dp
