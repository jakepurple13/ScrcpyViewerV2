package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalDensity
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
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.COPY_CONFIRM_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleRaised
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
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

/**
 * Easing for height and size changes. The emphasized curves move almost half the distance in the first frame, which
 * reads as a jump for a card or pane resizing, so sizes use the standard curve and only fades use the emphasized ones.
 */
internal val SizeEasing = FastOutSlowInEasing

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
        sizeTransform = SizeTransform(clip = false) { _, _ -> tween(millis, easing = SizeEasing) },
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

/** One part of a [StackedBar], named by [key]: [fraction] of the bar's whole width. */
internal data class BarSegment(val key: String, val fraction: Float, val color: Color)

/**
 * A [BarHeight] bar with [segments] laid end to end. Each segment is tracked by its key: it grows from 0 over
 * [BAR_MILLIS], eases between values and colors, and a segment that is no longer passed shrinks to 0 in its slot.
 */
@Composable
internal fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier) {
    // Every key seen so far, in first-seen order, with its last color: a missing key keeps its slot at width 0.
    val known = remember { LinkedHashMap<String, Color>() }
    segments.forEach { known[it.key] = it.color }
    val byKey = segments.associateBy { it.key }
    val reduce = LocalInfoReduceMotion.current
    val drawn = known.map { (k, color) ->
        key(k) {
            val width = animateGrow((byKey[k]?.fraction ?: 0f).coerceIn(0f, 1f), BAR_MILLIS)
            val tint = animateColorAsState(color, tween(if (reduce) 0 else 400), label = "barColor")
            width to tint
        }
    }
    val shape = styledCorner(BarHeight / 2)
    Canvas(
        modifier.fillMaxWidth().height(BarHeight).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        var x = 0f
        drawn.forEach { (w, tint) ->
            val width = (size.width * w.value).coerceAtMost(size.width - x)
            if (width > 0f) drawRect(tint.value, topLeft = Offset(x, 0f), size = Size(width, size.height))
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

/** The height one line of [style] takes. */
@Composable
private fun lineHeight(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }

/** A pulsing block centered in one text line of [line] height, so a placeholder line is as tall as the real one. */
@Composable
private fun PulseLine(pulse: State<Float>, line: Dp, width: Dp? = null, block: Dp = 12.dp) {
    Box(
        (if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)).height(line),
        contentAlignment = Alignment.CenterStart,
    ) { PulseBlock(pulse, width, block.coerceAtMost(line)) }
}

/** [count] key/value placeholder lines, spaced like [KvRow]s. */
@Composable
private fun PulseKvLines(pulse: State<Float>, count: Int) {
    val line = lineHeight(MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { repeat(count) { PulseLine(pulse, line) } }
}

/**
 * Pulsing blocks shaped like card [index]'s body (Battery … Software) line for line, so a placeholder is as tall as
 * the card it turns into. Software lays out typical values with the real row layout, so long values stack and wrap
 * the same way.
 */
@Composable
private fun PlaceholderBody(index: Int, pulse: State<Float>) {
    val big = lineHeight(bigNumberStyle())
    val small = lineHeight(MaterialTheme.typography.bodySmall)
    val label = lineHeight(MaterialTheme.typography.labelSmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (index) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PulseBlock(pulse, width = RingSize, height = RingSize, round = true)
                    Column {
                        PulseLine(pulse, big, width = 64.dp, block = 26.dp)
                        PulseLine(pulse, small, width = 96.dp)
                    }
                }
                PulseKvLines(pulse, 3)
            }
            1 -> {
                PulseLine(pulse, big, width = 140.dp, block = 26.dp)
                PulseBlock(pulse, width = null, height = BarHeight)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PulseLine(pulse, label, width = 160.dp, block = 10.dp)
                    PulseLine(pulse, label, width = 100.dp, block = 10.dp)
                }
            }
            2 -> {
                PulseLine(pulse, big, width = 140.dp, block = 26.dp)
                PulseBlock(pulse, width = null, height = BarHeight)
                PulseLine(pulse, label, width = 120.dp, block = 10.dp)
            }
            3 -> PulseKvLines(pulse, 4)
            4 -> PulseKvLines(pulse, 3)
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SoftwareSample.forEach { (label, value, mono) -> KvRow(label, value, mono, pulse = pulse) }
            }
        }
    }
}

/** Typical Software rows (the Galaxy A15's), laid out invisibly by the placeholder so its height matches the card. */
private val SoftwareSample = listOf(
    Triple("Android", "16 (API 36)", false),
    Triple("One UI", "8.5", false),
    Triple("Security patch", "2026-07-05", false),
    Triple("Build", "BP4A.251205.006.A156U1UESCEZG1", true),
    Triple("ABI", "arm64-v8a", false),
    Triple("Kernel", "5.15.189-android13-8-33641424", true),
)

/** The last non-null [value], so content that is leaving keeps showing what it showed. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    return value ?: last.value
}

/** A line that comes into a card: its space opens while it fades in. */
private fun lineEnter(reduce: Boolean): EnterTransition {
    val millis = if (reduce) REDUCED_FADE_MILLIS else CROSSFADE_MILLIS
    return expandVertically(tween(millis, easing = SizeEasing)) + fadeIn(tween(millis, easing = EmphasizedDecelerate))
}

/** A line that leaves a card: it fades while its space closes. */
private fun lineExit(reduce: Boolean): ExitTransition {
    val millis = if (reduce) REDUCED_FADE_MILLIS else CLOSE_MILLIS
    return shrinkVertically(tween(millis, easing = SizeEasing)) + fadeOut(tween(millis, easing = EmphasizedAccelerate))
}

/**
 * A [KvRow] that opens in when [value] appears and closes away when it goes. Its 4 dp gap is its own padding, so the
 * gap closes with it.
 */
@Composable
private fun ColumnScope.OptionalKvRow(label: String, value: String?, ticking: Boolean = false) {
    val reduce = LocalInfoReduceMotion.current
    val shown = rememberLast(value)
    AnimatedVisibility(value != null, enter = lineEnter(reduce), exit = lineExit(reduce)) {
        if (shown != null) Box(Modifier.padding(vertical = 2.dp)) { KvRow(label, shown, ticking = ticking) }
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
private fun KvRow(
    label: String,
    value: String,
    mono: Boolean = false,
    ticking: Boolean = false,
    /** Draws the row as a pulsing placeholder with the same layout (for the Software placeholder). */
    pulse: State<Float>? = null,
) {
    val style = MaterialTheme.typography.bodySmall
    val family = if (mono) FontFamily.Monospace else null
    // Mono values (build id, kernel) may only wrap between their parts.
    val shown = if (mono) InfoText.breakable(value) else value
    val hidden = pulse != null
    val fill = if (pulse != null) Modifier.pulseFill(pulse) else Modifier
    val labelColor = if (hidden) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (hidden) Color.Transparent else Color.Unspecified
    Layout(
        content = {
            Text(label, style = style, color = labelColor, modifier = fill)
            if (ticking && !hidden) {
                TickingText(shown, style = style, fontFamily = family)
            } else {
                Text(shown, style = style.merge(TextStyle(fontFeatureSettings = "tnum")), color = valueColor, fontFamily = family, modifier = fill)
            }
        },
        measurePolicy = KvRowPolicy,
    )
}

/**
 * Lays out a [KvRow]: label left and value right-aligned on one line when the value fits beside the label without
 * wrapping; otherwise the label on its own line and the value below it at full width, left-aligned.
 */
private object KvRowPolicy : MeasurePolicy {
    private val gap = 12.dp

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val (labelM, valueM) = measurables
        val width = constraints.maxWidth
        val label = labelM.measure(Constraints(maxWidth = width))
        val gapPx = gap.roundToPx()
        val inline = !constraints.hasBoundedWidth || label.width + gapPx + valueM.maxIntrinsicWidth(Constraints.Infinity) <= width
        return if (inline) {
            val value = valueM.measure(Constraints(maxWidth = if (constraints.hasBoundedWidth) width - label.width - gapPx else Constraints.Infinity))
            val rowWidth = if (constraints.hasBoundedWidth) width else label.width + gapPx + value.width
            layout(rowWidth, maxOf(label.height, value.height)) {
                label.placeRelative(0, 0)
                value.placeRelative(rowWidth - value.width, 0)
            }
        } else {
            val value = valueM.measure(Constraints(maxWidth = width))
            layout(width, label.height + value.height) {
                label.placeRelative(0, 0)
                value.placeRelative(0, label.height)
            }
        }
    }
}

/** Fills the element with the pulsing placeholder color, inset to a text line's block height. */
@Composable
private fun Modifier.pulseFill(pulse: State<Float>): Modifier {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    val radius = if (currentStyle == ThemeStyle.Retro) 0.dp else 6.dp
    return drawBehind {
        val inset = 2.dp.toPx()
        drawRoundRect(
            color.copy(alpha = color.alpha * pulse.value),
            topLeft = Offset(0f, inset),
            size = Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(radius.toPx()),
        )
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
private fun LiveSection(live: Load<LiveInfo>, placeholderIndex: Int, content: @Composable (LiveInfo) -> Unit) {
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
                LiveKind.Loading -> PlaceholderBody(placeholderIndex, rememberPulse())
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
        LiveSection(live, placeholderIndex = 0) { info ->
            val battery = rememberLast(info.battery)
            val reduce = LocalInfoReduceMotion.current
            AnimatedContent(info.battery == null, transitionSpec = { crossFade(reduce) }, label = "batteryPresent") { none ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (none || battery == null) Muted(InfoText.NO_BATTERY) else BatteryBody(battery)
                }
            }
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
    Column {
        OptionalKvRow("Temperature", b.temperatureC?.let(InfoText::temperature), ticking = true)
        OptionalKvRow("Health", InfoText.health(b.health), ticking = true)
        OptionalKvRow("Voltage", b.voltageMv?.let(InfoText::voltage), ticking = true)
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
        val total = storage.totalBytes.toFloat()
        // Apps, media and the rest of used: they sum to df's used bytes (see InfoText.storageParts).
        val parts = InfoText.storageParts(storage)?.let { p ->
            listOf(
                Triple("Apps", p.apps, MaterialTheme.colorScheme.primary),
                Triple("Media", p.media, MaterialTheme.colorScheme.tertiary),
                Triple("Other", p.other, MaterialTheme.colorScheme.outline),
            )
        }.orEmpty()
        // Zero parts stay in the list at width 0, so each part keeps its slot and color. A key that is no longer
        // passed (for example "Used" when a breakdown arrives) shrinks away inside StackedBar.
        val segments = if (parts.isNotEmpty()) {
            parts.map { (name, bytes, color) -> BarSegment(name, bytes / total, color) }
        } else {
            listOf(BarSegment("Used", storage.usedBytes / total, MaterialTheme.colorScheme.primary))
        }
        StackedBar(segments)
        val legend = parts.filter { it.second > 0 }
        val reduce = LocalInfoReduceMotion.current
        AnimatedContent(legend, transitionSpec = { crossFade(reduce) }, label = "storageLegend") { shownLegend ->
            if (shownLegend.isNotEmpty()) WrapRow(horizontalGap = 12.dp, verticalGap = 4.dp) {
                shownLegend.forEach { (name, bytes, color) ->
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
        LiveSection(live, placeholderIndex = 2) { info ->
            val usable = info.memory?.takeIf { it.totalKb > 0 }
            val memory = rememberLast(usable)
            val reduce = LocalInfoReduceMotion.current
            AnimatedContent(usable == null, transitionSpec = { crossFade(reduce) }, label = "memoryPresent") { none ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (none || memory == null) Muted("Couldn't read memory") else MemoryBody(memory)
                }
            }
        }
    }
}

@Composable
private fun MemoryBody(m: MemoryInfo) {
    AmountLine(InfoText.gbFromKb(m.usedKb), "of ${InfoText.gbFromKb(m.totalKb)} GB in use")
    StackedBar(listOf(BarSegment("Used", m.usedKb.toFloat() / m.totalKb, MaterialTheme.colorScheme.primary)))
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
        // As tall as the real title line (labelMedium text beside a 14 dp icon).
        titleContent = { PulseLine(pulse, lineHeight(MaterialTheme.typography.labelMedium).coerceAtLeast(14.dp), width = 72.dp) },
    ) {
        PlaceholderBody(index, pulse)
    }
}

/** Number of cards (and placeholders): Battery, Storage, Memory, Display, Network, Software. */
internal const val CARD_COUNT = 6

/** The grid never uses more columns than this, so six cards fill it evenly (two per column). */
internal const val GRID_MAX_COLUMNS = 3

/**
 * A masonry grid: as many columns of at least [GridMinColumn] as fit (at most [GRID_MAX_COLUMNS]), [GridGap] apart.
 * Cards go to columns by index, round-robin, and each column stacks its cards [GridGap] apart at their own heights,
 * so a card that changes animates its own height and only the cards below it in its column follow. Assignment by
 * index (not by shortest column) keeps a card in its column while heights animate. [item] gets each card's index and
 * the modifier that sizes it.
 */
@Composable
internal fun InfoGrid(count: Int, modifier: Modifier = Modifier, item: @Composable (index: Int, Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = gridColumns(maxWidth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridGap), verticalAlignment = Alignment.Top) {
            repeat(columns) { column ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GridGap)) {
                    masonryColumn(column, columns, count).forEach { index -> item(index, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** How many [GridMinColumn] columns fit in [width], at least 1 and at most [GRID_MAX_COLUMNS]. */
internal fun gridColumns(width: Dp): Int =
    ((width + GridGap) / (GridMinColumn + GridGap)).toInt().coerceIn(1, GRID_MAX_COLUMNS)

/** The card indexes in [column] of [columns]: round-robin, so cards read left to right, then down. */
internal fun masonryColumn(column: Int, columns: Int, count: Int): List<Int> = (column until count step columns).toList()

/**
 * Children left to right, wrapping to a new line when the next one doesn't fit. Unlike `FlowRow` it reports its
 * wrapped height as its intrinsic height, so it measures correctly wherever intrinsics are asked for.
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
