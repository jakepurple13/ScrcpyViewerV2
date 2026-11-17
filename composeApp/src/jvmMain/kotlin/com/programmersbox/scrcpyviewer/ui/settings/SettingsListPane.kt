package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.SearchOff
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.ui.common.LabelTooltip
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.staggeredEntrance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The category sidebar. As a list ([icons] false): the search field and an icon and a title per category, the titles
 * ellipsizing as the sidebar narrows. As [icons]: a 64 dp column with a search button, which opens the field over the
 * page, and each category's icon, named by its tooltip. The two forms cross-fade. An available update badges the
 * Updates icon either way.
 */
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
    icons: Boolean = false,
) {
    // Reduced motion too: the width snaps, the labels still fade.
    val form = updateTransition(icons, label = "sidebarForm")
    val listAlpha by form.animateFloat({ tween(SidebarFadeMillis) }, label = "sidebarList") { if (it) 0f else 1f }
    var searchOpen by remember { mutableStateOf(false) }
    LaunchedEffect(icons) { if (!icons) searchOpen = false }
    Box(modifier.clipToBounds()) {
        if (listAlpha > 0f) {
            // While the sidebar springs to the icons, the list keeps its narrowest width and is clipped.
            CategoryList(
                matches, selected, query, searching, updateAvailable, onQuery, onSelect,
                Modifier.atLeastWidth(SidebarNarrowestListWidth).faded(listAlpha),
            )
        }
        if (listAlpha < 1f) {
            CategoryIcons(
                matches, selected, query, updateAvailable, onQuery, onSelect,
                searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                modifier = Modifier.faded(1f - listAlpha),
            )
        }
    }
}

@Composable
private fun CategoryList(
    matches: List<CategoryMatch>,
    selected: SettingsCategoryId?,
    query: String,
    searching: Boolean,
    updateAvailable: Boolean,
    onQuery: (String) -> Unit,
    onSelect: (SettingsCategoryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrolls, so a short window keeps every category reachable.
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SearchField(query, onQuery, Modifier.fillMaxWidth().padding(bottom = 10.dp))
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
                    icon = match.category.icon,
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
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Search", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
        },
        modifier = modifier,
    )
}

@Composable
private fun CategoryIcons(
    matches: List<CategoryMatch>,
    selected: SettingsCategoryId?,
    query: String,
    updateAvailable: Boolean,
    onQuery: (String) -> Unit,
    onSelect: (SettingsCategoryId) -> Unit,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Scrolls, so a short window keeps every category reachable.
        modifier.width(SidebarIconsWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SearchButton(query, onQuery, searchOpen, onSearchOpen, Modifier.padding(bottom = 10.dp))
        if (matches.isEmpty()) NoMatchIcon()
        val shownIds = matches.map { it.category.id }
        matches.forEachIndexed { i, match ->
            val id = match.category.id
            key(id) {
                CategoryIcon(
                    title = match.category.title,
                    icon = match.category.icon,
                    selected = id == selected,
                    badge = id == SettingsCategoryId.Updates && updateAvailable,
                    onClick = { onSelect(id) },
                    modifier = Modifier.staggeredEntrance(i, trigger = shownIds),
                )
            }
        }
    }
}

/** The icons form's search: a button that opens the search field beside it, over the page. Esc closes it. */
@Composable
private fun SearchButton(
    query: String,
    onQuery: (String) -> Unit,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    Box(modifier) {
        IconButton(onClick = { onOpen(!open) }) {
            // A search in progress tints the button.
            val tint by animateColorAsState(
                if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "searchTint",
            )
            Icon(Icons.Filled.Search, contentDescription = "Search settings", tint = tint)
        }
        if (shown.currentState || shown.targetState) {
            val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { BesideAnchor(gap) },
                onDismissRequest = { onOpen(false) },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = shown,
                    enter = if (reduceMotion) fadeIn(tween(SidebarFadeMillis)) else fadeIn(tween(150)) + expandHorizontally(layoutSpring()),
                    exit = if (reduceMotion) fadeOut(tween(SidebarFadeMillis)) else fadeOut(tween(120)) + shrinkHorizontally(layoutSpring()),
                ) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    Surface(
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 3.dp,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            val close = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                            if (close) onOpen(false)
                            close
                        },
                    ) {
                        SearchField(query, onQuery, Modifier.width(SearchPopupWidth).padding(8.dp).focusRequester(focus))
                    }
                }
            }
        }
    }
}

/** Beside the anchor's end edge, top-aligned with it, kept inside the window. */
private class BesideAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right + gap).coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
private fun CategoryItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    count: Int?,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = styledCorner(8.dp)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "categoryFill",
    )
    val bar by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "categoryBar")
    Row(
        modifier.inViewWhenSelected(selected).fillMaxWidth().clip(shape)
            .drawBehind { drawRect(fill) }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(20.dp).clip(styledCorner(2.dp)).drawBehind { drawRect(bar) })
        val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        // An available update badges the Updates icon.
        BadgedBox(badge = { if (badge) Badge() }, modifier = Modifier.padding(start = 9.dp)) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Text(
            title, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        )
        count?.let {
            Text(
                "$it", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/** A category as its icon, centred, with the selected highlight and 3 dp bar; its name is the tooltip and description. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryIcon(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = styledCorner(8.dp)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "categoryFill",
    )
    val bar by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "categoryBar")
    TooltipArea(tooltip = { LabelTooltip(title) }, modifier = modifier.inViewWhenSelected(selected)) {
        Box(
            Modifier.fillMaxWidth().height(CategoryIconHeight).clip(shape)
                .drawBehind { drawRect(fill) }
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).clip(styledCorner(2.dp)).drawBehind { drawRect(bar) })
            val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            BadgedBox(badge = { if (badge) Badge() }) {
                Icon(icon, contentDescription = title, tint = content, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The icons form's "No settings match": a muted icon, named by its tooltip and description. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoMatchIcon() {
    TooltipArea(tooltip = { LabelTooltip(NoMatchLabel) }) {
        Box(Modifier.width(SidebarIconsWidth - 24.dp).height(CategoryIconHeight), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.SearchOff, contentDescription = NoMatchLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Scrolls the sidebar to this category when it becomes the selected one (picked by search or the keyboard). */
@Composable
private fun Modifier.inViewWhenSelected(selected: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

private const val NoMatchLabel = "No settings match"

/** Lays the content out at least [min] wide, at the start; the parent clips what overflows. */
private fun Modifier.atLeastWidth(min: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val width = maxOf(constraints.maxWidth, min.roundToPx())
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
}

/** [alpha] while it fades; no layer once it is opaque. */
private fun Modifier.faded(alpha: Float): Modifier = if (alpha < 1f) graphicsLayer { this.alpha = alpha } else this

/** How long the list and icons forms take to cross-fade. */
private const val SidebarFadeMillis = 150

/** An icon row's height: a list row's (20 dp icon and 8 dp padding around a bodyLarge line). */
private val CategoryIconHeight = 40.dp

/** The search field opened over the page. */
private val SearchPopupWidth = 260.dp
