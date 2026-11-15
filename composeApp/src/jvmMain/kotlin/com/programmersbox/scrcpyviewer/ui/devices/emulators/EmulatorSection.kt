package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.emulator.AvdKind
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.BootStage
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.emulator.formatSize
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.mirror.mirrorOrigin
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import java.io.File
import java.nio.file.Path

/** Narrower rows than this (inside their padding) put the row's controls on a second line, under the name. */
internal val EMULATOR_ROW_ONE_LINE_MIN_WIDTH = 300.dp

/**
 * The Devices screen's collapsible Emulators section (placement A): a header with the AVD count and a fold chevron,
 * then one row per AVD and the SDK line, or the empty / error state. [now] is the wall clock the boot timers read.
 */
@Composable
internal fun EmulatorSection(
    state: EmulatorsUiState,
    onEvent: (EmulatorEvent) -> Unit,
    onChooseSdk: () -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = remember { !systemReducesMotion() },
    now: () -> Long = System::currentTimeMillis,
) {
    val found = state.sdk is SdkLookup.Found
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            count = if (found) state.rows.size else 0,
            expanded = state.expanded,
            onToggle = { onEvent(EmulatorEvent.SetExpanded(!state.expanded)) },
            motion = motion,
        )
        val visible = remember { MutableTransitionState(state.expanded) }
        visible.targetState = state.expanded
        AnimatedVisibility(visible, enter = foldEnter(motion), exit = foldExit(motion)) {
            SectionBody(state, onEvent, onChooseSdk, motion, now)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, motion: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(styledCorner(8.dp)).clickable(onClick = onToggle).padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Emulators", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CountPill(count, motion)
        Spacer(Modifier.weight(1f))
        val label = if (expanded) "Collapse" else "Expand"
        val angle by animateFloatAsState(chevronAngle(expanded), chevronSpec(motion), label = "emulators chevron")
        Tooltip(label) {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = label,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = angle },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountPill(count: Int, motion: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 7.dp),
    ) {
        AnimatedContent(count, transitionSpec = { countTransform(motion) }, label = "emulator count") { n ->
            Text(
                n.toString(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class BodyKind { Loading, NoSdk, NoEmulator, NoAvds, Rows }

private fun bodyKind(state: EmulatorsUiState): BodyKind = when (state.sdk) {
    null -> BodyKind.Loading
    is SdkLookup.NotFound -> BodyKind.NoSdk
    is SdkLookup.NoEmulator -> BodyKind.NoEmulator
    is SdkLookup.Found -> if (state.rows.isEmpty()) BodyKind.NoAvds else BodyKind.Rows
}

@Composable
private fun SectionBody(
    state: EmulatorsUiState,
    onEvent: (EmulatorEvent) -> Unit,
    onChooseSdk: () -> Unit,
    motion: Boolean,
    now: () -> Long,
) {
    // Keyed by the kind, so an outgoing state keeps the lookup it was drawn with while it fades.
    AnimatedContent(
        targetState = bodyKind(state) to state.sdk,
        contentKey = { it.first },
        transitionSpec = { bodyTransform(motion) },
        label = "emulators body",
    ) { (kind, sdk) ->
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (kind) {
                BodyKind.Loading -> Spacer(Modifier.height(1.dp))
                BodyKind.NoSdk -> SdkEmpty(
                    title = "No Android SDK found",
                    body = "Scrcpy Vwr looked in ANDROID_HOME, ANDROID_SDK_ROOT and " +
                        "${(sdk as? SdkLookup.NotFound)?.defaultPath ?: "the default folder"}. " +
                        "Install Android Studio, or choose the SDK folder.",
                    onChooseSdk = onChooseSdk,
                )
                BodyKind.NoEmulator -> SdkEmpty(
                    title = "Android Emulator isn't installed",
                    body = "Found the SDK at ${(sdk as? SdkLookup.NoEmulator)?.root?.let { displayPath(it, state.home) } ?: "its folder"}, " +
                        "but not its emulator. Install Android Emulator in Android Studio's SDK Manager, or choose another SDK folder.",
                    onChooseSdk = onChooseSdk,
                )
                BodyKind.NoAvds -> {
                    Text(
                        "No emulators yet. Create one in Android Studio's Device Manager.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    (sdk as? SdkLookup.Found)?.let { SdkLine(it, state.home, onChooseSdk) }
                }
                BodyKind.Rows -> {
                    state.rows.forEach { row ->
                        key(row.avd.id) { EmulatorRowItem(row, onEvent, motion = motion, now = now) }
                    }
                    (sdk as? SdkLookup.Found)?.let { SdkLine(it, state.home, onChooseSdk) }
                }
            }
        }
    }
}

/** [path] with the user's [home] shown as `~`. */
internal fun displayPath(path: Path, home: Path?): String {
    if (home == null || !path.startsWith(home) || path == home) return path.toString()
    return "~" + File.separator + home.relativize(path).toString()
}

/** The No SDK and No emulator states: a dashed box with what was found and a Choose SDK folder button. */
@Composable
internal fun SdkEmpty(title: String, body: String, onChooseSdk: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    outline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onChooseSdk, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose SDK folder")
        }
    }
}

/** Folder icon, the SDK path (home as `~`), "· emulator 37.3.9", and Change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SdkLine(found: SdkLookup.Found, home: Path?, onChooseSdk: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(16.dp).align(Alignment.CenterVertically), tint = muted)
        Text(
            displayPath(found.sdk.root, home), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically),
        )
        found.sdk.emulatorVersion?.let {
            Text("· emulator $it", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.align(Alignment.CenterVertically))
        }
        TextButton(
            onClick = onChooseSdk, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(28.dp).align(Alignment.CenterVertically),
        ) { Text("Change", style = MaterialTheme.typography.labelMedium) }
    }
}

private val AvdState.serialOrNull: String?
    get() = when (this) {
        is AvdState.Booting -> serial
        is AvdState.Running -> serial
        is AvdState.Stopping -> serial
        else -> null
    }

private fun AvdKind.icon(): ImageVector = when (this) {
    AvdKind.Phone -> Icons.Filled.PhoneAndroid
    AvdKind.Tablet -> Icons.Filled.TabletAndroid
    AvdKind.Watch -> Icons.Filled.Watch
    AvdKind.Tv -> Icons.Filled.Tv
}

/** A row's subtitle: what the AVD is (Stopped), the boot stage, the serial, or the failure. */
internal data class Subtitle(val text: String, val mono: Boolean = false, val error: Boolean = false)

internal fun subtitle(row: EmulatorRow): Subtitle = when (val s = row.state) {
    AvdState.Stopped -> Subtitle(
        listOfNotNull(
            row.avd.api?.let { "API $it" },
            row.avd.androidVersion?.let { "Android $it" },
            row.size?.let(::formatSize),
        ).joinToString(" · "),
    )
    is AvdState.Booting -> Subtitle(
        when (s.stage) {
            BootStage.Starting -> "Starting emulator…"
            BootStage.Booting -> "Booting Android…"
            BootStage.WaitingForAdb -> "Waiting for adb…"
        },
    )
    is AvdState.Running -> Subtitle(s.serial, mono = true)
    is AvdState.Stopping -> Subtitle(s.serial, mono = true)
    is AvdState.Failed -> Subtitle(s.reason, error = true)
}

/** Seconds since [startedAt] (wall clock), checked each frame and changed once a second; 0 when null. */
@Composable
private fun rememberElapsedSeconds(startedAt: Long?, now: () -> Long): State<Int> {
    val seconds = remember(startedAt) { mutableIntStateOf(startedAt?.let { ((now() - it) / 1000).toInt().coerceAtLeast(0) } ?: 0) }
    val clock by rememberUpdatedState(now)
    if (startedAt != null) {
        LaunchedEffect(startedAt) {
            while (true) {
                withFrameMillis { }
                val s = ((clock() - startedAt) / 1000).toInt().coerceAtLeast(0)
                if (s != seconds.intValue) seconds.intValue = s
            }
        }
    }
    return seconds
}

/**
 * One AVD: kind icon, name over subtitle (and the boot track), the state controls, and More. While it has a serial the
 * row registers itself as that serial's mirror origin, so "Mirror when ready" grows the window out of it.
 */
@Composable
internal fun EmulatorRowItem(
    row: EmulatorRow,
    onEvent: (EmulatorEvent) -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = remember { !systemReducesMotion() },
    now: () -> Long = System::currentTimeMillis,
    menuInitiallyOpen: Boolean = false,
) {
    val shape = styledCorner(12.dp)
    val booting = row.state as? AvdState.Booting
    val elapsed = rememberElapsedSeconds(booting?.startedAt, now)
    EmulatorRowLayout(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .mirrorOrigin(row.state.serialOrNull)
            .padding(10.dp),
        glyph = {
            Icon(row.avd.kind.icon(), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        text = {
            Column {
                Text(row.avd.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RowSubtitle(subtitle(row), motion)
                BootTrack(row, elapsed, motion)
            }
        },
        controls = { RowControls(row, elapsed, onEvent, motion) },
        more = { MoreButton(row, onEvent, menuInitiallyOpen) },
    )
}

/**
 * Glyph, text and More on one line, with the controls between text and More when the row is at least
 * [EMULATOR_ROW_ONE_LINE_MIN_WIDTH] wide; narrower, the controls go on a second line at the end, so they never clip.
 */
@Composable
private fun EmulatorRowLayout(
    modifier: Modifier,
    glyph: @Composable () -> Unit,
    text: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    more: @Composable () -> Unit,
) {
    Layout(contents = listOf(glyph, text, controls, more), modifier = modifier) { (g, t, c, m), constraints ->
        val gap = 8.dp.roundToPx()
        val smallGap = 2.dp.roundToPx()
        val lineGap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val gp = g.first().measure(loose)
        val mp = m.first().measure(loose)
        val cp = c.first().measure(loose)
        val oneLine = width >= EMULATOR_ROW_ONE_LINE_MIN_WIDTH.roundToPx()
        val textWidth = (width - gp.width - gap * 2 - mp.width - if (oneLine) cp.width + smallGap else 0).coerceAtLeast(0)
        val tp = t.first().measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        val lineHeight = maxOf(gp.height, tp.height, mp.height, if (oneLine) cp.height else 0)
        val height = if (oneLine) lineHeight else lineHeight + lineGap + cp.height
        layout(width, height) {
            gp.place(0, (lineHeight - gp.height) / 2)
            tp.place(gp.width + gap, (lineHeight - tp.height) / 2)
            mp.place(width - mp.width, (lineHeight - mp.height) / 2)
            if (oneLine) {
                cp.place(width - mp.width - smallGap - cp.width, (lineHeight - cp.height) / 2)
            } else {
                cp.place(width - cp.width, lineHeight + lineGap)
            }
        }
    }
}

@Composable
private fun RowSubtitle(subtitle: Subtitle, motion: Boolean) {
    val rise = with(LocalDensity.current) { SUBTITLE_RISE.roundToPx() }
    AnimatedContent(
        subtitle, transitionSpec = { subtitleTransform(motion, rise) }, contentAlignment = Alignment.CenterStart,
        label = "emulator subtitle",
    ) { s ->
        Text(
            s.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (s.mono) FontFamily.Monospace else null,
            color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (s.error) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The 3 dp boot track: grows in when booting starts, eases its fill towards 96 % over the expected boot time (moving
 * once a second), fills to 100 % when the emulator is ready, then shrinks away.
 */
@Composable
private fun BootTrack(row: EmulatorRow, elapsed: State<Int>, motion: Boolean) {
    val booting = row.state as? AvdState.Booting
    val running = row.state is AvdState.Running
    val visible = remember { MutableTransitionState(booting != null) }
    val fill = remember { Animatable(0f) }
    val expected = expectedBootMillis(row.prefs.lastBootMillis, booting?.cold == true)
    LaunchedEffect(booting != null, running) {
        when {
            booting != null -> {
                if (!visible.targetState) fill.snapTo(0f)
                visible.targetState = true
            }
            visible.targetState -> {
                if (running) fill.animateTo(1f, trackFillSpec(motion))
                visible.targetState = false
            }
        }
    }
    if (booting != null) {
        val seconds = elapsed.value
        LaunchedEffect(seconds, expected) { fill.animateTo(bootProgress(seconds * 1000L, expected), trackFillSpec(motion)) }
    }
    val color = warnColor()
    AnimatedVisibility(visible, enter = trackEnter(motion), exit = trackExit(motion)) {
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(color.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(color, size = Size(size.width * fill.value, size.height), cornerRadius = r)
            },
        )
    }
}

private enum class ChipKind { Booting, Running, Stopping, Failed }

private enum class ButtonKind { Start, Stop, None, Retry }

/** The state chip (with spinner, dot or nothing) and the state's button: Start, Stop, Retry or none. */
@Composable
private fun RowControls(row: EmulatorRow, elapsed: State<Int>, onEvent: (EmulatorEvent) -> Unit, motion: Boolean) {
    val id = row.avd.id
    val chip = when (row.state) {
        AvdState.Stopped -> null
        is AvdState.Booting -> ChipKind.Booting
        is AvdState.Running -> ChipKind.Running
        is AvdState.Stopping -> ChipKind.Stopping
        is AvdState.Failed -> ChipKind.Failed
    }
    val button = when (row.state) {
        AvdState.Stopped -> ButtonKind.Start
        is AvdState.Booting, is AvdState.Running -> ButtonKind.Stop
        is AvdState.Stopping -> ButtonKind.None
        is AvdState.Failed -> ButtonKind.Retry
    }
    val rise = with(LocalDensity.current) { CONTROLS_RISE.roundToPx() }
    // The chip keeps its node across Booting, Running, Stopping and Failed, so its colours can ease between them.
    // A plain holder, not state: while the chip leaves (back to Stopped) it keeps showing the last kind.
    val lastChip = remember { arrayOf(chip ?: ChipKind.Running) }
    if (chip != null) lastChip[0] = chip
    val chipVisible = remember { MutableTransitionState(chip != null) }
    chipVisible.targetState = chip != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AnimatedVisibility(chipVisible, enter = chipEnter(motion, rise), exit = chipExit(motion)) {
            StateChip(chip ?: lastChip[0], elapsed, motion, rise)
        }
        AnimatedContent(
            button, transitionSpec = { controlsTransform(motion, rise) }, contentAlignment = Alignment.CenterEnd,
            label = "emulator button",
        ) { kind ->
            when (kind) {
                ButtonKind.Start -> TonalAction("Start", Icons.Filled.PlayArrow) { onEvent(EmulatorEvent.Start(id)) }
                ButtonKind.Retry -> TonalAction("Retry", Icons.Filled.Replay) { onEvent(EmulatorEvent.Retry(id)) }
                ButtonKind.Stop -> Tooltip("Stop") {
                    IconButton(onClick = { onEvent(EmulatorEvent.Stop(id)) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ButtonKind.None -> Spacer(Modifier.size(0.dp, 32.dp))
            }
        }
    }
}

@Composable
private fun TonalAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, softWrap = false)
    }
}

@Stable
private class ChipColors(val container: Color, val content: Color)

@Composable
private fun chipColors(kind: ChipKind): ChipColors {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        ChipKind.Booting -> warnColor().let { ChipColors(it.copy(alpha = 0.16f), it) }
        ChipKind.Running -> ChipColors(scheme.primary.copy(alpha = 0.14f), scheme.primary)
        ChipKind.Stopping -> ChipColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
        ChipKind.Failed -> ChipColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** "12s" with a spinner (Booting), a dot and "Running", a spinner and "Stopping", or "Failed". */
@Composable
private fun StateChip(kind: ChipKind, elapsed: State<Int>, motion: Boolean, rise: Int) {
    val colors = chipColors(kind)
    val container by animateColorAsState(colors.container, chipColorSpec(motion), label = "emulator chip")
    val content by animateColorAsState(colors.content, chipColorSpec(motion), label = "emulator chip content")
    Box(Modifier.clip(CircleShape).background(container).padding(horizontal = 9.dp, vertical = 3.dp)) {
        AnimatedContent(
            kind, transitionSpec = { controlsTransform(motion, rise) }, contentAlignment = Alignment.CenterStart,
            label = "emulator chip label",
        ) { k ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                when (k) {
                    ChipKind.Booting, ChipKind.Stopping ->
                        CircularProgressIndicator(Modifier.size(11.dp), color = content, strokeWidth = 1.5.dp)
                    ChipKind.Running -> Box(Modifier.size(6.dp).clip(CircleShape).background(content))
                    ChipKind.Failed -> Unit
                }
                Text(
                    when (k) {
                        // Ticks in place each second, like a clock.
                        ChipKind.Booting -> "${elapsed.value}s"
                        ChipKind.Running -> "Running"
                        ChipKind.Stopping -> "Stopping"
                        ChipKind.Failed -> "Failed"
                    },
                    style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun MoreButton(row: EmulatorRow, onEvent: (EmulatorEvent) -> Unit, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        Tooltip("More") {
            IconButton(onClick = { open = !open }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EmulatorMenu(row, onEvent, onDismiss = { open = false })
        }
    }
}

/**
 * The ⋮ menu's items: the AVD id, Cold boot (Stopped or Failed only), and the Mirror when ready and Show emulator
 * window checkboxes, which leave the menu open. Internal for screenshots (popups do not render offscreen).
 */
@Composable
internal fun EmulatorMenu(row: EmulatorRow, onEvent: (EmulatorEvent) -> Unit, onDismiss: () -> Unit) {
    val id = row.avd.id
    Text(
        id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (row.state is AvdState.Stopped || row.state is AvdState.Failed) {
        DropdownMenuItem(
            text = { Text("Cold boot") },
            onClick = { onDismiss(); onEvent(EmulatorEvent.ColdBoot(id)) },
            leadingIcon = { Icon(Icons.Filled.AcUnit, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
    DropdownMenuItem(
        text = { Text("Mirror when ready") },
        onClick = { onEvent(EmulatorEvent.ToggleMirror(id)) },
        leadingIcon = { Checkbox(checked = row.prefs.mirrorWhenReady, onCheckedChange = null) },
    )
    DropdownMenuItem(
        text = { Text("Show emulator window") },
        onClick = { onEvent(EmulatorEvent.ToggleWindow(id)) },
        leadingIcon = { Checkbox(checked = row.prefs.showWindow, onCheckedChange = null) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = styledCorner(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) { content() }
}
