package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.WakeStage
import com.programmersbox.scrcpyviewer.ui.mirror.shots.COPIED_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.COPY_ICON_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ShotLease
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Tip
import kotlinx.coroutines.launch
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt

/** With reduced motion everything only fades, this long. */
const val EDITOR_FADE_MILLIS = 150

/** The toolbar's height follows a wrap onto another row (or back) over this long. */
const val TOOLBAR_ROWS_MILLIS = 250

/** A tool's selection background crossfades. */
const val TOOL_SELECT_MILLIS = 200

/** The picked swatch grows to [SWATCH_SCALE] with its ring. */
const val SWATCH_SELECT_MILLIS = 250

/** An undone mark fades out. */
const val UNDO_FADE_MILLIS = 150

/** The discard prompt's box grows from .9 while it fades in; it fades out. */
const val PROMPT_IN_MILLIS = 300
const val PROMPT_FADE_MILLIS = 200

const val SWATCH_SCALE = 1.12f

/** The footer's hint. */
const val EDITOR_HINT = "Pixelate hides private text such as emails or order numbers."

/** The footer's image size: "1080 × 2340". */
fun editorImageSize(width: Int, height: Int): String = "$width × $height"

/** The canvas area's padding around the screenshot. */
private val CANVAS_PADDING = 12.dp

private val MarkupTool.label: String get() = name

private val MarkupTool.icon: ImageVector
    get() = when (this) {
        MarkupTool.Pen -> Icons.Filled.Edit
        MarkupTool.Arrow -> Icons.Filled.NorthEast
        MarkupTool.Box -> Icons.Outlined.CropSquare
        MarkupTool.Circle -> Icons.Outlined.Circle
        MarkupTool.Pixelate -> Icons.Filled.BlurOn
    }

/**
 * One edit of [shot]: its marks, the picked tool and color, and whether "Discard your marks?" asks. Made by
 * [rememberMarkupEditorState]; the window holding [MarkupEditorContent] calls [requestClose] for its close button.
 * UI thread only.
 */
@Stable
class MarkupEditorState internal constructor(val shot: Shot) {
    internal val doc = MarkupDocument(shot.bitmap.width, shot.bitmap.height)

    var tool by mutableStateOf(MarkupTool.Pen)
        internal set
    var color by mutableStateOf(MarkupColor.Red)
        internal set

    /** "Discard your marks?" shows. */
    var confirming by mutableStateOf(false)
        private set

    /** A copy or save runs. */
    internal var busy by mutableStateOf(false)

    /** Set by [MarkupEditorContent]: false while it leaves (nothing acts any more), what closes it, and Keep editing. */
    internal var active = true
    internal var close: () -> Unit = {}
    internal var declined: () -> Unit = {}

    /** There are marks not saved or copied. */
    val dirty: Boolean get() = doc.dirty

    /**
     * The window's close button, Esc, or the mirror window closing: with marks not saved or copied it asks "Discard
     * your marks?" (Keep editing / Discard, which closes), else it closes at once. True when it closed at once. Does
     * nothing (false) while a copy or save runs or the editor is leaving.
     */
    fun requestClose(): Boolean {
        if (busy || !active) return false
        if (doc.dirty) {
            confirming = true
            return false
        }
        close()
        return true
    }

    internal fun keepEditing() {
        confirming = false
        declined()
    }

    internal fun discard() {
        confirming = false
        close()
    }
}

/** The state of an edit of [shot]; a new one for each shot. */
@Composable
fun rememberMarkupEditorState(shot: Shot): MarkupEditorState = remember(shot) { MarkupEditorState(shot) }

/**
 * The markup editor's content, shaped for its own window: a toolbar (Pen, Arrow, Box, Pixelate | Undo | the four
 * swatches, then Save and Copy at its right), the screenshot filling the rest at its aspect ratio, and a footer with
 * the hint and the image size. "Discard your marks?" shows over it.
 *
 * The screenshot is marked with the pointer, in one of four colors; Undo (also Ctrl/Cmd+Z) takes the last mark back.
 * [onCopy] copies the marked image (true: the Copy button shows a check for 1.5 s), [onSave] saves it (true: the
 * editor closes through [onClose]; false: it stays open with its marks). Esc and the window's close button
 * ([MarkupEditorState.requestClose] on [state]) ask "Discard your marks?" while there are marks not saved or copied,
 * else call [onClose]. Keep editing in that prompt calls [onCloseDeclined] (a close the mirror window asked for is
 * off). Esc and Ctrl/Cmd+Z are consumed.
 *
 * The canvas draws through [wake] (0 off, 1 on; read at draw time only): black, then the screen switching on as the
 * mirror window's does ([WakeStage]), or with [wakeFades] a plain fade in. [active] false (the window is leaving):
 * nothing acts any more.
 *
 * The shot's bitmap is [leases]'s: the content acquires it while it draws it, so it is never closed under it.
 * [motion] false (reduced motion): only 150 ms fades.
 */
@Composable
fun MarkupEditorContent(
    shot: Shot,
    motion: Boolean,
    onCopy: suspend (Shot, List<Mark>) -> Boolean,
    onSave: suspend (Shot, List<Mark>) -> Boolean,
    onClose: () -> Unit,
    leases: ScreenshotThumbnailState,
    modifier: Modifier = Modifier,
    state: MarkupEditorState = rememberMarkupEditorState(shot),
    wake: () -> Float = { 1f },
    active: Boolean = true,
    onCloseDeclined: () -> Unit = {},
    wakeFades: Boolean = !motion,
) {
    val colors = MaterialTheme.colorScheme
    // This slot draws the shot's bitmap until it leaves the composition: only then may the thumbnail close it.
    val drawable = !shot.bitmap.asSkiaBitmap().isClosed
    remember(shot) { ShotLease(leases, shot) }
    SideEffect {
        state.active = active
        state.close = onClose
        state.declined = onCloseDeclined
    }
    val scope = rememberCoroutineScope()
    val doc = state.doc
    var copied by remember { mutableStateOf(false) }
    var copies by remember { mutableIntStateOf(0) }
    // The undone mark fading out, and its alpha (read at draw time).
    var fading by remember { mutableStateOf<Mark?>(null) }
    val fade = remember { Animatable(0f) }
    val currentActive by rememberUpdatedState(active)
    val currentOnCopy by rememberUpdatedState(onCopy)
    val currentOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        copied = true
        // On the frame clock, like the animations around it.
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < COPIED_MILLIS) Unit
        copied = false
    }

    fun undo() {
        val removed = doc.undo() ?: return
        fading = removed
        scope.launch {
            fade.snapTo(1f)
            fade.animateTo(0f, tween(UNDO_FADE_MILLIS))
            if (fading === removed) fading = null
        }
    }

    fun copy() {
        if (state.busy || !currentActive) return
        state.busy = true
        val marks = doc.marks.toList()
        scope.launch {
            try {
                if (currentOnCopy(shot, marks)) {
                    // Marks drawn meanwhile are not in the copy: they stay unsaved.
                    if (doc.marks.toList() == marks) doc.markClean()
                    copies++
                }
            } finally {
                state.busy = false
            }
        }
    }

    fun save() {
        if (state.busy || !currentActive) return
        state.busy = true
        val marks = doc.marks.toList()
        scope.launch {
            var saved = false
            try {
                if (currentOnSave(shot, marks)) {
                    doc.markClean()
                    saved = true
                }
            } finally {
                state.busy = false
            }
            if (saved) state.close()
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier
            .background(colors.surface)
            .focusRequester(focus)
            .focusable()
            // The editor's keys stay in its own window: the mirror window's device keys never see them.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && currentActive) {
                    when {
                        event.key == Key.Escape -> if (state.confirming) state.keepEditing() else state.requestClose()
                        event.key == Key.Z && (event.isMetaPressed || event.isCtrlPressed) && !event.isShiftPressed ->
                            if (!state.confirming) undo()
                    }
                }
                event.key == Key.Escape || event.key == Key.Z && (event.isMetaPressed || event.isCtrlPressed)
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            EditorToolbar(state, motion, copied, ::undo, ::copy, ::save)
            HorizontalDivider(color = colors.outlineVariant)
            // The screenshot, centred at its aspect ratio in whatever room is left: it scales with the window.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(colors.surfaceContainer)
                    .padding(CANVAS_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                val aspect = shot.bitmap.width.toFloat() / shot.bitmap.height.coerceAtLeast(1)
                var w = maxWidth
                var h = w / aspect
                if (h > maxHeight) {
                    h = maxHeight
                    w = h * aspect
                }
                Box(Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                    if (wakeFades) {
                        Box(Modifier.matchParentSize().graphicsLayer { alpha = wake().coerceIn(0f, 1f) }) {
                            MarkCanvas(shot, doc, drawable, state.tool, state.color, fading, { fade.value })
                        }
                    } else {
                        WakeStage(wake, standby = false) {
                            MarkCanvas(shot, doc, drawable, state.tool, state.color, fading, { fade.value })
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    EDITOR_HINT,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    editorImageSize(shot.bitmap.width, shot.bitmap.height),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        DiscardPrompt(
            visible = state.confirming,
            motion = motion,
            onKeep = state::keepEditing,
            onDiscard = state::discard,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * The top toolbar: the tools, Undo, the swatches, then Save and Copy at the right end of its last row. Nothing is ever
 * hidden: when the window is narrower than the toolbar, it wraps onto a second row (whole groups move: the tools,
 * "| Undo", "| swatches", and Save + Copy, which stay right-aligned), and its height animates to the new rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorToolbar(
    state: MarkupEditorState,
    motion: Boolean,
    copied: Boolean,
    onUndo: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    val mac = remember { Os.isMac }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .animateContentSize(if (motion) tween(TOOLBAR_ROWS_MILLIS, easing = EmphasizedDecelerate) else snap())
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Markup tools" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarGroup {
            MarkupTool.entries.forEach { t ->
                ToolButton(t, selected = t == state.tool, motion) { state.tool = t }
            }
        }
        ToolbarGroup {
            ToolbarSeparator()
            Tip(if (mac) "Undo (⌘Z)" else "Undo (Ctrl+Z)") {
                IconButton(onClick = onUndo, enabled = state.doc.canUndo, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", Modifier.size(20.dp))
                }
            }
        }
        ToolbarGroup {
            ToolbarSeparator()
            MarkupColor.entries.forEach { c -> Swatch(c, selected = c == state.color, motion) { state.color = c } }
            Spacer(Modifier.width(4.dp)) // room for the picked swatch to grow
        }
        // Fills what is left of its row, so Save and Copy sit at its right end (on a row of their own when need be).
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSave) { Text("Save", maxLines = 1) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onCopy, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                val iconMillis = if (motion) COPY_ICON_MILLIS else EDITOR_FADE_MILLIS
                Crossfade(copied, animationSpec = tween(iconMillis), label = "editorCopyIcon") { done ->
                    if (done) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp).testTag("copiedIcon"))
                    } else {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp).testTag("copyIcon"))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied" else "Copy", maxLines = 1)
            }
        }
    }
}

/** Toolbar items that wrap together. */
@Composable
private fun ToolbarGroup(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun ToolbarSeparator() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** The screenshot filling its place, with its marks; drags draw the current [tool]. */
@Composable
private fun MarkCanvas(
    shot: Shot,
    doc: MarkupDocument,
    drawable: Boolean,
    tool: MarkupTool,
    color: MarkupColor,
    fading: Mark?,
    fadeAlpha: () -> Float,
) {
    val currentTool by rememberUpdatedState(tool)
    val currentColor by rememberUpdatedState(color)
    // The drag in progress: drawn over the marks, added to the document on release.
    var preview by remember { mutableStateOf<Mark?>(null) }
    val cache = remember { PixelateCache() }
    val imageW = shot.bitmap.width.toFloat()
    val imageH = shot.bitmap.height.toFloat()
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Screenshot" }
            .pointerInput(shot) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val t = currentTool
                    val argb = currentColor.argb
                    val sx = imageW / size.width.coerceAtLeast(1)
                    val sy = imageH / size.height.coerceAtLeast(1)
                    fun image(at: androidx.compose.ui.geometry.Offset) = MarkPoint(at.x * sx, at.y * sy)
                    val start = image(down.position)
                    val points = ArrayList<MarkPoint>().apply { add(start) }
                    fun mark(at: MarkPoint, square: Boolean = false): Mark {
                        // Shift makes a Box a square and a Circle a circle.
                        val end = if (square && (t == MarkupTool.Box || t == MarkupTool.Circle)) squareCorner(start, at) else at
                        return when (t) {
                        // A copy: with the live list, each new Pen equals the last (same list), so the state
                        // write is skipped and the stroke only showed on release.
                        MarkupTool.Pen -> Mark.Pen(argb, points.toList())
                        MarkupTool.Arrow -> Mark.Arrow(argb, start, end)
                        MarkupTool.Box -> Mark.Box(argb, start, end)
                        MarkupTool.Circle -> Mark.Circle(argb, start, end)
                        MarkupTool.Pixelate -> Mark.Pixelate(start, end)
                        }
                    }
                    preview = mark(start)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val at = image(change.position)
                            if (t == MarkupTool.Pen) points += at
                            preview = mark(at, square = event.keyboardModifiers.isShiftPressed)
                        }
                        preview?.let(doc::add) // copies the pen's points; nothing for a drag under 2 px
                    } finally {
                        preview = null
                    }
                }
            },
    ) {
        // Checked again at draw time: a closed native bitmap must never be drawn.
        if (!drawable || shot.bitmap.asSkiaBitmap().isClosed) return@Canvas
        drawImage(
            shot.bitmap,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val marks = doc.marks
        val drag = preview
        val undone = fading
        val alpha = fadeAlpha()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val original = shot.bitmap.asSkiaBitmap()
            native.save()
            native.scale(size.width / imageW, size.height / imageH)
            drawMarks(native, original, marks, cache)
            if (undone != null && alpha > 0f) {
                Paint().use { paint ->
                    paint.alpha = (alpha * 255).roundToInt()
                    native.saveLayer(null, paint)
                }
                drawMarks(native, original, listOf(undone), cache)
                native.restore()
            }
            if (drag != null) drawMarks(native, original, listOf(drag), cache)
            native.restore()
        }
    }
}

@Composable
private fun ToolButton(tool: MarkupTool, selected: Boolean, motion: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spec = tween<Color>(if (motion) TOOL_SELECT_MILLIS else EDITOR_FADE_MILLIS)
    val background by animateColorAsState(if (selected) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f), spec)
    val tint by animateColorAsState(if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant, spec)
    Tip(tool.label) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(background)
                .selectable(selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = tool.label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(tool.icon, contentDescription = null, Modifier.size(20.dp), tint = tint)
        }
    }
}

@Composable
private fun Swatch(color: MarkupColor, selected: Boolean, motion: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        if (selected && motion) SWATCH_SCALE else 1f,
        tween(SWATCH_SELECT_MILLIS, easing = EmphasizedDecelerate),
    )
    val ring by animateColorAsState(
        if (selected) colors.onSurface else colors.onSurface.copy(alpha = 0f),
        tween(if (motion) SWATCH_SELECT_MILLIS else EDITOR_FADE_MILLIS),
    )
    Box(
        Modifier
            .size(26.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(2.dp, ring, CircleShape)
            .clip(CircleShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = color.label }
            .padding(4.dp)
            .background(Color(color.argb), CircleShape)
            // A hairline, so Black shows on a dark toolbar.
            .border(1.dp, colors.outline.copy(alpha = 0.5f), CircleShape),
    )
}

/** "Discard your marks?" over the editor, with its own scrim. */
@Composable
private fun DiscardPrompt(visible: Boolean, motion: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(tween(if (motion) PROMPT_FADE_MILLIS else EDITOR_FADE_MILLIS)),
        exit = fadeOut(tween(if (motion) PROMPT_FADE_MILLIS else EDITOR_FADE_MILLIS)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val boxEnter = if (motion) {
                scaleIn(tween(PROMPT_IN_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.9f)
            } else {
                EnterTransition.None
            }
            Surface(
                Modifier.widthIn(max = 320.dp).padding(16.dp).animateEnterExit(enter = boxEnter, exit = ExitTransition.None),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHighest,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp)) {
                    Text("Discard your marks?", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your marks aren't saved or copied yet.",
                        Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onKeep) { Text("Keep editing") }
                        TextButton(onClick = onDiscard) { Text("Discard") }
                    }
                }
            }
        }
    }
}
