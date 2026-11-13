package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.programmersbox.scrcpyviewer.files.DEVICE_ROOT
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.devicePath
import com.programmersbox.scrcpyviewer.files.isInside
import com.programmersbox.scrcpyviewer.files.normalizeDir
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.reportsSheetFocus
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** Menus scale from this at the pointer, and back to it on the way out. */
private const val MENU_SCALE = 0.92f
private const val MENU_IN_MILLIS = 150
private const val MENU_OUT_MILLIS = 110

/** Which menu is open, at [at] (in the Files tab's own pixels). */
internal sealed interface FilesMenu {
    val at: Offset

    /** The item menu, for [names] (the selection when it opened). */
    data class Item(override val at: Offset, val names: Set<String>) : FilesMenu

    /** Right-click on empty space in the list. */
    data class Space(override val at: Offset) : FilesMenu

    /** The toolbar's ⋮: view options. */
    data class View(override val at: Offset) : FilesMenu
}

/** One "Move to…" folder: [enabled] false when a selected folder would land inside itself. */
internal data class MoveTarget(val label: String, val dir: String, val enabled: Boolean)

private val MOVE_FOLDERS = listOf(
    "Internal storage" to DEVICE_ROOT,
    "Download" to "$DEVICE_ROOT/Download",
    "DCIM/Camera" to "$DEVICE_ROOT/DCIM/Camera",
    "Documents" to "$DEVICE_ROOT/Documents",
    "Pictures" to "$DEVICE_ROOT/Pictures",
    "Movies" to "$DEVICE_ROOT/Movies",
)

/** The fixed Move to… list without the open folder [current]; a target inside one of [selectedDirs] is disabled. */
internal fun moveTargets(current: String, selectedDirs: List<String>): List<MoveTarget> {
    val here = normalizeDir(current)
    return MOVE_FOLDERS.filterNot { (_, dir) -> dir.equals(here, ignoreCase = true) }
        .map { (label, dir) -> MoveTarget(label, dir, selectedDirs.none { isInside(dir, it) }) }
}

/** What the menus do outside the browser: the computer's side of pulls, pushes and the clipboard. */
internal class MenuActions(
    val pullToDownloads: (List<String>) -> Unit,
    val pullTo: (List<String>) -> Unit,
    val pushFiles: () -> Unit,
    val copy: (String) -> Unit,
)

/**
 * The open [menu] as a popup at its point, scaling from [MENU_SCALE] there and fading (150 ms decelerate in, 110 ms
 * out). The popup takes focus (reported as the sheet's, so the window never takes its Esc): Esc and a click outside
 * call [onDismiss]; ↑ and ↓ move between items.
 */
@Composable
internal fun FilesMenuHost(
    menu: FilesMenu?,
    browser: FilesBrowser,
    actions: MenuActions,
    isMac: Boolean,
    motion: Boolean,
    onDismiss: () -> Unit,
) {
    // The last menu, so it keeps its content while it fades out. A plain holder.
    val kept = remember { arrayOfNulls<FilesMenu>(1) }
    if (menu != null) kept[0] = menu
    val state = remember { MutableTransitionState(false) }
    state.targetState = menu != null
    val shown = kept[0] ?: return
    if (!state.currentState && !state.targetState) return
    // Which corner sits at the point: the popup flips to stay inside the window. Read at draw time.
    val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val provider = remember(shown.at) { PointerPosition(shown.at, origin) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = if (motion) fadeIn(tween(MENU_IN_MILLIS, easing = EmphasizedDecelerate)) else EnterTransition.None,
            exit = if (motion) fadeOut(tween(MENU_OUT_MILLIS)) else ExitTransition.None,
        ) {
            val scale by transition.animateFloat(
                transitionSpec = {
                    when {
                        !motion -> snap()
                        targetState == EnterExitState.Visible -> tween(MENU_IN_MILLIS, easing = EmphasizedDecelerate)
                        else -> tween(MENU_OUT_MILLIS)
                    }
                },
            ) { if (it == EnterExitState.Visible) 1f else MENU_SCALE }
            MenuSurface(
                onDismiss,
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = origin.value
                },
            ) {
                when (shown) {
                    is FilesMenu.Item -> ItemMenu(browser, shown.names, actions, isMac, onDismiss)
                    is FilesMenu.Space -> SpaceMenu(browser, actions, isMac, onDismiss)
                    is FilesMenu.View -> ViewMenu(browser, actions, onDismiss)
                }
            }
        }
    }
}

/** Places the popup's corner at [at] inside the anchor (the tab), flipping left or up when it would leave the window. */
private class PointerPosition(private val at: Offset, private val origin: MutableState<TransformOrigin>) :
    PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val px = anchorBounds.left + at.x.toInt()
        val py = anchorBounds.top + at.y.toInt()
        val flipX = px + popupContentSize.width > windowSize.width && px - popupContentSize.width >= 0
        val flipY = py + popupContentSize.height > windowSize.height && py - popupContentSize.height >= 0
        val x = (if (flipX) px - popupContentSize.width else px).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (if (flipY) py - popupContentSize.height else py).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        val w = popupContentSize.width.coerceAtLeast(1).toFloat()
        val h = popupContentSize.height.coerceAtLeast(1).toFloat()
        origin.value = TransformOrigin(((px - x) / w).coerceIn(0f, 1f), ((py - y) / h).coerceIn(0f, 1f))
        return IntOffset(x, y)
    }
}

@Composable
private fun MenuSurface(onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        modifier.reportsSheetFocus(),
        shape = styledCorner(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.widthIn(min = 200.dp, max = 280.dp).padding(vertical = 6.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> onDismiss()
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusable(),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector?,
    enabled: Boolean = true,
    shortcut: String? = null,
    trailing: ImageVector? = null,
    checked: Boolean? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    val tint = (if (danger) colors.error else colors.onSurface).copy(alpha = alpha)
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 6.dp).clip(styledCorner(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val leading = if (checked != null) Icons.Filled.Check.takeIf { checked } else icon
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (leading != null) Icon(leading, contentDescription = null, Modifier.size(18.dp), tint = tint)
        }
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = tint, maxLines = 1)
        if (shortcut != null) {
            Text(shortcut, fontSize = 12.sp, color = colors.onSurfaceVariant.copy(alpha = alpha), maxLines = 1)
        }
        if (trailing != null) Icon(trailing, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant.copy(alpha = alpha))
    }
}

@Composable
private fun MenuDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** The item menu (the spec's table), or its Move to… list. */
@Composable
private fun ItemMenu(browser: FilesBrowser, names: Set<String>, actions: MenuActions, isMac: Boolean, onDismiss: () -> Unit) {
    var moving by remember { mutableStateOf(false) }
    val path = browser.path
    val entries = browser.visible.filter { it.name in names }
    val ordered = entries.map { it.name }
    fun run(block: () -> Unit) {
        onDismiss()
        block()
    }
    if (moving) {
        MenuItem("Move to…", Icons.AutoMirrored.Filled.ArrowBack) { moving = false }
        MenuDivider()
        val dirs = entries.filter { it.isDir }.map { devicePath(path, it.name) }
        for (target in moveTargets(path, dirs)) {
            MenuItem(target.label, Icons.Filled.Folder, enabled = target.enabled) { run { browser.move(ordered, target.dir) } }
        }
        return
    }
    val single = entries.singleOrNull()
    if (single != null && single.isDir) MenuItem("Open", Icons.Filled.FolderOpen) { run { browser.openEntry(single.name) } }
    if (single != null && !single.isDir) {
        MenuItem("Open on computer", Icons.AutoMirrored.Filled.OpenInNew) { run { browser.openOnComputer(single.name) } }
        if (isApk(single)) MenuItem("Install on this device", Icons.Filled.Android) { run { browser.install(single.name) } }
    }
    if (single != null) MenuDivider()
    MenuItem("Pull to Downloads", Icons.Filled.Download) { run { actions.pullToDownloads(ordered) } }
    MenuItem("Pull to…", Icons.Filled.Download) { run { actions.pullTo(ordered) } }
    MenuDivider()
    MenuItem("Rename", Icons.Filled.DriveFileRenameOutline, enabled = single != null, shortcut = "F2") {
        run { single?.let { browser.startRename(it.name) } }
    }
    MenuItem("Move to…", Icons.AutoMirrored.Filled.DriveFileMove, trailing = Icons.Filled.ChevronRight) { moving = true }
    MenuItem(if (ordered.size == 1) "Copy path" else "Copy paths", Icons.Filled.ContentCopy) {
        run { actions.copy(ordered.joinToString("\n") { devicePath(path, it) }) }
    }
    MenuDivider()
    MenuItem("Delete", Icons.Filled.Delete, shortcut = if (isMac) "⌫" else "Delete", danger = true) { run { browser.askDelete() } }
}

/** Right-click on empty space: New folder, Push files here…, Refresh, Copy folder path. */
@Composable
private fun SpaceMenu(browser: FilesBrowser, actions: MenuActions, isMac: Boolean, onDismiss: () -> Unit) {
    val listedHere = browser.listingPath == browser.path
    val blocked = listedHere && browser.listing == Listing.Blocked
    fun run(block: () -> Unit) {
        onDismiss()
        block()
    }
    MenuItem(
        "New folder", Icons.Filled.CreateNewFolder,
        enabled = browser.listed && browser.pendingNewFolder == null,
        shortcut = shortcutLabel("N", isMac, shift = true),
    ) { run(browser::newFolder) }
    MenuItem("Push files here…", Icons.Filled.FileUpload, enabled = !blocked) { run(actions.pushFiles) }
    MenuItem("Refresh", Icons.Filled.Refresh, shortcut = shortcutLabel("R", isMac)) { run(browser::refresh) }
    MenuDivider()
    MenuItem("Copy folder path", Icons.Filled.ContentCopy) { run { actions.copy(browser.path) } }
}

/** The toolbar's ⋮: Show hidden files, Sort by (the current key shows its direction), Copy folder path. */
@Composable
private fun ViewMenu(browser: FilesBrowser, actions: MenuActions, onDismiss: () -> Unit) {
    fun run(block: () -> Unit) {
        onDismiss()
        block()
    }
    MenuItem("Show hidden files", null, checked = browser.showHidden) { run(browser::toggleHidden) }
    MenuDivider()
    Text(
        "Sort by", Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((label, key) in listOf("Name" to SortKey.Name, "Size" to SortKey.Size, "Modified" to SortKey.Modified)) {
        val current = browser.sort == key
        MenuItem(
            label, null, checked = current,
            trailing = if (!current) null else if (browser.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        ) { run { browser.setSort(key) } }
    }
    MenuDivider()
    MenuItem("Copy folder path", Icons.Filled.ContentCopy) { run { actions.copy(browser.path) } }
}

