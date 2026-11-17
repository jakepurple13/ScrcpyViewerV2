package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveListDetail
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.itemSwitchTransform
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import com.programmersbox.scrcpyviewer.ui.common.switchDirection
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    panes: ListDetailPanes = rememberListDetailPanes(),
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
        onSelectCategory = viewModel::selectCategory,
        onQuery = viewModel::setQuery,
    )
    SettingsContent(state, updates, nav, actions, modifier, panes = panes)
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
    panes: ListDetailPanes = rememberListDetailPanes(),
) {
    val matches = searchSettings(categories, nav.query)
    val shown = nav.shown(matches.map { it.category.id })
    val match = matches.firstOrNull { it.category.id == shown }
    // A category is always selected, but a narrow window still opens on the list: picking one pushes it.
    AdaptiveListDetail(
        panes = panes,
        listMinWidth = SettingsListWidth,
        listPreferredWidth = SettingsListWidth,
        hasSelection = true,
        resizable = false,
        gap = 1.dp,
        modifier = modifier,
        list = {
            // Typing filters in place: the list stays in view until a category is picked.
            SettingsListPane(
                matches = matches, selected = shown, query = nav.query, searching = nav.query.isNotBlank(),
                updateAvailable = updates.available != null, onQuery = actions.onQuery,
                onSelect = {
                    actions.onSelectCategory(it)
                    panes.push()
                },
            )
        },
        detail = { onBack ->
            val bindings = SettingsBindings(state, updates, actions)
            // Keyed by category, so filtering rows while typing updates in place instead of re-animating.
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = match,
                contentKey = { it?.category?.id },
                transitionSpec = {
                    val from = categories.indexOfFirst { it.id == initialState?.category?.id }
                    val to = categories.indexOfFirst { it.id == targetState?.category?.id }
                    itemSwitchTransform(switchDirection(from, to), reduceMotion)
                },
                modifier = Modifier.fillMaxSize(),
                label = "settingsCategory",
            ) { shownMatch ->
                SettingsDetailPane(
                    category = shownMatch?.category, entries = shownMatch?.entries.orEmpty(), query = nav.query,
                    bindings = bindings, onBack = onBack,
                )
            }
        },
    )
}

/** The category list's width beside the detail. */
private val SettingsListWidth = 200.dp
