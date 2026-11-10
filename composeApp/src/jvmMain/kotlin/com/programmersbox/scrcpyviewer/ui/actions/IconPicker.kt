package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.programmersbox.scrcpyviewer.actions.ActionIcon
import com.programmersbox.scrcpyviewer.actions.ActionIcons

private const val COLUMNS = 8
private val CellSize = 40.dp

/** The icon picker as a popover just under its anchor (the editor's 56 dp icon button). */
@Composable
fun IconPickerPopup(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val below = with(LocalDensity.current) { 64.dp.roundToPx() }
    Popup(
        offset = IntOffset(0, below),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        IconPickerContent(selected, onPick, onDismiss)
    }
}

/**
 * Search field, then the icon groups in an 8-column grid. The [selected] icon is filled with the accent color.
 * Arrow keys move the highlight (Left and Right only while the search is empty), Enter picks it, Esc closes.
 */
@Composable
fun IconPickerContent(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val groups = remember(query) { ActionIcons.search(query) }
    // Grid rows across all groups, so Up / Down cross group labels.
    val rows = remember(groups) { groups.flatMap { it.icons.chunked(COLUMNS) } }
    val flat = remember(rows) { rows.flatten() }
    var highlight by remember { mutableIntStateOf(0) }
    LaunchedEffect(flat) { highlight = flat.indexOfFirst { it.name == selected }.coerceAtLeast(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    /** Moves the highlight [delta] rows, keeping its column where the target row is long enough. */
    fun moveVertically(delta: Int) {
        if (flat.isEmpty()) return
        var start = 0
        var row = 0
        while (row < rows.lastIndex && highlight >= start + rows[row].size) start += rows[row++].size
        val column = highlight - start
        val target = (row + delta).coerceIn(rows.indices)
        highlight = rows.take(target).sumOf { it.size } + column.coerceAtMost(rows[target].size - 1)
    }

    Surface(
        modifier = modifier.width(CellSize * COLUMNS + 32.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                // With a search typed, Left and Right move the caret in the field instead.
                Key.DirectionLeft -> if (query.isEmpty()) highlight = (highlight - 1).coerceAtLeast(0) else return@onPreviewKeyEvent false
                Key.DirectionRight -> if (query.isEmpty()) {
                    highlight = (highlight + 1).coerceAtMost((flat.size - 1).coerceAtLeast(0))
                } else {
                    return@onPreviewKeyEvent false
                }
                Key.DirectionUp -> moveVertically(-1)
                Key.DirectionDown -> moveVertically(1)
                Key.Enter, Key.NumPadEnter -> flat.getOrNull(highlight)?.let { onPick(it.name) }
                Key.Escape -> onDismiss()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
        shape = styledCorner(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Search icons") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (groups.isEmpty()) {
                    Text("No icons match", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var offset = 0
                groups.forEach { group ->
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    group.icons.chunked(COLUMNS).forEach { row ->
                        Row {
                            row.forEachIndexed { i, icon ->
                                IconCell(icon, isSelected = icon.name == selected, isHighlighted = offset + i == highlight) { onPick(icon.name) }
                            }
                        }
                        offset += row.size
                    }
                }
            }
        }
    }
}

@Composable
private fun IconCell(icon: ActionIcon, isSelected: Boolean, isHighlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = styledCorner(10.dp)
    Box(
        Modifier.size(CellSize).padding(2.dp).clip(shape)
            .background(if (isSelected) colors.primary else colors.surfaceContainerHigh)
            // The keyboard highlight: a ring, in a color that also shows on the selected fill.
            .then(if (isHighlighted) Modifier.border(2.dp, if (isSelected) colors.onSurface else colors.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon.vector, contentDescription = icon.name, tint = if (isSelected) colors.onPrimary else colors.onSurfaceVariant)
    }
}
