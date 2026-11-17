package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.Surface
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.DEVICE_ROOT
import com.programmersbox.scrcpyviewer.files.SearchHit
import com.programmersbox.scrcpyviewer.files.formatItemCount
import com.programmersbox.scrcpyviewer.files.formatModified
import com.programmersbox.scrcpyviewer.files.formatSize
import com.programmersbox.scrcpyviewer.files.kindOf
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.FilterChip
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import java.time.ZoneId
import java.util.Locale

/** Test tags. */
const val SEARCH_FIELD_TAG = "files-search-field"
const val SEARCH_RESULTS_TAG = "files-search-results"

/** "Internal storage" for the root, else the folder's path under it ("DCIM/Camera"). */
internal fun searchFolderLabel(dir: String): String {
    val rel = dir.removePrefix(DEVICE_ROOT).trim('/')
    return rel.ifEmpty { "Internal storage" }
}

/**
 * The bar that takes the toolbar's place while searching: the device-wide field (focused when it appears), a clear
 * button while it has text, and Close. ↓ in the field moves to the results; Enter shows the match under the cursor
 * (or the first) in its folder.
 */
@Composable
internal fun SearchBar(
    search: FilesSearch,
    onFocus: (Boolean) -> Unit,
    onDown: () -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    motion: Boolean,
    focusKey: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusKey) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.weight(1f).height(32.dp).clip(styledCorner(16.dp)).background(colors.surfaceContainerHighest)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, Modifier.size(16.dp), tint = colors.primary)
            Box(Modifier.weight(1f).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (search.query.isEmpty()) {
                    Text(
                        "Search all of internal storage", fontSize = 13.sp, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = search.query,
                    onValueChange = search::setQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(SEARCH_FIELD_TAG)
                        .semantics { contentDescription = "Search this device" }
                        .onFocusChanged { onFocus(it.isFocused) }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    onDown()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    onEnter()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            AnimatedVisibility(
                search.query.isNotEmpty(),
                enter = if (motion) fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f) else fadeIn(snap()),
                exit = if (motion) fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.6f) else fadeOut(snap()),
            ) {
                IconButton(onClick = { search.setQuery(""); runCatching { focus.requestFocus() } }, Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = onClose, Modifier.size(30.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close search", Modifier.size(18.dp))
        }
    }
}

/** The type chips, each with how many matches of its type were found; none on shows every type. */
@Composable
internal fun SearchChips(search: FilesSearch, motion: Boolean) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((kind, label) in SEARCH_KINDS) {
            val count = search.counts[kind] ?: 0
            FilterChip(
                selected = kind in search.kinds,
                onClick = { search.toggleKind(kind) },
                label = { Text(label, fontSize = 12.sp) },
                trailing = { Text("$count", fontSize = 11.sp) },
                motion = motion,
            )
        }
    }
}

/** What the search is doing: "Searching… 14 found" with Stop, or the finished count and time. */
internal fun searchStatusText(search: FilesSearch): String {
    val found = search.hits.size
    val shown = search.shown.size
    val folders = search.groups.size
    val filtered = search.kinds.isNotEmpty()
    fun matches(n: Int) = if (n == 1) "1 match" else "%,d matches".format(Locale.US, n)
    fun inFolders() = if (folders == 1) "1 folder" else "$folders folders"
    return when {
        search.running -> if (found == 0) "Searching internal storage…" else "Searching… ${"%,d".format(Locale.US, found)} found"
        search.error != null -> "Search failed"
        search.stopped -> "Stopped · ${matches(found)} so far"
        search.truncated -> "First ${matches(found)} · type more to narrow it"
        filtered -> "$shown of ${matches(found)} in ${inFolders()}"
        else -> {
            val secs = search.elapsedMillis?.let { " · %.1f s".format(Locale.US, it / 1000.0) }.orEmpty()
            "${matches(found)} in ${inFolders()}$secs"
        }
    }
}

/** The status line under the chips, with Stop while a search runs and a thin progress bar under it. */
@Composable
internal fun SearchStatus(search: FilesSearch, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (search.searched.isNotEmpty() || search.running) {
                Text(
                    searchStatusText(search), Modifier.weight(1f), fontSize = 11.5.sp, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            AnimatedVisibility(
                search.running,
                enter = fadeIn(if (motion) tween(150) else snap()),
                exit = fadeOut(if (motion) tween(150) else snap()),
            ) {
                TextButton(onClick = search::stop, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(24.dp)) {
                    Text("Stop", fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(
            search.running,
            enter = fadeIn(if (motion) tween(150) else snap()),
            exit = fadeOut(if (motion) tween(150) else snap()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/** What the results area shows. */
private enum class ResultsState { Prompt, Failed, Empty, List }

/**
 * The matches grouped by folder: each group's header opens its folder; a click puts the cursor on a match (with Show
 * in folder beside it), a double-click opens it, a right-click opens [onMenu]. New matches fade in as they stream;
 * a chip change slides the rest into place.
 */
@Composable
internal fun SearchResults(
    browser: FilesBrowser,
    search: FilesSearch,
    motion: Boolean,
    zone: ZoneId,
    locale: Locale,
    listFocused: Boolean,
    onRowPress: () -> Unit,
    onMenu: (SearchHit) -> Unit,
) {
    val groups = search.groups
    val state = when {
        search.error != null -> ResultsState.Failed
        search.query.isBlank() -> ResultsState.Prompt
        groups.isEmpty() && !search.running -> ResultsState.Empty
        else -> ResultsState.List
    }
    AnimatedContent(
        state,
        Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(if (motion) tween(OPEN_MILLIS) else snap()) togetherWith fadeOut(if (motion) tween(120) else snap()) },
        contentAlignment = Alignment.TopStart,
    ) { s ->
        when (s) {
            ResultsState.Prompt -> SearchPromptState()
            ResultsState.Failed -> SearchFailedState(search.error ?: "Search failed", search::rerun)
            ResultsState.Empty -> SearchNoMatchState(search.searched.ifEmpty { search.query.trim() }, kindsOn = search.hits.isNotEmpty())
            ResultsState.List -> ResultList(browser, search, motion, zone, locale, listFocused, onRowPress, onMenu)
        }
    }
}

@Composable
private fun ResultList(
    browser: FilesBrowser,
    search: FilesSearch,
    motion: Boolean,
    zone: ZoneId,
    locale: Locale,
    listFocused: Boolean,
    onRowPress: () -> Unit,
    onMenu: (SearchHit) -> Unit,
) {
    val listState = rememberLazyListState()
    val groups = search.groups
    val cursor = search.cursor
    val highlight = search.searched
    // The lazy index of each match (headers count too), for scrolling the cursor into view.
    val index = remember(groups) {
        val map = HashMap<String, Int>()
        var i = 0
        for (g in groups) {
            i++
            for (h in g.hits) map[h.path] = i++
        }
        map
    }
    val currentIndex by rememberUpdatedState(index)
    LaunchedEffect(cursor) {
        val i = cursor?.let { currentIndex[it] } ?: return@LaunchedEffect
        listState.bringIntoView(i, motion)
    }
    val narrowRows = LocalFilesLayout.current.modifiedBelow
    val placement = if (motion) layoutSpring<androidx.compose.ui.unit.IntOffset>() else null
    LazyColumn(
        Modifier.fillMaxSize().testTag(SEARCH_RESULTS_TAG),
        state = listState,
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 64.dp),
    ) {
        for (group in groups) {
            item(key = "h:" + group.dir, contentType = "header") {
                GroupHeader(
                    group.dir, group.hits.size,
                    onOpen = {
                        search.close()
                        browser.open(group.dir)
                    },
                    modifier = if (motion) Modifier.animateItem(fadeInSpec = tween(OPEN_MILLIS), placementSpec = placement, fadeOutSpec = tween(120)) else Modifier,
                )
            }
            items(group.hits, key = { it.path }, contentType = { "hit" }) { hit ->
                val entry = hit.entry
                val selected = hit.path == cursor
                Box(
                    if (motion) Modifier.animateItem(fadeInSpec = tween(OPEN_MILLIS), placementSpec = placement, fadeOutSpec = tween(120)) else Modifier,
                ) {
                    FileRow(
                        name = entry.name,
                        kind = kindOf(entry),
                        sizeText = if (entry.isDir) entry.itemCount?.let(::formatItemCount) else formatSize(entry.size),
                        dateText = remember(entry.mtimeMillis, zone, locale) { formatModified(entry.mtimeMillis, zone, locale) },
                        hidden = entry.hidden,
                        selected = selected,
                        arriving = false,
                        leavingIndex = -1,
                        motion = motion,
                        onPress = { _, double ->
                            search.pointAt(hit.path)
                            if (double) browser.openHit(hit)
                            onRowPress()
                        },
                        onSecondaryPress = {
                            search.pointAt(hit.path)
                            onMenu(hit)
                        },
                        focused = listFocused && selected,
                        match = highlight,
                        // The icon-only Show in folder sits at the end of the name's line: the name stops short of it.
                        endInset = if (selected && narrowRows) REVEAL_ICON_INSET else 0.dp,
                    )
                    // Show in folder sits over the date while the match has the cursor.
                    AnimatedVisibility(
                        selected,
                        Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                        enter = if (motion) fadeIn(tween(150)) + scaleIn(tween(150, easing = EmphasizedDecelerate), initialScale = 0.85f) else fadeIn(snap()),
                        exit = if (motion) fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.85f) else fadeOut(snap()),
                    ) {
                        RevealButton(motion) { browser.reveal(hit) }
                    }
                }
            }
        }
    }
}

/** Room the name leaves for the icon-only Show in folder (26 dp, 10 dp from the end) on a selected narrow result. */
private val REVEAL_ICON_INSET = 36.dp

@Composable
private fun GroupHeader(dir: String, count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(styledCorner(6.dp)).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(14.dp), tint = colors.primary)
            Text(
                searchFolderLabel(dir), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("· $count", fontSize = 11.5.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * The primary pill "Show in folder"; once the rows put their date under the name ([FilesLayout.modifiedBelow]) just
 * its folder icon (described "Show in folder", with a tooltip), so it no longer covers the name. The two forms
 * cross-fade while the button's size follows with [layoutSpring].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RevealButton(motion: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val iconOnly = LocalFilesLayout.current.modifiedBelow
    val glide = motion && !LocalReduceMotion.current
    AnimatedContent(
        iconOnly,
        Modifier.clip(styledCorner(13.dp)).background(colors.primary),
        transitionSpec = {
            (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
                .using(SizeTransform(clip = true) { _, _ -> if (glide) layoutSpring() else snap() })
        },
        contentAlignment = Alignment.CenterEnd,
    ) { icon ->
        if (icon) {
            TooltipArea(
                tooltip = {
                    Surface(shape = styledCorner(4.dp), color = colors.inverseSurface) {
                        Text(
                            "Show in folder", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.inverseOnSurface,
                        )
                    }
                },
            ) {
                Box(
                    Modifier.size(26.dp).clickable(role = Role.Button, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Show in folder", Modifier.size(14.dp), tint = colors.onPrimary)
                }
            }
        } else {
            Row(
                Modifier.height(26.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(14.dp), tint = colors.onPrimary)
                Text("Show in folder", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.onPrimary, maxLines = 1)
            }
        }
    }
}
