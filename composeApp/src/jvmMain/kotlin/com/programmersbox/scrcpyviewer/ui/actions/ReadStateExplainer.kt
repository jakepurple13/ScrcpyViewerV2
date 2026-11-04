package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The flow, result chips, match table and footnote under the "How a toggle knows it's on" intro page. */
@Composable
internal fun ReadStateExplainer() {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowBox("Read state", "settings get global stay_on_while_plugged_in", Modifier.weight(1.5f))
            FlowArrow()
            FlowBox("Device prints", "7", Modifier.weight(0.8f))
            FlowArrow()
            FlowBox("On when output", "equals 7", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ResultChip("7 → On", filled = true)
            ResultChip("0 → Off", filled = false)
            ResultChip("error → unknown", filled = false)
        }
        MatchTable()
        Text(
            "Spaces and line breaks around the output are ignored. No read command? The toggle shows whatever you last set it to.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowBox(label: String, text: String, modifier: Modifier) {
    Surface(
        modifier.fillMaxHeight(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FlowArrow() {
    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultChip(text: String, filled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (filled) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (filled) colors.onPrimary else colors.onSurface,
        border = if (filled) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}

private val matchRows = listOf(
    Triple("equals", "is exactly the value", "1"),
    Triple("contains", "includes the value anywhere", "mWakefulness=Awake"),
    Triple("matches regex", "matches a pattern", "^[1-3]$"),
)

@Composable
private fun MatchTable() {
    Column(Modifier.fillMaxWidth()) {
        TableRow("Match", "On when the output…", "Example", header = true)
        matchRows.forEach { (match, rule, example) ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TableRow(match, rule, example, header = false)
        }
    }
}

@Composable
private fun TableRow(match: String, rule: String, example: String, header: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = if (header) colors.primary else colors.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = if (header) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(match, Modifier.weight(1f), style = style, color = color)
        Text(rule, Modifier.weight(1.6f), style = style, color = color)
        Text(
            example, Modifier.weight(1.4f), style = style, color = color,
            fontFamily = if (header) null else FontFamily.Monospace,
        )
    }
}
