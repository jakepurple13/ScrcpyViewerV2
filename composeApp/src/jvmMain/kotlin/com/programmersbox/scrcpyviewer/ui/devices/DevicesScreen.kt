package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.ui.mirror.mirrorOrigin
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import com.programmersbox.scrcpyviewer.ui.window.launch.launchEntrance
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Devices
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.ui.common.openSwitcher
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.TooltipArea
import com.programmersbox.scrcpyviewer.ui.theme.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.AssistChip
import com.programmersbox.scrcpyviewer.ui.theme.FilterChip
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionSpec
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveListDetail
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveRow
import com.programmersbox.scrcpyviewer.ui.common.BarItem
import com.programmersbox.scrcpyviewer.ui.common.BarSpacer
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.ListPresentation
import com.programmersbox.scrcpyviewer.ui.common.ListStrip
import com.programmersbox.scrcpyviewer.ui.common.ListSwitcherChip
import com.programmersbox.scrcpyviewer.ui.common.LocalBarProbe
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.PriorityBar
import com.programmersbox.scrcpyviewer.ui.common.SelectionHighlightState
import com.programmersbox.scrcpyviewer.ui.common.StripItem
import com.programmersbox.scrcpyviewer.ui.common.closeOverlay
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.rememberSelectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.RowHighlight
import com.programmersbox.scrcpyviewer.ui.common.highlightContainer
import com.programmersbox.scrcpyviewer.ui.common.RowOrigin
import com.programmersbox.scrcpyviewer.ui.common.highlightPart
import com.programmersbox.scrcpyviewer.ui.common.rowOrigin
import com.programmersbox.scrcpyviewer.ui.common.rememberRowHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlightTarget
import com.programmersbox.scrcpyviewer.ui.common.rememberComposedViewModelStoreOwner
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import org.koin.compose.viewmodel.koinViewModel
import com.programmersbox.scrcpyviewer.ui.common.OptionRow
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.selectedRow
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import com.programmersbox.scrcpyviewer.ui.devices.info.DetailTab
import com.programmersbox.scrcpyviewer.ui.devices.info.DetailTabRow
import com.programmersbox.scrcpyviewer.ui.devices.info.DetailPane
import com.programmersbox.scrcpyviewer.ui.devices.info.DeviceInfoUiState
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorEvent
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorSection
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorsUiState
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorsViewModel
import com.programmersbox.scrcpyviewer.ui.common.rememberPickerGuard
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Path

@Composable
internal fun DevicesScreen(
    viewModel: DevicesViewModel,
    emulatorsViewModel: EmulatorsViewModel,
    /** Asks for the Android SDK folder (Choose SDK folder / Change); null when cancelled. */
    chooseSdkFolder: suspend () -> Path?,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
    detailTab: DetailTab = DetailTab.Options,
    onDetailTab: (DetailTab) -> Unit = {},
    info: DeviceInfoUiState = DeviceInfoUiState(),
    onInfoActive: (Boolean) -> Unit = {},
    onInfoRetry: () -> Unit = {},
) {
    val pageState by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    // The search field needs each key at once (see DevicesViewModel.query); the list may follow an event later.
    val state = pageState.copy(query = query)
    val emulators by emulatorsViewModel.state.collectAsStateWithLifecycle()
    // The AVD list is read each time the screen opens.
    LaunchedEffect(emulatorsViewModel) { emulatorsViewModel.onEvent(EmulatorEvent.Reload) }
    val pickerScope = rememberCoroutineScope()
    val picker = rememberPickerGuard()
    var pairing by remember { mutableStateOf(false) }
    val pairAnchor = remember { PairDialogAnchor() }
    pairAnchor.visibility.targetState = pairing
    // Kept while the dialog shrinks back into its button after closing.
    if (pairAnchor.showing) {
        // Owned by the dialog: closing it clears the ViewModel, which stops polling.
        val pairingVm = koinViewModel<WifiPairingViewModel>(viewModelStoreOwner = rememberComposedViewModelStoreOwner())
        LaunchedEffect(pairingVm) {
            pairingVm.results.collect {
                viewModel.notify(it)
                pairing = false
            }
        }
        val pairingState by pairingVm.state.collectAsStateWithLifecycle()
        WifiPairingDialog(
            pairingState, remember(pairingVm) { pairingVm.actions() }, onDismiss = { pairing = false },
            visibleState = pairAnchor.visibility, anchor = pairAnchor,
        )
    }
    DevicesContent(
        state = state,
        onSelect = viewModel::select,
        onOpen = viewModel::openDevice,
        onQuery = viewModel::setQuery,
        onGroup = viewModel::setGroup,
        onToggle = viewModel::toggle,
        onValue = viewModel::setValue,
        onSave = viewModel::save,
        onSaveAsNew = viewModel::saveAsNew,
        onMirror = viewModel::mirror,
        modifier = modifier,
        onPreset = viewModel::applyPreset,
        panes = panes,
        onPairWifi = { pairing = true },
        onConnectWifi = viewModel::connectOverWifi,
        onDisconnect = viewModel::disconnect,
        canMirror = viewModel::canMirror,
        onRestartAdb = viewModel::requestRestartAdb,
        restartSucceeded = pageState.restartSucceeded,
        pairAnchor = pairAnchor,
        onAdvanced = viewModel::setAdvanced,
        detailTab = detailTab,
        onDetailTab = onDetailTab,
        info = info,
        onInfoActive = onInfoActive,
        onInfoRetry = onInfoRetry,
        emulators = emulators,
        onEmulator = emulatorsViewModel::onEvent,
        onChooseSdk = {
            picker.launch(pickerScope) {
                chooseSdkFolder()?.let { emulatorsViewModel.onEvent(EmulatorEvent.SdkChosen(it.toString())) }
            }
        },
    )
    state.restartConfirm?.let { impact ->
        RestartAdbDialog(impact, onConfirm = viewModel::confirmRestartAdb, onDismiss = viewModel::cancelRestartAdb)
    }
}

/** Narrowest device column that still shows a device row's dot, name and serial. */
val DEVICE_LIST_MIN_WIDTH = 240.dp
private val DeviceListPreferredWidth = 270.dp

/** The option grid splits into two columns from this pane content width. */
private val TwoOptionColumnsWidth = 560.dp

/** Stateless Devices screen (Concept C: device column, searchable option grid, command footer). */
@Composable
internal fun DevicesContent(
    state: DevicesUiState,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onQuery: (String) -> Unit,
    onGroup: (OptionGroup?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
    onSave: () -> Unit,
    onSaveAsNew: (String) -> Unit,
    onMirror: () -> Unit,
    modifier: Modifier = Modifier,
    onPreset: (String?) -> Unit = {},
    /** Starts the preset menu open (for screenshots). */
    presetMenuOpen: Boolean = false,
    panes: ListDetailPanes = rememberListDetailPanes(),
    onPairWifi: () -> Unit = {},
    onConnectWifi: (String) -> Unit = {},
    onDisconnect: (String) -> Unit = {},
    /** Whether the right-click Mirror item is enabled for a serial. */
    canMirror: (String) -> Boolean = { serial -> state.devices.any { it.serial == serial && it.state == DeviceState.Device } },
    /** Starts with this row's right-click menu open (for screenshots). */
    contextMenuSerial: String? = null,
    /** The device column header's restart button. */
    onRestartAdb: () -> Unit = {},
    /** Whether the last adb restart worked (null: not known yet); the restart button shows a check when it did. */
    restartSucceeded: Boolean? = true,
    /** Where the Pair over Wi-Fi button is, so the pairing dialog can grow out of it. */
    pairAnchor: PairDialogAnchor? = null,
    /** Switches the detail pane between the simple view (false) and every option (true). */
    onAdvanced: (Boolean) -> Unit = {},
    /** The detail pane's Options | Info tab. */
    detailTab: DetailTab = DetailTab.Options,
    onDetailTab: (DetailTab) -> Unit = {},
    info: DeviceInfoUiState = DeviceInfoUiState(),
    /** True while the Info tab is on screen; the Info view model polls only then. */
    onInfoActive: (Boolean) -> Unit = {},
    onInfoRetry: () -> Unit = {},
    /** The Emulators section under the connected devices; null leaves it out. */
    emulators: EmulatorsUiState? = null,
    onEmulator: (EmulatorEvent) -> Unit = {},
    onChooseSdk: () -> Unit = {},
    motion: Boolean = remember { !systemReducesMotion() },
    /** The wall clock the emulators' boot timers read (screenshots pass a fixed one). */
    emulatorNow: () -> Long = System::currentTimeMillis,
) {
    val rowActions = RowActions(onOpen = onOpen, onConnectWifi = onConnectWifi, onDisconnect = onDisconnect, canMirror = canMirror)
    val reduceMotion = LocalReduceMotion.current
    // The detail pane's view switch has a motion of its own; an emulator's title change follows this one.
    val titleMotion = motion
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Device options can't be saved: $it", color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        AdaptiveListDetail(
            panes = panes,
            listMinWidth = DEVICE_LIST_MIN_WIDTH,
            listPreferredWidth = DeviceListPreferredWidth,
            hasSelection = state.selectedSerial != null,
            list = { presentation ->
                // Rows and avatars cross-fade while the list's width glides between them.
                Crossfade(presentation, animationSpec = tween(if (reduceMotion) 150 else 220), label = "devicePresentation") { shown ->
                    when (shown) {
                        ListPresentation.Full -> DeviceColumn(
                            state,
                            onSelect = {
                                onSelect(it)
                                panes.navigation.openDetail()
                                panes.closeOverlay()
                            },
                            onSelect, rowActions, onPairWifi,
                            contextMenuSerial, onRestartAdb, restartSucceeded, pairAnchor,
                            emulators, onEmulator, onChooseSdk, motion, emulatorNow,
                        )
                        ListPresentation.Strip -> DeviceStrip(
                            state, emulators, onSelect, onShowList = panes.navigation::backToList,
                            onShowEmulators = {
                                onEmulator(EmulatorEvent.SetExpanded(true))
                                panes.openSwitcher()
                            },
                            onRestartAdb, restartSucceeded, onPairWifi, pairAnchor,
                        )
                    }
                }
            },
            detail = { openSwitcher ->
                // Item view only: the open device leaving adb's list leaves item view too.
                if (openSwitcher != null) BackToListWhenGone(state, panes.navigation::backToList)
                Column(Modifier.fillMaxSize()) {
                    val shownDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
                    DetailTabRow(
                        state.selectedSerial != null, detailTab, onDetailTab, onBack = null,
                        leading = openSwitcher?.let { open ->
                            {
                                val title = shownDevice?.let { connectedTitle(it, emulators?.avdNames?.get(it.serial)) } ?: "Devices"
                                ListSwitcherChip(title, open)
                            }
                        },
                    )
                    // Each device's content (and none) animates in and out as a whole; `shown` is that device's state.
                    DetailPane(
                        state, { it.selectedSerial }, state.devices.map { it.serial }, detailTab, info, onInfoActive, onInfoRetry,
                        Modifier.weight(1f).fillMaxWidth(),
                        empty = { emptyModifier ->
                            Box(emptyModifier, contentAlignment = Alignment.Center) {
                                Text("Select a device to choose its options", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    ) { shown, tabModifier ->
                        ViewSwitch(shown.advanced, tabModifier) { advanced, motion ->
                            if (advanced) {
                                Box(Modifier.fillMaxSize()) {
                                    ViewSwitchContainer(motion, isPane = true, Modifier.matchParentSize())
                                    Column(Modifier.fillMaxSize()) {
                                        OptionsPane(
                                            shown, onQuery, onGroup, onToggle, onValue,
                                            // False while this device's pane leaves for another's.
                                            current = shown.selectedSerial == state.selectedSerial,
                                            onSimple = { onAdvanced(false) },
                                            modifier = Modifier.weight(1f).advancedContentMotion(motion),
                                        )
                                        Footer(shown, onSave, onSaveAsNew, onMirror, onPreset, presetMenuOpen, onConnectWifi, motion)
                                    }
                                }
                            } else {
                                SimpleDevicePane(
                                    shown, onPreset, onMirror, onConnectWifi,
                                    onAdvanced = { onAdvanced(true) }, onBack = null, presetMenuOpen = presetMenuOpen,
                                    motion = motion,
                                    avdName = emulators?.avdNames?.get(shown.selectedSerial),
                                    titleMotion = titleMotion,
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Calls [backToList] when the device that was open leaves adb's list (unplugged, or adb restarted): item view would
 * otherwise jump to whichever device is selected next.
 */
@Composable
private fun BackToListWhenGone(state: DevicesUiState, backToList: () -> Unit) {
    val open = remember { arrayOf(state.selectedSerial) }
    val serials = state.devices.map { it.serial }
    LaunchedEffect(state.selectedSerial, serials) {
        val was = open[0]
        open[0] = state.selectedSerial
        if (was != null && was !in serials) backToList()
    }
}

/** What a device row's double-click and right-click menu do. */
internal class RowActions(
    val onOpen: (String) -> Unit,
    val onConnectWifi: (String) -> Unit,
    val onDisconnect: (String) -> Unit,
    val canMirror: (String) -> Boolean,
)

@Composable
private fun DeviceColumn(
    state: DevicesUiState,
    onSelect: (String) -> Unit,
    /** Selects without opening the detail pane (right-click). */
    onHighlight: (String) -> Unit,
    actions: RowActions,
    onPairWifi: () -> Unit,
    contextMenuSerial: String?,
    onRestartAdb: () -> Unit,
    restartSucceeded: Boolean?,
    pairAnchor: PairDialogAnchor?,
    emulators: EmulatorsUiState?,
    onEmulator: (EmulatorEvent) -> Unit,
    onChooseSdk: () -> Unit,
    motion: Boolean,
    emulatorNow: () -> Long,
) {
    val live = state.adbConnected && !state.restartingAdb
    // Emulators still booting are left out until they are ready; they then arrive like any new device.
    val hidden = emulators?.hiddenSerials.orEmpty()
    val devices = remember(state.devices, hidden) { connectedRows(state.devices, hidden) }
    val list = rememberDeviceEntries(devices, live)
    val entries = list.entries
    val highlight = rememberRowHighlight()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.adbConnected && !state.restartingAdb) deviceSummary(devices) else "Devices",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            RestartAdbButton(restarting = state.restartingAdb, onClick = onRestartAdb, succeeded = restartSucceeded)
        }
        // The device rows (or what stands in for them) and the Emulators section scroll together.
        // Material: the selected row's highlight slides between rows; it is drawn in the scrolled content, so it
        // scrolls with them (see RowHighlight). The other styles mark the selected row themselves (selectedRow).
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).then(
                if (currentStyle == ThemeStyle.Material) Modifier.highlightContainer(highlight) else Modifier,
            ),
        ) {
            when {
                !live -> Row(
                    Modifier.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Starting adb…", style = MaterialTheme.typography.bodyMedium)
                }
                entries.isEmpty() -> Text(
                    "No devices. Connect a device with USB debugging on, or pair one over Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
                // Each row carries its own gap below it, so a leaving row's gap collapses along with it.
                else -> entries.forEachIndexed { index, entry ->
                    AnimatedDeviceItem(entry, onExited = list::exited) { glow ->
                        val device = entry.device
                        DeviceRow(
                            device = device,
                            selected = device.serial == state.selectedSerial,
                            onClick = { onSelect(device.serial) },
                            onSecondaryClick = { onHighlight(device.serial) },
                            menu = { onDismiss -> DeviceMenu(device, state, actions, onDismiss) },
                            menuInitiallyOpen = entry.present && device.serial == contextMenuSerial,
                            onDoubleClick = { actions.onOpen(device.serial) },
                            // A leaving row is already gone from adb: no clicks, double-clicks or menu while it exits.
                            interactive = entry.present,
                            status = chipStatus(device, switching = device.serial in state.switching),
                            glow = glow,
                            highlight = highlight,
                            avdName = emulators?.avdNames?.get(device.serial),
                            motion = motion,
                            // Icon unfold's launch: the rows fade up in a stagger as the window appears.
                            modifier = Modifier.padding(bottom = 6.dp),
                            entrance = Modifier.launchEntrance(index),
                        )
                    }
                }
            }
            if (emulators != null) {
                EmulatorSection(
                    emulators, onEmulator, onChooseSdk,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), motion = motion, now = emulatorNow,
                )
            }
        }
        val pairOrigin = rememberPairOrigin(pairAnchor, iconOnly = false)
        FilledTonalButton(
            onClick = {
                pairOrigin?.claim()
                onPairWifi()
            },
            modifier = Modifier.fillMaxWidth().pairDialogOrigin(pairOrigin),
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pair over Wi-Fi")
        }
    }
}

/** A device row's status dot (and its strip avatar's badge): ready, waiting for approval, or a problem. */
@Composable
private fun statusDotColor(state: DeviceState): Color = when (state) {
    DeviceState.Device -> MaterialTheme.colorScheme.primary
    DeviceState.Unauthorized -> warnColor()
    DeviceState.Offline, DeviceState.Other -> MaterialTheme.colorScheme.error
}

/** The strip's emulator tile: opens the full list at the Emulators section. */
private object EmulatorTileKey

/**
 * The device list beside an open device: an avatar per connected device, then, after an "AVD" divider, the emulator
 * tile (the AVD count on it; it opens the full list over the device with the Emulators section expanded) and an
 * avatar per running emulator, selectable like a device. The restart button and an icon-only Pair over Wi-Fi sit
 * under them.
 */
@Composable
private fun DeviceStrip(
    state: DevicesUiState,
    emulators: EmulatorsUiState?,
    onSelect: (String) -> Unit,
    onShowList: () -> Unit,
    onShowEmulators: () -> Unit,
    onRestartAdb: () -> Unit,
    restartSucceeded: Boolean?,
    onPairWifi: () -> Unit,
    pairAnchor: PairDialogAnchor?,
) {
    val (running, devices) = connectedRows(state.devices, emulators?.hiddenSerials.orEmpty()).partition { isEmulatorSerial(it.serial) }
    @Composable
    fun deviceItem(device: Device, title: String, divider: String?) = StripItem(
        key = device.serial,
        label = title,
        avatar = {
            Icon(
                if (title.contains("Tablet", ignoreCase = true)) Icons.Filled.TabletAndroid else Icons.Filled.PhoneAndroid,
                contentDescription = null,
                Modifier.size(22.dp),
            )
        },
        badgeColor = statusDotColor(device.state),
        dividerLabel = divider,
    )
    val tile = emulators?.let { avds ->
        StripItem(
            key = EmulatorTileKey,
            label = "Emulators",
            avatar = { Icon(Icons.Filled.Devices, contentDescription = null, Modifier.size(22.dp)) },
            dividerLabel = "AVD",
            badgeCount = if (avds.sdk is SdkLookup.Found) avds.rows.size.takeIf { it > 0 } else null,
        )
    }
    val items = devices.map { deviceItem(it, deviceTitle(it), divider = null) } +
        listOfNotNull(tile) +
        running.mapIndexed { i, device ->
            deviceItem(device, connectedTitle(device, emulators?.avdNames?.get(device.serial)), divider = if (tile == null && i == 0) "AVD" else null)
        }
    ListStrip(
        items, state.selectedSerial,
        onSelect = { key -> if (key == EmulatorTileKey) onShowEmulators() else onSelect(key as String) },
        onShowList = onShowList,
        modifier = Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        RestartAdbButton(restarting = state.restartingAdb, onClick = onRestartAdb, succeeded = restartSucceeded)
        val pairOrigin = rememberPairOrigin(pairAnchor, iconOnly = true)
        FilledTonalIconButton(
            onClick = {
                pairOrigin?.claim()
                onPairWifi()
            },
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).size(44.dp).pairDialogOrigin(pairOrigin),
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = "Pair over Wi-Fi", Modifier.size(20.dp))
        }
    }
}

/** The devices the connected rows show: all but the emulators still booting ([hidden]). */
internal fun connectedRows(devices: List<Device>, hidden: Set<String>): List<Device> =
    if (hidden.isEmpty()) devices else devices.filter { it.serial !in hidden }

/**
 * The right-click menu's items: Mirror, plus Connect over Wi-Fi (USB) or Disconnect (Wi-Fi); an emulator gets no Wi-Fi
 * item. Internal for screenshots.
 */
@Composable
internal fun DeviceMenu(device: Device, state: DevicesUiState, actions: RowActions, onDismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Mirror") },
        onClick = { onDismiss(); actions.onOpen(device.serial) },
        enabled = actions.canMirror(device.serial),
        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon = { Text("double-click", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) },
    )
    if (device.isTcp) {
        DropdownMenuItem(
            text = { Text("Disconnect") },
            onClick = { onDismiss(); actions.onDisconnect(device.serial) },
            leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null, Modifier.size(18.dp)) },
        )
    } else if (!isEmulatorSerial(device.serial)) {
        // An emulator is reached over its own adb port: it has no Wi-Fi to switch to.
        val switching = device.serial in state.switching
        val onWifi = isOnWifi(device, state.devices)
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        switching -> "Connecting over Wi-Fi…"
                        onWifi -> "Connected over Wi-Fi"
                        else -> "Connect over Wi-Fi"
                    },
                )
            },
            onClick = { onDismiss(); actions.onConnectWifi(device.serial) },
            enabled = !switching && !onWifi && device.state == DeviceState.Device,
            leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DeviceRow(
    device: Device,
    selected: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: () -> Unit,
    menu: @Composable (onDismiss: () -> Unit) -> Unit,
    menuInitiallyOpen: Boolean,
    onDoubleClick: () -> Unit,
    status: ChipStatus,
    /** Plays the "just arrived" tint: the device connected after the list first loaded. */
    glow: Boolean,
    /** The list's sliding selection (Material); the selected row marks itself as its target. */
    highlight: RowHighlight,
    modifier: Modifier = Modifier,
    /** The row's entrance; it moves the row in a layer, so the highlight's bounds are read outside it. */
    entrance: Modifier = Modifier,
    /** False while the row plays its exit: it ignores clicks and opens no menu. */
    interactive: Boolean = true,
    /** An emulator's AVD display name once the launcher knows it: its title instead of the system model. */
    avdName: String? = null,
    motion: Boolean = true,
) {
    var menuAt by remember { mutableStateOf(if (menuInitiallyOpen) Offset(120f, 40f) else null) }
    val shape = styledCorner(12.dp)
    val emulator = isEmulatorSerial(device.serial)
    val dot = statusDotColor(device.state)
    val material = currentStyle == ThemeStyle.Material
    val hint = deviceHint(device)
    val origin = remember { RowOrigin() }
    Box(
        modifier
            .then(if (material) Modifier.selectionHighlightTarget(highlight.state, selected).rowOrigin(highlight, origin) else Modifier)
            .then(entrance),
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Press) { event ->
                if (interactive && event.buttons.isSecondaryPressed) {
                    onSecondaryClick()
                    menuAt = event.changes.first().position
                }
            }
            .clip(shape)
            .arrivalGlow(glow, MaterialTheme.colorScheme.surface)
            // Material: the sliding highlight, over this row's surface. The other styles keep their own selected look.
            .then(if (material) Modifier.highlightPart(highlight, shape, origin) else if (selected) Modifier.selectedRow(shape) else Modifier)
            .combinedClickable(enabled = interactive, onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(
            Modifier.weight(1f).then(
                // An emulator's title can change while it shows (its AVD is matched): the row's height follows.
                if (emulator && motion) Modifier.animateContentSize(tween(EMULATOR_TITLE_MILLIS, easing = EmphasizedDecelerate)) else Modifier,
            ),
        ) {
            val title = connectedTitle(device, avdName)
            // The title line (name and tag) and the serial under it; the serial is the title when there is no name.
            val titleBlock = @Composable { shownTitle: String ->
                Column {
                    val serialText = @Composable { modifier: Modifier ->
                        Text(
                            device.serial,
                            style = if (shownTitle == device.serial) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = modifier,
                        )
                    }
                    // The name keeps 80 dp; below that the chip goes under it.
                    AdaptiveRow(
                        text = {
                            if (shownTitle != device.serial) {
                                Text(shownTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                serialText(Modifier)
                            }
                        },
                        // An emulator's connection is no USB cable: its tag says what it is (the dot gives its state).
                        control = { if (emulator) EmulatorTag() else StatusChip(status) },
                        textMin = 80.dp,
                        gap = 6.dp,
                        packInline = true,
                    )
                    if (shownTitle != device.serial) serialText(Modifier)
                }
            }
            if (emulator) {
                // Serial or model, then the AVD's name once matched: the whole title block crossfades (the column's
                // height follows), never a swap.
                Crossfade(title, animationSpec = if (motion) tween(EMULATOR_TITLE_MILLIS) else snap(), label = "emulatorTitle") {
                    titleBlock(it)
                }
            } else {
                titleBlock(title)
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = dot, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    menuAt?.let { at ->
        // A zero-size anchor at the pointer, so the menu opens where the user clicked.
        Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
            DropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) { menu { menuAt = null } }
        }
    }
    }
}

/** An emulator row's title crossfades to its AVD's name (and its row's height follows) over this long. */
internal const val EMULATOR_TITLE_MILLIS = 250

/** The hint an offline emulator shows instead of the cable hint. */
internal const val EMULATOR_OFFLINE_HINT = "Emulator offline. Stop and start it again from Emulators."

/** A connected row's title: an emulator's AVD name once known, else [deviceTitle]. */
internal fun connectedTitle(device: Device, avdName: String?): String =
    avdName?.takeIf { isEmulatorSerial(device.serial) && it.isNotBlank() } ?: deviceTitle(device)

/** The line under a connected row that needs attention; null for a ready device. */
internal fun deviceHint(device: Device): String? = when (device.state) {
    DeviceState.Device -> null
    DeviceState.Unauthorized -> "Accept the USB debugging prompt on the device"
    DeviceState.Offline -> if (isEmulatorSerial(device.serial)) EMULATOR_OFFLINE_HINT else "Reconnect the cable or run adb reconnect"
    DeviceState.Other -> device.rawState
}

/** The "Emulator" pill after an emulator's name in the connected rows. */
@Composable
private fun EmulatorTag() {
    Text(
        "Emulator",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clip(styledCorner(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun OptionsPane(
    state: DevicesUiState,
    onQuery: (String) -> Unit,
    onGroup: (OptionGroup?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
    current: Boolean,
    onSimple: () -> Unit,
    modifier: Modifier,
) {
    var searchOpen by remember { mutableStateOf(false) }
    // Another device's pane takes over: its popup must not linger while this one animates out.
    LaunchedEffect(current) { if (!current) searchOpen = false }
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Folds Simple view, then the search field, into More; the folded search opens in a popup.
        Box {
            PriorityBar(
                listOf(
                    BarItem("simple", priority = 2, menuLabel = "Simple view", onMenuClick = onSimple) {
                        TextButton(onSimple, modifier = Modifier.offset(x = (-8).dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, Modifier.size(18.dp))
                            Text("Simple view")
                        }
                    },
                    BarItem("title", priority = 0, menuLabel = "Options", onMenuClick = null, minWidth = 110.dp, grow = true) {
                        Text("Options", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    BarItem("search", priority = 1, menuLabel = "Search options", onMenuClick = { searchOpen = true }) {
                        // The bar shows the field again (the window widened): it replaces the popup.
                        if (!LocalBarProbe.current) LaunchedEffect(Unit) { searchOpen = false }
                        OptionSearchField(state.query, onQuery, Modifier.widthIn(max = 260.dp))
                    },
                ),
                gap = 10.dp,
            )
            if (searchOpen && current) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { searchOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(focus) { focus.requestFocus() }
                    Surface(shape = styledCorner(28.dp), shadowElevation = 6.dp, modifier = Modifier.testTag("option-search-popup")) {
                        OptionSearchField(state.query, onQuery, Modifier.widthIn(max = 260.dp).focusRequester(focus))
                    }
                }
            }
        }
        GroupChips(state.groupFilter, onGroup)
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (state.visibleOptions.isEmpty()) {
                Text("No options match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            } else {
                val twoColumns = maxWidth >= TwoOptionColumnsWidth
                val (left, right) = remember(state.visibleOptions) { splitColumns(state.visibleOptions) }
                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    LookaheadScope {
                        // The same movable items in either layout, so each glides to its place when the columns change.
                        val items = rememberOptionItems(this, state, onToggle, onValue)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            if (twoColumns) {
                                OptionColumn(left, items, Modifier.weight(1f))
                                OptionColumn(right, items, Modifier.weight(1f))
                            } else {
                                OptionColumn(left + right, items, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text("Search ${OptionCatalog.all.size} options", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = styledCorner(28.dp),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupChips(selected: OptionGroup?, onGroup: (OptionGroup?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FilterChip(selected == null, { onGroup(null) }, label = { Text("All") })
        OptionGroup.entries.forEach { g ->
            FilterChip(selected == g, { onGroup(g) }, label = { Text(g.label) })
        }
    }
}

private data class GroupBlock(val group: OptionGroup, val options: List<OptionSpec>)

/** Splits the visible options into two columns of whole groups, balanced by row count. */
private fun splitColumns(options: List<OptionSpec>): Pair<List<GroupBlock>, List<GroupBlock>> {
    val blocks = options.groupBy { it.group }.map { GroupBlock(it.key, it.value) }.sortedBy { it.group.ordinal }
    val total = blocks.sumOf { it.options.size + 1 }
    val left = ArrayList<GroupBlock>()
    var count = 0
    for (b in blocks) {
        val size = b.options.size + 1
        // Put a group on the left while that keeps the left column closer to half than leaving it out.
        if (left.isEmpty() || kotlin.math.abs(count + size - total / 2.0) <= kotlin.math.abs(count - total / 2.0)) {
            left += b
            count += size
        }
    }
    return left to blocks.filter { it !in left }
}

/**
 * Movable content for every group header and option row, so an item keeps its node (and its bounds animation) when a
 * search or group filter moves it to a new position, even into the other column.
 */
private class OptionItems(
    val header: (OptionGroup) -> @Composable () -> Unit,
    val row: (OptionSpec) -> @Composable () -> Unit,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberOptionItems(
    lookahead: LookaheadScope,
    state: DevicesUiState,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
): OptionItems {
    val currentState by rememberUpdatedState(state)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentValue by rememberUpdatedState(onValue)
    return remember(lookahead) {
        val headers = HashMap<OptionGroup, @Composable () -> Unit>()
        val rows = HashMap<String, @Composable () -> Unit>()
        OptionItems(
            header = { group ->
                headers.getOrPut(group) {
                    movableContentOf {
                        Text(
                            group.label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.optionMotion(lookahead).padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
            },
            row = { spec ->
                rows.getOrPut(spec.flag) {
                    movableContentOf {
                        val s = currentState
                        OptionRow(
                            spec = spec,
                            fieldKey = s.selectedSerial,
                            checked = spec.flag in s.values.entries,
                            value = s.values.entries[spec.flag],
                            error = s.errors[spec.flag],
                            onCheckedChange = { currentToggle(spec.flag, it) },
                            onValueChange = { currentValue(spec.flag, it) },
                            modifier = Modifier.optionMotion(lookahead),
                        )
                    }
                }
            },
        )
    }
}

/**
 * Slides an option to its new bounds when the visible set or the column count changes, and fades it in when it first
 * appears. Under reduced motion it jumps.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.optionMotion(lookahead: LookaheadScope): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(220)) }
    val glide = if (LocalReduceMotion.current) this else animateBounds(lookahead, boundsTransform = { _, _ -> layoutSpring() })
    return glide.graphicsLayer { this.alpha = alpha.value }
}

@Composable
private fun OptionColumn(blocks: List<GroupBlock>, items: OptionItems, modifier: Modifier) {
    Column(modifier) {
        blocks.forEach { block ->
            items.header(block.group)()
            block.options.forEach { spec -> items.row(spec)() }
        }
    }
}

@Composable
private fun Footer(
    state: DevicesUiState,
    onSave: () -> Unit,
    onSaveAsNew: (String) -> Unit,
    onMirror: () -> Unit,
    onPreset: (String?) -> Unit,
    presetMenuOpen: Boolean,
    onConnectWifi: (String) -> Unit,
    motion: ViewSwitchMotion? = null,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(presetMenuOpen) }
    val modified = state.isModified
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp)) {
        // Everything but the shared controls fades with the pane; those glide in from the simple view instead.
        HorizontalDivider(Modifier.advancedContentMotion(motion), color = MaterialTheme.colorScheme.outlineVariant)
        // Out of room, Save as new preset folds into More first, then the preset chip, Save and Wi-Fi; Mirror never.
        // An item measured by the bar (LocalBarProbe) leaves out its shared bounds and mirror origin: those exist once.
        val items = buildList {
            add(
                BarItem("preset", priority = 3, menuLabel = "Preset: ${state.presetLabel}", onMenuClick = { presetOpen = true }.takeIf { state.selectedSerial != null }) {
                    PresetChip(state, onOpen = { presetOpen = true }, Modifier.sharedPartOutsideProbe(motion, SharedPart.Preset))
                },
            )
            if (modified && state.hasBasePreset) {
                add(BarItem("save", priority = 2, menuLabel = "Save", onMenuClick = onSave) {
                    TextButton(onSave, Modifier.advancedContentMotionOutsideProbe(motion)) { Text("Save") }
                })
            }
            if (modified || !state.hasBasePreset) {
                add(BarItem("saveAs", priority = 5, menuLabel = "Save as new preset", onMenuClick = { naming = true }) {
                    TextButton({ naming = true }, Modifier.advancedContentMotionOutsideProbe(motion)) { Text("Save as new preset") }
                })
            }
            add(BarSpacer)
            // An emulator has no Wi-Fi to switch to: no button.
            state.devices.firstOrNull { it.serial == state.selectedSerial && !isEmulatorSerial(it.serial) }?.let { device ->
                val wifi = wifiButtonState(device, state)
                val connect = { onConnectWifi(device.serial) }
                add(BarItem("wifi", priority = 1, menuLabel = wifi.label, onMenuClick = connect.takeIf { wifi.enabled }) {
                    OutlinedButton(
                        onClick = connect,
                        enabled = wifi.enabled,
                        modifier = Modifier.sharedPartOutsideProbe(motion, SharedPart.Wifi),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(wifi.label, maxLines = 1)
                    }
                })
            }
            add(BarItem("mirror", priority = 0, menuLabel = "Mirror", onMenuClick = onMirror.takeIf { state.canMirror }) {
                val probe = LocalBarProbe.current
                Button(
                    onClick = onMirror,
                    enabled = state.canMirror,
                    modifier = if (probe) Modifier else Modifier.sharedPart(motion, SharedPart.Mirror).mirrorOrigin(state.selectedSerial),
                ) {
                    Text("Mirror")
                }
            })
        }
        Box(Modifier.padding(top = 10.dp)) {
            PriorityBar(items, Modifier.fillMaxWidth(), gap = 10.dp)
            // Outside the bar, which composes its items twice; it opens under the bar's start, where the chip sits.
            DropdownMenu(expanded = presetOpen, onDismissRequest = { presetOpen = false }) {
                PresetMenuItem("No preset", checked = state.basePresetId == null) { presetOpen = false; onPreset(null) }
                state.presets.forEach { preset ->
                    PresetMenuItem(preset.name, checked = state.basePresetId == preset.id) { presetOpen = false; onPreset(preset.id) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).advancedContentMotion(motion),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = styledCorner(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                SelectionContainer {
                    Text(
                        state.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            CopyButton(text = { state.command })
        }
    }
    if (naming) SaveAsDialog(onDismiss = { naming = false }, onConfirm = { onSaveAsNew(it); naming = false })
}

/** The footer's "Preset: …" chip; [onOpen] opens the preset menu, which the footer keeps outside its bar. */
@Composable
private fun PresetChip(state: DevicesUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onOpen,
        enabled = state.selectedSerial != null,
        label = { Text("Preset: ${state.presetLabel}", maxLines = 1) },
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose preset", Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

/** [sharedPart], except while a [PriorityBar] only measures the item (a shared element must exist once). */
@Composable
private fun Modifier.sharedPartOutsideProbe(motion: ViewSwitchMotion?, part: SharedPart): Modifier =
    if (LocalBarProbe.current) this else sharedPart(motion, part)

/** [advancedContentMotion], except while a [PriorityBar] only measures the item. */
@Composable
private fun Modifier.advancedContentMotionOutsideProbe(motion: ViewSwitchMotion?): Modifier =
    if (LocalBarProbe.current) this else advancedContentMotion(motion)

@Composable
internal fun PresetMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Filled.Check, contentDescription = "Current preset", Modifier.size(18.dp))
            else Spacer(Modifier.size(18.dp))
        },
    )
}

@Composable
private fun SaveAsDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as new preset") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Preset name") })
        },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * The device column header's "Restart adb server" icon. While a restart runs the button is disabled and the icon
 * spins; when it ends the icon slows to rest with a small overshoot, and a check pops in on the button for a moment
 * when [succeeded] says the restart worked (null: not known yet, so it waits briefly for the answer).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RestartAdbButton(restarting: Boolean, onClick: () -> Unit, succeeded: Boolean? = true) {
    val label = if (restarting) "Restarting adb…" else "Restart adb server"
    var check by remember { mutableStateOf(false) }
    val currentSucceeded by rememberUpdatedState(succeeded)
    var settledAt by remember { mutableStateOf(0L) }
    val rotation = rememberSpinSettle(restarting) { settledAt = System.nanoTime() }
    LaunchedEffect(restarting) { if (restarting) check = false }
    LaunchedEffect(settledAt) {
        if (settledAt == 0L) return@LaunchedEffect
        val worked = withTimeoutOrNull(RESTART_OUTCOME_WAIT_MILLIS) {
            snapshotFlow { currentSucceeded }.filterNotNull().first()
        }
        if (worked == true) {
            check = true
            delay(RESTART_CHECK_MILLIS)
            check = false
        }
    }
    TooltipArea(
        tooltip = {
            Surface(shape = styledCorner(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        Box {
            IconButton(
                onClick = onClick,
                enabled = !restarting,
                modifier = Modifier.size(32.dp),
                // The spinning icon is the progress indicator, so it keeps an active color while the button is disabled.
                colors = IconButtonDefaults.iconButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = label,
                    Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
                )
            }
            RestartCheckBadge(check, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
        }
    }
}

/** Asks before restarting adb when that would close mirror windows or drop Wi-Fi devices. */
@Composable
internal fun RestartAdbDialog(impact: RestartAdbImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val closesMirrors = impact.mirrors.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restart adb server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Restarting adb will:")
                if (closesMirrors) Text("• ${restartLine("Close", impact.mirrors, "open mirror")}")
                if (impact.wifiDevices.isNotEmpty()) {
                    Text("• ${restartLine("Disconnect", impact.wifiDevices, "Wi-Fi device")}. Reconnect ${if (impact.wifiDevices.size == 1) "it" else "them"} from Pair over Wi-Fi.")
                }
            }
        },
        confirmButton = {
            if (closesMirrors) {
                Button(
                    onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Close mirrors and restart") }
            } else {
                TextButton(onConfirm) { Text("Restart") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** "Close 2 open mirrors (Pixel 8, Galaxy Tab)". */
internal fun restartLine(verb: String, names: List<String>, noun: String): String =
    "$verb ${names.size} $noun${if (names.size == 1) "" else "s"} (${names.joinToString()})"

/**
 * Shows the Wi-Fi and adb notices in [host], with their Mirror or Restart adb action. Collected by App, not this
 * screen: only the shown tab is composed, so a result arriving while another tab is up would be lost.
 */
internal suspend fun DevicesViewModel.showNotices(host: SnackbarHostState) {
    notices.collect { notice ->
        val result = host.showSnackbar(
            notice.text,
            actionLabel = when {
                notice.restartAdb -> "Restart adb"
                notice.mirrorSerial != null -> "Mirror"
                else -> null
            },
            duration = if (notice.restartAdb || notice.mirrorSerial != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (notice.restartAdb) restartAdb() else mirrorFromNotice(notice)
        }
    }
}
