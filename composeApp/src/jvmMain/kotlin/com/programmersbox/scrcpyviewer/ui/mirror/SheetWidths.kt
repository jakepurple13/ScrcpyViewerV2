package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The narrowest the device area (stage) gets while the sheet is dragged wider. */
val STAGE_MIN_WIDTH = 160.dp

/** How far one Left or Right key press on the sheet's drag bar moves it. */
val SHEET_KEY_STEP = 16.dp

/** Sheet widths below which the Actions tab shows 2 tile columns, then 3; 4 from the second on. */
private val THREE_COLUMNS_FROM = 360.dp
private val FOUR_COLUMNS_FROM = 480.dp

/**
 * The open sheet's width on each tab, its drag bar included; each tab remembers its own. The defaults are also each
 * tab's minimum.
 */
data class SheetWidths(val actions: Dp = QUICK_ACTIONS_WIDTH, val shell: Dp = QUICK_ACTIONS_SHELL_WIDTH) {
    operator fun get(tab: SheetTab): Dp = if (tab == SheetTab.Shell) shell else actions

    fun with(tab: SheetTab, width: Dp): SheetWidths = if (tab == SheetTab.Shell) copy(shell = width) else copy(actions = width)

    companion object {
        /** [tab]'s default width, also its minimum. */
        fun default(tab: SheetTab): Dp = SheetWidths()[tab]
    }
}

/**
 * The sheet width to show for [requested] on [tab], in a content row [windowWidth] wide that also holds the side
 * toolbar ([toolbarWidth]) and the stage: no wider than leaves the stage [STAGE_MIN_WIDTH], and never under the tab's
 * minimum (that wins in a window too narrow for both).
 */
fun clampSheetWidth(requested: Dp, tab: SheetTab, windowWidth: Dp, toolbarWidth: Dp): Dp =
    requested.coerceAtMost(windowWidth - toolbarWidth - STAGE_MIN_WIDTH).coerceAtLeast(SheetWidths.default(tab))

/** Tile columns on the Actions tab for a sheet [width] wide: 2 under 360 dp, 3 under 480 dp, else 4. */
fun tileColumns(width: Dp): Int = when {
    width < THREE_COLUMNS_FROM -> 2
    width < FOUR_COLUMNS_FROM -> 3
    else -> 4
}
