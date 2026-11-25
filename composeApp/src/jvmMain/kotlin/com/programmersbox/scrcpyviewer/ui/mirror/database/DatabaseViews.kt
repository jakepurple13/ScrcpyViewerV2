package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.database.SqlOutcome

/** Colors for [highlightSql]: keywords, strings, numbers and comments. */
data class SqlColors(val keyword: Color, val string: Color, val number: Color, val comment: Color)

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "ORDER", "BY", "DESC", "ASC", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "UPDATE", "SET",
    "DELETE", "INSERT", "INTO", "VALUES", "GROUP", "HAVING", "COUNT", "JOIN", "LEFT", "INNER", "ON", "AS", "LIMIT", "OFFSET",
    "PRAGMA", "CREATE", "TABLE", "INDEX", "VIEW", "PRIMARY", "KEY", "INTEGER", "TEXT", "REAL", "BLOB", "DEFAULT", "REFERENCES",
    "UNIQUE", "FOREIGN", "AUTOINCREMENT", "CASCADE", "WITHOUT", "ROWID", "DISTINCT", "BEGIN", "COMMIT", "ROLLBACK", "DROP", "ALTER",
)
private val TOKENS = Regex("""(--[^\n]*)|('(?:[^']|'')*'?)|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_]+)\b""")

/** [sql] with its keywords, strings, numbers and comments colored. Same text, same length. */
fun highlightSql(sql: String, colors: SqlColors): AnnotatedString = buildAnnotatedString {
    append(sql)
    for (m in TOKENS.findAll(sql)) {
        val style = when {
            m.groups[1] != null -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
            m.groups[2] != null -> SpanStyle(color = colors.string)
            m.groups[3] != null -> SpanStyle(color = colors.number)
            m.value.uppercase() in KEYWORDS -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.SemiBold)
            else -> null
        } ?: continue
        addStyle(style, m.range.first, m.range.last + 1)
    }
}

@Composable
fun sqlColors(): SqlColors {
    val c = MaterialTheme.colorScheme
    return SqlColors(keyword = c.tertiary, string = c.secondary, number = c.primary, comment = c.outline)
}

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp)

/** The Query view: an SQL editor (⌘↵ or Ctrl+↵ runs), sample queries and the last result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueryView(controller: DatabaseController, motion: Boolean, onSeeBrowse: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sql = sqlColors()
    val table = controller.table.orEmpty()
    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            controller.queryText, { controller.queryText = it },
            Modifier.fillMaxWidth().padding(10.dp).heightIn(min = 84.dp)
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp))
                .background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)).padding(10.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isMetaPressed || e.isCtrlPressed)) {
                        controller.runQuery(controller.queryText)
                        true
                    } else {
                        false
                    }
                },
            textStyle = Mono.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = { text -> TransformedText(highlightSql(text.text, sql), OffsetMapping.Identity) },
        )
        FlowRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (sample in listOf("SELECT * FROM $table LIMIT 100;", "SELECT COUNT(*) FROM $table;", "PRAGMA integrity_check;")) {
                TextButton(onClick = { controller.queryText = sample; controller.runQuery(sample) }) {
                    Text(sample, style = Mono.copy(fontSize = 11.5.sp), maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { controller.runQuery(controller.queryText) }) { Text("Run") }
        }
        AnimatedContent(
            controller.queryOutcome,
            Modifier.fillMaxSize().padding(top = 6.dp),
            transitionSpec = { if (motion) fadeIn(tween(200)) togetherWith fadeOut(tween(120)) else fadeIn(snap()) togetherWith fadeOut(snap()) },
        ) { outcome ->
            Column(Modifier.fillMaxSize()) {
                when (outcome) {
                    null -> Text(
                        "Reads run on the pulled copy. Changes are staged until you write them to the device.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    is SqlOutcome.Rows -> {
                        Text("${outcome.rows.size} ${if (outcome.rows.size == 1) "row" else "rows"} · ${outcome.millis} ms",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        TableGrid(outcome.columns.map { GridColumn(it) }, outcome.rows, motion = motion)
                    }
                    is SqlOutcome.Changed -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${outcome.count} ${if (outcome.count == 1) "row" else "rows"} changed in the copy. Staged for writing to the device.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.primary,
                        )
                        TextButton(onClick = onSeeBrowse) { Text("See in Browse") }
                    }
                    is SqlOutcome.Failed -> Text(outcome.message, Modifier.padding(12.dp), style = Mono, color = colors.error)
                }
            }
        }
    }
}

/** The Structure view: the table's CREATE statement, its indexes and where the file lives. */
@Composable
fun StructureView(controller: DatabaseController) {
    val colors = MaterialTheme.colorScheme
    val s = controller.structure ?: return
    val sql = sqlColors()
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun block(title: String, text: AnnotatedString) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
                    Text(text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), style = Mono)
                }
            }
            block("Definition", highlightSql(s.ddl, sql))
            block("Indexes", if (s.indexes.isEmpty()) AnnotatedString("-- none") else highlightSql(s.indexes.joinToString("\n"), sql))
            block("File", AnnotatedString("${s.path}\njournal_mode = ${s.journalMode} · opened via ${s.via}"))
        }
    }
}

/** "N changes staged in <db>", Review SQL, Discard and Write to device; springs up from the bottom while anything is staged. */
@Composable
fun BoxScope.StagedBar(controller: DatabaseController, motion: Boolean, onReview: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val n = controller.staged
    val last = remember { mutableStateOf(n) }
    if (n > 0) last.value = n
    AnimatedVisibility(
        visible = n > 0,
        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
        enter = if (motion) slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn() else fadeIn(snap()),
        exit = if (motion) slideOutVertically(tween(220)) { it * 2 } + fadeOut(tween(160)) else fadeOut(snap()),
    ) {
        Surface(color = colors.inverseSurface, contentColor = colors.inverseOnSurface, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val count = last.value
                Text(
                    "$count ${if (count == 1) "change" else "changes"} staged in ${controller.db.orEmpty()}",
                    Modifier.align(Alignment.CenterVertically).padding(vertical = 10.dp).weight(1f, fill = true),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onReview, Modifier.align(Alignment.CenterVertically)) { Text("Review SQL", color = colors.inversePrimary) }
                TextButton(onClick = controller::discard, Modifier.align(Alignment.CenterVertically)) { Text("Discard", color = colors.inversePrimary) }
                Button(onClick = controller::requestWrite, Modifier.align(Alignment.CenterVertically), enabled = !controller.busy) { Text("Write to device") }
            }
        }
    }
}

/** The staged SQL, read-only. */
@Composable
fun ReviewSqlDialog(controller: DatabaseController, onClose: () -> Unit) {
    val sql = sqlColors()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Staged SQL") },
        text = {
            SelectionContainer {
                Text(highlightSql(controller.log.joinToString("\n"), sql), Modifier.verticalScroll(rememberScrollState()), style = Mono)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** The write-back dialogs: confirm (stopping the app when it runs) and the conflict warning. */
@Composable
fun WriteDialogs(controller: DatabaseController) {
    when (val d = controller.dialog) {
        null -> Unit
        is WriteDialog.Confirm -> {
            var relaunch by remember(d) { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = controller::dismissDialog,
                title = { Text("Write to ${d.appLabel}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (d.running) "${d.appLabel} is running" else "${d.appLabel} isn't running", style = MaterialTheme.typography.labelLarge)
                        val changes = if (d.count == 1) "1 change runs" else "${d.count} changes run"
                        Text(
                            if (d.running) {
                                "It will be force-stopped so it isn't holding ${controller.db} open. $changes in one transaction, then the file is copied back."
                            } else {
                                "$changes in one transaction, then the file is copied back. Nothing needs to stop."
                            },
                        )
                        if (d.running) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(relaunch, { relaunch = it })
                                Text("Open ${d.appLabel} again afterwards")
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { controller.confirmWrite(relaunch) }) { Text(if (d.running) "Stop app and write" else "Write") } },
                dismissButton = { TextButton(onClick = controller::dismissDialog) { Text("Cancel") } },
            )
        }
        is WriteDialog.Conflict -> AlertDialog(
            onDismissRequest = controller::dismissDialog,
            title = { Text("${d.appLabel} changed this database") },
            text = { Text("${d.appLabel} changed this database since it was pulled. Writing replaces those changes.") },
            confirmButton = { Button(onClick = controller::writeAnyway) { Text("Write anyway") } },
            dismissButton = { TextButton(onClick = controller::pullAgain) { Text("Pull again") } },
        )
    }
}

