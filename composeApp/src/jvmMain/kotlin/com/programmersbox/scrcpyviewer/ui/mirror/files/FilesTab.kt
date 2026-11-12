package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.crumbs
import com.programmersbox.scrcpyviewer.files.formatFree
import com.programmersbox.scrcpyviewer.files.formatItemCount
import com.programmersbox.scrcpyviewer.files.formatModified
import com.programmersbox.scrcpyviewer.files.formatSize
import com.programmersbox.scrcpyviewer.files.kindOf
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.expandFadeIn
import com.programmersbox.scrcpyviewer.ui.common.shrinkFadeOut
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt
import java.util.Locale

/** Test tag of the breadcrumb bar. */
const val CRUMBS_TAG = "files-crumbs"

/** Opening a folder: the old list leaves this far toward the back side, the new one enters from the forward side. */
private val NAV_SHIFT = 32.dp
private const val NAV_OUT_MILLIS = 150
private const val NAV_IN_DELAY_MILLIS = 50

/** The first rows of a folder rise into place one after another. */
private const val STAGGER_ROWS = 14
private const val STAGGER_STEP_MILLIS = 16

private val CRUMB_SHIFT = 10.dp
private const val SPIN_MILLIS = 500
private const val SORT_MILLIS = 300

/** The list while the session is down. */
private const val DISCONNECTED_ALPHA = 0.38f

/**
 * The Files tab: toolbar (Back, Up, breadcrumbs, Refresh, New folder, Push files here), the filter field and free
 * space, the column header, and the open folder's rows or its state. Dates format in [zone] and [locale]; [motion]
 * false (the OS asks for less motion) drops every animation here.
 */
@Composable
fun FilesTab(
    browser: FilesBrowser,
    modifier: Modifier = Modifier,
    onPushFiles: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    motion: Boolean = remember { !systemReducesMotion() },
) {
    Column(
        modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // Until the rename field arrives, Esc drops a pending new folder so its row never sticks.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && browser.pendingNewFolder != null) {
                browser.cancelRename()
                true
            } else {
                false
            }
        },
    ) {
        Toolbar(browser, motion, onPushFiles)
        FilterRow(browser, motion)
        ColumnHeader(browser, motion)
        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // Fully qualified: the Column's ColumnScope.AnimatedVisibility would otherwise win.
        androidx.compose.animation.AnimatedVisibility(
            visible = !browser.connected,
            enter = if (motion) expandFadeIn() else EnterTransition.None,
            exit = if (motion) shrinkFadeOut() else ExitTransition.None,
        ) {
            DisconnectedBanner()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ListHost(browser, motion, onPushFiles, zone, locale)
            androidx.compose.animation.AnimatedVisibility(
                visible = browser.loading,
                enter = fadeIn(if (motion) tween(150) else snap()),
                exit = fadeOut(if (motion) tween(150) else snap()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun Toolbar(browser: FilesBrowser, motion: Boolean, onPushFiles: () -> Unit) {
    val connected = browser.connected
    val listed = browser.listingPath == browser.path
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", connected && browser.canGoBack, onClick = browser::back)
        ToolButton(Icons.Filled.ArrowUpward, "Up one folder", connected && browser.canGoUp, onClick = browser::up)
        Crumbs(browser, motion, Modifier.weight(1f))
        ToolButton(Icons.Filled.Refresh, "Refresh", connected, iconModifier = Modifier.graphicsLayer { rotationZ = spin.value }) {
            browser.refresh()
            if (motion) {
                scope.launch {
                    spin.snapTo(0f)
                    spin.animateTo(360f, tween(SPIN_MILLIS, easing = EmphasizedDecelerate))
                    spin.snapTo(0f)
                }
            }
        }
        ToolButton(
            Icons.Filled.CreateNewFolder, "New folder",
            connected && listed && browser.listing is Listing.Entries && browser.pendingNewFolder == null,
            onClick = browser::newFolder,
        )
        ToolButton(Icons.Filled.FileUpload, "Push files here", connected && !(listed && browser.listing == Listing.Blocked), onClick = onPushFiles)
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, Modifier.size(30.dp), enabled = enabled) {
        Icon(icon, contentDescription = description, iconModifier.size(18.dp))
    }
}

/** "Internal storage" and each folder below it; kept scrolled to its end. A crumb added later slides in. */
@Composable
private fun Crumbs(browser: FilesBrowser, motion: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val path = browser.path
    val items = remember(path) { crumbs(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(items, scroll.maxValue) {
        if (motion) scroll.animateScrollTo(scroll.maxValue) else scroll.scrollTo(scroll.maxValue)
    }
    // Crumbs composed with the bar itself just show; only later ones animate in. A plain holder.
    val settled = remember { BooleanArray(1) }
    SideEffect { settled[0] = true }
    Row(
        modifier.horizontalScroll(scroll).testTag(CRUMBS_TAG).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (label, dir) ->
            key(dir) {
                val appear = remember { Animatable(if (settled[0] && motion) 0f else 1f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) }
                Row(
                    Modifier.graphicsLayer {
                        alpha = appear.value
                        translationX = (1f - appear.value) * CRUMB_SHIFT.toPx()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(14.dp), tint = colors.outline)
                    }
                    val last = index == items.lastIndex
                    Box(
                        Modifier.height(28.dp).clip(styledCorner(8.dp))
                            .clickable(enabled = browser.connected, role = Role.Button) { browser.open(dir) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, fontSize = 13.sp, maxLines = 1,
                            fontWeight = if (last) FontWeight.SemiBold else null,
                            color = if (last) colors.onSurface else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The filter pill ("Filter in Download"; Esc clears it) and the device's free space. */
@Composable
private fun FilterRow(browser: FilesBrowser, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val filter = browser.filter
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).height(32.dp).clip(styledCorner(16.dp)).background(colors.surfaceContainerHighest)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Box(Modifier.weight(1f).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (filter.isEmpty()) {
                    Text("Filter in ${folderLabel(browser.path)}", fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = filter,
                    onValueChange = browser::setFilter,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Filter this folder" }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && filter.isNotEmpty()) {
                                browser.setFilter("")
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
        val space = browser.free
        // The last space shown, so the meter keeps its content while it fades out. A plain holder.
        val shown = remember { arrayOfNulls<DeviceSpace>(1) }
        if (space != null) shown[0] = space
        AnimatedVisibility(
            space != null,
            enter = fadeIn(if (motion) tween(OPEN_MILLIS) else snap()),
            exit = fadeOut(if (motion) tween(150) else snap()),
        ) {
            shown[0]?.let { FreeSpace(it) }
        }
    }
}

/** A thin used-space meter and "41.2 GB free". */
@Composable
private fun FreeSpace(space: DeviceSpace) {
    val colors = MaterialTheme.colorScheme
    val used = space.usedFraction
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.width(54.dp).height(4.dp).clip(styledCorner(2.dp)).background(colors.surfaceContainerHighest)
                .semantics { contentDescription = "${(used * 100).roundToInt()}% used" },
        ) {
            Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(colors.primary))
        }
        Text(formatFree(space.free), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** Name, Size, Modified: a click sorts by the column, a second reverses it; the arrow turns with the direction. */
@Composable
private fun ColumnHeader(browser: FilesBrowser, motion: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        Spacer(Modifier.width(ICON_COLUMN))
        HeaderCell("Name", SortKey.Name, browser, motion, Modifier.weight(1f), end = false)
        HeaderCell("Size", SortKey.Size, browser, motion, Modifier.width(SIZE_COLUMN), end = true)
        HeaderCell("Modified", SortKey.Modified, browser, motion, Modifier.width(DATE_COLUMN), end = true)
    }
}

@Composable
private fun HeaderCell(label: String, key: SortKey, browser: FilesBrowser, motion: Boolean, modifier: Modifier, end: Boolean) {
    val colors = MaterialTheme.colorScheme
    val active = browser.sort == key
    val spec = if (motion) tween<Float>(200) else snap()
    val arrowAlpha by animateFloatAsState(if (active) 1f else 0f, spec)
    val turn by animateFloatAsState(if (active && browser.descending) 180f else 0f, spec)
    Row(modifier, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        Row(
            Modifier.clip(styledCorner(4.dp)).clickable(role = Role.Button) { browser.setSort(key) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, maxLines = 1)
            Icon(
                Icons.Filled.ArrowUpward, contentDescription = null,
                Modifier.size(12.dp).graphicsLayer {
                    alpha = arrowAlpha
                    rotationZ = turn
                },
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** One folder's content as shown: kept as it was while a newer folder replaces it. */
private class Page(val listing: Listing?, val rows: List<DeviceEntry>, val filter: String)

/**
 * The listed folder (which lags [FilesBrowser.path] while the next one loads, so its rows stay until the new ones are
 * ready). Moving to another folder slides along the shared X axis by [FilesBrowser.navDirection].
 */
@Composable
private fun ListHost(browser: FilesBrowser, motion: Boolean, onPushFiles: () -> Unit, zone: ZoneId, locale: Locale) {
    val density = LocalDensity.current
    val dim by animateFloatAsState(if (browser.connected) 1f else DISCONNECTED_ALPHA, if (motion) tween(OPEN_MILLIS) else snap())
    AnimatedContent(
        targetState = browser.listingPath,
        modifier = Modifier.fillMaxSize().alpha(dim),
        transitionSpec = { navTransform(browser.navDirection, motion, density) },
        contentAlignment = Alignment.TopStart,
    ) { dir ->
        // The current folder follows the browser; one on its way out keeps its last rows. A plain holder.
        val kept = remember { arrayOfNulls<Page>(1) }
        if (dir == browser.listingPath) kept[0] = Page(browser.listing, browser.visible, browser.filter)
        val page = kept[0] ?: return@AnimatedContent
        if (dir == null) return@AnimatedContent
        when (val listing = page.listing) {
            null -> Unit
            Listing.Blocked -> BlockedState(dir, browser.canGoUp && browser.connected, browser::up)
            is Listing.Failed -> ListingFailedState(listing.reason, browser.connected, browser::refresh)
            is Listing.Entries -> when {
                page.rows.isNotEmpty() -> Rows(browser, page.rows, motion, zone, locale)
                page.filter.isNotBlank() -> NoMatchState(page.filter)
                else -> EmptyFolderState(folderLabel(dir), browser.connected, onPushFiles)
            }
        }
    }
}

private fun AnimatedContentTransitionScope<String?>.navTransform(direction: Int, motion: Boolean, density: Density): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    val sign = if (direction < 0) -1 else 1
    val shift = with(density) { NAV_SHIFT.roundToPx() }
    val inSpec = tween<Float>(OPEN_MILLIS, delayMillis = NAV_IN_DELAY_MILLIS, easing = EmphasizedDecelerate)
    val enter = slideInHorizontally(tween(OPEN_MILLIS, delayMillis = NAV_IN_DELAY_MILLIS, easing = EmphasizedDecelerate)) { sign * shift } +
        fadeIn(inSpec)
    val exit = slideOutHorizontally(tween(NAV_OUT_MILLIS, easing = LinearEasing)) { -sign * shift } +
        fadeOut(tween(NAV_OUT_MILLIS, easing = LinearEasing))
    return enter togetherWith exit
}

@Composable
private fun Rows(browser: FilesBrowser, rows: List<DeviceEntry>, motion: Boolean, zone: ZoneId, locale: Locale) {
    val state = rememberLazyListState()
    // The folder's stagger-in, as elapsed time; each of the first rows starts STAGGER_STEP_MILLIS after the last.
    val total = (STAGGER_ROWS - 1) * STAGGER_STEP_MILLIS + OPEN_MILLIS
    val elapsed = remember { Animatable(if (motion) 0f else total.toFloat()) }
    LaunchedEffect(Unit) {
        elapsed.animateTo(total.toFloat(), tween(total, delayMillis = NAV_IN_DELAY_MILLIS, easing = LinearEasing))
    }
    val leaving = browser.leaving
    val leavingOrder = if (leaving.isEmpty()) emptyList() else rows.map { it.name }.filter { it in leaving }
    val selection = browser.selection.names
    val arriving = browser.arriving
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 64.dp)) {
        itemsIndexed(rows, key = { _, e -> e.name }) { index, entry ->
            val sizeText = if (entry.isDir) entry.itemCount?.let(::formatItemCount) else formatSize(entry.size)
            FileRow(
                name = entry.name,
                kind = kindOf(entry),
                sizeText = sizeText,
                dateText = remember(entry.mtimeMillis, zone, locale) { formatModified(entry.mtimeMillis, zone, locale) },
                hidden = entry.hidden,
                selected = entry.name in selection,
                arriving = entry.name in arriving || entry.name == browser.pendingNewFolder,
                leavingIndex = leavingOrder.indexOf(entry.name),
                motion = motion,
                onPress = { event, double -> browser.press(entry.name, event, double) },
                modifier = if (motion) {
                    Modifier.animateItem(
                        fadeInSpec = tween(OPEN_MILLIS),
                        placementSpec = tween(SORT_MILLIS, easing = EmphasizedDecelerate),
                        fadeOutSpec = tween(NAV_OUT_MILLIS),
                    )
                } else {
                    Modifier
                },
                enter = if (index < STAGGER_ROWS) {
                    { staggerFraction(elapsed.value - index * STAGGER_STEP_MILLIS) }
                } else {
                    { 1f }
                },
            )
        }
    }
}

/** A row's eased progress [elapsed] milliseconds into its own stagger-in. */
private fun staggerFraction(elapsed: Float): Float =
    EmphasizedDecelerate.transform((elapsed / OPEN_MILLIS).coerceIn(0f, 1f))

/** Click selects, Shift extends, ⌘ (Ctrl off macOS) toggles; a plain double-click opens. */
private fun FilesBrowser.press(name: String, event: PointerEvent, double: Boolean) {
    val mods = event.keyboardModifiers
    when {
        mods.isShiftPressed -> shiftClick(name)
        if (Os.isMac) mods.isMetaPressed else mods.isCtrlPressed -> toggle(name)
        double -> openEntry(name)
        else -> click(name)
    }
}
