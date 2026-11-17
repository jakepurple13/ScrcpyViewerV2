package com.programmersbox.scrcpyviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.navigation.railShowsLabels
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import com.programmersbox.scrcpyviewer.ui.navigation.RailIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.graphicsLayer
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.OvershootEasing
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.devices.DevicesScreen
import com.programmersbox.scrcpyviewer.ui.devices.info.DeviceInfoViewModel
import com.programmersbox.scrcpyviewer.ui.devices.DevicesViewModel
import com.programmersbox.scrcpyviewer.ui.devices.showNotices
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.recordResultMessage
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavDisplay
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.ui.navigation.MainWindowRequests
import com.programmersbox.scrcpyviewer.ui.onboarding.OnboardingDialog
import com.programmersbox.scrcpyviewer.ui.onboarding.PrivacyChoices
import com.programmersbox.scrcpyviewer.ui.onboarding.PrivacyPromptDialog
import com.programmersbox.scrcpyviewer.ui.onboarding.showPrivacyPrompt
import com.programmersbox.scrcpyviewer.ui.presets.NativePresetFileDialogs
import com.programmersbox.scrcpyviewer.ui.presets.PresetsScreen
import com.programmersbox.scrcpyviewer.ui.presets.PresetsViewModel
import com.programmersbox.scrcpyviewer.ui.settings.NativeSettingsFileDialogs
import com.programmersbox.scrcpyviewer.ui.settings.SettingsScreen
import com.programmersbox.scrcpyviewer.ui.settings.SettingsViewModel
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.programmersbox.scrcpyviewer.actions.ActionInbox
import org.koin.compose.koinInject
import androidx.compose.ui.graphics.compositeOver
import com.programmersbox.scrcpyviewer.ui.theme.LocalStyleTokens
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.drawBevel
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.programmersbox.scrcpyviewer.emulator.EmulatorLauncher
import com.programmersbox.scrcpyviewer.ui.common.openExternally
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorsViewModel

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
 * The navigation rail. Its selection pill is one shape drawn behind all items ([RailIndicator]) rather than each
 * item's own indicator, so it travels between destinations: the edge facing the move leads, the other follows.
 */
/** The Settings badge: pops in and out with [visible], and breathes while [pulse] (a background download). */
@Composable
private fun SettingsBadge(visible: Boolean, pulse: Boolean) {
    // The infinite transition runs only while pulsing.
    val pulseScale: State<Float>? = if (pulse) {
        rememberInfiniteTransition(label = "badgePulse").animateFloat(
            1f, 1.35f, infiniteRepeatable(tween(BADGE_PULSE_MILLIS, easing = EmphasizedDecelerate), RepeatMode.Reverse),
            label = "badgeScale",
        )
    } else {
        null
    }
    AnimatedVisibility(
        visible,
        enter = scaleIn(tween(OPEN_MILLIS, easing = OvershootEasing)) + fadeIn(),
        exit = scaleOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) + fadeOut(),
    ) {
        // Read in the layer only, so the pulse redraws without recomposing.
        Badge(Modifier.graphicsLayer {
            val s = pulseScale?.value ?: 1f
            scaleX = s
            scaleY = s
        })
    }
}

private const val BADGE_PULSE_MILLIS = 700

@Composable
internal fun Rail(navigator: TopLevelNavigator, settingsBadge: Boolean, windowWidth: Dp, settingsBadgePulse: Boolean = false) {
    val showLabels = railShowsLabels(windowWidth)
    val reduceMotion = LocalReduceMotion.current
    val railWidth by animateDpAsState(if (showLabels) 80.dp else 60.dp, if (reduceMotion) tween(120) else layoutSpring())
    val indicator = remember { RailIndicator() }
    val selected = navigator.current
    val selectedCenter = indicator.centers[selected]
    val halfHeight = with(LocalDensity.current) { IndicatorHeight.toPx() / 2 }
    LaunchedEffect(indicator, selected, selectedCenter, halfHeight) {
        selectedCenter?.let { indicator.moveTo(selected, it, halfHeight) }
    }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalStyleTokens.current
    // Glass fills the pill with the accent; Soft presses it in; Retro draws it as a pushed-in button.
    val pill = when (tokens.style) {
        ThemeStyle.Glass -> scheme.primary
        ThemeStyle.Soft -> tokens.softDark.copy(alpha = 0.45f).compositeOver(scheme.surface)
        ThemeStyle.Retro -> scheme.surfaceContainerHighest
        ThemeStyle.Material -> scheme.secondaryContainer
    }
    val onPill = when (tokens.style) {
        ThemeStyle.Glass -> scheme.onPrimary
        ThemeStyle.Soft -> scheme.primary
        ThemeStyle.Retro -> scheme.onSurface
        ThemeStyle.Material -> scheme.onSecondaryContainer
    }
    val container = if (tokens.style == ThemeStyle.Glass) scheme.surfaceContainerLow else scheme.surfaceContainer
    // Set during placement, before the items report their positions.
    val railCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        Modifier.fillMaxHeight()
            .background(container)
            .onPlaced { railCoordinates[0] = it }
            .drawBehind {
                if (!indicator.placed) return@drawBehind
                // Animated edges are read here, so the move only redraws.
                val width = IndicatorWidth.toPx()
                val top = indicator.top.value
                val topLeft = Offset(indicator.centerX - width / 2, top)
                val pillSize = Size(width, indicator.bottom.value - top)
                val corner = if (tokens.style == ThemeStyle.Retro) 0f else IndicatorHeight.toPx() / 2
                drawRoundRect(pill, topLeft = topLeft, size = pillSize, cornerRadius = CornerRadius(corner))
                if (tokens.style == ThemeStyle.Retro) drawBevel(tokens.bevel, raised = false, topLeft, pillSize)
            },
    ) {
        // The rail's own minimum width is 80 dp; a tight outer width wins over it.
        NavigationRail(Modifier.width(railWidth), containerColor = Color.Transparent, contentColor = contentColorFor(container)) {
            TopLevelRoute.all.forEach { route ->
                val icon = Modifier.onGloballyPositioned { coordinates ->
                    val rail = railCoordinates[0] ?: return@onGloballyPositioned
                    if (!rail.isAttached || !coordinates.isAttached) return@onGloballyPositioned
                    val center = rail.localPositionOf(coordinates, Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
                    if (indicator.centers[route] != center) indicator.centers[route] = center
                }
                NavigationRailItem(
                    selected = route == selected,
                    onClick = { navigator.select(route) },
                    icon = {
                        if (route == TopLevelRoute.Settings) {
                            BadgedBox(badge = { SettingsBadge(settingsBadge, settingsBadgePulse) }) {
                                Icon(route.icon, contentDescription = route.label, modifier = icon)
                            }
                        } else {
                            Icon(route.icon, contentDescription = route.label, modifier = icon)
                        }
                    },
                    label = {
                        AnimatedVisibility(
                            showLabels,
                            enter = if (reduceMotion) fadeIn(tween(120)) else fadeIn() + expandVertically(layoutSpring()),
                            exit = if (reduceMotion) fadeOut(tween(120)) else fadeOut() + shrinkVertically(layoutSpring()),
                        ) { Text(route.label) }
                    },
                    // The shared pill above replaces the item's own indicator.
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = Color.Transparent, selectedIconColor = onPill,
                    ),
                )
            }
        }
    }
}

/** Material 3's rail indicator size, centered on the icon. */
private val IndicatorWidth = 56.dp
private val IndicatorHeight = 32.dp

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
    deviceInfoViewModel: DeviceInfoViewModel = koinViewModel(),
    presetsViewModel: PresetsViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
    updateViewModel: UpdateViewModel = koinViewModel(),
    actionsViewModel: ActionsViewModel = koinViewModel(),
    mirrorController: MirrorController = koinInject(),
    appSettings: AppSettings = koinInject(),
    actionsNavigation: ActionsNavigation = koinInject(),
    mainWindowRequests: MainWindowRequests = koinInject(),
    inbox: ActionInbox = koinInject(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val update by updateViewModel.state.collectAsStateWithLifecycle()
    val deviceInfo by deviceInfoViewModel.state.collectAsStateWithLifecycle()
    val detailTab by deviceInfoViewModel.tab.collectAsStateWithLifecycle()
    // Read from the store, not the ViewModel's state, so the first frame already has the saved theme.
    val settings by appSettings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // No picker can be shown (Linux without zenity or kdialog, a failed native call): say so in the snackbar.
    val nativeDialogs = remember { nativeFileDialogs { message -> scope.launch { snackbar.showSnackbar(message) } } }
    val fileDialogs = remember { NativePresetFileDialogs(nativeDialogs) }
    val actionFileDialogs = remember { NativePresetFileDialogs(nativeDialogs, what = "actions") }
    val settingsDialogs = remember { NativeSettingsFileDialogs(nativeDialogs) }
    // Held here, with the other screens' ViewModels, so the Emulators section's state survives tab switches.
    val emulatorsViewModel: EmulatorsViewModel = koinViewModel()
    val emulatorLauncher: EmulatorLauncher = koinInject()
    // The launcher's results, from any screen: ready, didn't start (with its log), couldn't stop.
    LaunchedEffect(emulatorLauncher) {
        emulatorLauncher.notices.collect { notice ->
            val log = notice.logFile
            val result = snackbar.showSnackbar(
                notice.text,
                actionLabel = if (log != null) "Show log" else null,
                duration = if (log != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && log != null) openExternally(log.toString())
        }
    }
    // Recordings that end with no mirror window open for their device (closed window, app-wide view).
    LaunchedEffect(mirrorController) {
        mirrorController.recordingResults
            .filter { it.serial !in mirrorController.open.value }
            .collect { snackbar.showSnackbar(recordResultMessage(it.result)) }
    }
    // Collected here rather than by their screens: only the shown tab is composed, so a Wi-Fi connect or adb
    // restart result (or an Actions message) arriving while another tab is up would be lost.
    LaunchedEffect(devicesViewModel, snackbar) { devicesViewModel.showNotices(snackbar) }
    LaunchedEffect(actionsViewModel, snackbar) { actionsViewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Files AI tools dropped in the actions inbox: say what was added, changed or rejected.
    LaunchedEffect(inbox) { inbox.messages.collect { snackbar.showSnackbar(it) } }
    // A mirror window asked for the Actions screen: show it and open the requested action.
    LaunchedEffect(actionsNavigation, navigator, actionsViewModel, actionsPanes) {
        actionsNavigation.requests.collect { request ->
            navigator.select(TopLevelRoute.Actions)
            actionsViewModel.handle(request)
            // An opened action is pushed: a narrow window shows its editor over the list.
            if (request.new || request.actionId != null) actionsPanes.push()
        }
    }
    // The tray menu's Settings… (or a device it can't mirror): show that destination. Main.kt raises the window.
    LaunchedEffect(mainWindowRequests, navigator) {
        mainWindowRequests.requests.collect { request -> request.route?.let(navigator::select) }
    }
    // Inside AppWindow: follow the window's revealed theme, not the settings directly.
    ScrcpyViewerTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(
                LocalSnackbarHostState provides snackbar,
            ) {
                Row(Modifier.fillMaxSize()) {
                    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
                    Rail(
                        navigator,
                        settingsBadge = update.available != null,
                        windowWidth = windowWidth,
                        // A background download breathes the badge until it is Ready.
                        settingsBadgePulse = (update.state as? UpdateState.Downloading)?.background == true,
                    )
                    Box(Modifier.fillMaxSize()) {
                        TopLevelNavDisplay(navigator) { route ->
                            when (route) {
                                TopLevelRoute.Devices -> DevicesScreen(
                                    devicesViewModel,
                                    emulatorsViewModel,
                                    chooseSdkFolder = { settingsDialogs.chooseFolder("Choose SDK folder") },
                                    panes = devicesPanes,
                                    detailTab = detailTab,
                                    onDetailTab = deviceInfoViewModel::setTab,
                                    info = deviceInfo,
                                    onInfoActive = deviceInfoViewModel::setActive,
                                    onInfoRetry = deviceInfoViewModel::retry,
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
                val inProgress = (update.state as? UpdateState.Downloading)?.background == false ||
                    update.state is UpdateState.Installing
                if ((update.showPopup && settings.onboardingSeen) || inProgress) {
                    UpdateDialog(
                        ui = update,
                        onLater = {
                            val ready = update.state as? UpdateState.Ready
                            updateViewModel.later()
                            if (ready != null) {
                                scope.launch {
                                    snackbar.showSnackbar("${ready.release.version} installs the next time you quit Scrcpy Vwr.")
                                }
                            }
                        },
                        onUpdate = updateViewModel::update,
                        onCancel = updateViewModel::cancel,
                        onRestart = updateViewModel::restart,
                    )
                }
                // Null in a build without an analytics key: the guide has no consent page and there is no prompt.
                val privacy = if (settingsViewModel.telemetryEnabled) {
                    PrivacyChoices(
                        usage = settings.usageStats,
                        crash = settings.crashReports,
                        onUsage = settingsViewModel::setUsageStats,
                        onCrash = settingsViewModel::setCrashReports,
                    )
                } else {
                    null
                }
                if (!settings.onboardingSeen) {
                    // Leaves composition on close, so reopening from Settings starts at the first page.
                    var page by remember { mutableIntStateOf(0) }
                    OnboardingDialog(
                        page = page,
                        onPage = { page = it },
                        onFinish = settingsViewModel::finishOnboarding,
                        privacy = privacy,
                    )
                }
                // Users who saw the guide before it had the consent page get it once on its own, after any other
                // one-time dialog (the Actions intro, the update popup) has closed.
                val actionsIntroShowing = navigator.current == TopLevelRoute.Actions && !settings.actionsIntroSeen
                val otherDialog = actionsIntroShowing || (update.showPopup && settings.onboardingSeen) || inProgress
                if (privacy != null && showPrivacyPrompt(settings, settingsViewModel.telemetryEnabled, otherDialog)) {
                    PrivacyPromptDialog(privacy, onDone = settingsViewModel::finishPrivacyPrompt)
                }
            }
        }
    }
}
