package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.itemSwitchTransform
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import com.programmersbox.scrcpyviewer.ui.common.switchDirection
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import com.programmersbox.scrcpyviewer.ui.common.layoutFraction
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Paths

/** About's "Open-source licenses" opens the licenses this long after About starts closing. */
internal const val ABOUT_TO_LICENSES_DELAY_MILLIS = 180L

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    files: SettingsFileDialogs,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val updates by updateViewModel.state.collectAsStateWithLifecycle()
    var showAiSkillDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    // The licence list loads the first time the licenses open, then stays for the next time.
    var licensesLoaded by remember { mutableStateOf(false) }
    val openLicenses = {
        licensesLoaded = true
        showLicenses = true
    }
    val dialogScope = rememberCoroutineScope()
    // Pickers run through onPickerThread (off the UI thread on Windows / Linux), so they are suspending.
    // One at a time (PickerGuard): off macOS the UI stays live while a picker is open.
    val pickerScope = rememberCoroutineScope()
    val picker = rememberPickerGuard()
    val actions = SettingsActions(
        onTheme = viewModel::setTheme,
        onPalette = viewModel::setPalette,
        onPureBlack = viewModel::setPureBlack,
        onCustomPalette = viewModel::setCustomPalette,
        onNeon = viewModel::setNeon,
        onCustomNeon = viewModel::setCustomNeon,
        onStyle = viewModel::setStyle,
        onRetroPalette = viewModel::setRetroPalette,
        onDesktopBlur = viewModel::setDesktopBlur,
        onGlassOpacity = viewModel::setGlassOpacity,
        onDefaultPreset = viewModel::setDefaultPreset,
        onAnimateMirrorWindows = viewModel::setAnimateMirrorWindows,
        onShowTrayIcon = viewModel::setShowTrayIcon,
        onLaunchQuitAnimation = viewModel::setLaunchQuitAnimation,
        onUseBundledAdb = viewModel::useBundledAdb,
        onUseCustomAdb = viewModel::useCustomAdb,
        onBrowseAdb = { picker.launch(pickerScope) { files.chooseAdbFile()?.let { viewModel.useCustomAdb(it.toString()) } } },
        onBrowseCaptureDir = {
            val current = Paths.get(state.settings.captureDir)
            picker.launch(pickerScope) { files.chooseFolder(current)?.let { viewModel.setCaptureDir(it.toString()) } }
        },
        onAfterScreenshot = viewModel::setAfterScreenshot,
        onShowOnboarding = viewModel::showOnboarding,
        onShowAbout = { showAbout = true },
        onShowLicenses = openLicenses,
        onCheckUpdates = updateViewModel::check,
        onUpdate = updateViewModel::update,
        onCancelUpdate = updateViewModel::cancel,
        onRestartToUpdate = updateViewModel::restart,
        onAutoDownloadUpdates = viewModel::setAutoDownloadUpdates,
        onOpenAiSkill = { showAiSkillDialog = true },
        onInstallAiSkill = { targets ->
            showAiSkillDialog = false
            viewModel.installAiSkill(targets)
        },
        onChooseAiSkillFolder = { picker.launch(pickerScope) { files.chooseFolder("Choose a skills folder")?.let(viewModel::addAiSkillFolder) } },
        onDismissAiInstall = viewModel::dismissAiInstallResult,
        onUsageStats = viewModel::setUsageStats,
        onCrashReports = viewModel::setCrashReports,
        onResetInstallId = viewModel::resetInstallId,
        onSelectCategory = viewModel::selectCategory,
        onQuery = viewModel::setQuery,
    )
    SettingsContent(state, updates, nav, actions, modifier)
    if (showAiSkillDialog) {
        AddAiSkillDialog(
            rows = state.aiSkill, home = System.getProperty("user.home"),
            onInstall = actions.onInstallAiSkill, onChooseFolder = actions.onChooseAiSkillFolder,
            onDismiss = { showAiSkillDialog = false },
        )
    }
    state.aiInstallResult?.let { AiInstallResultDialog(it, actions.onDismissAiInstall) }
    AboutDialog(
        visible = showAbout,
        info = remember(state.bundledAdbVersion) { AboutInfo.current(state.bundledAdbVersion) },
        onOpenLicenses = {
            // About closes first, then the licenses open over the settling scrim.
            showAbout = false
            dialogScope.launch {
                delay(ABOUT_TO_LICENSES_DELAY_MILLIS)
                openLicenses()
            }
        },
        onDismiss = { showAbout = false },
    )
    if (licensesLoaded) {
        val entries by rememberLicenseEntries(platformToolsVersion(state.bundledAdbVersion))
        LicensesDialog(visible = showLicenses, entries = entries, onDismiss = { showLicenses = false })
    }
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    updates: UpdateUiState,
    nav: SettingsNav,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    categories: List<SettingsCategory> = settingsCategories,
) {
    val matches = searchSettings(categories, nav.query)
    val shown = nav.shown(matches.map { it.category.id })
    val match = matches.firstOrNull { it.category.id == shown }
    val reduceMotion = LocalReduceMotion.current
    // The sidebar follows the window, not this content: a drag inside one form moves it the same frame. Only the
    // switch between the list and the icons springs.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val icons = settingsSidebarIcons(windowWidth)
    val toIcons by layoutFraction(icons, glide = !reduceMotion)
    val listWidth = settingsSidebarWidth(maxOf(windowWidth, SidebarListMinWindow))
    // The spring can undershoot; a width never goes below 0.
    val sidebarWidth = lerp(listWidth, SidebarIconsWidth, toIcons).coerceAtLeast(0.dp)
    // Composed at layout, as AdaptiveListDetail composes its panes: the sidebar's and the page's entrances keep the
    // frame they started on when Settings used it. Its constraints go unread on purpose. Without it the last category
    // row is still mid-entrance in SettingsScreenshotTest and StylesScreenshotTest (styles-settings-*) renders, which
    // then differ (up to 2/255 on the Help row) from the renders before the sidebar stayed. Keep it unless those
    // renders are meant to change.
    BoxWithConstraints(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            SettingsListPane(
                matches = matches, selected = shown, query = nav.query, searching = nav.query.isNotBlank(),
                updateAvailable = updates.available != null, onQuery = actions.onQuery, onSelect = actions.onSelectCategory,
                icons = icons,
                modifier = Modifier.width(sidebarWidth).fillMaxHeight().testTag(SettingsSidebarTag),
            )
            // Hairline so the divider reads in the light theme too.
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            val bindings = SettingsBindings(state, updates, actions)
            // Keyed by category, so filtering rows while typing updates in place instead of re-animating.
            AnimatedContent(
                targetState = match,
                contentKey = { it?.category?.id },
                transitionSpec = {
                    val from = categories.indexOfFirst { it.id == initialState?.category?.id }
                    val to = categories.indexOfFirst { it.id == targetState?.category?.id }
                    itemSwitchTransform(switchDirection(from, to), reduceMotion)
                },
                modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                label = "settingsCategory",
            ) { shownMatch ->
                SettingsDetailPane(
                    category = shownMatch?.category, entries = shownMatch?.entries.orEmpty(), query = nav.query,
                    bindings = bindings,
                )
            }
        }
    }
}

/**
 * The category sidebar's width in a [windowWidth] window: 200 dp from 840 dp; across 480–840 dp it narrows with the
 * window from 200 to 136 dp; below 480 dp it is the 64 dp column of icons.
 */
fun settingsSidebarWidth(windowWidth: Dp): Dp = when {
    windowWidth >= SidebarFullWindow -> SidebarFullWidth
    settingsSidebarIcons(windowWidth) -> SidebarIconsWidth
    else -> lerp(SidebarNarrowestListWidth, SidebarFullWidth, (windowWidth - SidebarListMinWindow) / (SidebarFullWindow - SidebarListMinWindow))
}

/** Whether the sidebar is a column of category icons in a [windowWidth] window (else the named list). */
fun settingsSidebarIcons(windowWidth: Dp): Boolean = windowWidth < SidebarListMinWindow

/** The sidebar's test tag. */
const val SettingsSidebarTag = "settingsSidebar"

private val SidebarFullWindow = 840.dp
private val SidebarListMinWindow = 480.dp
private val SidebarFullWidth = 200.dp
/** The list form's narrowest width (from a 480 dp window). */
internal val SidebarNarrowestListWidth = 136.dp

/** The icons column's width. */
internal val SidebarIconsWidth = 64.dp
