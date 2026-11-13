package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

/** The log rows' font: monospace 12 sp, tabular digits. */
internal val LogRowStyle = LogcatMono.copy(fontSize = 12.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")

// Mockup `--crash-bg`.
private val CrashLight = Color(0xFFFDE8E5)
private val CrashDark = Color(0xFF3A1512)

private const val GAP = 10 // dp between columns, counted in each column's width
private const val PAD = 10 // dp row padding at each side
private const val BADGE = 20 // dp badge column

/** Room trace frames get before the indent steps in: about 65 monospace characters. */
private val MIN_TRACE_WIDTH = 480.dp

/**
 * Column widths for the header and every row. [ids] animates 1 → 0 as PID-TID and Package hide (compact view or a
 * sheet under 600 dp), [squeeze] 0 → 1 into compact view. Widths are read in layout, so their animation doesn't
 * recompose rows; [idsShown] flips only when the columns appear or finish hiding.
 */
@Stable
internal class LogcatColumns(
    val compact: Boolean,
    private val ids: State<Float>,
    private val squeeze: State<Float>,
    private val narrow: State<Float>,
) {
    val idsShown: Boolean by derivedStateOf { ids.value > 0.001f }

    // Mockup grid: 92 86 128 150 20, compact 62 112 20, each plus a 10 dp gap.
    fun time(): Dp = lerp(92f, 62f, squeeze.value) + GAP.dp
    fun pid(): Dp = (86f + GAP).dp * ids.value
    fun tag(): Dp = lerp(128f, 112f, squeeze.value) + GAP.dp
    fun pkg(): Dp = (150f + GAP).dp * ids.value

    /**
     * Where trace text starts in a row [rowWidth] wide. The mockup's indents (386 dp, 224 dp in compact view, 10 dp
     * under 600 dp) when that leaves [MIN_TRACE_WIDTH] for the frames, else the Tag column, else the row's padding,
     * so frames don't wrap mid-name.
     */
    fun traceIndent(rowWidth: Dp): Dp {
        val mockup = PAD.dp + time() + pid() + tag() + (20f + 20f * ids.value).dp
        val wide = mockup + (PAD.dp - mockup) * narrow.value
        val atTag = PAD.dp + time() + pid()
        return when {
            rowWidth - wide >= MIN_TRACE_WIDTH -> wide
            rowWidth - atTag >= MIN_TRACE_WIDTH -> minOf(atTag, wide)
            else -> minOf(PAD.dp, wide)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = (a + (b - a) * t).dp
}

/** Columns for [compact] view in a sheet that is [narrow] (under 600 dp), animating 250 ms with [motion]. */
@Composable
internal fun rememberLogcatColumns(compact: Boolean, narrow: Boolean, motion: Boolean): LogcatColumns {
    val spec = if (motion) tween<Float>(250, easing = EmphasizedDecelerate) else snap()
    val ids = animateFloatAsState(if (compact || narrow) 0f else 1f, spec)
    val squeeze = animateFloatAsState(if (compact) 1f else 0f, spec)
    val tight = animateFloatAsState(if (narrow) 1f else 0f, spec)
    return remember(compact) { LogcatColumns(compact, ids, squeeze, tight) }
}

/** Sizes the element to [width] (read during layout), clipping what doesn't fit. */
internal fun Modifier.widthBy(width: () -> Dp): Modifier = layout { measurable, constraints ->
    val w = width().roundToPx().coerceIn(0, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
    val p = measurable.measure(Constraints(w, w, constraints.minHeight, constraints.maxHeight))
    layout(w, p.height) { p.place(0, 0) }
}.clipToBounds()

/** Insets the element's start by [start] of the available width (read during layout). */
private fun Modifier.startBy(start: (Dp) -> Dp): Modifier = layout { measurable, constraints ->
    val available = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else Dp.Infinity
    val s = start(available).roundToPx().coerceIn(0, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
    val inner = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = (constraints.minWidth - s).coerceAtLeast(0), maxWidth = constraints.maxWidth - s)
    } else constraints
    val p = measurable.measure(inner)
    layout(p.width + s, p.height) { p.place(s, 0) }
}

/** The header over the list: column names for the [columns] shown. */
@Composable
internal fun LogcatHeader(columns: LogcatColumns) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.6.sp)
    val edge = scheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerLow)
            .drawBehind { drawLine(edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = PAD.dp, vertical = 6.dp),
    ) {
        HeaderCell("TIME", columns::time, style)
        if (columns.idsShown) HeaderCell("PID-TID", columns::pid, style)
        HeaderCell("TAG", columns::tag, style)
        if (columns.idsShown) HeaderCell("PACKAGE", columns::pkg, style)
        Spacer(Modifier.width((BADGE + GAP).dp))
        Text("MESSAGE", style = style, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun HeaderCell(label: String, width: () -> Dp, style: TextStyle) =
    Text(label, Modifier.widthBy(width), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)

/** What a row's clicks do; one instance per list so rows skip. */
@Stable
internal class LogcatRowActions(
    /** Adds (or with alt, excludes) `key:value`. */
    val filter: (key: String, value: String, exclude: Boolean) -> Unit,
    /** Folds or unfolds the trace of the entry with this key. */
    val toggle: (key: Long) -> Unit,
    /** Copies an own frame's `File.kt:118`. */
    val copyFrame: (String) -> Unit,
)

/**
 * One log entry: time, PID-TID, tag, package, level badge and message, then its folded trace when [expanded].
 * Crash rows sit on a red tint and, with [flash], flash red once (1.2 s). [words] are highlighted in the message.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LogcatLineRow(
    item: LogcatItem.Line,
    columns: LogcatColumns,
    words: List<String>,
    wrap: Boolean,
    expanded: Boolean,
    mine: String?,
    flash: Boolean,
    motion: Boolean,
    actions: LogcatRowActions,
) {
    val scheme = MaterialTheme.colorScheme
    val entry = item.entry
    val line = entry.line
    val crash = entry.isCrash
    val crashBg = if (LogcatColors.isDark()) CrashDark else CrashLight
    val flashColor = scheme.error.copy(alpha = 0.35f)
    val flashAnim = if (flash) remember { Animatable(1f) } else null
    if (flashAnim != null) LaunchedEffect(Unit) { flashAnim.animateTo(0f, tween(1200, easing = EmphasizedDecelerate)) }
    val bg = Modifier.drawBehind {
        if (crash) drawRect(crashBg)
        val f = flashAnim?.value ?: 0f
        if (f > 0f) drawRect(flashColor.copy(alpha = flashColor.alpha * f))
    }
    val messageColor = LogcatColors.message(line.level)
    Column(Modifier.fillMaxWidth().then(bg)) {
        Row(Modifier.padding(horizontal = PAD.dp, vertical = 1.dp), verticalAlignment = Alignment.Top) {
            val dim = scheme.onSurfaceVariant
            Text(if (columns.compact) line.time.take(8) else line.time, Modifier.widthBy(columns::time), style = LogRowStyle, color = dim, maxLines = 1, softWrap = false)
            if (columns.idsShown) {
                Text("${line.pid}-${line.tid}", Modifier.widthBy(columns::pid), style = LogRowStyle, color = dim, maxLines = 1, softWrap = false)
            }
            Box(Modifier.widthBy(columns::tag)) {
                FilterCell(line.tag, LogcatColors.tag(line.tag)) { exclude -> actions.filter("tag", line.tag, exclude) }
            }
            if (columns.idsShown) {
                Box(Modifier.widthBy(columns::pkg)) {
                    val pkg = item.pkg
                    if (pkg != null) FilterCell(pkg, dim) { exclude -> actions.filter("package", pkg, exclude) }
                }
            }
            LevelBadge(line.level)
            Spacer(Modifier.width(GAP.dp))
            Row(Modifier.weight(1f)) {
                MessageText(line.message, words, messageColor, line.level == LogLevel.Assert, wrap, Modifier.weight(1f, fill = false))
                if (entry.hasTrace) TraceToggle(entry.trace.size, expanded, motion) { actions.toggle(entry.seq) }
            }
        }
        if (entry.hasTrace) {
            AnimatedVisibility(
                expanded,
                enter = if (motion) expandVertically(tween(260, easing = EmphasizedDecelerate)) + fadeIn(tween(200)) else EnterTransition.None,
                exit = if (motion) shrinkVertically(tween(260, easing = EmphasizedAccelerate)) + fadeOut(tween(160)) else ExitTransition.None,
            ) {
                TraceText(entry.trace, mine, actions.copyFrame, Modifier.startBy(columns::traceIndent).padding(end = PAD.dp, bottom = 2.dp))
            }
        }
    }
}

/** A tag or package: click filters to it, alt-click hides it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterCell(text: String, color: Color, onClick: (exclude: Boolean) -> Unit) {
    Text(
        text,
        Modifier.padding(end = GAP.dp).pointerHoverIcon(PointerIcon.Hand)
            .onClick(keyboardModifiers = { isAltPressed }) { onClick(true) }
            .onClick(keyboardModifiers = { !isAltPressed }) { onClick(false) },
        style = LogRowStyle,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The level letter on a rounded square, 18 × 17 dp. */
@Composable
private fun LevelBadge(level: LogLevel) {
    val (bg, fg) = LogcatColors.level(level)
    Box(Modifier.padding(top = 1.dp).size(18.dp, 17.dp).background(bg, BadgeShape), contentAlignment = Alignment.Center) {
        Text(level.letter.toString(), style = LogRowStyle.copy(fontSize = 11.sp, lineHeight = 11.sp), color = fg, fontWeight = FontWeight.SemiBold)
    }
}

private val BadgeShape = RoundedCornerShape(4.dp)

@Composable
private fun MessageText(message: String, words: List<String>, color: Color, bold: Boolean, wrap: Boolean, modifier: Modifier) {
    val mark = LogcatColors.level(LogLevel.Warn).first.copy(alpha = 0.45f)
    val weight = if (bold) FontWeight.SemiBold else null
    val maxLines = if (wrap) Int.MAX_VALUE else 1
    if (words.isEmpty()) {
        Text(message, modifier, style = LogRowStyle, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = wrap)
    } else {
        val text = remember(message, words, mark) { highlight(message, words, mark) }
        Text(text, modifier, style = LogRowStyle, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = wrap)
    }
}

/** [message] with each case-insensitive occurrence of [words] on a [mark] background. */
internal fun highlight(message: String, words: List<String>, mark: Color): AnnotatedString {
    // Matched on the original text: lowercasing can change the length (İ) and shift indices.
    val ranges = mutableListOf<IntRange>()
    for (w in words) {
        if (w.isEmpty()) continue
        var i = 0
        while (i <= message.length - w.length) {
            if (message.regionMatches(i, w, 0, w.length, ignoreCase = true)) {
                ranges += i until i + w.length
                i += w.length
            } else {
                i++
            }
        }
    }
    return buildAnnotatedString {
        append(message)
        for (r in ranges) addStyle(SpanStyle(background = mark), r.first, r.last + 1)
    }
}

/** "▸ N lines": the chevron turns 90° when unfolded. */
@Composable
private fun TraceToggle(lines: Int, expanded: Boolean, motion: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val turn by animateFloatAsState(if (expanded) 90f else 0f, if (motion) tween(250, easing = EmphasizedDecelerate) else snap())
    Row(
        Modifier.padding(start = 8.dp).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = turn }, tint = primary)
        Text(if (lines == 1) "1 line" else "$lines lines",style = LogRowStyle.copy(fontSize = 11.5.sp), color = primary, maxLines = 1, softWrap = false)
    }
}

/** True when the frame's `class.method` [at] is inside package [mine] (a package boundary, not a prefix). */
internal fun isOwnFrame(at: String, mine: String): Boolean = at == mine || at.startsWith("$mine.")

private val frame = Regex("""^\s*at\s+([\w$.<>\-]+)\((.*)\)\s*$""")
private val fileLine = Regex("""^[\w$.\-]+:\d+$""")

/**
 * The folded trace in the error color, `at` frames dimmed. With [mine] picked, frames of classes under it link
 * their `File.kt:118` to [onCopy].
 */
@Composable
private fun TraceText(trace: List<String>, mine: String?, onCopy: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dim = scheme.onSurfaceVariant
    val primary = scheme.primary
    val text = remember(trace, mine, dim, primary, onCopy) {
        val link = TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            trace.forEachIndexed { i, l ->
                if (i > 0) append('\n')
                val m = frame.find(l)
                if (m == null) {
                    append(l)
                    return@forEachIndexed
                }
                withStyle(SpanStyle(color = dim)) {
                    val loc = m.groups[2]!!
                    if (mine != null && isOwnFrame(m.groupValues[1], mine) && fileLine.matches(loc.value)) {
                        append(l.substring(0, loc.range.first))
                        withLink(LinkAnnotation.Clickable(loc.value, link) { onCopy(loc.value) }) { append(loc.value) }
                        append(l.substring(loc.range.last + 1))
                    } else {
                        append(l)
                    }
                }
            }
        }
    }
    Text(text, modifier, style = LogRowStyle, color = LogcatColors.message(LogLevel.Error))
}

/** The dashed divider before lines read back on resume: "Paused HH:MM:SS – HH:MM:SS". */
@Composable
internal fun LogcatGapRow(gap: LogcatItem.Gap) {
    val scheme = MaterialTheme.colorScheme
    val edge = scheme.outlineVariant
    Box(
        Modifier.fillMaxWidth().padding(horizontal = PAD.dp, vertical = 4.dp).drawBehind {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
            drawLine(edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f, pathEffect = dash)
            drawLine(edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f, pathEffect = dash)
        }.padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Paused ${gap.pausedAt} – ${gap.resumedAt}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = scheme.onSurfaceVariant)
    }
}
