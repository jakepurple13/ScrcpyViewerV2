package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.apps.AppFilter
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

private val CardShape = RoundedCornerShape(16.dp)
private val FieldShape = RoundedCornerShape(22.dp)
private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val ActionShape = RoundedCornerShape(16.dp)

/** Cards at least this wide: three a row at the Apps tab's default width. */
private val CARD_MIN_WIDTH = 128.dp

/** Cards composed this soon after the grid appears pop in one after another; later ones (scrolled to) just show. */
private const val STAGGER_WINDOW_MILLIS = 400L
private const val STAGGER_STEP_MILLIS = 22L
private const val STAGGER_MAX_MILLIS = 220L

/** How long the copy button shows "Copied". */
private const val COPIED_MILLIS = 1_600L

/** What the tab body shows; [AnimatedContent] fades between them. */
private enum class AppsBody { Loading, Empty, Grid }

/**
 * The Apps tab: a search field, All / User / System filter chips with counts, and the apps as a staggered grid of
 * cards (filtering animates each card with [animateItem]). Clicking a card opens an options sheet over the tab
 * (launch, force stop, copy the package name, uninstall with a confirm step); right-clicking one opens the same
 * options as a menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppsTabContent(state: AppsUiState, enabled: Boolean, onEvent: (AppsEvent) -> Unit) {
    var selected by remember { mutableStateOf<DeviceApp?>(null) }
    var confirm by remember { mutableStateOf(false) }
    // The last app shown, so the sheet keeps its content while it slides away.
    val shown = remember { arrayOfNulls<DeviceApp>(1) }
    selected?.let { shown[0] = it }
    // An app uninstalled (or gone after a reload) while its sheet is open closes the sheet.
    val current = selected
    if (current != null && state.apps?.none { it.packageName == current.packageName } == true) selected = null

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = remember(onEvent, clipboard) {
        AppActions(
            launch = { onEvent(AppsEvent.Launch(it)) },
            forceStop = { onEvent(AppsEvent.ForceStop(it)) },
            copy = { app ->
                scope.launch {
                    runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(app.packageName))) }
                    onEvent(AppsEvent.Copied(app))
                }
            },
            uninstall = { onEvent(AppsEvent.Uninstall(it)) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Sets the tab's own controls apart from the tab bar above them, the same gap on both sides (the tab bar
            // ends where this content starts).
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SearchField(state.query, onChange = { onEvent(AppsEvent.Search(it)) }, Modifier.padding(horizontal = 12.dp))
            FilterRow(state, enabled, onEvent, Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp))
            val body = when {
                state.loading || state.apps == null -> AppsBody.Loading
                state.visible.isEmpty() -> AppsBody.Empty
                else -> AppsBody.Grid
            }
            AnimatedContent(
                targetState = body,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.96f))
                        .togetherWith(fadeOut(tween(120)))
                },
                contentAlignment = Alignment.TopCenter,
            ) { kind ->
                when (kind) {
                    AppsBody.Loading -> SkeletonGrid()
                    AppsBody.Empty -> EmptyMessage(state.query, state.filter)
                    AppsBody.Grid -> AppGrid(
                        state, actions,
                        selected = selected?.packageName,
                        onOpen = { confirm = false; selected = it },
                        onAskUninstall = { confirm = true; selected = it },
                        onEvent = onEvent,
                    )
                }
            }
        }
        OptionsOverlay(
            visible = selected != null,
            app = shown[0],
            icon = shown[0]?.let { state.icons[it.packageName] },
            confirm = confirm,
            onConfirmChange = { confirm = it },
            onClose = { selected = null },
            actions = actions,
        )
    }
}

/** An outline [width] wide, or none at 0 dp ([border] draws a hairline at 0 dp). */
private fun Modifier.ring(width: Dp, color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (width > 0.dp) border(width, color, shape) else this

/** What a card's menu and the options sheet can do with an app. */
private class AppActions(
    val launch: (DeviceApp) -> Unit,
    val forceStop: (DeviceApp) -> Unit,
    val copy: (DeviceApp) -> Unit,
    val uninstall: (DeviceApp) -> Unit,
)

/** The pill-shaped search field. Esc clears it first; an Esc on an empty field goes on to close the sheet. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val ring by animateDpAsState(if (focused) 2.dp else 0.dp, tween(150))
    val container by animateColorAsState(if (focused) colors.surfaceBright else colors.surfaceContainerHighest, tween(150))
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(FieldShape).background(container)
            .ring(ring, colors.primary, FieldShape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search apps or packages", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "Search apps by name or package" }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                            onChange("")
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(tween(150)) + scaleIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.6f),
            exit = fadeOut(tween(150)) + scaleOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate), targetScale = 0.6f),
        ) {
            IconButton(onClick = { onChange("") }, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search", Modifier.size(18.dp))
            }
        }
    }
}

/** The filter chips, each with its count for the current search, and the reload button. */
@Composable
private fun FilterRow(state: AppsUiState, enabled: Boolean, onEvent: (AppsEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppFilter.entries.forEach { filter ->
            val selected = filter == state.filter
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onEvent(AppsEvent.Filter(filter)) },
                label = {
                    Text(filter.name)
                    if (state.apps != null) {
                        Spacer(Modifier.width(6.dp))
                        Counter(state.count(filter))
                    }
                },
                leadingIcon = {
                    // The check grows into the chip as it is selected and shrinks away as it is not.
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(tween(220, easing = EmphasizedDecelerate)) +
                            scaleIn(tween(220, easing = EmphasizedDecelerate), initialScale = 0.4f) + fadeIn(tween(160)),
                        exit = shrinkHorizontally(tween(180, easing = EmphasizedAccelerate)) +
                            scaleOut(tween(180), targetScale = 0.4f) + fadeOut(tween(120)),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        ReloadButton(state.loading, enabled) { onEvent(AppsEvent.Reload) }
    }
}

/** A count that rolls up when it grows and down when it shrinks. */
@Composable
private fun Counter(count: Int) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(220, easing = EmphasizedDecelerate)) { if (up) it else -it } + fadeIn(tween(160)))
                .togetherWith(slideOutVertically(tween(160, easing = EmphasizedAccelerate)) { if (up) -it else it } + fadeOut(tween(120)))
                .using(SizeTransform(clip = true))
        },
    ) { n ->
        Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

/** Spins while [loading]; when the listing ends it finishes its turn instead of stopping mid-rotation. */
@Composable
private fun ReloadButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) rotation.animateTo(rotation.value + 360f, tween(700, easing = LinearEasing))
        } else {
            rotation.animateTo(ceil(rotation.value / 360f) * 360f, spring())
        }
    }
    IconButton(onClick = onClick, enabled = enabled && !loading) {
        Icon(Icons.Filled.Refresh, contentDescription = "Reload app list", Modifier.graphicsLayer { rotationZ = rotation.value })
    }
}

@Composable
private fun AppGrid(
    state: AppsUiState,
    actions: AppActions,
    selected: String?,
    onOpen: (DeviceApp) -> Unit,
    onAskUninstall: (DeviceApp) -> Unit,
    onEvent: (AppsEvent) -> Unit,
) {
    val shownAt = remember { System.currentTimeMillis() }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.visible, key = { it.packageName }) { app ->
            val icon = state.icons[app.packageName]
            LaunchedEffect(app.packageName, icon == null) { if (icon == null) onEvent(AppsEvent.NeedIcon(app)) }
            AppCard(
                app = app,
                icon = icon,
                selected = app.packageName == selected,
                busy = app.packageName in state.busy,
                appearDelay = remember {
                    val elapsed = System.currentTimeMillis() - shownAt
                    if (elapsed > STAGGER_WINDOW_MILLIS) null
                    else min(state.visible.indexOf(app) * STAGGER_STEP_MILLIS, STAGGER_MAX_MILLIS)
                },
                actions = actions,
                onOpen = { onOpen(app) },
                onAskUninstall = { onAskUninstall(app) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * One app: icon, label (up to two lines), package name and version. It pops in after [appearDelay] (null: already
 * shown), lifts its icon on hover, sinks a little while pressed and is outlined while its options sheet is open.
 * Right-click opens the options menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppCard(
    app: DeviceApp,
    icon: ImageBitmap?,
    selected: Boolean,
    busy: Boolean,
    appearDelay: Long?,
    actions: AppActions,
    onOpen: () -> Unit,
    onAskUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 1_500f))
    val lift by animateFloatAsState(if (hovered) 1f else 0f, tween(OPEN_MILLIS, easing = EmphasizedDecelerate))
    val container by animateColorAsState(
        if (selected || hovered) colors.surfaceBright else colors.surfaceContainerHighest, tween(150),
    )
    val ring by animateDpAsState(if (selected) 2.dp else 0.dp, tween(200))
    val appear = remember { Animatable(if (appearDelay == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        // The delay is part of the animation, so it runs on the frame clock like the rest of the motion.
        if (appearDelay != null) appear.animateTo(1f, tween(280, delayMillis = appearDelay.toInt(), easing = EmphasizedDecelerate))
    }
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier
            .graphicsLayer {
                val s = (0.86f + 0.14f * appear.value) * press
                scaleX = s
                scaleY = s
                alpha = appear.value
            }
            .onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isSecondaryPressed) menu = true },
    ) {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(container).ring(ring, colors.primary, CardShape)
                .hoverable(interactions)
                .clickable(interactions, LocalIndication.current, onClick = onOpen)
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AppIcon(
                app, icon, 52.dp,
                Modifier.graphicsLayer {
                    translationY = -2.dp.toPx() * lift
                    scaleX = 1f + 0.04f * lift
                    scaleY = 1f + 0.04f * lift
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = colors.onSurfaceVariant,
                maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
            )
            VersionLine(app, fontSize = 10.5.sp)
        }
        if (app.system) SystemBadge(Modifier.align(Alignment.TopEnd).padding(8.dp), short = true)
        AnimatedVisibility(
            visible = busy,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.5f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.5f),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        AppMenu(menu, onDismiss = { menu = false }, app, actions, onAskUninstall)
    }
}

/** The right-click menu: the options sheet's commands; Uninstall opens the sheet on its confirm step. */
@Composable
private fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, app: DeviceApp, actions: AppActions, onAskUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, Modifier.reportsSheetFocus()) {
        DropdownMenuItem(
            text = { Text("Launch") },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); actions.launch(app) },
        )
        DropdownMenuItem(
            text = { Text("Force stop") },
            leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
            onClick = { onDismiss(); actions.forceStop(app) },
        )
        DropdownMenuItem(
            text = { Text("Copy package name") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = { onDismiss(); actions.copy(app) },
        )
        DropdownMenuItem(
            text = { Text("Uninstall…", color = colors.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.error) },
            onClick = { onDismiss(); onAskUninstall() },
        )
    }
}

/** The app's icon, or a letter tile in a color picked from its package until (or unless) the icon loads. */
@Composable
private fun AppIcon(app: DeviceApp, icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.3f)
    AnimatedContent(
        targetState = icon,
        modifier = modifier.size(size),
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.85f))
                .togetherWith(fadeOut(tween(150)))
        },
        contentKey = { it != null },
    ) { bitmap ->
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, Modifier.size(size).clip(shape))
        } else {
            val (container, content) = letterColors(app.packageName)
            Box(Modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
                Text(
                    app.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                    color = content, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp,
                )
            }
        }
    }
}

/** A container / content pair from the theme, the same for a package every time. */
@Composable
private fun letterColors(packageName: String): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val pairs = listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
    )
    return pairs[Math.floorMod(packageName.hashCode(), pairs.size)]
}

@Composable
private fun VersionLine(app: DeviceApp, fontSize: androidx.compose.ui.unit.TextUnit) {
    val colors = MaterialTheme.colorScheme
    val name = app.versionName?.let { "v$it" } ?: "Unknown version"
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = colors.primary))
            append(name)
            pop()
            app.versionCode?.let { append(" · $it") }
        },
        fontSize = fontSize, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SystemBadge(modifier: Modifier = Modifier, short: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        if (short) "SYS" else "System",
        modifier.clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerHigh).padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = "System app" },
        fontSize = if (short) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
    )
}

/** Placeholder cards that pulse while the apps are listed, each a beat after the one before it. */
@Composable
private fun SkeletonGrid() {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Loading apps" },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(12) { i ->
            val alpha by pulse.animateFloat(
                initialValue = 0.45f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(550, delayMillis = (i % 3) * 120 + (i / 3) * 60, easing = LinearEasing), RepeatMode.Reverse,
                ),
            )
            Column(
                Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(CardShape).background(colors.surfaceContainerHighest)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val bone = Modifier.graphicsLayer { this.alpha = alpha }.background(colors.surfaceContainerHigh, RoundedCornerShape(6.dp))
                Box(Modifier.size(52.dp).graphicsLayer { this.alpha = alpha }.background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)))
                Box(bone.width(70.dp).height(11.dp))
                Box(bone.width(90.dp).height(9.dp))
                Box(bone.width(56.dp).height(9.dp))
            }
        }
    }
}

@Composable
private fun EmptyMessage(query: String, filter: AppFilter) {
    val colors = MaterialTheme.colorScheme
    val kind = if (filter == AppFilter.All) "" else filter.name.lowercase() + " "
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.SearchOff, contentDescription = null, Modifier.size(32.dp), tint = colors.onSurfaceVariant)
        Text(
            if (query.isBlank()) "No ${kind}apps" else "No ${kind}apps match “${query.trim()}”",
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
        )
        Text(
            "Search checks app names and package names.", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/**
 * The options sheet over the tab: the scrim fades in, the sheet slides up and its rows rise in one after another.
 * Esc steps back from the confirm step, then closes; so does a click on the scrim.
 */
@Composable
private fun OptionsOverlay(
    visible: Boolean,
    app: DeviceApp?,
    icon: ImageBitmap?,
    confirm: Boolean,
    onConfirmChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    actions: AppActions,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(OPEN_MILLIS)), exit = fadeOut(tween(CLOSE_MILLIS))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible = visible && app != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(340, easing = EmphasizedDecelerate)) { it },
            exit = slideOutVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { it },
        ) {
            if (app != null) OptionsSheet(app, icon, confirm, onConfirmChange, onClose, actions)
        }
    }
}

/** [this]'s child rising in [delayMillis] after the sheet starts to slide up, and fading out at once as it leaves. */
@OptIn(ExperimentalAnimationApi::class)
private fun AnimatedVisibilityScope.rise(delayMillis: Int): Modifier = Modifier.animateEnterExit(
    enter = fadeIn(tween(220, delayMillis = delayMillis)) +
        slideInVertically(tween(320, delayMillis = delayMillis, easing = EmphasizedDecelerate)) { it / 2 },
    exit = fadeOut(tween(120)),
)

@Composable
private fun AnimatedVisibilityScope.OptionsSheet(
    app: DeviceApp,
    icon: ImageBitmap?,
    confirm: Boolean,
    onConfirmChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    actions: AppActions,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().clip(SheetShape).background(colors.surfaceContainerHighest)
            .focusRequester(focus).focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (confirm) onConfirmChange(false) else onClose()
                    true
                } else {
                    false
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) {} // clicks stay off the scrim
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.outline))
        }
        Row(rise(60), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(app, icon, 60.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.system) SystemBadge()
                }
                Text(
                    app.packageName, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.onSurfaceVariant,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close app options") }
        }
        Row(rise(100).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBox("Version name", app.versionName ?: "Unknown", Modifier.weight(1f))
            InfoBox("Version code", app.versionCode?.toString() ?: "Unknown", Modifier.weight(1f), mono = true)
        }
        AnimatedContent(
            targetState = confirm,
            modifier = rise(140).fillMaxWidth(),
            transitionSpec = {
                val toConfirm = targetState
                (slideInHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { if (toConfirm) it / 8 else -it / 8 } + fadeIn(tween(180, delayMillis = 40)))
                    .togetherWith(slideOutHorizontally(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { if (toConfirm) -it / 8 else it / 8 } + fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
        ) { confirming ->
            if (confirming) {
                ConfirmUninstall(app, onCancel = { onConfirmChange(false) }, onConfirm = { onClose(); actions.uninstall(app) })
            } else {
                ActionButtons(app, actions, onClose, onAskUninstall = { onConfirmChange(true) })
            }
        }
    }
}

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionButtons(app: DeviceApp, actions: AppActions, onClose: () -> Unit, onAskUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetAction(Icons.Filled.PlayArrow, "Launch", colors.primary, colors.onPrimary, Modifier.weight(1f)) {
            onClose()
            actions.launch(app)
        }
        SheetAction(Icons.Filled.Stop, "Force stop", colors.surfaceContainerHigh, colors.onSurface, Modifier.weight(1f)) {
            onClose()
            actions.forceStop(app)
        }
        val copyContainer by animateColorAsState(if (copied) colors.tertiaryContainer else colors.surfaceContainerHigh, tween(200))
        val copyContent by animateColorAsState(if (copied) colors.onTertiaryContainer else colors.onSurface, tween(200))
        SheetAction(
            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            if (copied) "Copied" else "Copy package",
            copyContainer, copyContent, Modifier.weight(1f),
        ) {
            copied = true
            actions.copy(app)
        }
        SheetAction(Icons.Filled.Delete, "Uninstall", colors.errorContainer, colors.onErrorContainer, Modifier.weight(1f), onAskUninstall)
    }
}

/**
 * A square-ish action: icon over label. The icon swaps with a scale and fade when it changes (Copy → Copied), and the
 * button sinks a little while pressed.
 */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = 1_500f))
    Column(
        modifier.height(72.dp).graphicsLayer { scaleX = press; scaleY = press }.clip(ActionShape).background(container)
            .clickable(interactions, LocalIndication.current, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(tween(240, easing = EmphasizedDecelerate), initialScale = 0.3f) + fadeIn(tween(160)))
                    .togetherWith(scaleOut(tween(160), targetScale = 0.3f) + fadeOut(tween(120)))
            },
        ) { vector ->
            Icon(vector, contentDescription = null, Modifier.size(22.dp), tint = content)
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(160, delayMillis = 60)).togetherWith(fadeOut(tween(100))) },
        ) { text ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConfirmUninstall(app: DeviceApp, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (app.system) {
                "Uninstall ${app.label} for the current user? It is a system app, so it stays on the device's system " +
                    "image and can be restored."
            } else {
                "Uninstall ${app.label}? This removes the app and all of its data from the device."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            FilledTonalButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.error, contentColor = colors.onError,
                ),
            ) { Text("Uninstall") }
        }
    }
}
