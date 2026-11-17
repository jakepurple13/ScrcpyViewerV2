package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.state.AfterScreenshot
import com.programmersbox.scrcpyviewer.state.afterShot
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveRow
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveSegmentedButtons
import com.programmersbox.scrcpyviewer.ui.common.ControlSize
import com.programmersbox.scrcpyviewer.ui.common.SegmentOption
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import com.programmersbox.scrcpyviewer.ui.theme.Switch
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape

/** System, Light and Dark as segments, or one menu button when the row is too narrow ([size]). */
@Composable
internal fun ThemeControl(b: SettingsBindings, size: ControlSize = ControlSize.Full) {
    val origin = LocalThemeRevealOrigin.current
    // The reveal starts at the clicked segment, or at the menu button.
    val sources = remember { ThemeMode.entries.associateWith { RevealSource() } }
    val menuSource = rememberRevealSource()
    fun source(mode: ThemeMode) = if (size == ControlSize.Menu) menuSource else sources.getValue(mode)
    AdaptiveSegmentedButtons(
        options = remember { ThemeMode.entries.map { SegmentOption(it, it.name) } },
        selected = b.state.settings.themeMode,
        onSelect = { mode ->
            source(mode).mark(origin)
            b.actions.onTheme(mode)
        },
        size = size,
        segmentModifier = { source(it).modifier },
    )
}

/** The folder field keeps 160 dp beside the button, else the button goes under it at full width. */
@Composable
internal fun CaptureDirControl(b: SettingsBindings) {
    AdaptiveRow(
        text = {
            OutlinedTextField(
                value = b.state.settings.captureDir, onValueChange = {}, readOnly = true, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        control = { OutlinedButton(onClick = b.actions.onBrowseCaptureDir) { Text("Choose…", maxLines = 1) } },
        textMin = 160.dp, gap = 12.dp, fillControlWhenStacked = true,
    )
}

/** The choices as segments, or one menu button when the row is too narrow ([size]). */
@Composable
internal fun AfterScreenshotControl(b: SettingsBindings, size: ControlSize = ControlSize.Full) {
    AdaptiveSegmentedButtons(
        options = remember { AfterScreenshot.entries.map { SegmentOption(it, it.label) } },
        selected = b.state.settings.afterShot,
        onSelect = b.actions.onAfterScreenshot,
        size = size,
        // So "Show thumbnail" is not cut to its longest word.
        wholeLabels = true,
    )
}

@Composable
internal fun AdbPicker(b: SettingsBindings) {
    val state = b.state
    val override = state.settings.adbPathOverride
    var draft by remember(override) { mutableStateOf(override.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().selectable(selected = override == null, role = Role.RadioButton, onClick = b.actions.onUseBundledAdb),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = override == null, onClick = null)
                Text("Bundled (version ${state.bundledAdbVersion ?: "…"})", Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().selectable(selected = override != null, role = Role.RadioButton, onClick = b.actions.onBrowseAdb),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = override != null, onClick = null)
                Text("Custom path", Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            }
        }
        // The path keeps 160 dp beside the buttons, else they go under it.
        AdaptiveRow(
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    placeholder = { Text("/path/to/adb") },
                    isError = state.adbPathError != null,
                    supportingText = {
                        val error = state.adbPathError
                        when {
                            error != null -> Text(error)
                            override != null -> Text("Version ${state.customAdbVersion ?: "…"}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            control = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = b.actions.onBrowseAdb, modifier = Modifier.padding(top = 8.dp)) { Text("Browse…", maxLines = 1) }
                    OutlinedButton(onClick = { b.actions.onUseCustomAdb(draft) }, modifier = Modifier.padding(top = 8.dp)) { Text("Use", maxLines = 1) }
                }
            },
            textMin = 160.dp, gap = 12.dp, fillControlWhenStacked = true, verticalAlignment = Alignment.Top,
        )
    }
}

@Composable
internal fun DefaultPresetControl(b: SettingsBindings) {
    var open by remember { mutableStateOf(false) }
    val label = b.state.presets.firstOrNull { it.id == b.state.defaultPresetId }?.name ?: "None"
    Column {
        // 220 dp, or the row's width when that is less (width() gives way to tighter constraints).
        OutlinedButton(onClick = { open = true }, modifier = Modifier.width(220.dp)) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { open = false; b.actions.onDefaultPreset(null) })
            b.state.presets.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; b.actions.onDefaultPreset(p.id) })
            }
        }
    }
}

/** The whole row toggles. */
internal fun animateMirrorToggle(b: SettingsBindings) = Modifier.toggleable(
    value = b.state.settings.animateMirrorWindows, role = Role.Switch,
    onValueChange = b.actions.onAnimateMirrorWindows,
)

@Composable
internal fun AnimateMirrorSwitch(b: SettingsBindings) {
    Switch(checked = b.state.settings.animateMirrorWindows, onCheckedChange = null)
}

/** The whole row toggles. */
internal fun trayIconToggle(b: SettingsBindings) = Modifier.toggleable(
    value = b.state.settings.showTrayIcon, role = Role.Switch,
    onValueChange = b.actions.onShowTrayIcon,
)

@Composable
internal fun TrayIconSwitch(b: SettingsBindings) {
    Switch(checked = b.state.settings.showTrayIcon, onCheckedChange = null)
}
