package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.programmersbox.scrcpyviewer.ui.theme.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode

@Composable
internal fun ThemeControl(b: SettingsBindings) {
    val origin = LocalThemeRevealOrigin.current
    SingleChoiceSegmentedButtonRow {
        ThemeMode.entries.forEachIndexed { i, mode ->
            val source = rememberRevealSource()
            SegmentedButton(
                selected = b.state.settings.themeMode == mode,
                onClick = {
                    source.mark(origin)
                    b.actions.onTheme(mode)
                },
                shape = styledSegmentShape(i, ThemeMode.entries.size),
                modifier = source.modifier,
            ) { Text(mode.name) }
        }
    }
}

@Composable
internal fun CaptureDirControl(b: SettingsBindings) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = b.state.settings.captureDir, onValueChange = {}, readOnly = true, singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = b.actions.onBrowseCaptureDir) { Text("Choose…") }
    }
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
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = b.actions.onBrowseAdb, modifier = Modifier.padding(top = 8.dp)) { Text("Browse…") }
            OutlinedButton(onClick = { b.actions.onUseCustomAdb(draft) }, modifier = Modifier.padding(top = 8.dp)) { Text("Use") }
        }
    }
}

@Composable
internal fun DefaultPresetControl(b: SettingsBindings) {
    var open by remember { mutableStateOf(false) }
    val label = b.state.presets.firstOrNull { it.id == b.state.defaultPresetId }?.name ?: "None"
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.width(220.dp)) {
            Text(label, Modifier.weight(1f))
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

/** Icon unfold, Screen on, or Off for the main window's launch and quit. */
@Composable
internal fun LaunchQuitAnimationControl(b: SettingsBindings) {
    val current = com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.fromName(b.state.settings.launchQuitAnimation)
    val all = com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.entries
    SingleChoiceSegmentedButtonRow {
        all.forEachIndexed { i, value ->
            SegmentedButton(
                selected = current == value,
                onClick = { b.actions.onLaunchQuitAnimation(value) },
                shape = styledSegmentShape(i, all.size),
            ) { Text(value.label) }
        }
    }
}
