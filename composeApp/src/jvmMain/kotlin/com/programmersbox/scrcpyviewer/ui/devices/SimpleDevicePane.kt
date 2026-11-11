package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.ui.mirror.mirrorOrigin
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleSunken
import androidx.compose.foundation.background
import com.programmersbox.scrcpyviewer.ui.theme.styledFieldColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import com.programmersbox.scrcpyviewer.ui.theme.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.ui.common.DetailBackButton
import com.programmersbox.scrcpyviewer.ui.theme.warnColor

/**
 * The detail pane's default view: the device's basics, its preset, Mirror and Connect over Wi-Fi, with a way into every
 * option. Internal for screenshots.
 */
@Composable
internal fun SimpleDevicePane(
    state: DevicesUiState,
    onPreset: (String?) -> Unit,
    onMirror: () -> Unit,
    onConnectWifi: (String) -> Unit,
    onAdvanced: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Starts the preset menu open (for screenshots). */
    presetMenuOpen: Boolean = false,
    /** Animates the switch to and from the advanced view; null when there is no switch to animate. */
    motion: ViewSwitchMotion? = null,
) {
    val device = state.devices.firstOrNull { it.serial == state.selectedSerial } ?: return
    Column(modifier.fillMaxSize()) {
        if (onBack != null) DetailBackButton(onBack, Modifier.padding(start = 6.dp, top = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                // The shared controls and the row's surface escape this fade: they move to the advanced pane instead.
                Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp)
                    .simpleContentMotion(motion),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                DeviceHeader(device)
                PresetSection(state, onPreset, presetMenuOpen, motion)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onMirror,
                        enabled = state.canMirror,
                        modifier = Modifier.sharedPart(motion, SharedPart.Mirror).mirrorOrigin(device.serial).fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mirror", style = MaterialTheme.typography.titleMedium)
                    }
                    val wifi = wifiButtonState(device, state)
                    OutlinedButton(
                        onClick = { onConnectWifi(device.serial) },
                        enabled = wifi.enabled,
                        modifier = Modifier.sharedPart(motion, SharedPart.Wifi).fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(wifi.label)
                    }
                    wifi.hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                AdvancedLink(onAdvanced, motion)
            }
        }
    }
}

/** The Connect over Wi-Fi button's label, whether it can be pressed, and the line under it in the simple view. */
internal data class WifiButtonState(val label: String, val enabled: Boolean, val hint: String?)

internal fun wifiButtonState(device: Device, state: DevicesUiState): WifiButtonState {
    val switching = device.serial in state.switching
    return when {
        device.isTcp || isOnWifi(device, state.devices) ->
            WifiButtonState("Connected over Wi-Fi", enabled = false, hint = "This device is already on Wi-Fi.")
        switching -> WifiButtonState("Connecting over Wi-Fi…", enabled = false, hint = null)
        device.state != DeviceState.Device -> WifiButtonState("Connect over Wi-Fi", enabled = false, hint = null)
        else -> WifiButtonState(
            "Connect over Wi-Fi", enabled = true, hint = "Keeps this device connected after you unplug the cable.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceHeader(device: Device) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                deviceTitle(device),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val connection = if (device.isTcp) "Wi-Fi" else "USB"
                when (device.state) {
                    DeviceState.Device -> Fact(
                        "Connected · $connection",
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    DeviceState.Unauthorized -> Fact("Needs USB debugging approval", contentColor = warnColor())
                    DeviceState.Offline -> Fact("Offline", contentColor = MaterialTheme.colorScheme.error)
                    DeviceState.Other -> Fact(device.rawState, contentColor = MaterialTheme.colorScheme.error)
                }
                device.info?.let { info ->
                    Fact("Android ${info.androidVersion}")
                    Fact("SDK ${info.sdk}")
                }
                Fact(device.serial, mono = true)
            }
        }
    }
}

@Composable
private fun Fact(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    mono: Boolean = false,
) {
    Surface(color = containerColor, contentColor = contentColor, shape = styledCorner(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(state: DevicesUiState, onPreset: (String?) -> Unit, initiallyOpen: Boolean, motion: ViewSwitchMotion?) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val base = state.presets.firstOrNull { it.id == state.basePresetId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PRESET",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(Modifier.sharedPart(motion, SharedPart.Preset)) {
            val shape = styledCorner(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .presetField(shape)
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.presetLabel, fontWeight = FontWeight.SemiBold)
                    val description = base?.description?.takeIf { it.isNotBlank() }
                        ?: if (base == null) "Your own options for this device" else null
                    description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose preset", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PresetMenuItem("No preset", checked = state.basePresetId == null) { open = false; onPreset(null) }
                state.presets.forEach { preset ->
                    PresetMenuItem(preset.name, checked = state.basePresetId == preset.id) { open = false; onPreset(preset.id) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val flags = flagSummary(state.values)
            if (flags.isEmpty()) FlagChip("no extra options") else flags.forEach { FlagChip(it) }
        }
    }
}

/** The selected options as scrcpy flags (`--max-size=1920`), in catalog order. */
internal fun flagSummary(values: OptionValues): List<String> =
    OptionCatalog.all.filter { it.flag in values.entries }.map { spec ->
        val value = values.entries[spec.flag]
        if (value.isNullOrEmpty()) "--${spec.flag}" else "--${spec.flag}=$value"
    }

@Composable
private fun FlagChip(text: String) {
    val shape = styledCorner(6.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AdvancedLink(onAdvanced: () -> Unit, motion: ViewSwitchMotion?) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.offset(y = 4.dp)) {
            // The spot the advanced pane grows out of and shrinks back into.
            ViewSwitchContainer(motion, isPane = false, Modifier.matchParentSize())
            TextButton(
                onClick = onAdvanced,
                shape = styledCorner(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Advanced options", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "  ·  ${OptionCatalog.all.size} options, command line",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/** The preset picker's frame: Material's outline, or the style's pressed-in field. */
@Composable
private fun Modifier.presetField(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val scheme = MaterialTheme.colorScheme
    return when (currentStyle) {
        ThemeStyle.Material -> clip(shape).border(1.dp, scheme.outline, shape)
        ThemeStyle.Retro -> background(scheme.surfaceContainerLowest).styleSunken(shape).clip(shape)
        else -> styleSunken(shape).clip(shape).background(styledFieldColors().unfocusedContainerColor)
    }
}
