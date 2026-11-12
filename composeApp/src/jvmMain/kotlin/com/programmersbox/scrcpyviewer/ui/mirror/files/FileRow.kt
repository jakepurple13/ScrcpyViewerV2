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
import androidx.compose.ui.text.TextRange
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
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

/**
 * One 40 dp entry row. [arriving] rows grow in and then hold a primary-container highlight that fades over
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
) {
    val colors = MaterialTheme.colorScheme
    // Grows from nothing only when it arrived while the folder was already showing.
    val grow = remember { Animatable(if (arriving && motion) 0f else 1f) }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(arriving) {
        if (!arriving || !motion) return@LaunchedEffect
        grow.animateTo(1f, tween(OPEN_MILLIS, easing = EmphasizedDecelerate))
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(ARRIVING_FADE_MILLIS.toInt(), easing = LinearEasing))
    }
    val leaving = leavingIndex >= 0
    val stay by animateFloatAsState(
        if (leaving) 0f else 1f,
        if (motion) tween(CLOSE_MILLIS, delayMillis = if (leaving) leavingIndex * LEAVING_STAGGER_MILLIS.toInt() else 0, easing = EmphasizedAccelerate) else snap(),
    )
    val container = when {
        selected -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val content = if (selected) colors.onSecondaryContainer else colors.onSurface
    val muted = if (selected) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant
    val shape = styledCorner(10.dp)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnSecondary by rememberUpdatedState(onSecondaryPress)
    val lastPress = remember { LongArray(1) { Long.MIN_VALUE } }
    Row(
        modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val height = (ROW_HEIGHT.toPx() * grow.value * stay).roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = ROW_HEIGHT.roundToPx(), maxHeight = ROW_HEIGHT.roundToPx()))
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                val e = enter()
                alpha = e * (if (motion) grow.value else 1f) * stay
                translationY = (1f - e) * STAGGER_RISE.toPx()
                translationX = -(1f - stay) * LEAVE_SHIFT.toPx()
                clip = true
            }
            .clip(shape)
            .background(container)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .drawBehind {
                // Without motion the highlight simply holds while the browser marks the row as arriving.
                val h = if (motion) highlight.value else if (arriving) 1f else 0f
                if (h > 0f) drawRect(colors.primaryContainer.copy(alpha = h * 0.9f))
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        val dim = Modifier.graphicsLayer { alpha = if (hidden) HIDDEN_ALPHA else 1f }
        KindTile(kind, dim)
        if (renaming) {
            RenameField(name, kind == FileKind.Folder, renameError != null, motion, onRenameCommit, Modifier.weight(1f))
            if (renameError != null) {
                Text(
                    renameError, Modifier.width(SIZE_COLUMN + DATE_COLUMN + COLUMN_GAP),
                    fontSize = CELL_FONT, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                return@Row
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
            modifier = Modifier.weight(1f).then(dim),
        ) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = content)
        }
        // The item count fades in when it arrives after the listing; a count already there just shows.
        val countAlpha by animateFloatAsState(if (sizeText != null) 1f else 0f, if (motion) tween(OPEN_MILLIS) else snap())
        Text(
            sizeText.orEmpty(),
            Modifier.width(SIZE_COLUMN).graphicsLayer { alpha = countAlpha }.then(dim),
            fontSize = CELL_FONT, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Text(
            dateText, Modifier.width(DATE_COLUMN).then(dim),
            fontSize = CELL_FONT, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
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
internal fun KindTile(kind: FileKind, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val tint = kindTint(kind, dark, colors.primary, colors.tertiary)
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
