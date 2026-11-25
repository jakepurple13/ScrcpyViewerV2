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
    val database: Dp = QUICK_ACTIONS_DATABASE_WIDTH,
) {
    operator fun get(tab: SheetTab): Dp = when (tab) {
        SheetTab.Actions -> actions
        SheetTab.Apps -> apps
        SheetTab.Shell -> shell
        SheetTab.Files -> files
        SheetTab.Database -> database
        SheetTab.Links -> links
        SheetTab.Logcat -> logcat
    }

    fun with(tab: SheetTab, width: Dp): SheetWidths = when (tab) {
        SheetTab.Actions -> copy(actions = width)
        SheetTab.Apps -> copy(apps = width)
        SheetTab.Shell -> copy(shell = width)
        SheetTab.Files -> copy(files = width)
        SheetTab.Database -> copy(database = width)
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
            database: Int = QUICK_ACTIONS_DATABASE_WIDTH.value.toInt(),
        ): SheetWidths = SheetWidths(
            database = database.dp.coerceIn(default(SheetTab.Database), SHEET_MAX_WIDTH),
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

/** Where the open quick actions sheet goes in the content row: beside the stage, over it, or over all of it. */
enum class SheetPlacement {
    /** Right of the side toolbar, taking its width from the stage (as in a wide window). */
    Docked,

    /** Over the stage, its right edge at the toolbar's start, the stage keeping the row less the toolbar. */
    Floating,

    /** Over the whole stage: the row less the toolbar, even under the tab's minimum. */
    Full,
}

/**
 * Where a sheet [sheetWidth] wide (its tab's saved width) goes in a content row [windowWidth] wide that also holds the
 * side toolbar ([toolbarWidth]): docked while the stage keeps [STAGE_MIN_WIDTH] beside it, full while the row cannot
 * hold the toolbar and the tab's minimum ([sheetMin]), floating over the stage in between.
 */
fun sheetPlacement(windowWidth: Dp, toolbarWidth: Dp, sheetWidth: Dp, sheetMin: Dp): SheetPlacement = when {
    windowWidth >= toolbarWidth + STAGE_MIN_WIDTH + sheetWidth -> SheetPlacement.Docked
    windowWidth < toolbarWidth + sheetMin -> SheetPlacement.Full
    else -> SheetPlacement.Floating
}

/**
 * [sheetPlacement] for [tab] at its [saved] width (never under the tab's minimum) in a row [rowWidth] wide; docked
 * while the row is unknown.
 */
fun sheetPlacement(saved: Dp, tab: SheetTab, rowWidth: Dp?, toolbarWidth: Dp): SheetPlacement {
    val min = SheetWidths.default(tab)
    return if (rowWidth == null) SheetPlacement.Docked else sheetPlacement(rowWidth, toolbarWidth, saved.coerceAtLeast(min), min)
}

/**
 * The sheet width to show for [requested] on [tab] in a row [rowWidth] wide, placed by the tab's [saved] width: docked,
 * [clampSheetWidth]; floating, between the tab's minimum and the row less the toolbar; full, the row less the toolbar.
 * An unknown row (null: the window is growing or shrinking for the sheet) applies only the tab's minimum.
 */
fun fitSheetWidth(
    requested: Dp,
    tab: SheetTab,
    rowWidth: Dp?,
    toolbarWidth: Dp,
    saved: Dp = requested,
    /** The placement to fit for, when the window keeps it (a drag in progress); null computes it from [saved]. */
    placement: SheetPlacement? = null,
): Dp {
    if (rowWidth == null) return requested.coerceAtLeast(SheetWidths.default(tab))
    return when (placement ?: sheetPlacement(saved, tab, rowWidth, toolbarWidth)) {
        SheetPlacement.Docked -> clampSheetWidth(requested, tab, rowWidth, toolbarWidth)
        SheetPlacement.Floating -> requested.coerceAtMost(rowWidth - toolbarWidth).coerceAtLeast(SheetWidths.default(tab))
        SheetPlacement.Full -> rowWidth - toolbarWidth
    }
}

/** The sheet's tab control turns compact (icons, the selected tab also named) on a sheet narrower than this. */
private val COMPACT_TABS_UNDER = 340.dp

/** Whether the sheet's tab control is compact on a sheet [width] wide. */
fun sheetTabsCompact(width: Dp): Boolean = width < COMPACT_TABS_UNDER
