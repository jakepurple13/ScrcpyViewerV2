package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.shots.COPIED_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.COPY_ICON_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ShotLease
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Tip
import kotlinx.coroutines.launch
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt

/** The scrim fades in over this long when the editor opens. */
const val EDITOR_SCRIM_IN_MILLIS = 300

/** The dialog grows from [EDITOR_FROM_SCALE] and rises [EDITOR_RISE] while it fades in. */
const val EDITOR_IN_MILLIS = 450

/** Closing: the dialog shrinks to [EDITOR_TO_SCALE] and fades, with the scrim. */
const val EDITOR_OUT_MILLIS = 200

/** With reduced motion everything only fades, this long. */
const val EDITOR_FADE_MILLIS = 150

/** A tool's selection background crossfades. */
const val TOOL_SELECT_MILLIS = 200

/** The picked swatch grows to [SWATCH_SCALE] with its ring. */
const val SWATCH_SELECT_MILLIS = 250

/** An undone mark fades out. */
const val UNDO_FADE_MILLIS = 150

/** The discard prompt's box grows from .9 while it fades in; it fades out. */
const val PROMPT_IN_MILLIS = 300
const val PROMPT_FADE_MILLIS = 200

const val EDITOR_FROM_SCALE = 0.88f
const val EDITOR_TO_SCALE = 0.95f
val EDITOR_RISE = 14.dp
const val SWATCH_SCALE = 1.12f

/** The screenshot takes at most this share of the window's height, and at least [MIN_IMAGE_HEIGHT]. */
private const val IMAGE_HEIGHT_SHARE = 0.62f
private val MIN_IMAGE_HEIGHT = 320.dp
private val SIDE_WIDTH = 264.dp
private val DIALOG_PADDING = 22.dp
private val DIALOG_GAP = 18.dp
private val WINDOW_MARGIN = 16.dp

/** Material's standard easing: the dialog leaving. */
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

const val EDITOR_HINT = "Draw on the screenshot. Pixelate hides private text such as emails or order numbers."

private val MarkupTool.label: String get() = name

private val MarkupTool.icon: ImageVector
    get() = when (this) {
        MarkupTool.Pen -> Icons.Filled.Edit
        MarkupTool.Arrow -> Icons.Filled.NorthEast
        MarkupTool.Box -> Icons.Outlined.CropSquare
        MarkupTool.Pixelate -> Icons.Filled.BlurOn
    }

/**
 * The markup editor: a dialog titled "Edit screenshot" over a scrim filling this composable's place (the mirror
 * window). Shown while [shot] is not null; it keeps the last shot while it animates out.
 *
 * The screenshot is drawn scaled to fit and marked with the pointer: Pen, Arrow, Box, Pixelate, in one of four colors,
 * Undo (also Ctrl/Cmd+Z). [onCopy] copies the marked image (true: the Copy button shows a check for 1.5 s), [onSave]
 * saves it (true: the editor asks to close through [onClose]; false: it stays open with its marks). Close and Esc ask
 * "Discard your marks?" while there are marks not saved or copied, else call [onClose]. Every key that reaches the
 * editor is consumed: none goes on to the device.
 *
 * The shot's bitmap is [leases]'s: the editor acquires it while it draws it, so it is never closed under it.
 * [motion] false (reduced motion): only 150 ms fades.
 */
@Composable
fun MarkupEditor(
    shot: Shot?,
    motion: Boolean,
    onCopy: suspend (Shot, List<Mark>) -> Boolean,
    onSave: suspend (Shot, List<Mark>) -> Boolean,
    onClose: () -> Unit,
    leases: ScreenshotThumbnailState,
    modifier: Modifier = Modifier,
) {
    // The dialog keeps showing the last shot while it leaves. A plain holder.
    val last = remember { arrayOfNulls<Shot>(1) }
    shot?.let { last[0] = it }
    AnimatedVisibility(
        visible = shot != null,
        modifier = modifier,
        enter = fadeIn(tween(if (motion) EDITOR_SCRIM_IN_MILLIS else EDITOR_FADE_MILLIS)),
        exit = fadeOut(tween(if (motion) EDITOR_OUT_MILLIS else EDITOR_FADE_MILLIS)),
    ) {
        val current = shot ?: last[0] ?: return@AnimatedVisibility
        key(current.id) {
            EditorScrim(current, open = shot != null, motion, onCopy, onSave, onClose, leases)
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.EditorScrim(
    shot: Shot,
    open: Boolean,
    motion: Boolean,
    onCopy: suspend (Shot, List<Mark>) -> Boolean,
    onSave: suspend (Shot, List<Mark>) -> Boolean,
    onClose: () -> Unit,
    leases: ScreenshotThumbnailState,
) {
    val colors = MaterialTheme.colorScheme
    // This slot draws the shot's bitmap until it leaves the composition: only then may the thumbnail close it.
    val drawable = !shot.bitmap.asSkiaBitmap().isClosed
    remember(shot) { ShotLease(leases, shot) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.5f))
            // The scrim takes every pointer event: nothing under it (the device's screen) gets one.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val rise = with(LocalDensity.current) { EDITOR_RISE.roundToPx() }
        val enter: EnterTransition
        val exit: ExitTransition
        if (motion) {
            val spec = tween<Float>(EDITOR_IN_MILLIS, easing = EmphasizedDecelerate)
            enter = scaleIn(spec, initialScale = EDITOR_FROM_SCALE) +
                slideInVertically(tween(EDITOR_IN_MILLIS, easing = EmphasizedDecelerate)) { rise } +
                fadeIn(spec)
            exit = scaleOut(tween(EDITOR_OUT_MILLIS, easing = Standard), targetScale = EDITOR_TO_SCALE) +
                fadeOut(tween(EDITOR_OUT_MILLIS, easing = Standard))
        } else {
            enter = fadeIn(tween(EDITOR_FADE_MILLIS))
            exit = fadeOut(tween(EDITOR_FADE_MILLIS))
        }
        val aspect = shot.bitmap.width.toFloat() / shot.bitmap.height.coerceAtLeast(1)
        val maxImageWidth = maxWidth - WINDOW_MARGIN * 2 - DIALOG_PADDING * 2 - DIALOG_GAP - SIDE_WIDTH
        var imageHeight = maxOf(maxHeight * IMAGE_HEIGHT_SHARE, MIN_IMAGE_HEIGHT)
        if (imageHeight * aspect > maxImageWidth) imageHeight = maxOf(maxImageWidth, 1.dp) / aspect
        EditorDialog(
            shot, open, motion, drawable, imageHeight * aspect, imageHeight, onCopy, onSave, onClose,
            Modifier.animateEnterExit(enter = enter, exit = exit),
        )
    }
}

@Composable
private fun EditorDialog(
    shot: Shot,
    open: Boolean,
    motion: Boolean,
    drawable: Boolean,
    imageWidth: Dp,
    imageHeight: Dp,
    onCopy: suspend (Shot, List<Mark>) -> Boolean,
    onSave: suspend (Shot, List<Mark>) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val doc = remember { MarkupDocument(shot.bitmap.width, shot.bitmap.height) }
    var tool by remember { mutableStateOf(MarkupTool.Pen) }
    var color by remember { mutableStateOf(MarkupColor.Red) }
    var confirming by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var copies by remember { mutableIntStateOf(0) }
    /** A copy or save runs. */
    var busy by remember { mutableStateOf(false) }
    // The undone mark fading out, and its alpha (read at draw time).
    var fading by remember { mutableStateOf<Mark?>(null) }
    val fade = remember { Animatable(0f) }
    val currentOpen by rememberUpdatedState(open)

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
        if (busy || !currentOpen) return
        busy = true
        val marks = doc.marks.toList()
        scope.launch {
            try {
                if (onCopy(shot, marks)) {
                    // Marks drawn meanwhile are not in the copy: they stay unsaved.
                    if (doc.marks.toList() == marks) doc.markClean()
                    copies++
                }
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        if (busy || !currentOpen) return
        busy = true
        val marks = doc.marks.toList()
        scope.launch {
            try {
                if (onSave(shot, marks)) {
                    doc.markClean()
                    onClose()
                }
            } finally {
                busy = false
            }
        }
    }

    fun requestClose() {
        if (busy || !currentOpen) return
        if (doc.dirty) confirming = true else onClose()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val mac = remember { Os.isMac }

    Box(
        modifier
            .focusRequester(focus)
            .focusable()
            // Every key is the editor's while it is open: it never reaches the device.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && currentOpen) {
                    when {
                        event.key == Key.Escape -> if (confirming) confirming = false else requestClose()
                        event.key == Key.Z && (event.isMetaPressed || event.isCtrlPressed) && !event.isShiftPressed ->
                            if (!confirming) undo()
                    }
                }
                event.key == Key.Escape || event.key == Key.Z && (event.isMetaPressed || event.isCtrlPressed)
            },
    ) {
        Surface(shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerHigh, shadowElevation = 8.dp) {
            Row(Modifier.padding(DIALOG_PADDING), horizontalArrangement = Arrangement.spacedBy(DIALOG_GAP)) {
                MarkCanvas(shot, doc, drawable, tool, color, fading, { fade.value }, imageWidth, imageHeight)
                Column(Modifier.width(SIDE_WIDTH), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Edit screenshot", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MarkupTool.entries.forEach { t ->
                            ToolButton(t, selected = t == tool, motion) { tool = t }
                        }
                        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(colors.outlineVariant))
                        Tip(if (mac) "Undo (⌘Z)" else "Undo (Ctrl+Z)") {
                            IconButton(onClick = ::undo, enabled = doc.canUndo, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", Modifier.size(20.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkupColor.entries.forEach { c -> Swatch(c, selected = c == color, motion) { color = c } }
                    }
                    Text(EDITOR_HINT, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = ::copy, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            val iconMillis = if (motion) COPY_ICON_MILLIS else EDITOR_FADE_MILLIS
                            Crossfade(copied, animationSpec = tween(iconMillis), label = "editorCopyIcon") { done ->
                                if (done) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp).testTag("copiedIcon"))
                                } else {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp).testTag("copyIcon"))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (copied) "Copied" else "Copy")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = ::save) { Text("Save") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = ::requestClose) { Text("Close") }
                    }
                }
            }
        }
        DiscardPrompt(
            visible = confirming,
            motion = motion,
            onKeep = { confirming = false },
            onDiscard = {
                confirming = false
                onClose()
            },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** The screenshot at [width] x [height] with its marks; drags draw the current [tool]. */
@Composable
private fun MarkCanvas(
    shot: Shot,
    doc: MarkupDocument,
    drawable: Boolean,
    tool: MarkupTool,
    color: MarkupColor,
    fading: Mark?,
    fadeAlpha: () -> Float,
    width: Dp,
    height: Dp,
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
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
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
                    fun mark(end: MarkPoint): Mark = when (t) {
                        MarkupTool.Pen -> Mark.Pen(argb, points)
                        MarkupTool.Arrow -> Mark.Arrow(argb, start, end)
                        MarkupTool.Box -> Mark.Box(argb, start, end)
                        MarkupTool.Pixelate -> Mark.Pixelate(start, end)
                    }
                    preview = mark(start)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val at = image(change.position)
                            if (t == MarkupTool.Pen) points += at
                            preview = mark(at)
                        }
                        preview?.let(doc::add) // copies the pen's points; nothing for a drag under 2 px
                    } finally {
                        preview = null
                    }
                }
            },
    ) {
        if (!drawable) return@Canvas
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
                .size(40.dp)
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
            .size(30.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(2.dp, ring, CircleShape)
            .clip(CircleShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = color.label }
            .padding(4.dp)
            .background(Color(color.argb), CircleShape),
    )
}

/** "Discard your marks?" over the dialog, with its own scrim. */
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
                .clip(RoundedCornerShape(26.dp))
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
