package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.options.OptionSpec
import com.programmersbox.scrcpyviewer.options.OptionType

/**
 * One option: checkbox, label with its `--flag`, and (for valued options) a value field that is enabled only while
 * the option is checked. [error] is shown under the row.
 */
@Composable
fun OptionRow(
    spec: OptionSpec,
    checked: Boolean,
    value: String?,
    error: String?,
    onCheckedChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldKey: Any? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(Modifier.weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "--${spec.flag}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (spec.type !is OptionType.Flag) {
                ValueField(spec.type, checked, value.orEmpty(), error != null, Pair(fieldKey, spec.flag), onValueChange, Modifier.width(140.dp))
            }
        }
        if (error != null && checked) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun ValueField(
    type: OptionType,
    enabled: Boolean,
    value: String,
    isError: Boolean,
    fieldKey: Any?,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val inner = (type as? OptionType.OptionalValue)?.inner ?: type
    if (inner is OptionType.Choice) {
        ChoiceField(inner.values, enabled, value, onValueChange, modifier)
        return
    }
    // Local text state: the upstream value arrives asynchronously, so echoing it back would drop characters and
    // move the caret. Reset when the device, option or checked state changes.
    var sync by remember(fieldKey, enabled) { mutableStateOf(FieldSync(value)) }
    LaunchedEffect(value) { sync = reconcile(sync, value) }
    val textStyle = MaterialTheme.typography.bodySmall.copy(
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = sync.text,
        onValueChange = {
            sync = sync.typed(it)
            onValueChange(it)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = sync.text,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction,
                isError = isError && enabled,
                placeholder = { Text(placeholder(inner), style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(values: List<String>, enabled: Boolean, value: String, onSelect: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }, modifier = modifier) {
        val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Row(
            Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f)), styledCorner(4.dp))
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value.ifEmpty { values.first() }, color = color, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = color)
        }
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            values.forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onSelect(v); expanded = false })
            }
        }
    }
}

private fun placeholder(type: OptionType): String = when (type) {
    is OptionType.IntValue -> "number"
    OptionType.BitRate -> "8M"
    OptionType.Size -> "1920x1080"
    OptionType.Crop -> "w:h:x:y"
    OptionType.Decimal -> "number"
    is OptionType.Formatted -> type.example
    else -> "value"
}

/**
 * Text shown in a value field plus the values we sent upstream that have not been echoed back yet.
 * Only in-flight sends are remembered; once upstream catches up, `pending` is empty.
 */
data class FieldSync(val text: String, val pending: List<String> = emptyList()) {
    fun typed(new: String) = FieldSync(new, pending + new)
}

/** Decides what the field shows when the upstream (stored) value becomes [upstream]. */
fun reconcile(state: FieldSync, upstream: String): FieldSync {
    // Consume sends in order: an echo of something we typed (possibly lagging) keeps the newer text.
    val idx = state.pending.indexOf(upstream)
    if (idx >= 0) return state.copy(pending = state.pending.drop(idx + 1))
    if (upstream == state.text) return state
    // Anything else (preset applied, option reset) is authoritative.
    return FieldSync(upstream)
}
