package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import java.nio.file.Paths

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
        onLaunchQuitAnimation = viewModel::setLaunchQuitAnimation,
        onUseBundledAdb = viewModel::useBundledAdb,
        onUseCustomAdb = viewModel::useCustomAdb,
        onBrowseAdb = { picker.launch(pickerScope) { files.chooseAdbFile()?.let { viewModel.useCustomAdb(it.toString()) } } },
        onBrowseCaptureDir = {
            val current = Paths.get(state.settings.captureDir)
            picker.launch(pickerScope) { files.chooseFolder(current)?.let { viewModel.setCaptureDir(it.toString()) } }
        },
        onShowOnboarding = viewModel::showOnboarding,
        onCheckUpdates = updateViewModel::check,
        onUpdate = updateViewModel::update,
        onCancelUpdate = updateViewModel::cancel,
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
    SettingsContent(state, updates, nav, actions, modifier)
    if (showAiSkillDialog) {
        AddAiSkillDialog(
            rows = state.aiSkill, home = System.getProperty("user.home"),
            onInstall = actions.onInstallAiSkill, onChooseFolder = actions.onChooseAiSkillFolder,
            onDismiss = { showAiSkillDialog = false },
        )
    }
    state.aiInstallResult?.let { AiInstallResultDialog(it, actions.onDismissAiInstall) }
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
    Row(modifier.fillMaxSize()) {
        SettingsListPane(
            matches = matches, selected = shown, query = nav.query, searching = nav.query.isNotBlank(),
            updateAvailable = updates.available != null, onQuery = actions.onQuery, onSelect = actions.onSelectCategory,
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val bindings = SettingsBindings(state, updates, actions)
        // Keyed by category, so filtering rows while typing updates in place instead of re-animating.
        AnimatedContent(
            targetState = match,
            contentKey = { it?.category?.id },
            transitionSpec = {
                val from = categories.indexOfFirst { it.id == initialState?.category?.id }
                val to = categories.indexOfFirst { it.id == targetState?.category?.id }
                val direction = if (to >= from) 1 else -1
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(220)) { direction * it / 12 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -direction * it / 24 })
            },
            modifier = Modifier.weight(1f),
            label = "settingsCategory",
        ) { shownMatch ->
            SettingsDetailPane(
                category = shownMatch?.category, entries = shownMatch?.entries.orEmpty(), query = nav.query,
                bindings = bindings,
            )
        }
    }
}
