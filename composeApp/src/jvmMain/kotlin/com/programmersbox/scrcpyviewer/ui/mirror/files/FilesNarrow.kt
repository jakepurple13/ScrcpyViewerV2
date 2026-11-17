package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the Files tab reflows at its width. Each flag turns on below its own threshold; at 440 dp and up none is on and
 * the tab is laid out as before.
 */
data class FilesLayout(
    /** Refresh and New folder leave the toolbar for the top of its ⋮ menu (below 440 dp). */
    val foldRefreshAndNew: Boolean,
    /** Push files here does too (below 340 dp). */
    val foldPush: Boolean,
    /** The free-space meter takes a line of its own under the filter (below 380 dp). */
    val spaceBelowFilter: Boolean,
    /** A row's modified date moves to a second line under its name; the header becomes a sort chip (below 400 dp). */
    val modifiedBelow: Boolean,
    /** The size joins it there, before the date (below 330 dp). */
    val sizeBelow: Boolean,
    /** The selection bar's Rename and Delete fold into its ⋮ (below 380 dp). */
    val foldRenameDelete: Boolean,
    /** Transfer rows stack: name, bar, then destination and progress (below 400 dp). */
    val trayStacked: Boolean,
    /** Dialog buttons stack at full width, the filled one on top (below 340 dp). */
    val dialogButtonsStacked: Boolean,
)

/** The Files tab's layout at [width]. */
fun filesLayout(width: Dp): FilesLayout = FilesLayout(
    foldRefreshAndNew = width < 440.dp,
    foldPush = width < 340.dp,
    spaceBelowFilter = width < 380.dp,
    modifiedBelow = width < 400.dp,
    sizeBelow = width < 330.dp,
    foldRenameDelete = width < 380.dp,
    trayStacked = width < 400.dp,
    dialogButtonsStacked = width < 340.dp,
)

/** The layout for the tab's current width; wide (nothing folded) outside a Files tab. */
internal val LocalFilesLayout = compositionLocalOf { filesLayout(Dp.Infinity) }
