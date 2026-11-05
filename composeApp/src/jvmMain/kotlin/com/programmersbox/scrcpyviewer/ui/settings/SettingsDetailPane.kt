package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp

/** One category's settings as rows in a card; [entries] is the (possibly search-filtered) subset to show. */
@Composable
fun SettingsDetailPane(
    category: SettingsCategory?,
    entries: List<SettingEntry>,
    query: String,
    bindings: SettingsBindings,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            bindings.state.persistenceError?.let { PersistenceError(it) }
            if (category == null) return@Column
            Text(category.title, style = MaterialTheme.typography.headlineSmall)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                Column {
                    entries.forEachIndexed { i, entry ->
                        // Keyed so a row keeps its own state when search hides the rows above it.
                        key(entry.id) {
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingRow(entry, query, bindings)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersistenceError(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            "Settings can't be saved: $message", color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

@Composable
private fun SettingRow(entry: SettingEntry, query: String, bindings: SettingsBindings) {
    val text = @Composable {
        Text(highlighted(entry.title, query), style = MaterialTheme.typography.bodyLarge)
        entry.description?.let {
            Text(highlighted(it, query), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.notes(bindings)
    }
    val padding = Modifier.fillMaxWidth().then(entry.rowModifier(bindings)).padding(horizontal = 16.dp, vertical = 12.dp)
    when (entry.layout) {
        EntryLayout.Inline -> Row(padding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { text() }
            entry.content(bindings)
        }
        EntryLayout.Block -> Column(padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { text() }
            entry.content(bindings)
        }
    }
}

@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
