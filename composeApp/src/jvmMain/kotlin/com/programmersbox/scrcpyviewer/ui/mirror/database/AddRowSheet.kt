package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.database.ColumnInfo
import com.programmersbox.scrcpyviewer.database.SqlValue

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)

/** Why [text] can't go in a column of [type]; null when it can (or is empty). */
fun addRowError(text: String, type: String): String? {
    if (text.isEmpty() || text == "NULL") return null
    val t = type.uppercase()
    return when {
        "INT" in t && text.toLongOrNull() == null -> "Needs a whole number"
        ("REAL" in t || "FLOA" in t || "DOUB" in t) && text.toDoubleOrNull() == null -> "Needs a number"
        else -> null
    }
}

/** The values to insert: the filled-in fields only, so the others take their defaults (or NULL). */
fun addRowValues(columns: List<ColumnInfo>, texts: List<String>): Map<String, SqlValue> =
    columns.indices.filter { texts[it].isNotEmpty() }.associate { i -> columns[i].name to parseCell(texts[i], columns[i].type, SqlValue.Null) }

/**
 * The + Row form, sliding up over the Browse view while [visible]: a field per column of [table]. Required columns
 * (NOT NULL without a default) are marked and must be filled before Add row; empty fields take the column's default,
 * or NULL. The first required field has focus; Esc cancels.
 */
@Composable
fun BoxScope.AddRowSheet(
    visible: Boolean,
    table: String,
    columns: List<ColumnInfo>,
    motion: Boolean,
    onAdd: (Map<String, SqlValue>) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible, Modifier.matchParentSize(),
        enter = fadeIn(if (motion) tween(200) else snap()), exit = fadeOut(if (motion) tween(160) else snap()),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(remember { MutableInteractionSource() }, null, onClick = onCancel))
    }
    AnimatedVisibility(
        visible, Modifier.align(Alignment.BottomCenter),
        enter = if (motion) slideInVertically(tween(340)) { it } else fadeIn(snap()),
        exit = if (motion) slideOutVertically(tween(220)) { it } else fadeOut(snap()),
    ) {
        val texts = remember(table, columns) { mutableStateListOf(*Array(columns.size) { "" }) }
        val first = remember { FocusRequester() }
        val focusAt = columns.indexOfFirst { it.required }.coerceAtLeast(0)
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val errors = columns.indices.map { addRowError(texts[it], columns[it].type) }
        val ready = errors.all { it == null } && columns.indices.none { columns[it].required && texts[it].isBlank() }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .background(colors.surfaceContainerHighest, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(32.dp, 4.dp).background(colors.outline, RoundedCornerShape(2.dp)))
            Text("New row in $table", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.forEachIndexed { i, c ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, style = Mono.copy(fontWeight = FontWeight.Medium))
                            if (c.type.isNotEmpty()) Text(c.type, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            if (c.required) Text("required", style = MaterialTheme.typography.labelSmall, color = colors.error, fontWeight = FontWeight.SemiBold)
                        }
                        val placeholder = when {
                            c.rowidAlias -> "auto"
                            c.default != null -> "default: ${c.default}"
                            c.notNull -> ""
                            else -> "NULL"
                        }
                        BasicTextField(
                            texts[i], { texts[i] = it },
                            Modifier.fillMaxWidth().then(if (i == focusAt) Modifier.focusRequester(first) else Modifier)
                                .background(colors.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, if (errors[i] != null) colors.error else colors.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            singleLine = true, textStyle = Mono.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                            decorationBox = { inner ->
                                Box {
                                    if (texts[i].isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Mono, color = colors.outline)
                                    inner()
                                }
                            },
                        )
                        errors[i]?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { onAdd(addRowValues(columns, texts.toList())) }, enabled = ready) { Text("Add row") }
            }
        }
    }
}
