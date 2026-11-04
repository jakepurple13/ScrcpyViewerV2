package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.actions.ActionIcons
import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import com.programmersbox.scrcpyviewer.actions.ActionTemplates
import com.programmersbox.scrcpyviewer.actions.CommandTemplate
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.TemplateGroup
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.VariableKind

/** The "Start from a template" picker as a dialog; Esc or a click outside closes it. */
@Composable
fun TemplatePickerDialog(onPick: (ActionTemplate) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TemplatePickerContent(onPick, onDismiss)
    }
}

/** Search field, category chips, then the matching templates under group headers in two columns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatePickerContent(
    onPick: (ActionTemplate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    initialGroup: TemplateGroup? = null,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var group by remember { mutableStateOf(initialGroup) }
    val results = remember(query, group) { ActionTemplates.search(query, group) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(
        modifier.widthIn(max = 780.dp).fillMaxWidth().padding(24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start from a template", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Search templates") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(group == null, { group = null }, label = { Text("All") })
                TemplateGroup.entries.forEach { g -> FilterChip(group == g, { group = g }, label = { Text(g.label) }) }
            }
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (results.isEmpty()) {
                    Text(
                        "No templates match \"${query.trim()}\".",
                        Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                results.groupBy { it.group }.forEach { (g, templates) ->
                    Text(
                        g.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    templates.chunked(2).forEach { pair ->
                        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { TemplateCard(it, { onPick(it) }, Modifier.weight(1f).fillMaxHeight()) }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** One template; the whole card is the button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCard(template: ActionTemplate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val action = template.action
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(
                        ActionIcons.forName(action.icon, action.defaultIcon), contentDescription = null,
                        tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp),
                    )
                }
                Text(template.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Pill(if (action is ToggleAction) "Toggle" else "Run once")
            }
            Text(template.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            CommandBox(action)
            val variables = (action as? RunOnceAction)?.variables.orEmpty()
            if (variables.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    variables.forEach { (name, v) -> Pill("{$name} · ${kindName(v.kind)}", mono = true) }
                }
            }
        }
    }
}

private fun kindName(kind: VariableKind) = when (kind) {
    VariableKind.Text -> "Text"
    VariableKind.List -> "List"
    VariableKind.App -> "App"
}

@Composable
private fun Pill(text: String, mono: Boolean = false) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

/** The command in a monospace box; toggles show their on, off and read lines. */
@Composable
private fun CommandBox(action: CustomAction) {
    val colors = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        when (action) {
            is RunOnceAction -> appendCommand(action.command, highlight = true, colors.primary)
            is ToggleAction -> {
                appendLabelled("on", action.on, colors.onSurfaceVariant)
                appendLabelled("off", action.off, colors.onSurfaceVariant)
                action.read?.let { appendLabelled("read", it, colors.onSurfaceVariant, last = true) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface)
    }
}

private fun AnnotatedString.Builder.appendLabelled(
    label: String, command: String, labelColor: Color, last: Boolean = false,
) {
    withStyle(SpanStyle(color = labelColor)) { append("$label: ") }
    appendCommand(command, highlight = false, labelColor)
    if (!last) append("\n")
}

/** Appends [command]; with [highlight], each `{variable}` is bold in [color]. Toggle braces are literal. */
private fun AnnotatedString.Builder.appendCommand(
    command: String, highlight: Boolean, color: Color,
) {
    if (!highlight) return append(command)
    val names = CommandTemplate.variables(command).map { "{$it}" }
    var i = 0
    while (i < command.length) {
        val hit = names.firstOrNull { command.startsWith(it, i) }
        if (hit == null) {
            append(command[i]); i++
        } else {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(hit) }
            i += hit.length
        }
    }
}
