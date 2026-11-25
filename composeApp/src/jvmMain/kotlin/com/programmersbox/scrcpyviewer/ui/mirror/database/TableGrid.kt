package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.database.SqlValue
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring

/** A grid column: its name, declared type (shown small) and whether it's part of the primary key. */
data class GridColumn(val name: String, val type: String = "", val key: Boolean = false)

/** How a cell reads in the grid. */
fun cellText(v: SqlValue): String = when (v) {
    SqlValue.Null -> "NULL"
    is SqlValue.Integer -> v.v.toString()
    is SqlValue.Real -> v.v.toString()
    is SqlValue.Text -> v.v
    is SqlValue.Blob -> "BLOB · ${v.size} bytes"
}

/** What the user typed into a cell, as a value for a column of [type]: numbers stay numbers, NULL stays NULL. */
fun parseCell(text: String, type: String, was: SqlValue): SqlValue {
    if (text.isEmpty() && was == SqlValue.Null) return SqlValue.Null
    if (text == "NULL") return SqlValue.Null
    val t = type.uppercase()
    if ("INT" in t) text.toLongOrNull()?.let { return SqlValue.Integer(it) }
    if ("REAL" in t || "FLOA" in t || "DOUB" in t) text.toDoubleOrNull()?.let { return SqlValue.Real(it) }
    return SqlValue.Text(text)
}

private fun columnWidth(c: GridColumn): Dp = if ("INT" in c.type.uppercase() || "REAL" in c.type.uppercase()) 96.dp else 180.dp

private val ROW_NUMBER_WIDTH = 48.dp
private val CELL_HEIGHT = 30.dp
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/**
 * Rows as a grid with a sticky header, scrolling both ways. When [onEdit] is set, a double-click opens a cell for
 * editing (Enter saves, Esc cancels) and a right-click on a row offers to delete it ([onDelete]). [edited] cells,
 * by (row, column), take the primary container tint, fading in. Rows rise in on [motion].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TableGrid(
    columns: List<GridColumn>,
    rows: List<List<SqlValue>>,
    modifier: Modifier = Modifier,
    edited: Set<Pair<Int, Int>> = emptySet(),
    motion: Boolean = false,
    onEdit: ((row: Int, column: Int, SqlValue) -> Unit)? = null,
    onDelete: ((row: Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val widths = columns.map(::columnWidth)
    val total = ROW_NUMBER_WIDTH + widths.fold(0.dp) { a, b -> a + b }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menuRow by remember { mutableStateOf<Int?>(null) }
    val horizontal = rememberScrollState()
    Box(modifier.fillMaxSize().horizontalScroll(horizontal)) {
        Column(Modifier.width(total).fillMaxHeight()) {
            Row(Modifier.height(CELL_HEIGHT).background(colors.surfaceContainerHigh), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(ROW_NUMBER_WIDTH))
                columns.forEachIndexed { i, c ->
                    Row(Modifier.width(widths[i]).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = Mono.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (c.type.isNotEmpty()) Text(" ${c.type}", style = Mono.copy(fontSize = 10.sp), color = colors.onSurfaceVariant, maxLines = 1)
                        if (c.key) Text(" PK", style = Mono.copy(fontSize = 10.sp), color = colors.primary, maxLines = 1)
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), rememberLazyListState()) {
                itemsIndexed(rows) { r, row ->
                    Box(
                        (if (motion) Modifier.animateItem(placementSpec = layoutSpring()) else Modifier)
                            .then(
                                if (onDelete != null) {
                                    Modifier.onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) menuRow = r }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Row(Modifier.height(CELL_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${r + 1}", Modifier.width(ROW_NUMBER_WIDTH).padding(end = 8.dp), style = Mono,
                                color = colors.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1,
                            )
                            row.forEachIndexed { c, value ->
                                val dirty = (r to c) in edited
                                val tint by animateColorAsState(if (dirty) colors.primaryContainer else Color.Transparent, tween(if (motion) 300 else 0))
                                Box(
                                    Modifier.width(widths[c]).fillMaxHeight().background(tint)
                                        .then(
                                            if (onEdit != null && value !is SqlValue.Blob) {
                                                Modifier.doublePress { editing = r to c }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (editing == r to c && onEdit != null) {
                                        CellEditor(
                                            initial = if (value == SqlValue.Null) "" else cellText(value),
                                            onDone = { text ->
                                                editing = null
                                                if (text != null) onEdit(r, c, parseCell(text, columns[c].type, value))
                                            },
                                        )
                                    } else {
                                        Text(
                                            cellText(value), style = Mono.copy(fontStyle = if (value == SqlValue.Null) FontStyle.Italic else FontStyle.Normal),
                                            color = when {
                                                dirty -> colors.onPrimaryContainer
                                                value == SqlValue.Null || value is SqlValue.Blob -> colors.outline
                                                else -> colors.onSurface
                                            },
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            textAlign = if (value is SqlValue.Integer || value is SqlValue.Real) TextAlign.End else TextAlign.Start,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                        DropdownMenu(expanded = menuRow == r, onDismissRequest = { menuRow = null }) {
                            DropdownMenuItem(
                                text = { Text("Delete row", color = colors.error) },
                                onClick = {
                                    menuRow = null
                                    onDelete?.invoke(r)
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = colors.surfaceContainerHigh)
                }
            }
        }
    }
}

/** Calls [onDouble] on a second primary press within the double-tap timeout (no minimum gap, like the Files tab). */
private fun Modifier.doublePress(onDouble: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onDouble)
    pointerInput(Unit) {
    var last = Long.MIN_VALUE
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press || !event.buttons.isPrimaryPressed) continue
            val time = event.changes.firstOrNull()?.uptimeMillis ?: continue
            val double = last != Long.MIN_VALUE && time - last <= viewConfiguration.doubleTapTimeoutMillis
            last = if (double) Long.MIN_VALUE else time
            if (double) current()
        }
    }
    }
}

/** A cell's text field: focused at once with the caret at the end. [onDone] gets the text (Enter, or focus moving away) or null (Esc). */
@Composable
private fun CellEditor(initial: String, onDone: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    fun finish(text: String?) {
        if (done) return
        done = true
        onDone(text)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value, { value = it },
        Modifier.fillMaxWidth().background(colors.surface).focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) finish(value.text)
                focused = it.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.Escape || e.key == Key.Enter
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        finish(value.text)
                        true
                    }
                    Key.Escape -> {
                        finish(null)
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = Mono.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
    )
}
