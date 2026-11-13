package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.info.BatteryInfo
import com.programmersbox.scrcpyviewer.info.ChargeStatus
import com.programmersbox.scrcpyviewer.info.DisplayInfo
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.MemoryInfo
import com.programmersbox.scrcpyviewer.info.NetworkInfo
import com.programmersbox.scrcpyviewer.info.SoftwareInfo
import com.programmersbox.scrcpyviewer.info.StorageInfo
import com.programmersbox.scrcpyviewer.ui.common.COPY_CONFIRM_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleRaised
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------------------------------
// Motion numbers (spec "Motion" table)
// ---------------------------------------------------------------------------------------------------------------------

/** The battery ring's sweep from 0 (and between values). */
internal const val RING_MILLIS = 800

/** Every bar segment's growth from 0 (and between values). */
internal const val BAR_MILLIS = 700

/** A changing number: the old one slides up and out, the new one comes up from below. */
internal const val TICK_MILLIS = 200

/** One full placeholder pulse: dim to bright and back. */
internal const val PULSE_MILLIS = 1_200

/** Placeholders and cards cross-fade over this. */
internal const val CROSSFADE_MILLIS = 300

/** With reduced motion, a change that isn't instant is this fade. */
internal const val REDUCED_FADE_MILLIS = 150

/** True when the OS asks for less motion: the pane's changes become instant swaps or [REDUCED_FADE_MILLIS] fades. */
internal val LocalInfoReduceMotion = staticCompositionLocalOf { false }

/** The ring's color for a healthy charge: the green dot device rows use for Ready (the theme's primary). */
@Composable
internal fun readyColor(): Color = MaterialTheme.colorScheme.primary

private val GridMinColumn = 220.dp
private val GridGap = 12.dp
private val RingSize = 64.dp
private val RingStroke = 7.dp
private val BarHeight = 10.dp

/** The headline number in a card (battery percent, GB used). */
@Composable
private fun bigNumberStyle(): TextStyle = MaterialTheme.typography.headlineMedium.copy(
    fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 30.sp,
)

// ---------------------------------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A value that grows from 0 to [target] the first time it shows and eases between values after that, over [millis]
 * with [EmphasizedDecelerate]. With reduced motion it jumps.
 */
@Composable
private fun animateGrow(target: Float, millis: Int): Animatable<Float, AnimationVector1D> {
    val reduce = LocalInfoReduceMotion.current
    val value = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) value.snapTo(target) else value.animateTo(target, tween(millis, easing = EmphasizedDecelerate))
    }
    return value
}

/** The last value [load] had while ready, so content that is leaving keeps showing what it showed. */
@Composable
internal fun <T> rememberLastReady(load: Load<T>): T? {
    val last = remember { mutableStateOf<T?>(null) }
    val current = (load as? Load.Ready)?.value
    SideEffect { if (current != null) last.value = current }
    return current ?: last.value
}

/**
 * Text whose changes tick: the old value slides up and fades while the new one rises from below over [TICK_MILLIS].
 * Tabular figures keep the width from jumping as digits change.
 */
@Composable
internal fun TickingText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
) {
    val reduce = LocalInfoReduceMotion.current
    val tabular = style.merge(TextStyle(fontFeatureSettings = "tnum"))
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(REDUCED_FADE_MILLIS)) togetherWith fadeOut(tween(REDUCED_FADE_MILLIS))
            } else {
                (slideInVertically(tween(TICK_MILLIS, easing = EmphasizedDecelerate)) { it } +
                    fadeIn(tween(TICK_MILLIS, easing = EmphasizedDecelerate))) togetherWith
                    (slideOutVertically(tween(TICK_MILLIS, easing = EmphasizedAccelerate)) { -it } +
                        fadeOut(tween(TICK_MILLIS, easing = EmphasizedAccelerate))) using
                    SizeTransform(clip = false) { _, _ -> tween(TICK_MILLIS, easing = EmphasizedDecelerate) }
            }
        },
        contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
        label = "tick",
    ) {
        Text(it, style = tabular, color = color, fontFamily = fontFamily, textAlign = textAlign)
    }
}

/** A cross-fade of [CROSSFADE_MILLIS] (or the reduced fade) for content keyed on its kind, not its values. */
internal fun crossFade(reduce: Boolean): ContentTransform {
    val millis = if (reduce) REDUCED_FADE_MILLIS else CROSSFADE_MILLIS
    return ContentTransform(
        targetContentEnter = fadeIn(tween(millis, easing = EmphasizedDecelerate)),
        initialContentExit = fadeOut(tween(millis, easing = EmphasizedAccelerate)),
        sizeTransform = SizeTransform(clip = false) { _, _ -> tween(millis, easing = EmphasizedDecelerate) },
    )
}

/** The battery ring: a [RingSize] arc from 12 o'clock that sweeps to [fraction] and eases its [color]. */
@Composable
internal fun BatteryRing(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val sweep = animateGrow(fraction.coerceIn(0f, 1f), RING_MILLIS)
    val tint by animateColorAsState(color, tween(400), label = "ringColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.size(RingSize)) {
        val stroke = RingStroke.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        // Read in the draw phase, so each frame only redraws.
        val degrees = 360f * sweep.value
        if (degrees > 0.5f) {
            drawArc(tint, -90f, degrees, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** One part of a [StackedBar]: [fraction] of the bar's whole width. */
internal data class BarSegment(val fraction: Float, val color: Color)

/** A [BarHeight] bar with [segments] laid end to end; each grows from 0 over [BAR_MILLIS] and eases between values. */
@Composable
internal fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier) {
    val widths = segments.map { animateGrow(it.fraction.coerceIn(0f, 1f), BAR_MILLIS) }
    val colors = segments.map { it.color }
    val shape = styledCorner(BarHeight / 2)
    Canvas(
        modifier.fillMaxWidth().height(BarHeight).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        var x = 0f
        widths.forEachIndexed { i, w ->
            val width = (size.width * w.value).coerceAtMost(size.width - x)
            if (width > 0f) drawRect(colors[i], topLeft = Offset(x, 0f), size = Size(width, size.height))
            x += width
        }
    }
}

/** A soft pulse of the surface color for placeholders, one loop per [PULSE_MILLIS]; steady with reduced motion. */
@Composable
internal fun rememberPulse(): State<Float> {
    if (LocalInfoReduceMotion.current) return remember { mutableStateOf(0.7f) }
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS / 2), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
}

/** A rounded block of the pulsing placeholder color. */
@Composable
private fun PulseBlock(pulse: State<Float>, width: Dp?, height: Dp, modifier: Modifier = Modifier, round: Boolean = false) {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    val shape = if (round) CircleShape else styledCorner(6.dp)
    Box(
        modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .clip(shape)
            // The alpha is read while drawing, so the pulse never recomposes.
            .drawBehind { drawRect(color.copy(alpha = color.alpha * pulse.value)) },
    )
}

/** Lines of placeholder text inside a card whose live values haven't arrived. */
@Composable
private fun PulseLines(lines: Int) {
    val pulse = rememberPulse()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i -> PulseBlock(pulse, width = null, height = 12.dp, modifier = Modifier.fillMaxWidth(if (i % 2 == 0) 1f else 0.7f)) }
    }
}

/** The frame every card shares: a filled card with a small uppercase title and icon. */
@Composable
internal fun InfoCard(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = styledCorner(14.dp)
    Surface(
        modifier = modifier.styleRaised(shape),
        shape = shape,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        border = if (currentStyle == ThemeStyle.Material) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (titleContent != null) {
                titleContent()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (icon != null) Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** A label on the left and its value on the right, as in the mockup's key/value lists. */
@Composable
private fun KvRow(label: String, value: String, mono: Boolean = false, ticking: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val family = if (mono) FontFamily.Monospace else null
        if (ticking) {
            TickingText(value, Modifier.weight(1f), MaterialTheme.typography.bodySmall, fontFamily = family, textAlign = TextAlign.End)
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                fontFamily = family,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A muted line for a card with nothing to show ("No battery", "Not on Wi-Fi"). */
@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "18.6" big, then " of 113.2 GB used" muted, on one baseline. */
@Composable
private fun AmountLine(amount: String, rest: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        TickingText(amount, style = bigNumberStyle(), modifier = Modifier.alignByBaseline())
        Text(
            " $rest",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** What a card fed by live polling shows: its values, a placeholder until the first poll, or the read error. */
private enum class LiveKind { Loading, Failed, Ready }

/** Cross-fades a live card between its placeholder, its error and its values (keyed on the kind, not the values). */
@Composable
private fun LiveSection(live: Load<LiveInfo>, placeholderLines: Int, content: @Composable (LiveInfo) -> Unit) {
    val last = rememberLastReady(live)
    val kind = when {
        live is Load.Ready || last != null -> LiveKind.Ready
        live is Load.Failed -> LiveKind.Failed
        else -> LiveKind.Loading
    }
    val reduce = LocalInfoReduceMotion.current
    AnimatedContent(kind, transitionSpec = { crossFade(reduce) }, label = "liveSection") { k ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (k) {
                LiveKind.Loading -> PulseLines(placeholderLines)
                LiveKind.Failed -> Muted("Couldn't read: ${(live as? Load.Failed)?.message ?: "no answer"}")
                LiveKind.Ready -> last?.let { content(it) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The six cards
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun BatteryCard(live: Load<LiveInfo>, modifier: Modifier = Modifier) {
    InfoCard("Battery", Icons.Filled.BatteryFull, modifier) {
        LiveSection(live, placeholderLines = 4) { info ->
            val battery = info.battery
            if (battery == null) Muted(InfoText.NO_BATTERY) else BatteryBody(battery)
        }
    }
}

@Composable
private fun BatteryBody(b: BatteryInfo) {
    val plugged = b.status == ChargeStatus.Charging || b.status == ChargeStatus.Full
    val low = b.level < 20 && !plugged
    val reduce = LocalInfoReduceMotion.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BatteryRing(b.level / 100f, if (low) MaterialTheme.colorScheme.error else readyColor())
        Column {
            TickingText("${b.level}%", style = bigNumberStyle())
            AnimatedContent(InfoText.batteryState(b), transitionSpec = { crossFade(reduce) }, label = "batteryState") {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        b.temperatureC?.let { KvRow("Temperature", InfoText.temperature(it), ticking = true) }
        InfoText.health(b.health)?.let { KvRow("Health", it, ticking = true) }
        b.voltageMv?.let { KvRow("Voltage", InfoText.voltage(it), ticking = true) }
    }
}

@Composable
internal fun StorageCard(storage: StorageInfo?, modifier: Modifier = Modifier) {
    InfoCard("Storage", Icons.Filled.Storage, modifier) {
        if (storage == null || storage.totalBytes <= 0) {
            Muted("Couldn't read storage")
            return@InfoCard
        }
        AmountLine(InfoText.gb(storage.usedBytes), "of ${InfoText.gb(storage.totalBytes)} GB used")
        val usedFraction = storage.usedBytes.toFloat() / storage.totalBytes
        val parts = storage.breakdown?.let { b ->
            listOf(
                Triple("Apps", b.apps, MaterialTheme.colorScheme.primary),
                Triple("Media", b.media, MaterialTheme.colorScheme.tertiary),
                Triple("System", b.system, warnColor()),
                Triple("Other", b.other, MaterialTheme.colorScheme.outline),
            ).filter { it.second > 0 }
        }.orEmpty()
        val sum = parts.sumOf { it.second }
        // diskstats counts some bytes outside /data, so the parts split the used length of the bar by share.
        val segments = if (sum > 0) {
            parts.map { (_, bytes, color) -> BarSegment(usedFraction * bytes / sum, color) }
        } else {
            listOf(BarSegment(usedFraction, MaterialTheme.colorScheme.primary))
        }
        StackedBar(segments)
        if (parts.isNotEmpty()) {
            WrapRow(horizontalGap = 12.dp, verticalGap = 4.dp) {
                parts.forEach { (name, bytes, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(8.dp).clip(styledCorner(2.dp)).background(color))
                        Text(
                            "$name ${InfoText.gb(bytes)} GB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryCard(live: Load<LiveInfo>, modifier: Modifier = Modifier) {
    InfoCard("Memory", Icons.Filled.Memory, modifier) {
        LiveSection(live, placeholderLines = 3) { info ->
            val memory = info.memory
            if (memory == null || memory.totalKb <= 0) Muted("Couldn't read memory") else MemoryBody(memory)
        }
    }
}

@Composable
private fun MemoryBody(m: MemoryInfo) {
    AmountLine(InfoText.gbFromKb(m.usedKb), "of ${InfoText.gbFromKb(m.totalKb)} GB in use")
    StackedBar(listOf(BarSegment(m.usedKb.toFloat() / m.totalKb, MaterialTheme.colorScheme.primary)))
    Text("Refreshes every 5 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun DisplayCard(display: DisplayInfo?, modifier: Modifier = Modifier) {
    InfoCard("Display", Icons.Filled.Smartphone, modifier) {
        if (display == null) {
            Muted("Couldn't read the display")
            return@InfoCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KvRow("Resolution", InfoText.resolution(display), mono = true)
            KvRow("Density", InfoText.density(display))
            display.refreshHz?.let { KvRow("Refresh rate", InfoText.refresh(it)) }
            KvRow("Smallest width", InfoText.smallestWidth(display))
        }
    }
}

@Composable
internal fun NetworkCard(network: NetworkInfo, modifier: Modifier = Modifier) {
    InfoCard("Network", Icons.Filled.Wifi, modifier) {
        if (InfoText.offWifi(network)) {
            Muted(InfoText.NOT_ON_WIFI)
            return@InfoCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            network.ssid?.let { KvRow("Wi-Fi", it) }
            network.ipAddress?.let { CopyableKvRow("IP address", it) }
            network.linkMbps?.let { KvRow("Link speed", InfoText.linkSpeed(it)) }
        }
    }
}

/** A key/value row whose value copies on click; the trailing copy icon morphs into a check while "Copied" shows. */
@Composable
private fun CopyableKvRow(label: String, value: String) {
    val copy = rememberCopyAction()
    var copies by remember { mutableIntStateOf(0) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        delay(COPY_CONFIRM_MILLIS)
        copies = 0
    }
    val reduce = LocalInfoReduceMotion.current
    val tint by animateColorAsState(
        if (copies > 0) readyColor() else MaterialTheme.colorScheme.onSurfaceVariant, tween(260), label = "ipCopyTint",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(styledCorner(4.dp))
                .clickable(onClickLabel = "Copy $label") { copy(value); copies++ }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                fontFamily = FontFamily.Monospace,
            )
            AnimatedContent(
                copies > 0,
                transitionSpec = {
                    val millis = if (reduce) REDUCED_FADE_MILLIS else 300
                    (fadeIn(tween(millis, easing = EmphasizedDecelerate)) + scaleIn(tween(millis, easing = EmphasizedDecelerate), initialScale = if (reduce) 1f else 0.3f)) togetherWith
                        (fadeOut(tween(millis, easing = EmphasizedAccelerate)) + scaleOut(tween(millis, easing = EmphasizedAccelerate), targetScale = if (reduce) 1f else 0.3f))
                },
                label = "ipCopyIcon",
            ) { copied ->
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun SoftwareCard(software: SoftwareInfo, modifier: Modifier = Modifier) {
    InfoCard("Software", Icons.Filled.Android, modifier) {
        val rows = listOfNotNull(
            InfoText.androidLine(software)?.let { Triple("Android", it, false) },
            software.oneUiVersion?.let { Triple("One UI", it, false) },
            software.securityPatch?.let { Triple("Security patch", it, false) },
            software.buildId?.let { Triple("Build", it, true) },
            software.abi?.let { Triple("ABI", it, false) },
            software.kernel?.let { Triple("Kernel", it, true) },
        )
        if (rows.isEmpty()) {
            Muted("Couldn't read the software version")
            return@InfoCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (label, value, mono) -> KvRow(label, value, mono) }
        }
    }
}

/** A card-sized placeholder for card [index] (Battery … Software): its title and pulsing lines shaped like its body. */
@Composable
internal fun PlaceholderCard(index: Int, pulse: State<Float>, modifier: Modifier = Modifier) {
    InfoCard(
        title = "",
        icon = null,
        modifier = modifier,
        titleContent = { PulseBlock(pulse, width = 72.dp, height = 12.dp) },
    ) {
        when (index) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PulseBlock(pulse, width = RingSize, height = RingSize, round = true)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseBlock(pulse, width = 64.dp, height = 26.dp)
                        PulseBlock(pulse, width = 96.dp, height = 12.dp)
                    }
                }
                repeat(3) { PulseBlock(pulse, width = null, height = 12.dp) }
            }
            1, 2 -> {
                PulseBlock(pulse, width = 140.dp, height = 26.dp)
                PulseBlock(pulse, width = null, height = BarHeight)
                PulseBlock(pulse, width = 120.dp, height = 10.dp)
            }
            3 -> repeat(4) { PulseBlock(pulse, width = null, height = 12.dp) }
            4 -> repeat(3) { PulseBlock(pulse, width = null, height = 12.dp) }
            else -> repeat(6) { PulseBlock(pulse, width = null, height = 12.dp) }
        }
    }
}

/** Number of cards (and placeholders): Battery, Storage, Memory, Display, Network, Software. */
internal const val CARD_COUNT = 6

/**
 * An adaptive grid: as many columns of at least [GridMinColumn] as fit, [GridGap] apart. Cards in a row share the
 * row's height. [item] gets each card's index and the modifier that sizes it.
 */
@Composable
internal fun InfoGrid(count: Int, modifier: Modifier = Modifier, item: @Composable (index: Int, Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + GridGap) / (GridMinColumn + GridGap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(GridGap)) {
            (0 until count).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    row.forEach { index -> item(index, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Children left to right, wrapping to a new line when the next one doesn't fit. Unlike `FlowRow` it reports its
 * wrapped height as its intrinsic height, so a card in an [InfoGrid] row (which sizes rows by intrinsics) isn't cut.
 */
@Composable
private fun WrapRow(horizontalGap: Dp, verticalGap: Dp, content: @Composable () -> Unit) {
    Layout(content, measurePolicy = remember(horizontalGap, verticalGap) { WrapPolicy(horizontalGap, verticalGap) })
}

private class WrapPolicy(private val hGap: Dp, private val vGap: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val lines = wrap(placeables.map { it.width }, maxWidth, hGap.roundToPx())
        val gapY = vGap.roundToPx()
        val lineHeights = lines.map { line -> line.maxOf { placeables[it].height } }
        val height = lineHeights.sum() + gapY * (lines.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOfOrNull { line -> line.sumOf { placeables[it].width } } ?: 0
        return layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            lines.forEachIndexed { li, line ->
                var x = 0
                line.forEach { i ->
                    placeables[i].placeRelative(x, y)
                    x += placeables[i].width + hGap.roundToPx()
                }
                y += lineHeights[li] + gapY
            }
        }
    }

    private fun IntrinsicMeasureScope.wrappedHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val widths = measurables.map { it.maxIntrinsicWidth(Int.MAX_VALUE) }
        val lines = wrap(widths, width, hGap.roundToPx())
        return lines.sumOf { line -> line.maxOf { measurables[it].minIntrinsicHeight(widths[it]) } } +
            vGap.roundToPx() * (lines.size - 1).coerceAtLeast(0)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        wrappedHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        wrappedHeight(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + hGap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)
}

/** Splits item [widths] into lines no wider than [maxWidth] with [gap] between items; each line has at least one item. */
private fun wrap(widths: List<Int>, maxWidth: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { i, w ->
        val line = lines.lastOrNull()
        if (line != null && used + gap + w <= maxWidth) {
            line += i
            used += gap + w
        } else {
            lines += mutableListOf(i)
            used = w
        }
    }
    return lines
}
