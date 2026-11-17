package com.programmersbox.scrcpyviewer.ui.devices.emulators

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.programmersbox.scrcpyviewer.ui.common.LabelTooltip
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.common.animateHeight
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.ui.layout.LookaheadScope
import com.programmersbox.scrcpyviewer.ui.common.revealLabel
import com.programmersbox.scrcpyviewer.ui.mirror.mirrorOrigin
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleRaised
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import java.io.File
import java.nio.file.Path
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion

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
    motion: Boolean = !LocalReduceMotion.current,
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

internal fun subtitle(row: EmulatorRow): Subtitle = if (row.wiping) Subtitle("Wiping data…") else when (val s = row.state) {
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
    motion: Boolean = !LocalReduceMotion.current,
    now: () -> Long = System::currentTimeMillis,
    menuInitiallyOpen: Boolean = false,
) {
    val shape = styledCorner(12.dp)
    val booting = row.state as? AvdState.Booting
    val elapsed = rememberElapsedSeconds(booting?.startedAt, now)
    var confirmWipe by remember { mutableStateOf(false) }
    WipeDialog(
        visible = confirmWipe,
        name = row.avd.name,
        onConfirm = {
            confirmWipe = false
            onEvent(EmulatorEvent.Wipe(row.avd.id))
        },
        onDismiss = { confirmWipe = false },
        motion = motion,
    )
    EmulatorRowLayout(
        motion = motion,
        // The row's height springs as its controls move between one line and two.
        modifier = modifier.fillMaxWidth()
            .then(if (motion) Modifier.animateHeight() else Modifier)
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
        more = { MoreButton(row, onEvent, menuInitiallyOpen, motion, onWipe = { confirmWipe = true }) },
    )
}

/**
 * Glyph, text and More on one line, with the controls between text and More when the row is at least
 * [EMULATOR_ROW_ONE_LINE_MIN_WIDTH] wide; narrower, the controls go on a second line at the end, so they never clip.
 * With [motion] the controls (chip and button) and More glide to their new places ([layoutSpring]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun EmulatorRowLayout(
    motion: Boolean,
    modifier: Modifier,
    glyph: @Composable () -> Unit,
    text: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    more: @Composable () -> Unit,
) = LookaheadScope {
    val glide = if (motion) Modifier.animateBounds(this, boundsTransform = { _, _ -> layoutSpring() }) else Modifier
    val contents = listOf<@Composable () -> Unit>(glyph, text, { Box(glide) { controls() } }, { Box(glide) { more() } })
    Layout(contents = contents, modifier = modifier) { (g, t, c, m), constraints ->
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
                // Held off while the AVD's data is being wiped (the launcher refuses it too).
                ButtonKind.Start -> TonalAction("Start", Icons.Filled.PlayArrow, enabled = !row.wiping) { onEvent(EmulatorEvent.Start(id)) }
                ButtonKind.Retry -> TonalAction("Retry", Icons.Filled.Replay, enabled = !row.wiping) { onEvent(EmulatorEvent.Retry(id)) }
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
private fun TonalAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
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
private fun MoreButton(
    row: EmulatorRow,
    onEvent: (EmulatorEvent) -> Unit,
    initiallyOpen: Boolean,
    motion: Boolean,
    onWipe: () -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        Tooltip("More") {
            IconButton(onClick = { open = !open }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EmulatorMenu(row, onEvent, onDismiss = { open = false }, onWipe = onWipe, motion = motion)
        }
    }
}

/**
 * Wipe data… is offered only for an AVD that is not running (Stopped or Failed), whose emulator process has exited,
 * and that is not being wiped already.
 */
internal fun canWipe(row: EmulatorRow): Boolean =
    (row.state is AvdState.Stopped || row.state is AvdState.Failed) && !row.wiping && !row.exiting

/**
 * The ⋮ menu's items: the AVD id, Cold boot (Stopped or Failed only), the Mirror when ready and Show emulator window
 * checkboxes (which leave the menu open), a divider, Show in Finder / Show in folder, and Wipe data… ([onWipe] opens
 * its confirm dialog; enabled only when [canWipe]). Cold boot comes and goes, and Wipe data's enabled state changes,
 * with a crossfade. Internal for screenshots (popups do not render offscreen).
 */
@Composable
internal fun EmulatorMenu(
    row: EmulatorRow,
    onEvent: (EmulatorEvent) -> Unit,
    onDismiss: () -> Unit,
    onWipe: () -> Unit = {},
    motion: Boolean = !LocalReduceMotion.current,
    platform: OpenPlatform = OpenPlatform.current(),
) {
    val id = row.avd.id
    Text(
        id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    val canColdBoot = (row.state is AvdState.Stopped || row.state is AvdState.Failed) && !row.wiping
    val coldBoot = remember { MutableTransitionState(canColdBoot) }
    coldBoot.targetState = canColdBoot
    AnimatedVisibility(coldBoot, enter = menuItemEnter(motion), exit = menuItemExit(motion)) {
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
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    DropdownMenuItem(
        text = { Text(revealLabel(platform)) },
        onClick = { onDismiss(); onEvent(EmulatorEvent.Reveal(id)) },
        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp)) },
    )
    val wipeEnabled = canWipe(row)
    // Always error-coloured; disabled, it fades to 38 % rather than switching colour in one frame.
    val wipeAlpha by animateFloatAsState(
        if (wipeEnabled) 1f else MENU_ITEM_DISABLED_ALPHA, menuItemAlphaSpec(motion), label = "wipe item",
    )
    val error = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text("Wipe data…") },
        onClick = { onDismiss(); onWipe() },
        enabled = wipeEnabled,
        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp)) },
        colors = MenuDefaults.itemColors(
            textColor = error, leadingIconColor = error, disabledTextColor = error, disabledLeadingIconColor = error,
        ),
        modifier = Modifier.graphicsLayer { alpha = wipeAlpha },
    )
}

/**
 * Confirms Wipe data for [name]: "Wipe <name>?", what it erases, Cancel and an error-coloured Wipe data. The scrim
 * fades in over 300 ms while the card scales from .88 and rises 14 dp over 450 ms; closing reverses over 150 ms
 * (reduced motion: 150 ms fades). Esc, a click on the scrim and Cancel close it without wiping; focus starts on Cancel,
 * so Enter there cancels, and Enter on Wipe data confirms. A real [Dialog] layer, so it covers the window and keeps
 * keyboard focus inside.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WipeDialog(
    visible: Boolean,
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    motion: Boolean = !LocalReduceMotion.current,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    // Only the first click or key acts: while it closes, the rest do nothing.
    val confirm = {
        if (shown.targetState) {
            shown.targetState = false
            onConfirm()
        }
    }
    val dismiss = {
        if (shown.targetState) {
            shown.targetState = false
            onDismiss()
        }
    }
    val scrimColor = remember { DialogProperties().scrimColor }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = Color.Transparent,
            animateTransition = false,
        ),
    ) {
        val transition = rememberTransition(shown, label = "wipe dialog")
        val scrim by transition.animateFloat({ wipeScrimSpec(motion, targetState) }, label = "scrim") { if (it) 1f else 0f }
        val card by transition.animateFloat({ wipeCardSpec(motion, targetState) }, label = "card") { if (it) 1f else 0f }
        val fade by transition.animateFloat({ wipeCardFadeSpec(motion, targetState) }, label = "fade") { if (it) 1f else 0f }
        val rise = with(LocalDensity.current) { WIPE_CARD_RISE.toPx() }
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(scrimColor, alpha = scrimColor.alpha * scrim) }
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                // Esc closes it wherever focus is inside (the dialog layer's own Esc handling may also call dismiss).
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            WipeCard(
                name, confirm, dismiss,
                Modifier.padding(24.dp).graphicsLayer {
                    val scale = WIPE_CARD_FROM_SCALE + (1f - WIPE_CARD_FROM_SCALE) * card
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - card) * rise
                    alpha = fade
                },
            )
        }
    }
}

@Composable
private fun WipeCard(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val style = currentStyle
    val shape = if (style == ThemeStyle.Retro) RectangleShape else MaterialTheme.shapes.extraLarge
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Surface(
        // Taps on the card stay off the scrim, which closes the dialog.
        modifier.widthIn(max = 400.dp).pointerInput(Unit) { detectTapGestures { } }
            .then(if (style == ThemeStyle.Material) Modifier else Modifier.styleRaised(shape)),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (style == ThemeStyle.Material) 6.dp else 0.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wipe $name?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "This erases apps, accounts and files on the emulator. It can't be undone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onDismiss, Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
                Button(
                    onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Wipe data") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = { LabelTooltip(label) },
    ) { content() }
}
