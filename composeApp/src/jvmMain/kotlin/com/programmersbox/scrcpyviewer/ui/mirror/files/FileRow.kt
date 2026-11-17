package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.derivedStateOf
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import kotlin.math.roundToInt

/** Row height, and the list's columns: kind icon, name (the rest), size or item count, modified. */
internal val ROW_HEIGHT = 40.dp
internal val ICON_COLUMN = 26.dp
internal val SIZE_COLUMN = 64.dp
internal val DATE_COLUMN = 104.dp
internal val COLUMN_GAP = 8.dp

/** How far a removed row slides left as it collapses. */
private val LEAVE_SHIFT = 16.dp

/** How far a row rises as it staggers in. */
private val STAGGER_RISE = 6.dp

/** Size and date cells: small enough that "Nov 10, 12:53 PM" fits the date column. */
private val CELL_FONT = 11.5.sp

/** Hidden (dot) entries, when shown. */
private const val HIDDEN_ALPHA = 0.6f

/** Rows being dragged inside the tab. */
private const val MOVING_ALPHA = 0.45f

/** A drop target row's icon. */
private const val DROP_ICON_SCALE = 1.12f

/**
 * One 40 dp entry row (52 dp with the date on a second line when the tab is narrow, see [FilesLayout]). [arriving]
 * rows grow in and then hold a primary-container highlight that fades over
 * [ARRIVING_FADE_MILLIS]; a row with a [leavingIndex] collapses out, [LEAVING_STAGGER_MILLIS] after the one before it.
 * [enter] (0 to 1) is the row's share of the folder's stagger-in, read at draw time. [onPress] gets every primary
 * press with whether it completes a double-click; [onSecondaryPress] every right-click. Both are consumed, so a press
 * the list sees unconsumed landed on empty space. [focused] draws the list's focus ring. While [renaming], the name is
 * a [RenameField] ([onRenameCommit] answers true when the name was refused, with [renameError] shown beside it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    name: String,
    kind: FileKind,
    sizeText: String?,
    dateText: String,
    hidden: Boolean,
    selected: Boolean,
    arriving: Boolean,
    leavingIndex: Int,
    motion: Boolean,
    onPress: (PointerEvent, doubleClick: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enter: () -> Float = { 1f },
    onSecondaryPress: () -> Unit = {},
    focused: Boolean = false,
    renaming: Boolean = false,
    renameError: String? = null,
    onRenameCommit: (String) -> Boolean = { false },
    dropTarget: Boolean = false,
    spring: () -> Float = { 0f },
    moving: Boolean = false,
    locked: Boolean = false,
    flash: Boolean = false,
    match: String? = null,
    endInset: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    // A folder adb can't read: dimmed, with a lock badge that pops onto its icon once the row is in.
    val lockBadge = remember { Animatable(if (locked && !motion) 1f else 0f) }
    LaunchedEffect(locked, motion) {
        val target = if (locked) 1f else 0f
        if (motion) lockBadge.animateTo(target, tween(OPEN_MILLIS, delayMillis = if (locked) LOCK_BADGE_DELAY_MILLIS else 0, easing = EmphasizedDecelerate)) else lockBadge.snapTo(target)
    }
    val lockedAlpha by animateFloatAsState(if (locked) LOCKED_ALPHA else 1f, if (motion) tween(OPEN_MILLIS) else snap())
    // A drop target: primary container and outline fade in, the icon grows to 112%.
    val lit by animateFloatAsState(if (dropTarget) 1f else 0f, if (motion) tween(150) else snap())
    val movingAlpha by animateFloatAsState(if (moving) MOVING_ALPHA else 1f, if (motion) tween(150) else snap())
    // Grows from nothing only when it arrived while the folder was already showing.
    val grow = remember { Animatable(if (arriving && motion) 0f else 1f) }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(arriving) {
        if (!arriving || !motion) return@LaunchedEffect
        grow.animateTo(1f, tween(OPEN_MILLIS, easing = EmphasizedDecelerate))
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(ARRIVING_FADE_MILLIS.toInt(), easing = LinearEasing))
    }
    // A row shown by a search's Show in folder: the highlight rises once the folder has slid in, then fades.
    LaunchedEffect(flash) {
        if (!flash || !motion) return@LaunchedEffect
        highlight.animateTo(1f, tween(OPEN_MILLIS, delayMillis = OPEN_MILLIS, easing = EmphasizedDecelerate))
        highlight.animateTo(0f, tween(ARRIVING_FADE_MILLIS.toInt(), easing = LinearEasing))
    }
    val leaving = leavingIndex >= 0
    val stay by animateFloatAsState(
        if (leaving) 0f else 1f,
        if (motion) tween(CLOSE_MILLIS, delayMillis = if (leaving) leavingIndex * LEAVING_STAGGER_MILLIS.toInt() else 0, easing = EmphasizedAccelerate) else snap(),
    )
    val style = currentStyle
    val container = when {
        // Glass's secondary container is nearly clear in light mode: a primary wash reads as selected there.
        selected && style == ThemeStyle.Glass -> colors.primary.copy(alpha = 0.22f)
        selected -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val content = if (selected) colors.onSecondaryContainer else colors.onSurface
    val muted = if (selected) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant
    val shape = styledCorner(10.dp)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnSecondary by rememberUpdatedState(onSecondaryPress)
    val lastPress = remember { LongArray(1) { Long.MIN_VALUE } }
    // Narrow (see [FilesLayout]): the date moves to a second line under the name, then the size joins it before the
    // date. The same cells move there, gliding with the row's height; snapped without motion.
    val narrow = LocalFilesLayout.current
    val glide = motion && !LocalReduceMotion.current
    val dateBelow = layoutFraction(narrow.modifiedBelow, glide)
    val sizeBelow = layoutFraction(narrow.sizeBelow, glide)
    // Read in composition only through this, so the row recomposes when the dot comes or goes, not every frame.
    val sizeLeaving by remember { derivedStateOf { sizeBelow.value > 0f } }
    // Space kept free at the end of the name's line (an overlay there, such as a search result's Show in folder).
    val inset by layoutDp(endInset, glide)
    // A folder whose count has not come yet has no size: nothing of it goes on the second line.
    val hasSize = !sizeText.isNullOrEmpty()
    Layout(
        modifier = modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val full = ROW_HEIGHT.toPx() + (TWO_LINE_ROW_HEIGHT - ROW_HEIGHT).toPx() * dateBelow.value.coerceIn(0f, 1f)
                val height = (full * grow.value * stay).roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = full.roundToInt(), maxHeight = full.roundToInt()))
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                val e = enter()
                alpha = e * (if (motion) grow.value else 1f) * stay * movingAlpha
                translationY = (1f - e) * STAGGER_RISE.toPx()
                translationX = -(1f - stay) * LEAVE_SHIFT.toPx()
                clip = true
            }
            .clip(shape)
            .background(container)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .drawBehind {
                // Without motion the highlight simply holds while the browser marks the row as arriving.
                val h = if (motion) highlight.value else if (arriving || flash) 1f else 0f
                if (h > 0f) drawRect(colors.primaryContainer.copy(alpha = h * 0.9f))
                if (lit > 0f) drawRect(colors.primaryContainer.copy(alpha = lit))
            }
            .then(if (lit > 0f) Modifier.border(1.5.dp, colors.primary.copy(alpha = lit), shape) else Modifier)
            // Spring-open: a line fills the bottom edge before the folder opens.
            .drawWithContent {
                drawContent()
                val p = spring()
                if (p > 0f) {
                    val h = 2.dp.toPx()
                    drawRect(colors.primary, Offset(0f, size.height - h), Size(size.width * p, h))
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        if (event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            currentOnSecondary()
                            continue
                        }
                        if (!event.buttons.isPrimaryPressed) continue
                        event.changes.forEach { it.consume() }
                        val time = event.changes.firstOrNull()?.uptimeMillis ?: continue
                        val double = lastPress[0] != Long.MIN_VALUE && time - lastPress[0] <= viewConfiguration.doubleTapTimeoutMillis
                        lastPress[0] = if (double) Long.MIN_VALUE else time
                        currentOnPress(event, double)
                    }
                }
            }
            .padding(horizontal = 6.dp),
        content = {
            val dim = Modifier.graphicsLayer { alpha = (if (hidden) HIDDEN_ALPHA else 1f) * lockedAlpha }
            Box(Modifier.layoutId(RowCell.Icon)) {
                KindTile(
                    kind,
                    selected,
                    dim.graphicsLayer {
                        scaleX = 1f + (DROP_ICON_SCALE - 1f) * lit
                        scaleY = scaleX
                    },
                )
                if (locked || lockBadge.value > 0f) LockBadge({ lockBadge.value }, Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp))
            }
            if (renaming) {
                RenameField(name, kind == FileKind.Folder, renameError != null, motion, onRenameCommit, Modifier.layoutId(RowCell.Name))
                if (renameError != null) {
                    Text(
                        renameError, Modifier.layoutId(RowCell.Error),
                        fontSize = CELL_FONT, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                    return@Layout
                }
            } else TooltipArea(
                tooltip = {
                    Surface(shape = styledCorner(4.dp), color = colors.inverseSurface) {
                        Text(
                            name,
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inverseOnSurface,
                        )
                    }
                },
                modifier = Modifier.layoutId(RowCell.Name).then(dim),
            ) {
                val mark = colors.primaryContainer
                val onMark = colors.onPrimaryContainer
                val text = remember(name, match, mark, onMark) { highlighted(name, match, SpanStyle(background = mark, color = onMark)) }
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = content)
            }
            // The item count fades in when it arrives after the listing; a count already there just shows.
            val countAlpha by animateFloatAsState(if (sizeText != null) 1f else 0f, if (motion) tween(OPEN_MILLIS) else snap())
            Text(
                sizeText.orEmpty(),
                Modifier.layoutId(RowCell.Size).graphicsLayer { alpha = countAlpha }.then(dim),
                fontSize = CELL_FONT, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            // "4.1 MB · Nov 8, 2:32 PM": the dot between them on the second line fades in as the size arrives there.
            if (hasSize && (narrow.sizeBelow || sizeLeaving)) {
                Text(
                    "·",
                    Modifier.layoutId(RowCell.Dot).clearAndSetSemantics { }.graphicsLayer { alpha = sizeBelow.value.coerceIn(0f, 1f) * countAlpha }.then(dim),
                    fontSize = CELL_FONT, color = muted, maxLines = 1, softWrap = false,
                )
            }
            Text(
                dateText, Modifier.layoutId(RowCell.Date).then(dim),
                fontSize = CELL_FONT, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        },
    ) { measurables, constraints ->
        rowCells(measurables, constraints, dateBelow.value.coerceIn(0f, 1f), sizeBelow.value.coerceIn(0f, 1f), hasSize, inset.roundToPx().coerceAtLeast(0))
    }
}

/** A [FileRow]'s cells, by layout id. */
private enum class RowCell { Icon, Name, Error, Size, Dot, Date }

/** A two-line row: the name, then the date (and the size before it) under it. */
internal val TWO_LINE_ROW_HEIGHT = 52.dp

/** Between the name and the second line, and around the dot on it. */
private val LINE_GAP = 2.dp
private val DOT_GAP = 4.dp

/**
 * Lays a row's cells out on one line (icon, name filling the rest, size, date, [COLUMN_GAP] apart, centred: as a Row),
 * or moves the date ([dateBelow] 1) and then the size ([sizeBelow] 1) under the name, each at its own width. Fractions
 * between place each cell part way, so the cells glide between the two. Without [hasSize] the second line has only
 * the date. The name's line ends [endInset] px short of the row's end.
 */
private fun MeasureScope.rowCells(
    measurables: List<Measurable>,
    constraints: Constraints,
    dateBelow: Float,
    sizeBelow: Float,
    hasSize: Boolean,
    endInset: Int,
): MeasureResult {
    val byId = measurables.associateBy { it.layoutId as RowCell }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val gap = COLUMN_GAP.roundToPx()
    val icon = byId.getValue(RowCell.Icon).measure(Constraints(maxWidth = width, maxHeight = height))
    val x0 = icon.width + gap
    val error = byId[RowCell.Error]
    val size = byId[RowCell.Size]
    val date = byId[RowCell.Date]
    val dot = byId[RowCell.Dot]
    fun fixed(w: Int) = Constraints(minWidth = w.coerceAtLeast(0), maxWidth = w.coerceAtLeast(0), maxHeight = height)
    val sizeColumn = SIZE_COLUMN.roundToPx()
    val dateColumn = DATE_COLUMN.roundToPx()
    val errorColumn = (SIZE_COLUMN + DATE_COLUMN + COLUMN_GAP).roundToPx()
    // What leaves the name's line as the cells move down.
    val reserved = if (error != null) {
        lerpPx(errorColumn + gap, 0, dateBelow)
    } else {
        lerpPx(dateColumn + gap, 0, dateBelow) + lerpPx(sizeColumn + gap, 0, sizeBelow)
    }
    val line1End = width - endInset
    val nameW = (line1End - x0 - reserved).coerceAtLeast(0)
    val name = byId.getValue(RowCell.Name).measure(fixed(nameW))
    val errorP = error?.measure(fixed(lerpPx(errorColumn, width - x0, dateBelow)))
    val sizeP = size?.let { it.measure(fixed(lerpPx(sizeColumn, it.maxIntrinsicWidth(height), sizeBelow))) }
    val dateP = date?.let { it.measure(fixed(lerpPx(dateColumn, it.maxIntrinsicWidth(height), dateBelow))) }
    val dotP = dot?.measure(Constraints(maxHeight = height))
    val line2H = maxOf(dateP?.height ?: 0, sizeP?.height ?: 0, errorP?.height ?: 0)
    val blockTop = Alignment.CenterVertically.align(name.height + LINE_GAP.roundToPx() + line2H, height)
    val line2Top = blockTop + name.height + LINE_GAP.roundToPx()
    fun oneLineY(h: Int) = Alignment.CenterVertically.align(h, height)
    // On the name's line once it has a second line under it.
    fun line1Y(h: Int) = lerpPx(oneLineY(h), blockTop + Alignment.CenterVertically.align(h, name.height), dateBelow)
    fun line2Y(h: Int) = line2Top + Alignment.CenterVertically.align(h, line2H)
    return layout(width, height) {
        icon.placeRelative(0, oneLineY(icon.height))
        name.placeRelative(x0, lerpPx(oneLineY(name.height), blockTop, dateBelow))
        errorP?.placeRelative(lerpPx(width - errorColumn, x0, dateBelow), lerpPx(oneLineY(errorP.height), line2Y(errorP.height), dateBelow))
        val dotGap = DOT_GAP.roundToPx()
        // The size's share of the second line, before the date.
        val sizeShare = if (sizeP == null || !hasSize) 0 else lerpPx(0, sizeP.width + dotGap + (dotP?.width ?: 0) + dotGap, sizeBelow)
        if (sizeP != null) {
            val inlineX = line1End - lerpPx(dateColumn + gap, 0, dateBelow) - sizeColumn
            sizeP.placeRelative(lerpPx(inlineX, x0, sizeBelow), lerpPx(line1Y(sizeP.height), line2Y(sizeP.height), sizeBelow))
            dotP?.placeRelative(x0 + sizeP.width + dotGap, line2Y(dotP.height))
        }
        if (dateP != null) {
            dateP.placeRelative(lerpPx(width - dateColumn, x0 + sizeShare, dateBelow), lerpPx(oneLineY(dateP.height), line2Y(dateP.height), dateBelow))
        }
    }
}

/** Rows of folders adb can't read. */
private const val LOCKED_ALPHA = 0.62f

/** The lock badge pops in this long after its row starts to rise in. */
private const val LOCK_BADGE_DELAY_MILLIS = 120

/** Test tag of a locked folder row's lock badge. */
const val LOCK_BADGE_TAG = "files-lock-badge"

/** The lock on a locked folder's icon, at [scale] (0 to 1, read at draw time). */
@Composable
private fun LockBadge(scale: () -> Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.testTag(LOCK_BADGE_TAG).size(15.dp)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scaleX
            }
            .clip(styledCorner(8.dp)).background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = "No access", Modifier.size(11.dp), tint = colors.onSurfaceVariant)
    }
}

/** [name] with the first case-insensitive match of [query] in [style]; plain without a query or a match. */
internal fun highlighted(name: String, query: String?, style: SpanStyle): AnnotatedString {
    val q = query?.trim().orEmpty()
    val at = if (q.isEmpty()) -1 else name.indexOf(q, ignoreCase = true)
    if (at < 0) return AnnotatedString(name)
    return buildAnnotatedString {
        append(name)
        addStyle(style, at, at + q.length)
    }
}

/** Test tag of the rename field. */
const val RENAME_FIELD_TAG = "files-rename-field"

/** A refused name shakes the field this far, over [SHAKE_MILLIS]. */
private val SHAKE = 4.dp
private const val SHAKE_MILLIS = 180

/** Where the name's stem ends: before the extension of a file ("notes" of "notes.txt"), the whole name for a folder. */
internal fun stemEnd(name: String, isDir: Boolean): Int {
    if (isDir) return name.length
    val dot = name.lastIndexOf('.')
    return if (dot > 0) dot else name.length
}

/**
 * The name as a text field, focused with its stem selected. Enter, or focus leaving, commits through [onCommit],
 * which answers true when the name was refused: then the field shakes, stays, and takes focus back. Esc is the tab's
 * (it cancels).
 */
@Composable
private fun RenameField(name: String, isDir: Boolean, error: Boolean, motion: Boolean, onCommit: (String) -> Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, stemEnd(name, isDir)))) }
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Whether the field has had focus, so its first unfocused state is not taken for clicking away. A plain holder.
    val hadFocus = remember { BooleanArray(1) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun commit(fromBlur: Boolean) {
        val refused = onCommit(value.text)
        // Refused as focus left: take it back, so the field stays usable (keys reach it again).
        if (refused && fromBlur) scope.launch { runCatching { focus.requestFocus() } }
        if (refused && motion) {
            scope.launch {
                shake.snapTo(0f)
                shake.animateTo(0f, keyframes {
                    durationMillis = SHAKE_MILLIS
                    1f at SHAKE_MILLIS / 6
                    -1f at SHAKE_MILLIS * 2 / 6
                    1f at SHAKE_MILLIS * 3 / 6
                    -1f at SHAKE_MILLIS * 4 / 6
                    0.5f at SHAKE_MILLIS * 5 / 6
                })
            }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .graphicsLayer { translationX = shake.value * SHAKE.toPx() }
            .testTag(RENAME_FIELD_TAG)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus[0] = true else if (hadFocus[0]) commit(fromBlur = true)
            }
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) commit(fromBlur = false)
                true
            },
        decorationBox = { inner ->
            Box(
                Modifier.height(26.dp).clip(styledCorner(6.dp)).background(colors.surface)
                    .border(1.5.dp, if (error) colors.error else colors.primary, styledCorner(6.dp))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** The kind's icon: a folder in the accent color, any other kind on its tinted tile. */
@Composable
internal fun KindTile(kind: FileKind, selected: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    // Retro fills a selected row with a solid color the palette's own accents can vanish into.
    val onRetroSelection = selected && currentStyle == ThemeStyle.Retro && (kind == FileKind.Folder || kind == FileKind.Document)
    val tint = if (onRetroSelection) colors.onSecondaryContainer else kindTint(kind, dark, colors.primary, colors.tertiary)
    Box(
        modifier.size(ICON_COLUMN).clip(styledCorner(7.dp))
            .background(if (kind == FileKind.Folder) Color.Transparent else tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kindIcon(kind), contentDescription = null, Modifier.size(18.dp), tint = tint)
    }
}

internal fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.Folder -> Icons.Filled.Folder
    FileKind.Image -> Icons.Filled.Image
    FileKind.Video -> Icons.Filled.Movie
    FileKind.Apk -> Icons.Filled.Android
    FileKind.Audio -> Icons.Filled.MusicNote
    FileKind.Document -> Icons.Filled.Description
}

/** Fixed hues for the media kinds (lighter on dark surfaces); folders and documents follow the palette. */
internal fun kindTint(kind: FileKind, dark: Boolean, primary: Color, tertiary: Color): Color = when (kind) {
    FileKind.Folder -> primary
    FileKind.Document -> tertiary
    FileKind.Image -> if (dark) Color(0xFF7FD3AE) else Color(0xFF2E7D5B)
    FileKind.Video -> if (dark) Color(0xFFF2A6BE) else Color(0xFFB04A6A)
    FileKind.Apk -> if (dark) Color(0xFF9AD98F) else Color(0xFF3D8B37)
    FileKind.Audio -> if (dark) Color(0xFFE8C07A) else Color(0xFF9A6A1A)
}
