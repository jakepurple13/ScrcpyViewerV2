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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import com.programmersbox.scrcpyviewer.telemetry.RecentEvent
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** Shown above the Privacy rows in a build without an analytics key. */
internal const val KEYLESS_BANNER = "This build has no analytics key, so nothing is sent. Release builds from GitHub include it."

/** Fades on the Privacy page: the dimmed rows, the ID swap, the Show / Hide label, and every change under reduce motion. */
private const val PRIVACY_FADE_MILLIS = 200
private const val PRIVACY_CHEVRON_MILLIS = 300

/** How far a Privacy row fades when its switch can't be used. */
private const val PRIVACY_DISABLED_ALPHA = 0.5f

/** The first 8 and last 4 characters of [id] around an ellipsis; a short [id] as it is. */
internal fun shortInstallId(id: String): String = if (id.length <= 13) id else id.take(8) + "…" + id.takeLast(4)

private fun privacyEnter(reduceMotion: Boolean) =
    if (reduceMotion) fadeIn(tween(PRIVACY_FADE_MILLIS)) else expandVertically(layoutSpring()) + fadeIn(tween(PRIVACY_FADE_MILLIS, easing = EmphasizedDecelerate))

private fun privacyExit(reduceMotion: Boolean) =
    if (reduceMotion) fadeOut(tween(PRIVACY_FADE_MILLIS)) else shrinkVertically(layoutSpring()) + fadeOut(tween(PRIVACY_FADE_MILLIS, easing = EmphasizedAccelerate))

/** The keyless-build banner above the Privacy rows. */
@Composable
internal fun PrivacyBanner(b: SettingsBindings) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(visible = !b.state.telemetryEnabled, enter = privacyEnter(reduceMotion), exit = privacyExit(reduceMotion)) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    KEYLESS_BANNER, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/** The whole row toggles; in a keyless build it is disabled and fades back. */
@Composable
internal fun privacyToggle(b: SettingsBindings, value: Boolean, onChange: (Boolean) -> Unit): Modifier {
    val enabled = b.state.telemetryEnabled
    val alpha by animateFloatAsState(if (enabled) 1f else PRIVACY_DISABLED_ALPHA, tween(PRIVACY_FADE_MILLIS), label = "privacyRowAlpha")
    return Modifier.graphicsLayer { this.alpha = alpha }
        .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
}

@Composable
internal fun PrivacySwitch(b: SettingsBindings, checked: Boolean) {
    Switch(checked = checked, onCheckedChange = null, enabled = b.state.telemetryEnabled)
}

/** A Show / Hide button over the recent events, which unfold below it. */
@Composable
internal fun RecentEventsControl(b: SettingsBindings) {
    var open by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val turn by animateFloatAsState(
        if (open) 180f else 0f,
        if (reduceMotion) snap() else tween(PRIVACY_CHEVRON_MILLIS, easing = EmphasizedDecelerate),
        label = "recentChevron",
    )
    Column {
        TextButton(onClick = { open = !open }) {
            Crossfade(open, animationSpec = tween(PRIVACY_FADE_MILLIS), label = "recentLabel") { Text(if (it) "Hide" else "Show", maxLines = 1) }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(turn))
        }
        AnimatedVisibility(visible = open, enter = privacyEnter(reduceMotion), exit = privacyExit(reduceMotion)) {
            RecentEventList(b.state.recent, reduceMotion)
        }
    }
}

/**
 * Newest first: the name, then the local time it was sent, or "not sent" (and the name struck through) when blocked.
 * The empty note and the list fade into each other while the space between them springs to the new height.
 */
@Composable
private fun RecentEventList(events: List<RecentEvent>, reduceMotion: Boolean) {
    AnimatedContent(
        targetState = events.isEmpty(),
        transitionSpec = {
            (fadeIn(tween(PRIVACY_FADE_MILLIS)) togetherWith fadeOut(tween(PRIVACY_FADE_MILLIS)))
                .using(SizeTransform(clip = true) { _, _ -> if (reduceMotion) snap() else layoutSpring() })
        },
        label = "recentEmpty",
    ) { empty ->
        if (empty) {
            Text(
                "Nothing recorded yet.", Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RecentEventRows(events, reduceMotion)
        }
    }
}

/** [new] with each row of [old] that left it put back after the row it followed, so it can shrink out in place. */
internal fun <T> withLeaving(old: List<T>, new: List<T>): List<T> {
    val out = new.toMutableList()
    val kept = new.toSet()
    old.forEachIndexed { i, e ->
        if (e in kept || e in out) return@forEachIndexed
        val before = old.subList(0, i).lastOrNull { it in out }
        out.add(if (before == null) 0 else out.indexOf(before) + 1, e)
    }
    return out
}

/**
 * The rows. Those shown when the list opens rise in one after another; an event recorded later opens its row from
 * height 0 at the top, pushing the others down, and one dropped past the limit shrinks away at the bottom. Under
 * reduce motion rows only fade.
 */
@Composable
private fun RecentEventRows(events: List<RecentEvent>, reduceMotion: Boolean) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val initial = remember { events.toSet() }
    val states = remember { HashMap<RecentEvent, MutableTransitionState<Boolean>>() }
    // The rows composed last time, leaving ones included; not state: it only feeds the next composition.
    val last = remember { arrayOf(emptyList<RecentEvent>()) }
    val current = events.toSet()
    val shown = withLeaving(last[0], events).filter { e ->
        val s = states.getOrPut(e) { MutableTransitionState(e in initial) }
        // A leaving row stays until its exit has finished.
        e in current || !(s.isIdle && !s.currentState)
    }
    SideEffect {
        last[0] = shown
        states.keys.retainAll(shown.toSet())
        for (e in shown) states[e]?.targetState = e in current
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        shown.forEachIndexed { i, e ->
            key(e) {
                AnimatedVisibility(
                    visibleState = states.getValue(e),
                    enter = privacyEnter(reduceMotion),
                    exit = privacyExit(reduceMotion),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (reduceMotion || e !in initial) Modifier else Modifier.staggeredEntrance(i, trigger = Unit))
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.name, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = if (e.sent) MaterialTheme.colorScheme.onSurface else muted,
                            textDecoration = if (e.sent) null else TextDecoration.LineThrough,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(if (e.sent) time.format(e.at) else "not sent", style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The shortened ID under the row's title, crossfading to the new one after a reset. */
@Composable
internal fun InstallIdText(b: SettingsBindings) {
    Crossfade(b.state.installId, animationSpec = tween(PRIVACY_FADE_MILLIS), label = "installId") { id ->
        Text(
            shortInstallId(id), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
internal fun ResetIdButton(b: SettingsBindings) {
    OutlinedButton(onClick = b.actions.onResetInstallId, enabled = b.state.telemetryEnabled) { Text("Reset ID", maxLines = 1) }
}
