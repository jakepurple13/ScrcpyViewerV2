package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction
import kotlinx.coroutines.flow.filter

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
private val FieldShape = RoundedCornerShape(12.dp)

/**
 * The Shell tab, sized for the 400 dp sheet: filter, live bar while a command streams, the console, a chip per custom
 * run-once action (names only) and the command field. The field takes focus each time [selected] turns true.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellTabContent(
    console: ShellConsole,
    chips: List<RunOnceAction>,
    selected: Boolean = true,
    onEvent: (QuickActionEvent) -> Unit,
) {
    val copy = rememberCopyAction()
    fun visibleText() = console.visible.joinToString("\n") { it.text }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(console)
        if (console.running) {
            LiveBar(console, onCopy = { copy(visibleText()) })
        }
        Console(console, Modifier.weight(1f).fillMaxWidth(), onCopy = { copy(visibleText()) })
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip ->
                    AssistChip(onClick = { onEvent(QuickActionEvent.RunCustom(chip.id, fromChip = true)) }, label = { Text(chip.name, maxLines = 1) })
                }
            }
        }
        CommandField(console, selected)
    }
}

@Composable
private fun FilterField(console: ShellConsole) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(FieldShape).background(colors.surfaceContainerHighest).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (console.filter.isEmpty()) Text("Filter output", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            BasicTextField(
                value = console.filter,
                onValueChange = { console.filter = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && console.filter.isNotEmpty()) {
                        console.filter = ""
                        true
                    } else false
                },
            )
        }
        if (console.filter.isNotEmpty()) {
            Text("${console.matchCount} of ${console.lineCount}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            IconButton(onClick = { console.filter = "" }, Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear filter", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LiveBar(console: ShellConsole, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colors.error, CircleShape))
        Text(
            "Streaming · ${console.lineCount} lines", Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1,
        )
        TextButton(onClick = { console.paused = !console.paused }) { Text(if (console.paused) "Resume" else "Pause") }
        TextButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
            Text("Copy", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Console(console: ShellConsole, modifier: Modifier, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    val visible = console.visibleKeyed
    val running = console.running
    // Lines appended from here on rise in; the history already there, filter changes and scrolling do not animate.
    val arrivals = remember { LineArrivals() }
    arrivals.observe(console.nextSeq, console.filter)
    // Follow the bottom until the user scrolls up; reaching the bottom again resumes. Only the user's scrolling (wheel,
    // drag) stops it: it arrives through nested scroll, which our own scrollToItem does not dispatch.
    var following by remember { mutableStateOf(true) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y > 0f) following = false // content moved down: the user scrolled toward older lines
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect { if (!state.canScrollForward) following = true }
    }
    // Pause holds the view only while output can still arrive; once the command ends its last lines show.
    val paused = console.paused && console.running
    // The caret is the last item while a command runs.
    val lastIndex = visible.size - if (running) 0 else 1
    LaunchedEffect(visible.size, visible.lastOrNull(), paused, running) {
        if (!state.canScrollForward) following = true
        if (following && !paused && lastIndex >= 0) state.scrollToItem(lastIndex)
    }
    Box(modifier.nestedScroll(userScroll).clip(FieldShape).background(colors.surfaceContainerLowest).border(1.dp, colors.outlineVariant, FieldShape)) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                items(visible, key = { it.seq }, contentType = { it.line::class }) { keyed ->
                    val animate = remember { arrivals.claim(keyed.seq) }
                    ConsoleRow(keyed.line, console.filter, animate)
                }
                if (running) {
                    item(key = CaretKey) { BlinkingCaret(MonoStyle, colors.primary) }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLowest.copy(alpha = 0.9f))) {
            IconButton(onClick = onCopy, Modifier.size(28.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy output", Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            }
            IconButton(onClick = { console.clear() }, Modifier.size(28.dp)) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear output", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** The running caret's item key, never equal to a line's seq. */
private object CaretKey

/** One console line; [animate] rises it in (and pops an exit pill) as it arrives. */
@Composable
private fun ConsoleRow(line: ConsoleLine, filter: String, animate: Boolean) {
    val colors = MaterialTheme.colorScheme
    val highlight = colors.primary.copy(alpha = 0.3f)
    val rise = Modifier.riseIn(animate)
    when (line) {
        is ConsoleLine.Header -> Text(
            line.text, Modifier.padding(top = 6.dp).then(rise), style = MonoStyle,
            color = if (line.typed) colors.primary else colors.tertiary, fontWeight = FontWeight.SemiBold,
        )
        is ConsoleLine.Out -> Text(highlighted(line.text, filter, highlight), rise, style = MonoStyle, color = colors.onSurface)
        is ConsoleLine.Exit -> ExitPill(line.code, line.millis, MonoStyle, animate, rise)
    }
}

/** [text] with every case-insensitive occurrence of [filter] on a [highlight] background. */
private fun highlighted(text: String, filter: String, highlight: Color): AnnotatedString {
    if (filter.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        while (true) {
            val hit = text.indexOf(filter, at, ignoreCase = true)
            if (hit < 0) break
            append(text.substring(at, hit))
            withStyle(SpanStyle(background = highlight)) { append(text.substring(hit, hit + filter.length)) }
            at = hit + filter.length
        }
        append(text.substring(at))
    }
}

@Composable
private fun CommandField(console: ShellConsole, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(selected) { if (selected) runCatching { focus.requestFocus() } }
    // The field keeps its own selection; the console owns the text (history recall and clearing change it).
    var value by remember { mutableStateOf(TextFieldValue(console.input)) }
    if (value.text != console.input) value = TextFieldValue(console.input, TextRange(console.input.length))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(FieldShape).background(colors.surfaceContainerHighest).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = MonoStyle.copy(fontSize = 14.sp), color = colors.primary, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (value.text.isEmpty()) Text("Type an adb shell command", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        if (it.text != console.input) console.input = it.text
                    },
                    singleLine = true,
                    textStyle = MonoStyle.copy(fontSize = 13.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> { console.submit(); true }
                            event.key == Key.DirectionUp -> { console.historyUp(); true }
                            event.key == Key.DirectionDown -> { console.historyDown(); true }
                            // Ctrl, not Cmd, so Cmd+C stays copy on macOS; with a selection Ctrl+C copies too.
                            event.key == Key.C && event.isCtrlPressed && console.running && value.selection.collapsed -> { console.stop(); true }
                            else -> false
                        }
                    },
                )
            }
        }
        if (console.running) {
            Button(
                onClick = { console.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
            ) { Text("■ Stop") }
        } else {
            Button(onClick = { console.submit() }, enabled = console.input.isNotBlank()) { Text("Run") }
        }
    }
}
