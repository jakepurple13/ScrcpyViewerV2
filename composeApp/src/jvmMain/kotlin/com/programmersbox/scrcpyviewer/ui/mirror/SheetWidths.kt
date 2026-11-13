package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The narrowest the device area (stage) gets while the sheet is dragged wider. */
val STAGE_MIN_WIDTH = 160.dp

/** The widest a saved sheet width is taken as when a window opens (a hand-edited or corrupt settings file). */
val SHEET_MAX_WIDTH = 1200.dp

/** How far one Left or Right key press on the sheet's drag bar moves it. */
val SHEET_KEY_STEP = 16.dp

/** Sheet widths below which the Actions tab shows 2 tile columns, then 3; 4 from the second on. */
private val THREE_COLUMNS_FROM = 360.dp
private val FOUR_COLUMNS_FROM = 480.dp

/**
 * The open sheet's width on each tab, its drag bar included; each tab remembers its own. The defaults are also each
 * tab's minimum.
 */
data class SheetWidths(
    val actions: Dp = QUICK_ACTIONS_WIDTH,
    val shell: Dp = QUICK_ACTIONS_SHELL_WIDTH,
    val apps: Dp = QUICK_ACTIONS_APPS_WIDTH,
    val files: Dp = QUICK_ACTIONS_FILES_WIDTH,
    val links: Dp = QUICK_ACTIONS_LINKS_WIDTH,
    val logcat: Dp = QUICK_ACTIONS_LOGCAT_WIDTH,
) {
    operator fun get(tab: SheetTab): Dp = when (tab) {
        SheetTab.Actions -> actions
        SheetTab.Apps -> apps
        SheetTab.Shell -> shell
        SheetTab.Files -> files
        SheetTab.Links -> links
        SheetTab.Logcat -> logcat
    }

    fun with(tab: SheetTab, width: Dp): SheetWidths = when (tab) {
        SheetTab.Actions -> copy(actions = width)
        SheetTab.Apps -> copy(apps = width)
        SheetTab.Shell -> copy(shell = width)
        SheetTab.Files -> copy(files = width)
        SheetTab.Links -> copy(links = width)
        SheetTab.Logcat -> copy(logcat = width)
    }

    companion object {
        /** [tab]'s default width, also its minimum. */
        fun default(tab: SheetTab): Dp = SheetWidths()[tab]

        /** From the settings' dp values, each kept between its tab's minimum and [SHEET_MAX_WIDTH]. */
        fun fromSettings(
            actions: Int,
            shell: Int,
            apps: Int,
            files: Int,
            logcat: Int = QUICK_ACTIONS_LOGCAT_WIDTH.value.toInt(),
            links: Int = QUICK_ACTIONS_LINKS_WIDTH.value.toInt(),
        ): SheetWidths = SheetWidths(
            actions = actions.dp.coerceIn(default(SheetTab.Actions), SHEET_MAX_WIDTH),
            shell = shell.dp.coerceIn(default(SheetTab.Shell), SHEET_MAX_WIDTH),
            apps = apps.dp.coerceIn(default(SheetTab.Apps), SHEET_MAX_WIDTH),
            files = files.dp.coerceIn(default(SheetTab.Files), SHEET_MAX_WIDTH),
            links = links.dp.coerceIn(default(SheetTab.Links), SHEET_MAX_WIDTH),
            logcat = logcat.dp.coerceIn(default(SheetTab.Logcat), SHEET_MAX_WIDTH),
        )
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
