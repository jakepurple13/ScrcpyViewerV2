package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.SkillStatus
import com.programmersbox.scrcpyviewer.actions.SkillTarget
import com.programmersbox.scrcpyviewer.ui.common.rememberCopyAction

const val AI_EXAMPLE_PROMPT = "make a Scrcpy Vwr toggle for Bluetooth"

/** Targets that start checked: tools found on this computer, and folders the user already added. */
fun preChecked(rows: List<AiSkillRow>): Set<SkillTarget> = rows.filter { it.found }.mapTo(linkedSetOf()) { it.target }

fun addButtonLabel(n: Int): String = if (n == 1) "Add to 1 tool" else "Add to $n tools"

/** [path] with the [home] prefix shown as `~`. */
fun abbreviateHome(path: String, home: String): String {
    val base = home.trimEnd('/', '\\')
    val under = path == base || path.startsWith("$base/") || path.startsWith("$base\\")
    return if (base.isNotEmpty() && under) "~" + path.removePrefix(base) else path
}

fun installResultTitle(results: List<Pair<String, String?>>): String =
    if (results.any { it.second != null }) "Some installs failed" else "Skill added"

fun installResultLine(label: String, error: String?): String = if (error == null) "✓ $label" else "✗ $label: $error"

/** The Settings entry: the open button, then one line per target that already has the skill. */
@Composable
internal fun AiSkillControl(b: SettingsBindings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = b.actions.onOpenAiSkill) { Text("Add skill to an AI tool…") }
        b.state.aiSkill.filter { it.status != SkillStatus.NotInstalled }.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.target.label, style = MaterialTheme.typography.bodyMedium)
                if (row.status == SkillStatus.UpToDate) {
                    Pill("up to date")
                } else {
                    TextButton(onClick = { b.actions.onInstallAiSkill(listOf(row.target)) }) { Text("update available") }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun AddAiSkillDialog(
    rows: List<AiSkillRow>,
    home: String,
    onInstall: (List<SkillTarget>) -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(preChecked(rows)) }
    var known by remember { mutableStateOf(rows.map { it.target }.toSet()) }
    // Rows that show up while the dialog is open (a folder just picked, or a late load) start checked like the rest.
    LaunchedEffect(rows) {
        val added = rows.filter { it.target !in known }
        known = known + added.map { it.target }
        checked = checked + preChecked(added)
    }
    val selected = rows.map { it.target }.filter { it in checked }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AI skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Copies the skill into each tool's skills folder.", style = MaterialTheme.typography.bodyMedium)
                rows.forEach { row ->
                    val on = row.target in checked
                    Row(
                        Modifier.fillMaxWidth().clickable { checked = if (on) checked - row.target else checked + row.target },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(row.target.label)
                            Text(
                                abbreviateHome(row.target.skillDir.toString(), home),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.target is SkillTarget.Tool) Pill(if (row.found) "Found" else "Not found")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onChooseFolder),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Column {
                        Text("Choose folder…")
                        Text(
                            "For any other AI tool",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInstall(selected) }, enabled = selected.isNotEmpty()) { Text(addButtonLabel(selected.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AiInstallResultDialog(results: List<Pair<String, String?>>, onDone: () -> Unit) {
    val copy = rememberCopyAction()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(installResultTitle(results)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                results.forEach { (label, error) -> Text(installResultLine(label, error)) }
                Text(
                    "Try it: open the tool and ask \"$AI_EXAMPLE_PROMPT\". Restart the tool if it was already open.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = { copy(AI_EXAMPLE_PROMPT) }) { Text("Copy example prompt") } },
    )
}
