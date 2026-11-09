package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The search field and the category list. Plain text items, so it doesn't read as a second rail. */
@Composable
fun SettingsListPane(
    matches: List<CategoryMatch>,
    selected: SettingsCategoryId?,
    query: String,
    searching: Boolean,
    updateAvailable: Boolean,
    onQuery: (String) -> Unit,
    onSelect: (SettingsCategoryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(200.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("Search", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        if (matches.isEmpty()) {
            Text(
                "No settings match", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // The categories fill in one after another when the list appears and whenever search changes which are shown.
        val shownIds = matches.map { it.category.id }
        matches.forEachIndexed { i, match ->
            val id = match.category.id
            key(id) {
                CategoryItem(
                    title = match.category.title,
                    selected = id == selected,
                    count = if (searching) match.entries.size else null,
                    badge = id == SettingsCategoryId.Updates && updateAvailable,
                    onClick = { onSelect(id) },
                    modifier = Modifier.staggeredEntrance(i, trigger = shownIds),
                )
            }
        }
    }
}

@Composable
private fun CategoryItem(
    title: String,
    selected: Boolean,
    count: Int?,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "categoryFill",
    )
    val bar by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "categoryBar")
    Row(
        modifier.fillMaxWidth().clip(shape)
            .drawBehind { drawRect(fill) }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).drawBehind { drawRect(bar) })
        Text(
            title, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 9.dp, top = 8.dp, bottom = 8.dp),
        )
        if (badge) Badge(Modifier.padding(end = 8.dp))
        count?.let {
            Text(
                "$it", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
