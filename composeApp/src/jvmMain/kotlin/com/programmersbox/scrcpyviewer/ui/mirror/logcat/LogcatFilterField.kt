package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.programmersbox.scrcpyviewer.logcat.LogcatQuery
import com.programmersbox.scrcpyviewer.logcat.QuerySpan
import com.programmersbox.scrcpyviewer.logcat.SpanKind
import com.programmersbox.scrcpyviewer.logcat.Suggestion
import com.programmersbox.scrcpyviewer.logcat.applyTo
import com.programmersbox.scrcpyviewer.logcat.suggest
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

private val FieldShape = RoundedCornerShape(8.dp)
private val PopupShape = RoundedCornerShape(10.dp)
internal val LogcatMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/**
 * [text] with the query syntax colors of [spans]: keys, `-`, operators and quoted values in [color]; an invalid term
 * underlined in its color (Compose has no wavy underline).
 */
internal fun highlightQuery(text: String, spans: List<QuerySpan>, color: (SpanKind) -> Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (s in spans) {
            if (s.start < 0 || s.end > text.length || s.start >= s.end) continue
            val style = when (s.kind) {
                SpanKind.Key -> SpanStyle(color = color(s.kind), fontWeight = FontWeight.Medium)
                SpanKind.Negation, SpanKind.Operator -> SpanStyle(color = color(s.kind), fontWeight = FontWeight.SemiBold)
                SpanKind.Quoted -> SpanStyle(color = color(s.kind))
                SpanKind.Error -> SpanStyle(color = color(s.kind), textDecoration = TextDecoration.Underline)
            }
            addStyle(style, s.start, s.end)
        }
    }

/** What the autocomplete shows: [items] for the token at [start]; [keys] when they are filter keys (with the footer). */
private class Shown(val items: List<Suggestion>, val start: Int, val keys: Boolean)

/**
 * The filter field: the query in monospace with syntax colors, a clear button, autocomplete under the caret's token
 * (Up/Down move, Tab or Enter accepts, Esc closes) and the error hint, which opens under the field. The query text in
 * [controller] is the only state; chips and accepted suggestions edit it.
 */
@Composable
internal fun LogcatFilterField(controller: LogcatController, motion: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val query = controller.query
    var value by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // A change from outside (a chip, a tag click) puts the caret at the end.
    val shown = if (value.text == query) value else TextFieldValue(query, TextRange(query.length))
    fun change(v: TextFieldValue) {
        value = v
        if (v.text != controller.query) controller.query = v.text
    }

    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(shown.text) { dismissed = false }
    val caret = shown.selection.end
    val suggestions = remember(shown.text, caret, focused, shown.selection.collapsed) {
        if (!focused || !shown.selection.collapsed) {
            emptyList()
        } else {
            // Not observed: new lines shouldn't recompose the field; the list refreshes as the user types.
            Snapshot.withoutReadObservation { suggest(shown.text, caret, controller.suggestionContext()) }
        }
    }
    var selected by remember(suggestions) { mutableIntStateOf(0) }
    val open = focused && !dismissed && suggestions.isNotEmpty()
    val held = remember { arrayOf(Shown(emptyList(), 0, true)) }
    if (open) {
        val start = suggestions.first().replaceStart
        held[0] = Shown(suggestions, start, ':' !in shown.text.substring(start, caret))
    }
    val focus = remember { FocusRequester() }
    fun accept(i: Int) {
        val s = suggestions.getOrNull(i) ?: return
        val (t, c) = s.applyTo(shown.text)
        change(TextFieldValue(t, TextRange(c)))
        focus.requestFocus()
    }

    val bad = controller.parsed.error != null
    val border by animateColorAsState(
        when {
            bad -> scheme.error
            focused -> scheme.primary
            else -> scheme.primary.copy(alpha = 0f)
        },
        if (motion) tween(200) else snap(),
    )
    val container by animateColorAsState(
        if (focused) scheme.surface else scheme.surfaceContainerHigh,
        if (motion) tween(200) else snap(),
    )
    val colorFor = SpanKind.entries.associateWith { LogcatColors.query(it) }
    val transformation = remember(colorFor) {
        VisualTransformation { t ->
            TransformedText(highlightQuery(t.text, LogcatQuery.parse(t.text).spans, colorFor::getValue), OffsetMapping.Identity)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var wrapX by remember { mutableFloatStateOf(0f) }
    var fieldX by remember { mutableFloatStateOf(0f) }
    var wrapWidth by remember { mutableIntStateOf(0) }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned { wrapX = it.positionInRoot().x; wrapWidth = it.size.width }) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(container, FieldShape).border(1.dp, border, FieldShape)
                    .padding(start = 9.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (shown.text.isEmpty()) {
                        Text(
                            "Filter: package:mine level:warn tag:OkHttp",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = shown,
                        onValueChange = ::change,
                        singleLine = true,
                        textStyle = LogcatMono.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(focus)
                            .onGloballyPositioned { fieldX = it.positionInRoot().x }
                            .onFocusChanged {
                                if (it.isFocused && !focused) dismissed = false
                                focused = it.isFocused
                            }
                            .semantics { contentDescription = "Logcat filter" }
                            .onPreviewKeyEvent { e ->
                                if (!open || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> { selected = (selected + 1) % suggestions.size; true }
                                    Key.DirectionUp -> { selected = (selected - 1 + suggestions.size) % suggestions.size; true }
                                    Key.Tab, Key.Enter, Key.NumPadEnter -> { accept(selected); true }
                                    Key.Escape -> { dismissed = true; true }
                                    else -> false
                                }
                            },
                    )
                }
                AnimatedVisibility(
                    visible = shown.text.isNotEmpty(),
                    enter = if (motion) fadeIn(tween(150)) + scaleIn(tween(200, easing = EmphasizedDecelerate), initialScale = 0.6f) else EnterTransition.None,
                    exit = if (motion) fadeOut(tween(150)) + scaleOut(tween(200, easing = EmphasizedAccelerate), targetScale = 0.6f) else ExitTransition.None,
                ) {
                    IconButton(onClick = { change(TextFieldValue("")); focus.requestFocus() }, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear filter", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }

            val pop = remember { MutableTransitionState(false) }
            pop.targetState = open
            if (pop.currentState || pop.targetState) {
                val s = held[0]
                val tokenX = layout?.let { l -> l.getHorizontalPosition(s.start.coerceIn(0, l.layoutInput.text.length), true) } ?: 0f
                val minWidth = with(density) { 270.dp.toPx() }
                val x = (fieldX - wrapX + tokenX - with(density) { 8.dp.toPx() })
                    .coerceAtMost(wrapWidth - minWidth).coerceAtLeast(0f)
                Popup(
                    offset = IntOffset(x.toInt(), with(density) { 40.dp.roundToPx() }),
                    properties = PopupProperties(focusable = false),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = pop,
                        enter = if (motion) {
                            fadeIn(tween(140)) + scaleIn(tween(180, easing = EmphasizedDecelerate), 0.94f, TransformOrigin(0f, 0f))
                        } else {
                            EnterTransition.None
                        },
                        exit = if (motion) fadeOut(tween(120)) + scaleOut(tween(120), 0.94f, TransformOrigin(0f, 0f)) else ExitTransition.None,
                    ) {
                        SuggestionList(s, selected, motion, onPick = ::accept)
                    }
                }
            }
        }

        val heldError = remember { arrayOf("") }
        controller.parsed.error?.let { heldError[0] = it }
        AnimatedVisibility(
            visible = bad,
            enter = if (motion) expandVertically(tween(220, easing = EmphasizedDecelerate)) + fadeIn(tween(220)) else EnterTransition.None,
            exit = if (motion) shrinkVertically(tween(220, easing = EmphasizedAccelerate)) + fadeOut(tween(180)) else ExitTransition.None,
        ) {
            Text(
                heldError[0],
                Modifier.padding(start = 2.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionList(shown: Shown, selected: Int, motion: Boolean, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.widthIn(min = 260.dp, max = 360.dp).border(1.dp, scheme.outlineVariant, PopupShape),
        shape = PopupShape,
        color = scheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(4.dp)) {
            shown.items.forEachIndexed { i, s ->
                val bg by animateColorAsState(
                    if (i == selected) scheme.surfaceContainerHighest else scheme.surfaceContainerHighest.copy(alpha = 0f),
                    if (motion) tween(120) else snap(),
                )
                Row(
                    Modifier.fillMaxWidth().background(bg, RoundedCornerShape(7.dp)).clickable { onPick(i) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.label, style = LogcatMono.copy(fontSize = 12.5.sp), color = LogcatColors.query(SpanKind.Key), maxLines = 1)
                    if (s.description.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (shown.keys) {
                HorizontalDivider(Modifier.padding(top = 4.dp), color = scheme.outlineVariant)
                val op = LogcatColors.query(SpanKind.Operator)
                Text(
                    buildAnnotatedString {
                        listOf("-" to "excludes", "~" to "regex", "=" to "exact", "|" to "or").forEachIndexed { i, (c, w) ->
                            if (i > 0) append(" · ")
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = op)) { append(c) }
                            append(" $w")
                        }
                    },
                    Modifier.padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
