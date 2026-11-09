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
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.staggerDelayMillis
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance

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
                // The rows fill in one after another when they appear and whenever search changes which are shown.
                val shownIds = remember(entries) { entries.map { it.id } }
                Column {
                    entries.forEachIndexed { i, entry ->
                        // Keyed so a row keeps its own state when search hides the rows above it.
                        key(entry.id) {
                            Column(Modifier.staggeredEntrance(i, trigger = shownIds)) {
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingRow(entry, query, bindings, staggerDelayMillis(i), shownIds)
                            }
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
private fun SettingRow(entry: SettingEntry, query: String, bindings: SettingsBindings, entranceDelayMillis: Int, shownIds: Any) {
    val text = @Composable {
        HighlightedText(entry.title, query, MaterialTheme.typography.bodyLarge, sweepDelayMillis = entranceDelayMillis, trigger = shownIds)
        entry.description?.let {
            HighlightedText(
                it, query, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant,
                sweepDelayMillis = entranceDelayMillis, trigger = shownIds,
            )
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
