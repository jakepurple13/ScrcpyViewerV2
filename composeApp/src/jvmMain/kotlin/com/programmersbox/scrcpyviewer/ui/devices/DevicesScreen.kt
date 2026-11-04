package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionSpec
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.ResizableListDetail
import com.programmersbox.scrcpyviewer.ui.common.rememberComposedViewModelStoreOwner
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import org.koin.compose.viewmodel.koinViewModel
import com.programmersbox.scrcpyviewer.ui.common.OptionRow
import com.programmersbox.scrcpyviewer.ui.theme.warnColor

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    modifier: Modifier = Modifier,
    panes: ListDetailPanes = rememberListDetailPanes(),
) {
    val state by viewModel.state.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel, snackbar) {
        viewModel.notices.collect { notice ->
            val host = snackbar ?: return@collect
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
                if (notice.restartAdb) viewModel.restartAdb() else viewModel.mirrorFromNotice(notice)
            }
        }
    }
    if (pairing) {
        // Owned by the dialog: closing it clears the ViewModel, which stops polling.
        val pairingVm = koinViewModel<WifiPairingViewModel>(viewModelStoreOwner = rememberComposedViewModelStoreOwner())
        LaunchedEffect(pairingVm) {
            pairingVm.results.collect {
                viewModel.notify(it)
                pairing = false
            }
        }
        val pairingState by pairingVm.state.collectAsState()
        WifiPairingDialog(pairingState, remember(pairingVm) { pairingVm.actions() }, onDismiss = { pairing = false })
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
    )
    state.restartConfirm?.let { impact ->
        RestartAdbDialog(impact, onConfirm = viewModel::confirmRestartAdb, onDismiss = viewModel::cancelRestartAdb)
    }
}

/** Narrowest device column that still shows a device row's dot, name and serial. */
val DEVICE_LIST_MIN_WIDTH = 240.dp
private val DeviceListPreferredWidth = 270.dp

/** Narrowest options pane that still fits the search field and two columns of option rows. */
val DEVICE_OPTIONS_MIN_WIDTH = 640.dp

/** Stateless Devices screen (Concept C: device column, searchable option grid, command footer). */
@Composable
fun DevicesContent(
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
) {
    val rowActions = RowActions(onOpen = onOpen, onConnectWifi = onConnectWifi, onDisconnect = onDisconnect, canMirror = canMirror)
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Device options can't be saved: $it", color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        ResizableListDetail(
            panes = panes,
            listMinWidth = DEVICE_LIST_MIN_WIDTH,
            listPreferredWidth = DeviceListPreferredWidth,
            detailMinWidth = DEVICE_OPTIONS_MIN_WIDTH,
            list = {
                DeviceColumn(
                    state, onSelect = { onSelect(it); panes.navigation.openDetail() }, onSelect, rowActions, onPairWifi,
                    contextMenuSerial, onRestartAdb,
                )
            },
            detail = { onBack ->
                Column(Modifier.fillMaxSize()) {
                    if (state.selectedSerial == null) {
                        if (onBack != null) DetailBackButton(onBack, Modifier.padding(start = 6.dp, top = 6.dp))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Select a device to choose its options", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        OptionsPane(state, onQuery, onGroup, onToggle, onValue, onBack, Modifier.weight(1f))
                        Footer(state, onSave, onSaveAsNew, onMirror, onPreset, presetMenuOpen)
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
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
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.adbConnected && !state.restartingAdb) deviceSummary(state.devices) else "Devices",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            RestartAdbButton(restarting = state.restartingAdb, onClick = onRestartAdb)
        }
        when {
            !state.adbConnected || state.restartingAdb -> Row(
                Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Starting adb…", style = MaterialTheme.typography.bodyMedium)
            }
            state.devices.isEmpty() -> Text(
                "No devices. Connect a device with USB debugging on, or pair one over Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = device.serial == state.selectedSerial,
                        onClick = { onSelect(device.serial) },
                        onSecondaryClick = { onHighlight(device.serial) },
                        menu = { onDismiss -> DeviceMenu(device, state, actions, onDismiss) },
                        menuInitiallyOpen = device.serial == contextMenuSerial,
                        onDoubleClick = { actions.onOpen(device.serial) },
                    )
                }
            }
        }
        // The device list takes the free space when present; otherwise this pushes the button to the bottom.
        if (!state.adbConnected || state.devices.isEmpty()) Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onPairWifi, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pair over Wi-Fi")
        }
    }
}

/** The right-click menu's items: Mirror, plus Connect over Wi-Fi (USB) or Disconnect (Wi-Fi). Internal for screenshots. */
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
    } else {
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
) {
    var menuAt by remember { mutableStateOf(if (menuInitiallyOpen) Offset(120f, 40f) else null) }
    val shape = RoundedCornerShape(12.dp)
    val dot = when (device.state) {
        DeviceState.Device -> MaterialTheme.colorScheme.primary
        DeviceState.Unauthorized -> warnColor()
        DeviceState.Offline, DeviceState.Other -> MaterialTheme.colorScheme.error
    }
    val hint = when (device.state) {
        DeviceState.Device -> null
        DeviceState.Unauthorized -> "Accept the USB debugging prompt on the device"
        DeviceState.Offline -> "Reconnect the cable or run adb reconnect"
        DeviceState.Other -> device.rawState
    }
    Box {
    Row(
        Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    onSecondaryClick()
                    menuAt = event.changes.first().position
                }
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            val title = deviceTitle(device)
            val serialText = @Composable { modifier: Modifier ->
                Text(
                    device.serial,
                    style = if (title == device.serial) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = modifier,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title != device.serial) {
                    Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                } else {
                    serialText(Modifier.weight(1f, fill = false))
                }
                ConnectionTag(device.isTcp)
            }
            if (title != device.serial) serialText(Modifier)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = dot)
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

/** "USB" or "Wi-Fi" after the device name. */
@Composable
private fun ConnectionTag(wifi: Boolean) {
    val container = if (wifi) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (wifi) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(container).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(if (wifi) Icons.Filled.Wifi else Icons.Filled.Usb, contentDescription = null, Modifier.size(12.dp), tint = content)
        Text(if (wifi) "Wi-Fi" else "USB", style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun OptionsPane(
    state: DevicesUiState,
    onQuery: (String) -> Unit,
    onGroup: (OptionGroup?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) DetailBackButton(onBack, Modifier.offset(x = (-8).dp))
            Text("Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search ${OptionCatalog.all.size} options") },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.width(260.dp),
            )
        }
        GroupChips(state.groupFilter, onGroup)
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.visibleOptions.isEmpty()) {
                Text("No options match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            } else {
                val (left, right) = remember(state.visibleOptions) { splitColumns(state.visibleOptions) }
                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    LookaheadScope {
                        val items = rememberOptionItems(this, state, onToggle, onValue)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            OptionColumn(left, items, Modifier.weight(1f))
                            OptionColumn(right, items, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
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

/** Slides an option to its new bounds when the visible set changes, and fades it in when it first appears. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.optionMotion(lookahead: LookaheadScope): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(220)) }
    return animateBounds(lookahead, boundsTransform = { _, _ -> spring(stiffness = Spring.StiffnessMediumLow) })
        .graphicsLayer { this.alpha = alpha.value }
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
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val modified = state.isModified
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PresetChip(state, onPreset, presetMenuOpen)
            if (modified) {
                if (state.hasBasePreset) TextButton(onSave) { Text("Save") }
            }
            if (modified || !state.hasBasePreset) {
                TextButton({ naming = true }) { Text("Save as new preset") }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onMirror, enabled = state.canMirror) { Text("Mirror") }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
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

@Composable
private fun PresetChip(state: DevicesUiState, onPreset: (String?) -> Unit, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = state.selectedSerial != null,
            label = { Text("Preset: ${state.presetLabel}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose preset", Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PresetMenuItem("No preset", checked = state.basePresetId == null) { open = false; onPreset(null) }
            state.presets.forEach { preset ->
                PresetMenuItem(preset.name, checked = state.basePresetId == preset.id) { open = false; onPreset(preset.id) }
            }
        }
    }
}

@Composable
private fun PresetMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
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

/** The device column header's "Restart adb server" icon; disabled with a spinner while a restart runs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RestartAdbButton(restarting: Boolean, onClick: () -> Unit) {
    val label = if (restarting) "Restarting adb…" else "Restart adb server"
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick, enabled = !restarting, modifier = Modifier.size(32.dp)) {
            if (restarting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = label, Modifier.size(20.dp))
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
