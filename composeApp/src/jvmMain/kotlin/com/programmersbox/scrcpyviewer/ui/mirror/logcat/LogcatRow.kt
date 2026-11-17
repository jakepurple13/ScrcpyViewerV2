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
import androidx.compose.foundation.text.selection.DisableSelection
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
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
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutFraction
import kotlin.math.roundToInt
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring

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
 * sheet under 600 dp), [squeeze] 0 → 1 into compact view, [stack] 0 → 1 into two-line rows (under 420 dp, see
 * [LogcatRowShape.Stacked]). Widths and the stack are read in layout, so their animation doesn't recompose rows;
 * [idsShown], [stacked] and [headerShown] flip only at the animation's ends or middle.
 */
@Stable
internal class LogcatColumns(
    val compact: Boolean,
    private val ids: State<Float>,
    private val squeeze: State<Float>,
    private val narrow: State<Float>,
    private val stack: State<Float> = mutableFloatStateOf(0f),
) {
    val idsShown: Boolean by derivedStateOf { ids.value > 0.001f }

    /** True from halfway into two-line rows: the time drops its millis (its place is measured as if it already had). */
    val stacked: Boolean by derivedStateOf { stack.value >= 0.5f }

    /**
     * True as soon as rows start to stack, until they are back on one line: an unwrapped message gets its 2 lines
     * from the start, so the row's height rides the glide (see [rowCells]).
     */
    val stacking: Boolean by derivedStateOf { stack.value > 0f }

    /** False once rows have finished stacking: the header has folded away. */
    val headerShown: Boolean by derivedStateOf { stack.value < 0.999f }

    /** 0 for one-line rows, 1 for two-line rows, in between while they glide (read in layout). */
    fun stack(): Float = stack.value.coerceIn(0f, 1f)

    // Mockup grid: 92 86 128 150 20, compact 62 112 20, each plus a 10 dp gap.
    fun time(): Dp = lerp(92f, 62f, squeeze.value) + GAP.dp
    fun pid(): Dp = (86f + GAP).dp * ids.value.coerceAtLeast(0f)
    fun tag(): Dp = lerp(128f, 112f, squeeze.value) + GAP.dp
    fun pkg(): Dp = (150f + GAP).dp * ids.value.coerceAtLeast(0f)

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

/**
 * Columns for [compact] view in rows of [shape]. Every column change (the ID columns hiding, compact view's narrower
 * columns, two-line rows) glides with [layoutSpring] when [motion] and the OS allows it, so they move on one clock;
 * otherwise each lands in the same frame.
 */
@Composable
internal fun rememberLogcatColumns(compact: Boolean, shape: LogcatRowShape, motion: Boolean): LogcatColumns {
    val glide = motion && !LocalReduceMotion.current
    val ids = layoutFraction(!(compact || shape != LogcatRowShape.Full), glide)
    val squeeze = layoutFraction(compact, glide)
    val tight = layoutFraction(shape != LogcatRowShape.Full, glide)
    val stack = layoutFraction(shape == LogcatRowShape.Stacked, glide)
    return remember(compact, ids, squeeze, tight, stack) { LogcatColumns(compact, ids, squeeze, tight, stack) }
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
    // Two-line rows have no columns to name: the header folds up as they stack.
    if (!columns.headerShown) return
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.6.sp)
    val edge = scheme.outlineVariant
    Row(
        Modifier.foldBy(columns::stack).fillMaxWidth().background(scheme.surfaceContainerLow)
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

/** Shows the element's top (1 - [folded]) of its height (read during layout), clipping the rest. */
private fun Modifier.foldBy(folded: () -> Float): Modifier = clipToBounds().layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = (p.height * (1f - folded().coerceIn(0f, 1f))).roundToInt()
    layout(p.width, h) { p.place(0, 0) }
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
 * In two-line rows ([LogcatColumns.stack]) the same cells move: the badge, tag and HH:mm:ss on the first line, the
 * message under the tag on the second (2 lines, or all of it with [wrap]).
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
    val timeText = if (columns.compact || columns.stacked) line.time.take(STACKED_TIME_CHARS) else line.time
    val stacking = columns.stacking
    Column(Modifier.fillMaxWidth().then(bg)) {
        Layout(
            content = {
                val dim = scheme.onSurfaceVariant
                // Only the message and trace are selectable. Compose's selection across texts side by side, next to a
                // wrapped message, can end up with start > end and crash in draw.
                // The cells in [rowCells]'s order: time, (PID-TID), tag, (package), badge, message.
                DisableSelection {
                    Text(timeText, Modifier.clipToBounds(), style = LogRowStyle, color = dim, maxLines = 1, softWrap = false)
                    if (columns.idsShown) {
                        Text("${line.pid}-${line.tid}", Modifier.clipToBounds(), style = LogRowStyle, color = dim, maxLines = 1, softWrap = false)
                    }
                    Box(Modifier.clipToBounds()) {
                        FilterCell(line.tag, LogcatColors.tag(line.tag)) { exclude -> actions.filter("tag", line.tag, exclude) }
                    }
                    if (columns.idsShown) {
                        Box(Modifier.clipToBounds()) {
                            val pkg = item.pkg
                            if (pkg != null) FilterCell(pkg, dim) { exclude -> actions.filter("package", pkg, exclude) }
                        }
                    }
                    LevelBadge(line.level)
                }
                Row {
                    MessageText(
                        line.message, words, messageColor, line.level == LogLevel.Assert, wrap,
                        if (stacking) STACKED_MESSAGE_LINES else 1, Modifier.weight(1f, fill = false),
                    )
                    if (entry.hasTrace) DisableSelection { TraceToggle(entry.trace.size, expanded, motion) { actions.toggle(entry.seq) } }
                }
            },
            // Mid-glide an unwrapped message's second line can reach past the row's height, which rides the glide.
            modifier = Modifier.clipToBounds(),
        ) { measurables, constraints -> rowCells(measurables, constraints, columns, timeText.length, wrap) }
        if (entry.hasTrace) {
            AnimatedVisibility(
                expanded,
                enter = if (motion) expandVertically(layoutSpring()) + fadeIn(tween(200)) else EnterTransition.None,
                exit = if (motion) shrinkVertically(layoutSpring()) + fadeOut(tween(160)) else ExitTransition.None,
            ) {
                TraceText(entry.trace, mine, actions.copyFrame, Modifier.startBy(columns::traceIndent).padding(end = PAD.dp, bottom = 2.dp))
            }
        }
    }
}

/** An unwrapped message's lines in a two-line row. */
private const val STACKED_MESSAGE_LINES = 2

/** The time's characters on a two-line row (and in compact view): HH:mm:ss. */
private const val STACKED_TIME_CHARS = 8

/** Between the badge and the tag on a two-line row's first line. */
private const val STACK_BADGE_GAP = 8 // dp

private fun lerpPx(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).roundToInt()

private fun fixedWidth(w: Int): Constraints = Constraints.fixedWidth(w.coerceAtLeast(0))

/**
 * Lays a row's cells out on one line (time, PID-TID, tag, package at their column widths, the badge, then the
 * message filling the rest, all top-aligned in 1 dp of vertical padding, as a Row would), or at [LogcatColumns.stack]
 * 1 on two: the badge, the tag and the time at the end, then the message from the tag's start. Fractions between
 * place and size each cell part way, so the cells glide between the two and the row's height follows: from one line
 * (an unwrapped message counted at one line's height) to the line plus the whole message.
 *
 * [measurables] are the cells in order, PID-TID and package only when there are 6. The time's two-line place is
 * measured for its HH:mm:ss form, [timeChars] being what it shows now (monospace), so the place doesn't jump when the
 * millis drop halfway.
 */
private fun MeasureScope.rowCells(
    measurables: List<Measurable>,
    constraints: Constraints,
    columns: LogcatColumns,
    timeChars: Int,
    wrap: Boolean,
): MeasureResult {
    val ids = measurables.size == 6
    val timeM = measurables[0]
    val pidM = if (ids) measurables[1] else null
    val tagM = measurables[if (ids) 2 else 1]
    val pkgM = if (ids) measurables[3] else null
    val badgeM = measurables[if (ids) 4 else 2]
    val messageM = measurables[if (ids) 5 else 3]
    val width = constraints.maxWidth
    val pad = PAD.dp.roundToPx()
    val gap = GAP.dp.roundToPx()
    val top = 1.dp.roundToPx()
    val s = columns.stack()

    // One line, as the Row did: each fixed cell gets its column width, at most what is left.
    var left = (width - 2 * pad).coerceAtLeast(0)
    val timeCol = columns.time().roundToPx().coerceIn(0, left)
    left -= timeCol
    val pidCol = if (ids) columns.pid().roundToPx().coerceIn(0, left) else 0
    left -= pidCol
    val tagCol = columns.tag().roundToPx().coerceIn(0, left)
    left -= tagCol
    val pkgCol = if (ids) columns.pkg().roundToPx().coerceIn(0, left) else 0
    left -= pkgCol
    val badge = badgeM.measure(Constraints(maxWidth = left))
    val inlineMessage = (left - badge.width - gap).coerceAtLeast(0)
    val timeX0 = pad
    val pidX0 = timeX0 + timeCol
    val tagX0 = pidX0 + pidCol
    val pkgX0 = tagX0 + tagCol
    val badgeX0 = pkgX0 + pkgCol
    val messageX0 = badgeX0 + badge.width + gap

    // Two lines: badge, tag, time at the end; the message under the tag. Not needed on one line.
    val timeOwn = if (s == 0f) {
        timeCol
    } else {
        val now = timeM.maxIntrinsicWidth(Constraints.Infinity)
        val own = if (timeChars > STACKED_TIME_CHARS) now * STACKED_TIME_CHARS / timeChars else now
        own.coerceAtMost((width - 2 * pad).coerceAtLeast(0))
    }
    val tagX1 = pad + badge.width + STACK_BADGE_GAP.dp.roundToPx()
    val timeX1 = (width - pad - timeOwn).coerceAtLeast(tagX1)

    val time = timeM.measure(fixedWidth(lerpPx(timeCol, timeOwn, s)))
    val pid = pidM?.measure(fixedWidth(pidCol))
    val tag = tagM.measure(fixedWidth(lerpPx(tagCol, timeX1 - tagX1, s)))
    val pkg = pkgM?.measure(fixedWidth(pkgCol))
    val message = messageM.measure(fixedWidth(lerpPx(inlineMessage, width - pad - tagX1, s)))
    val line1 = maxOf(time.height, tag.height, badge.height, pid?.height ?: 0, pkg?.height ?: 0)
    val oneLine = if (wrap) message.height else minOf(message.height, line1)
    val height = lerpPx(top + maxOf(line1, oneLine) + top, top + line1 + message.height + top, s)
    val messageY = top + lerpPx(0, line1, s)
    return layout(width, height) {
        time.placeRelative(lerpPx(timeX0, timeX1, s), top)
        pid?.placeRelative(lerpPx(pidX0, tagX1, s), top)
        tag.placeRelative(lerpPx(tagX0, tagX1, s), top)
        pkg?.placeRelative(lerpPx(pkgX0, tagX1, s), top)
        badge.placeRelative(lerpPx(badgeX0, pad, s), top)
        message.placeRelative(lerpPx(messageX0, tagX1, s), messageY)
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
private fun MessageText(message: String, words: List<String>, color: Color, bold: Boolean, wrap: Boolean, lines: Int, modifier: Modifier) {
    val mark = LogcatColors.level(LogLevel.Warn).first.copy(alpha = 0.45f)
    val weight = if (bold) FontWeight.SemiBold else null
    // [lines] unwrapped; all of it with wrap on.
    val maxLines = if (wrap) Int.MAX_VALUE else lines
    val softWrap = wrap || lines > 1
    if (words.isEmpty()) {
        Text(message, modifier, style = LogRowStyle, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = softWrap)
    } else {
        val text = remember(message, words, mark) { highlight(message, words, mark) }
        Text(text, modifier, style = LogRowStyle, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = softWrap)
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
