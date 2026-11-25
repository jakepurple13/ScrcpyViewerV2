package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.database.SqlValue
import com.programmersbox.scrcpyviewer.ui.common.AdaptiveListDetail
import com.programmersbox.scrcpyviewer.ui.common.BackArrowRow
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberSelectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlightTarget
import java.time.format.DateTimeFormatter

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** The Data tab in the sheet: the tab itself, or a card while Databases is in its own window. The two cross-fade. */
@Composable
fun DatabaseSheetTab(controller: DatabaseController, sheetWidth: Dp, motion: Boolean, onShowDebuggableApps: () -> Unit) {
    val rise = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedContent(
        controller.popped,
        Modifier.fillMaxSize(),
        transitionSpec = {
            when {
                !motion -> EnterTransition.None togetherWith ExitTransition.None
                targetState -> (fadeIn(tween(400, delayMillis = 60)) + slideInVertically(tween(400)) { rise }) togetherWith fadeOut(tween(160))
                else -> fadeIn(tween(300, delayMillis = 60)) togetherWith fadeOut(tween(160))
            }
        },
    ) { popped ->
        if (popped) {
            DatabasePoppedCard(onShow = controller::raise, onDock = controller::dock)
        } else {
            DatabaseTab(controller, motion, onShowDebuggableApps, onPopOut = controller::popOut)
        }
    }
}

/**
 * The Data tab's panel, in the sheet or its own window: app and database pickers over a tables list beside the
 * selected table (Browse, Query, Structure). [onPopOut] null hides the pop-out button (in the own window).
 */
@Composable
fun DatabaseTab(
    controller: DatabaseController,
    motion: Boolean = !LocalReduceMotion.current,
    onShowDebuggableApps: () -> Unit,
    onPopOut: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var reviewing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(colors.surfaceContainerLow)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(controller, motion, onPopOut)
            val app = controller.app
            val state = when {
                app == null -> BodyState.NoApp
                controller.access == null && controller.root == RootState.Asking -> BodyState.Asking
                controller.access == null -> BodyState.Locked
                controller.db == null && controller.sync !is SyncStatus.Pulling -> BodyState.NoDatabases
                else -> BodyState.Open
            }
            AnimatedContent(
                state,
                Modifier.fillMaxSize(),
                transitionSpec = { if (motion) fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) else fadeIn(snap()) togetherWith fadeOut(snap()) },
            ) { s ->
                when (s) {
                    BodyState.NoApp -> Message(
                        "Pick an app to browse its databases",
                        "Debug builds open through run-as. Release builds need a rooted phone.",
                        "Show debuggable apps", onShowDebuggableApps,
                    )
                    BodyState.Asking -> Column(
                        Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("Allow root on your phone", style = MaterialTheme.typography.titleSmall)
                    }
                    BodyState.Locked -> Message(
                        "${app?.label}'s databases are private",
                        when (controller.root) {
                            RootState.Denied -> "Root was refused. Allow it in your root manager, then click the root chip to ask again."
                            else -> "Android only shares an app's data folder for debug builds, or when the phone is rooted. This phone isn't rooted."
                        },
                        "Show debuggable apps", onShowDebuggableApps, Icons.Filled.Lock,
                    )
                    BodyState.NoDatabases -> Message(
                        "${app?.label} has no databases",
                        (controller.sync as? SyncStatus.Failed)?.message ?: "Its databases folder is empty or missing.",
                        null, null,
                    )
                    BodyState.Open -> OpenDatabase(controller, motion)
                }
            }
        }
        StagedBar(controller, motion) { reviewing = true }
    }
    if (reviewing) ReviewSqlDialog(controller) { reviewing = false }
    WriteDialogs(controller)
}

private enum class BodyState { NoApp, Asking, Locked, NoDatabases, Open }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(controller: DatabaseController, motion: Boolean, onPopOut: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AppPicker(controller)
        controller.access?.let { access ->
            Surface(color = if (access is com.programmersbox.scrcpyviewer.database.AppDataAccess.RunAs) colors.tertiaryContainer else colors.secondaryContainer,
                shape = RoundedCornerShape(6.dp)) {
                Text(access.label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (controller.databases.isNotEmpty()) DatabasePicker(controller)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncLine(controller.sync, motion)
            IconButton(onClick = controller::refresh, enabled = controller.db != null && !controller.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Pull again", Modifier.size(18.dp))
            }
            if (onPopOut != null) {
                IconButton(onClick = onPopOut) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in its own window", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun AppPicker(controller: DatabaseController) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = ButtonDefaults.TextButtonContentPadding, modifier = Modifier.widthIn(max = 280.dp)) {
            Text(
                controller.app?.let { "${it.label} · ${it.packageName}" } ?: "Choose an app",
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(open, { open = false }) {
            val apps = controller.apps().orEmpty()
            if (apps.isEmpty()) DropdownMenuItem(text = { Text("Loading apps…") }, onClick = {}, enabled = false)
            val root = controller.root == RootState.Rooted || controller.root == RootState.AdbRoot || controller.root == RootState.Available
            for ((title, group) in listOf("Debuggable" to apps.filter { it.debuggable }, (if (root) "Release builds · through root" else "Release builds · need root") to apps.filter { !it.debuggable })) {
                if (group.isEmpty()) continue
                Text(title.uppercase(), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                for (app in group) {
                    DropdownMenuItem(
                        text = { Column { Text(app.label); Text(app.packageName, style = Mono, color = colors.onSurfaceVariant) } },
                        leadingIcon = { Icon(if (app.debuggable) Icons.Filled.BugReport else if (root) Icons.Filled.Storage else Icons.Filled.Lock, null) },
                        enabled = app.debuggable || root,
                        onClick = {
                            open = false
                            controller.pick(app)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DatabasePicker(controller: DatabaseController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Icon(Icons.Filled.Storage, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(controller.db ?: "Database", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(open, { open = false }) {
            for (db in controller.databases) {
                DropdownMenuItem(
                    text = { Column { Text(db.name); Text(sizeText(db.size) + (db.walSize?.let { " · + ${sizeText(it)} wal" } ?: ""), style = Mono) } },
                    onClick = {
                        open = false
                        controller.selectDb(db.name)
                    },
                )
            }
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** "Pulling via run-as…", "Pulled 09:21", "Writing…"; each change slides up. */
@Composable
private fun SyncLine(sync: SyncStatus, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    AnimatedContent(
        sync,
        transitionSpec = {
            if (motion) {
                (slideInVertically { it / 2 } + fadeIn(tween(180))).togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            } else {
                fadeIn(snap()) togetherWith fadeOut(snap())
            }
        },
    ) { s ->
        val (text, error) = when (s) {
            SyncStatus.Idle -> "" to false
            is SyncStatus.Pulling -> "Pulling via ${s.via}…" to false
            is SyncStatus.Pulled -> "Pulled ${s.at.format(TIME)}" to false
            SyncStatus.Stopping -> "Stopping the app…" to false
            SyncStatus.Writing -> "Writing…" to false
            is SyncStatus.Written -> "Written ${s.at.format(TIME)}" to false
            is SyncStatus.Failed -> s.message to true
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (error) colors.error else colors.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

@Composable
private fun Message(title: String, body: String, action: String?, onAction: (() -> Unit)?, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Storage) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(colors.secondaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun OpenDatabase(controller: DatabaseController, motion: Boolean) {
    val panes = rememberListDetailPanes()
    AdaptiveListDetail(
        panes = panes,
        listMinWidth = 160.dp,
        listPreferredWidth = 200.dp,
        hasSelection = controller.table != null,
        list = {
            TablesList(controller) {
                controller.selectTable(it)
                panes.push()
            }
        },
        detail = { onBack -> TableDetail(controller, motion, onBack) },
    )
}

@Composable
private fun TablesList(controller: DatabaseController, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight = rememberSelectionHighlight()
    LazyColumn(Modifier.fillMaxSize().selectionHighlight(highlight, colors.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp)) {
        item { Text("TABLES", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        items(controller.tables, key = { it.name }) { t ->
            val selected = t.name == controller.table
            Row(
                Modifier.fillMaxWidth().selectionHighlightTarget(highlight, selected).clip8()
                    .clickable(role = Role.Button) { onPick(t.name) }.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tint = if (selected) colors.onSecondaryContainer else if (t.system) colors.onSurfaceVariant else colors.onSurface
                Icon(Icons.Filled.TableChart, contentDescription = null, Modifier.size(15.dp), tint = tint)
                Text(t.name, Modifier.weight(1f), style = Mono, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%,d".format(t.rows), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun Modifier.clip8() = this.then(Modifier.clip(RoundedCornerShape(8.dp)))

@Composable
private fun TableDetail(controller: DatabaseController, motion: Boolean, onBack: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val table = controller.table ?: return
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        BackArrowRow(onBack) { rowModifier ->
            FlowRow(
                rowModifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(table, style = Mono.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp))
                val rows = controller.tables.firstOrNull { it.name == table }?.rows ?: 0
                Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                    Text("%,d %s".format(rows, if (rows == 1L) "row" else "rows"), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
                SubTabs(controller.view, controller::selectView)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        AnimatedContent(
            controller.view,
            Modifier.fillMaxSize(),
            transitionSpec = {
                if (!motion) return@AnimatedContent fadeIn(snap()) togetherWith fadeOut(snap())
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(300)) { if (forward) it / 10 else -it / 10 } + fadeIn(tween(220, delayMillis = 60)))
                    .togetherWith(slideOutHorizontally(tween(300)) { if (forward) -it / 10 else it / 10 } + fadeOut(tween(120)))
            },
        ) { view ->
            when (view) {
                DbView.Browse -> BrowseView(controller, motion)
                DbView.Query -> QueryView(controller, motion) { controller.selectView(DbView.Browse) }
                DbView.Structure -> StructureView(controller)
            }
        }
    }
}

@Composable
private fun SubTabs(view: DbView, onSelect: (DbView) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight = rememberSelectionHighlight()
    Row(
        Modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .selectionHighlight(highlight, colors.secondaryContainer, RoundedCornerShape(6.dp)).padding(2.dp),
    ) {
        for (v in DbView.entries) {
            val selected = v == view
            Text(
                v.name,
                Modifier.selectionHighlightTarget(highlight, selected).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Tab) { onSelect(v) }.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowseView(controller: DatabaseController, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val page = controller.page
    var where by remember(controller.table) { mutableStateOf(controller.where) }
    var adding by remember(controller.table) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WHERE", style = Mono.copy(fontWeight = FontWeight.SemiBold), color = colors.tertiary)
            BasicTextField(
                where, { where = it },
                Modifier.weight(1f).background(colors.surfaceContainerLow, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            controller.applyWhere(where)
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true, textStyle = Mono.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
            )
            if (page?.editable == true) {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Text("Row")
                }
            }
        }
        val error = controller.pageError
        if (error != null) {
            Text(error, Modifier.padding(12.dp), style = Mono, color = colors.error)
        } else if (page != null) {
            val keys = page.rows.map { it.key }
            val edited = controller.edited.mapNotNull { (key, column) ->
                val r = keys.indexOf(key)
                val c = page.columns.indexOfFirst { it.name == column }
                if (r >= 0 && c >= 0) r to c else null
            }.toSet()
            TableGrid(
                page.columns.map { GridColumn(it.name, it.type, it.pk > 0) },
                page.rows.map { it.values },
                Modifier.weight(1f),
                edited = edited,
                motion = motion,
                onEdit = if (page.editable) { r, c, v -> controller.setCell(r, c, v) } else null,
                onDelete = if (page.editable) controller::deleteRow else null,
            )
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (page.editable) "Double-click a cell to edit · right-click a row to delete" else "Views are read-only",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("%,d of %,d".format(page.rows.size, page.total), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            // Room for the staged bar over the footer.
            Spacer(Modifier.height(if (controller.staged > 0) 64.dp else 0.dp))
        }
    }
        AddRowSheet(
            visible = adding && page != null,
            table = controller.table.orEmpty(),
            columns = page?.columns.orEmpty(),
            motion = motion,
            onAdd = {
                adding = false
                controller.insertRow(it)
            },
            onCancel = { adding = false },
        )
    }
}

/** "Databases is open in its own window", with Show window ([onShow]) and Move back here ([onDock]). */
@Composable
fun DatabasePoppedCard(onShow: () -> Unit, onDock: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(scheme.surfaceContainerLowest).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(scheme.primaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(30.dp), tint = scheme.onPrimaryContainer)
        }
        Text("Databases is open in its own window", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "Staged changes stay with it. Close that window to move it back here.",
            Modifier.widthIn(max = 300.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShow) { Text("Show window") }
            com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton(onClick = onDock) { Text("Move back here") }
        }
    }
}

