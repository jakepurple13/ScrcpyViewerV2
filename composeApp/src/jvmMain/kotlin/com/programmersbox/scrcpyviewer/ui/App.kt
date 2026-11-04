package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.actions.ActionsNavigation
import com.programmersbox.scrcpyviewer.ui.actions.ActionsScreen
import com.programmersbox.scrcpyviewer.ui.actions.ActionsViewModel
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.devices.DevicesScreen
import com.programmersbox.scrcpyviewer.ui.devices.DevicesViewModel
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.recordResultMessage
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavDisplay
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.ui.onboarding.OnboardingDialog
import com.programmersbox.scrcpyviewer.ui.presets.AwtPresetFileDialogs
import com.programmersbox.scrcpyviewer.ui.presets.PresetsScreen
import com.programmersbox.scrcpyviewer.ui.presets.PresetsViewModel
import com.programmersbox.scrcpyviewer.ui.settings.AwtSettingsFileDialogs
import com.programmersbox.scrcpyviewer.ui.settings.SettingsScreen
import com.programmersbox.scrcpyviewer.ui.settings.SettingsViewModel
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.flow.filter
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val TopLevelRoute.label: String
    get() = when (this) {
        TopLevelRoute.Devices -> "Devices"
        TopLevelRoute.Presets -> "Presets"
        TopLevelRoute.Actions -> "Actions"
        TopLevelRoute.Settings -> "Settings"
    }

private val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TopLevelRoute.Devices -> Icons.Filled.PhoneAndroid
        TopLevelRoute.Presets -> Icons.Filled.Tune
        TopLevelRoute.Actions -> Icons.Filled.Bolt
        TopLevelRoute.Settings -> Icons.Filled.Settings
    }

/**
 * The main window's content. [navigator] and the pane states are hoisted (tests pass their own);
 * the pane states and ViewModels live above the navigation so divider positions and screen state survive tab switches.
 */
@Composable
fun App(
    navigator: TopLevelNavigator = remember { TopLevelNavigator() },
    devicesPanes: ListDetailPanes = rememberListDetailPanes(),
    presetsPanes: ListDetailPanes = rememberListDetailPanes(),
    actionsPanes: ListDetailPanes = rememberListDetailPanes(),
    devicesViewModel: DevicesViewModel = koinViewModel(),
    presetsViewModel: PresetsViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
    updateViewModel: UpdateViewModel = koinViewModel(),
    actionsViewModel: ActionsViewModel = koinViewModel(),
    mirrorController: MirrorController = koinInject(),
    appSettings: AppSettings = koinInject(),
    actionsNavigation: ActionsNavigation = koinInject(),
) {
    val update by updateViewModel.state.collectAsState()
    // Read from the store, not the ViewModel's state, so the first frame already has the saved theme.
    val settings by appSettings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val fileDialogs = remember { AwtPresetFileDialogs() }
    val actionFileDialogs = remember { AwtPresetFileDialogs(what = "actions") }
    val settingsDialogs = remember { AwtSettingsFileDialogs() }
    // Recordings that end with no mirror window open for their device (closed window, app-wide view).
    LaunchedEffect(mirrorController) {
        mirrorController.recordingResults
            .filter { it.serial !in mirrorController.open.value }
            .collect { snackbar.showSnackbar(recordResultMessage(it.result)) }
    }
    // A mirror window asked for the Actions screen: show it and open the requested action.
    LaunchedEffect(actionsNavigation, navigator, actionsViewModel, actionsPanes) {
        actionsNavigation.requests.collect { request ->
            navigator.select(TopLevelRoute.Actions)
            actionsViewModel.handle(request)
            // In the single-pane layout, an opened action shows the editor rather than the list.
            if (request.new || request.actionId != null) actionsPanes.navigation.openDetail()
        }
    }
    ScrcpyViewerTheme(settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        TopLevelRoute.all.forEach { route ->
                            NavigationRailItem(
                                selected = route == navigator.current,
                                onClick = { navigator.select(route) },
                                icon = {
                                    if (route == TopLevelRoute.Settings && update.available != null) {
                                        BadgedBox(badge = { Badge() }) { Icon(route.icon, contentDescription = route.label) }
                                    } else {
                                        Icon(route.icon, contentDescription = route.label)
                                    }
                                },
                                label = { Text(route.label) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        TopLevelNavDisplay(navigator) { route ->
                            when (route) {
                                TopLevelRoute.Devices -> DevicesScreen(
                                    devicesViewModel,
                                    panes = devicesPanes
                                )

                                TopLevelRoute.Presets -> PresetsScreen(
                                    presetsViewModel,
                                    fileDialogs,
                                    panes = presetsPanes
                                )

                                TopLevelRoute.Actions -> ActionsScreen(
                                    actionsViewModel,
                                    actionFileDialogs,
                                    introSeen = settings.actionsIntroSeen,
                                    onIntroSeen = settingsViewModel::finishActionsIntro,
                                    panes = actionsPanes
                                )

                                TopLevelRoute.Settings -> SettingsScreen(
                                    settingsViewModel,
                                    updateViewModel,
                                    settingsDialogs,
                                )
                            }
                        }
                        SnackbarHost(
                            snackbar,
                            Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        )
                    }
                }
                // Downloading and Installing show it even after "Later", since the user started them.
                val inProgress = update.state is UpdateState.Downloading || update.state is UpdateState.Installing
                if ((update.showPopup && settings.onboardingSeen) || inProgress) {
                    UpdateDialog(
                        ui = update,
                        onLater = updateViewModel::later,
                        onUpdate = updateViewModel::update,
                        onCancel = updateViewModel::cancel,
                    )
                }
                if (!settings.onboardingSeen) {
                    // Leaves composition on close, so reopening from Settings starts at the first page.
                    var page by remember { mutableIntStateOf(0) }
                    OnboardingDialog(
                        page = page,
                        onPage = { page = it },
                        onFinish = settingsViewModel::finishOnboarding,
                    )
                }
            }
        }
    }
}
