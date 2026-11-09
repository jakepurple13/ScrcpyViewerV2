package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

data class CategoryMatch(val category: SettingsCategory, val entries: List<SettingEntry>)

/**
 * The categories with at least one entry matching [query], each with its matching entries. A category whose title
 * matches keeps all its entries. Blank (after trim) query → every category with all entries.
 */
fun searchSettings(categories: List<SettingsCategory>, query: String): List<CategoryMatch> {
    val q = query.trim()
    if (q.isEmpty()) return categories.map { CategoryMatch(it, it.entries) }
    return categories.mapNotNull { category ->
        val entries = if (category.title.contains(q, ignoreCase = true)) category.entries
        else category.entries.filter { it.matches(q) }
        if (entries.isEmpty()) null else CategoryMatch(category, entries)
    }
}

private fun SettingEntry.matches(q: String) =
    title.contains(q, ignoreCase = true) ||
        description?.contains(q, ignoreCase = true) == true ||
        keywords.any { it.contains(q, ignoreCase = true) }

/** Case-insensitive, literal, non-overlapping occurrences of trimmed [query] in [text]; empty for a blank query. */
fun matchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return buildList {
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            add(i until i + q.length)
            from = i + q.length
        }
    }
}

/** How long a match's underline takes to sweep in under it, left to right. */
const val MATCH_SWEEP_MILLIS = 400

private object NoTrigger

/**
 * [text] with each occurrence of [query] in the primary color and semibold, underlined by a line that sweeps in
 * left to right. The sweep waits [sweepDelayMillis] (its row's entrance delay) when [trigger] changed since the last
 * sweep (the row is entering); a new query alone sweeps straight away. The underline is drawn from the text layout's
 * bounds of each match, one segment per line it spans.
 */
@Composable
internal fun HighlightedText(
    text: String,
    query: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    sweepDelayMillis: Int = 0,
    trigger: Any? = null,
) {
    val ranges = remember(text, query) { matchRanges(text, query) }
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, ranges, primary) {
        if (ranges.isEmpty()) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            ranges.forEach { addStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val sweep = remember { Animatable(0f) }
    val swept = remember { arrayOf<Any?>(NoTrigger) }
    LaunchedEffect(ranges, trigger) {
        val entering = swept[0] != trigger
        swept[0] = trigger
        sweep.snapTo(0f)
        if (ranges.isEmpty()) return@LaunchedEffect
        val wait = if (entering) sweepDelayMillis else 0
        sweep.animateTo(1f, tween(MATCH_SWEEP_MILLIS, delayMillis = wait, easing = EmphasizedDecelerate))
    }
    Text(
        annotated, style = style, color = color, onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            // Read here, so the sweep only redraws.
            val p = sweep.value
            val l = layout ?: return@drawBehind
            if (p == 0f) return@drawBehind
            val gap = 2.dp.toPx()
            val stroke = 1.5.dp.toPx()
            ranges.forEach { range ->
                underlineSegments(l, range).forEach { (left, right, line) ->
                    val y = l.getLineBaseline(line) + gap
                    drawLine(primary, Offset(left, y), Offset(left + (right - left) * p, y), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        },
    )
}

/** (left, right, line) for each line [range] spans in [layout]. */
private fun underlineSegments(layout: TextLayoutResult, range: IntRange): List<Triple<Float, Float, Int>> {
    if (range.isEmpty() || range.last >= layout.layoutInput.text.length) return emptyList()
    return (layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last)).mapNotNull { line ->
        val start = maxOf(range.first, layout.getLineStart(line))
        val end = minOf(range.last, layout.getLineEnd(line, visibleEnd = true) - 1)
        if (start > end) null else Triple(layout.getBoundingBox(start).left, layout.getBoundingBox(end).right, line)
    }
}
