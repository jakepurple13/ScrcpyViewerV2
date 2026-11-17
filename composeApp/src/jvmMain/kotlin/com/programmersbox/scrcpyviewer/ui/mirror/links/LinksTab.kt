package com.programmersbox.scrcpyviewer.ui.mirror.links

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.programmersbox.scrcpyviewer.ui.presets.ErrorBanner
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.links.Before
import com.programmersbox.scrcpyviewer.links.ExtraType
import com.programmersbox.scrcpyviewer.links.LinkKind
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.files.layoutDp
import com.programmersbox.scrcpyviewer.ui.mirror.files.layoutFraction
import com.programmersbox.scrcpyviewer.ui.mirror.reportsSheetFocus
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.warnColor

/** The spec's motion table, in milliseconds. */
internal const val KIND_POP_MILLIS = 350
internal const val DETAILS_MILLIS = 400
internal const val RESULT_MILLIS = 400
internal const val SHAKE_MILLIS = 400
internal const val ROW_IN_MILLIS = 420
internal const val FLIP_MILLIS = 420
internal const val FLASH_MILLIS = 600
internal const val FOLD_MILLIS = 300

/** Under reduced motion: changes are instant or this short fade, and nothing shakes. */
internal const val REDUCED_FADE_MILLIS = 150

private val FieldShape = RoundedCornerShape(10.dp)
private val ChipShape = RoundedCornerShape(10.dp)
private val MonoSize = 12.5.sp

/** "Before opening" choices, in menu order, with their copy. */
private fun beforeLabel(before: Before): String = when (before) {
    Before.None -> "Nothing"
    Before.ForceStop -> "Force-stop the app (cold start)"
    Before.ClearTask -> "Clear the task (--activity-clear-task)"
}

/** Opens downward from its top edge over [millis] (size FastOutSlowIn, with a fade), or fades in [REDUCED_FADE_MILLIS]. */
internal fun expandIn(millis: Int, motion: Boolean): EnterTransition =
    if (motion) {
        expandVertically(tween(millis, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(millis))
    } else {
        fadeIn(tween(REDUCED_FADE_MILLIS))
    }

/** The reverse of [expandIn]. */
internal fun foldOut(millis: Int, motion: Boolean): ExitTransition =
    if (motion) {
        shrinkVertically(tween(millis, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(millis))
    } else {
        fadeOut(tween(REDUCED_FADE_MILLIS))
    }

/**
 * 1 at rest; each time [key] changes after the first composition it drops to 0 in that same frame and climbs back to 1
 * over [millis] (linear: callers shape it). Without [motion] it stays 1. Read it in a layout, layer or draw block.
 */
@Composable
internal fun rememberReplay(key: Any?, millis: Int, motion: Boolean): State<Float> {
    val progress = remember { mutableFloatStateOf(1f) }
    val last = remember { arrayOf(key) }
    if (last[0] != key) {
        last[0] = key
        // Set here, not in the effect: the frame that shows the change already shows the start of its motion.
        if (motion) progress.floatValue = 0f
    }
    LaunchedEffect(key) {
        if (progress.floatValue >= 1f) return@LaunchedEffect
        var start = -1L
        while (progress.floatValue < 1f) {
            withFrameNanos { now ->
                if (start < 0) start = now
                progress.floatValue = ((now - start) / (millis * 1_000_000f)).coerceAtMost(1f)
            }
        }
    }
    return progress
}

/** The failure shake's horizontal offset in dp at [p] (0..1): ±5 dp at 20, 40, 60 and 80 %, still at both ends. */
internal fun shakeOffset(p: Float): Float {
    val stops = floatArrayOf(0f, -5f, 5f, -5f, 5f, 0f)
    if (p <= 0f || p >= 1f) return 0f
    val at = p * 5f
    val i = at.toInt().coerceAtMost(4)
    val f = at - i
    return stops[i] + (stops[i + 1] - stops[i]) * f
}

/** The field flash's strength at [p] (0..1): up to full at 30 %, back to nothing at the end. */
internal fun flashStrength(p: Float): Float = when {
    p <= 0f || p >= 1f -> 0f
    p < 0.3f -> p / 0.3f
    else -> (1f - p) / 0.7f
}

/**
 * The Links tab: the link field with its kind chip, "Open with" and "Before opening", the Intent details expander, the
 * command preview, Open on device and Save as quick action, the result strip and the history. [apps] is the Apps
 * tab's listing (labels for "Open with"); [enabled] is false while the device is unavailable. [now] dates history rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinksTabContent(
    links: LinksState,
    apps: AppsUiState,
    enabled: Boolean,
    onEvent: (LinksEvent) -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = remember { !systemReducesMotion() },
    now: () -> Long = System::currentTimeMillis,
) {
    val colors = MaterialTheme.colorScheme
    val glide = motion && !LocalReduceMotion.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = linksStacked(maxWidth)
        // 0 side by side .. 1 stacked: a spring over a stack/unstack flip, the target itself otherwise (a plain resize
        // moves the fields in the same frame).
        val stack = layoutFraction(stacked, glide)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            PersistenceBanners(links, motion)
            FieldLabel("Link or intent")
            val flash = rememberReplay(links.fills, FLASH_MILLIS, motion)
            val (kind, kindLabel) = links.kind
            LinkTextField(
                value = links.text,
                onChange = { onEvent(LinksEvent.SetText(it)) },
                description = "Link or intent",
                onEnter = { if (enabled) onEvent(LinksEvent.Open) },
                modifier = Modifier.testTag(FIELD_TAG),
                flash = { flashStrength(flash.value) },
                leading = {
                    Icon(Icons.Filled.Link, contentDescription = null, Modifier.padding(end = 8.dp).size(18.dp), tint = colors.onSurfaceVariant)
                },
                trailing = {
                    AnimatedVisibility(
                        visible = links.text.isNotBlank(),
                        enter = if (motion) scaleIn(tween(KIND_POP_MILLIS, easing = EmphasizedDecelerate), 0.7f) + fadeIn(tween(KIND_POP_MILLIS)) else fadeIn(tween(REDUCED_FADE_MILLIS)),
                        exit = if (motion) scaleOut(tween(CLOSE_MILLIS), 0.7f) + fadeOut(tween(CLOSE_MILLIS)) else fadeOut(tween(REDUCED_FADE_MILLIS)),
                    ) {
                        KindChip(kind, kindLabel, motion, Modifier.padding(start = 8.dp))
                    }
                },
            )

            // One box a row when stacked, two otherwise; the boxes keep their identity so they glide between the layouts.
            // The container's height follows with the same spring.
            Pickers(links, apps, enabled, motion, stack, onEvent)
            // While the Apps tab's listing loads, "Open with" has only "Let Android choose": a thin line says more is coming.
            AnimatedVisibility(apps.loading, enter = expandIn(OPEN_MILLIS, motion), exit = foldOut(CLOSE_MILLIS, motion)) {
                LinearProgressIndicator(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp))
            }

            DetailsHeader(links.detailsOpen, motion) { onEvent(LinksEvent.ToggleDetails) }
            AnimatedVisibility(links.detailsOpen, enter = expandIn(DETAILS_MILLIS, motion), exit = foldOut(DETAILS_MILLIS, motion)) {
                DetailsBody(links, onEvent, enabled, motion, stacked, glide, stack)
            }

            CommandPreview(links.preview, motion, Modifier.padding(top = 12.dp))

            Box(Modifier.padding(top = 12.dp).then(resizes(glide))) {
                LookaheadScope {
                    val glides = glides(this, glide)
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        OpenButton(links, enabled, motion, glides) { onEvent(LinksEvent.Open) }
                        TextButton(onClick = { onEvent(LinksEvent.Save) }, modifier = glides, enabled = enabled && kind != LinkKind.NotALink) {
                            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save as quick action")
                        }
                    }
                }
            }

            ResultStrip(links, motion)

            HistorySection(links, onEvent, motion, now)
        }
    }
}

/** "Open with" and "Before opening": side by side, or stacked in a narrow tab ([stack] 0..1 morphs between the two). */
@Composable
private fun Pickers(
    links: LinksState,
    apps: AppsUiState,
    enabled: Boolean,
    motion: Boolean,
    stack: State<Float>,
    onEvent: (LinksEvent) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MorphRow(stack, Arrangement.spacedBy(12.dp), 14.dp, listOf(null, null), Modifier.padding(top = 12.dp)) {
        Column {
            FieldLabel("Open with")
            val listed = remember(apps.apps) { apps.apps.orEmpty().sortedBy { it.label.lowercase() } }
            val chosen = links.packageName
            DropdownField(
                value = chosen?.let { p -> listed.firstOrNull { it.packageName == p }?.label ?: p } ?: "Let Android choose",
                options = listOf<DeviceApp?>(null) + listed,
                description = "Open with",
                enabled = enabled,
                motion = motion,
                onSelect = { onEvent(LinksEvent.SetPackage(it?.packageName)) },
            ) { app ->
                if (app == null) {
                    Text("Let Android choose")
                } else {
                    Column {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.packageName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Column {
            FieldLabel("Before opening")
            // Force-stop needs an app: offered only with one chosen, and shown as "Nothing" without.
            val choices = if (links.packageName != null) Before.entries else listOf(Before.None, Before.ClearTask)
            val shown = links.request.before
            DropdownField(
                value = beforeLabel(shown),
                options = choices,
                description = "Before opening",
                enabled = enabled,
                motion = motion,
                onSelect = { onEvent(LinksEvent.SetBefore(it)) },
            ) { Text(beforeLabel(it)) }
        }
    }
}

/**
 * Lays [content]'s children in one row ([stack] 0) or one under another ([stack] 1), and anything between by
 * interpolating each child's width and position, so the move follows whatever drives [stack] (a spring, or nothing).
 * [fixed] lists each child's own width (null: a share of what is left in the row; full width when stacked).
 */
@Composable
internal fun MorphRow(
    stack: State<Float>,
    horizontal: Arrangement.Horizontal,
    gapY: Dp,
    fixed: List<Dp?>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val gapX = horizontal.spacing.roundToPx()
        val gy = gapY.roundToPx()
        val n = measurables.size
        if (!constraints.hasBoundedWidth) {
            // No width to share: the children's own widths, in one row.
            val natural = measurables.map { it.measure(Constraints()) }
            val h = natural.maxOfOrNull { it.height } ?: 0
            layout(natural.sumOf { it.width } + gapX * (n - 1).coerceAtLeast(0), h) {
                var x = 0
                natural.forEach {
                    it.place(x, (h - it.height) / 2)
                    x += it.width + gapX
                }
            }
        } else {
            val f = stack.value.coerceIn(0f, 1f)
            // Staged so the children never overlap: first they move down (f 0..0.5), then they widen (f 0.5..1).
            val drop = FastOutSlowInEasing.transform((f / 0.5f).coerceIn(0f, 1f))
            val widen = FastOutSlowInEasing.transform(((f - 0.5f) / 0.5f).coerceIn(0f, 1f))
            val width = constraints.maxWidth
            val own = fixed.map { it?.roundToPx() }
            val flexible = own.count { it == null }.coerceAtLeast(1)
            val share = ((width - own.sumOf { it ?: 0 } - gapX * (n - 1)) / flexible).coerceAtLeast(0)
            val wide = own.map { it ?: share }
            val tall = own.map { it ?: width }
            val placeables = measurables.mapIndexed { i, m ->
                val w = (wide[i] + (tall[i] - wide[i]) * widen).roundToInt()
                m.measure(Constraints(minWidth = w, maxWidth = w))
            }
            val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
            val stackedHeight = placeables.sumOf { it.height } + gy * (n - 1).coerceAtLeast(0)
            val height = (rowHeight + (stackedHeight - rowHeight) * drop).roundToInt()
            layout(width, height) {
                var wideX = 0
                var stackedY = 0
                placeables.forEachIndexed { i, p ->
                    val x = wideX * (1f - widen)
                    val y = (rowHeight - p.height) / 2f * (1f - drop) + stackedY * drop
                    p.place(x.roundToInt(), y.roundToInt())
                    wideX += wide[i] + gapX
                    stackedY += p.height + gy
                }
            }
        }
    }
}

/** A size change follows [layoutSpring]; none without [glide]. */
private fun resizes(glide: Boolean): Modifier = if (glide) Modifier.animateContentSize(layoutSpring()) else Modifier

/** Position changes glide with [layoutSpring]; none without [glide]. */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun glides(scope: LookaheadScope, glide: Boolean): Modifier =
    if (glide) Modifier.animateBounds(scope, boundsTransform = { _, _ -> layoutSpring() }) else Modifier

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A one-line field in the tab's style: [value] is the state's, [onChange] sends edits (handled at once, so the field and
 * the state agree after each keystroke; a change from elsewhere, like a history row's fill, replaces the text). Esc
 * leaves the field (the sheet closes only on an Esc nothing used); Enter runs [onEnter] when given. [flash] (0..1) draws
 * a ring around the field.
 */
@Composable
private fun LinkTextField(
    value: String,
    onChange: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    onEnter: (() -> Unit)? = null,
    flash: () -> Float = { 0f },
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // Text set from elsewhere shows from its start (a long link reads from its scheme), or with the cursor at its end
    // while the field is being typed in.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange.Zero)) }
    // Written during composition on purpose: a LaunchedEffect would show one frame of the old text. It converges at once
    // (the next pass finds field.text == value), and only state local to this field is written.
    if (field.text != value) field = TextFieldValue(value, TextRange(if (focused) value.length else 0))
    val ring by animateDpAsState(if (focused) 2.dp else 1.dp, tween(150))
    val ringColor by animateColorAsState(if (focused) colors.primary else colors.outlineVariant, tween(150))
    val focusManager = LocalFocusManager.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = MonoSize, color = colors.onSurface)
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp)
            .drawWithContent {
                drawContent()
                val strength = flash()
                if (strength > 0f) {
                    val w = 3.dp.toPx()
                    drawRoundRect(
                        colors.primary.copy(alpha = 0.5f * strength),
                        topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(10.dp.toPx() - w / 2), style = Stroke(w),
                    )
                }
            }
            .clip(FieldShape).background(colors.surfaceContainerHighest).border(ring, ringColor, FieldShape)
            .padding(start = if (leading != null) 10.dp else 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (field.text.isEmpty() && placeholder != null) {
                Text(placeholder, style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.7f)), maxLines = 1)
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != value) onChange(it.text)
                },
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = description }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> {
                                focusManager.clearFocus()
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> onEnter?.let {
                                it()
                                true
                            } ?: false
                            else -> false
                        }
                    },
            )
        }
        trailing?.invoke()
    }
}

/** The detected kind: it pops (scale .7 → 1, 350 ms) each time its label changes, its colors easing to the new kind's. */
@Composable
private fun KindChip(kind: LinkKind, label: String, motion: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val warn = warnColor()
    val (bg, fg) = when (kind) {
        LinkKind.Web -> colors.primaryContainer to colors.onPrimaryContainer
        LinkKind.Scheme -> warn.copy(alpha = 0.16f) to warn
        LinkKind.Intent -> colors.tertiaryContainer to colors.onTertiaryContainer
        LinkKind.NotALink -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    val spec = tween<Color>(if (motion) 300 else 0)
    val container by animateColorAsState(bg, spec)
    val content by animateColorAsState(fg, spec)
    val pop = rememberReplay(label, KIND_POP_MILLIS, motion)
    Box(
        modifier
            .graphicsLayer {
                val p = EmphasizedDecelerate.transform(pop.value)
                val scale = 0.7f + 0.3f * p
                scaleX = scale
                scaleY = scale
                alpha = 0.3f + 0.7f * p
            }
            .then(if (motion) Modifier.animateContentSize(tween(KIND_POP_MILLIS, easing = EmphasizedDecelerate)) else Modifier)
            .clip(ChipShape).background(container)
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics { contentDescription = "Kind: $label" },
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

/**
 * A dropdown in the tab's field style showing [value]; its menu lists [options] through [option]. The menu reports
 * focus to the sheet, so keys typed in it stay there.
 */
@Composable
private fun <T> DropdownField(
    value: String,
    options: List<T>,
    description: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mono: Boolean = false,
    motion: Boolean = true,
    option: @Composable (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (motion) tween(OPEN_MILLIS, easing = EmphasizedDecelerate) else snap())
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(FieldShape).background(colors.surfaceContainerHighest)
                .border(1.dp, colors.outlineVariant, FieldShape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .semantics { contentDescription = description }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (mono) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = MonoSize)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.graphicsLayer { rotationZ = turn }, tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.reportsSheetFocus().heightIn(max = 360.dp)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { option(o) },
                    onClick = {
                        open = false
                        onSelect(o)
                    },
                )
            }
        }
    }
}

/** "Intent details" with its chevron, turning 90° as the body opens. */
@Composable
private fun DetailsHeader(open: Boolean, motion: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 90f else 0f, if (motion) tween(DETAILS_MILLIS, easing = FastOutSlowInEasing) else snap())
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            Modifier.size(20.dp).graphicsLayer { rotationZ = turn }, tint = colors.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text("Intent details", style = MaterialTheme.typography.labelLarge, color = colors.primary)
    }
}

/** Action, Category and one extra (type, key, value). Used only while open. */
@Composable
private fun DetailsBody(links: LinksState, onEvent: (LinksEvent) -> Unit, enabled: Boolean, motion: Boolean, stacked: Boolean, glide: Boolean, stack: State<Float>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        FieldLabel("Action")
        LinkTextField(links.action, { onEvent(LinksEvent.SetAction(it)) }, description = "Action", enabled = enabled)
        FieldLabel("Category", Modifier.padding(top = 10.dp))
        LinkTextField(links.category, { onEvent(LinksEvent.SetCategory(it)) }, description = "Category", enabled = enabled)
        FieldLabel("Extra", Modifier.padding(top = 10.dp))
        // Stacked: the type field alone on the first line, key and value full width under it, with a 2 dp rule at the
        // group's start. The details' expand is outside this scope; the fields glide inside it.
        val rule by layoutDp(if (stacked) 8.dp else 0.dp, glide)
        val ruleColor = colors.outlineVariant
        Box(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val share = (rule.toPx() / 8.dp.toPx()).coerceIn(0f, 1f)
                    if (share > 0f) drawRect(ruleColor.copy(alpha = ruleColor.alpha * share), size = Size(2.dp.toPx(), size.height))
                }
                .padding(start = rule.coerceAtLeast(0.dp)),
        ) {
            MorphRow(stack, Arrangement.spacedBy(8.dp), 8.dp, listOf(92.dp, null, null)) {
                val extra = links.extra
                DropdownField(
                    value = extra.type.name.lowercase(),
                    options = ExtraType.entries,
                    description = "Extra type",
                    enabled = enabled,
                    mono = true,
                    motion = motion,
                    onSelect = { onEvent(LinksEvent.SetExtra(extra.copy(type = it))) },
                ) { Text(it.name.lowercase(), fontFamily = FontFamily.Monospace) }
                LinkTextField(
                    extra.key, { onEvent(LinksEvent.SetExtra(links.extra.copy(key = it))) },
                    description = "Extra key", placeholder = "key", enabled = enabled,
                )
                LinkTextField(
                    extra.value, { onEvent(LinksEvent.SetExtra(links.extra.copy(value = it))) },
                    description = "Extra value", placeholder = "value", enabled = enabled,
                )
            }
        }
    }
}

/** The exact `adb -s … shell …` text: monospace, selectable, with the copy button; its height eases as it rewraps. */
@Composable
private fun CommandPreview(command: String, motion: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = styledCorner(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            SelectionContainer {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .then(if (motion) Modifier.animateContentSize(tween(OPEN_MILLIS, easing = FastOutSlowInEasing)) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Command" },
                )
            }
        }
        CopyButton(text = { command }, label = "Copy")
    }
}

/** Open on device: its text crossfades to a spinner and "Opening…" while the command runs (clicks then do nothing). */
@Composable
private fun OpenButton(links: LinksState, enabled: Boolean, motion: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    Button(onClick = onOpen, modifier = modifier, enabled = enabled && (links.canOpen || links.running)) {
        AnimatedContent(
            targetState = links.running,
            transitionSpec = {
                if (motion) {
                    fadeIn(tween(OPEN_MILLIS, delayMillis = 60)) togetherWith fadeOut(tween(CLOSE_MILLIS / 2)) using
                        SizeTransform(clip = false) { _, _ -> tween(OPEN_MILLIS, easing = FastOutSlowInEasing) }
                } else {
                    fadeIn(tween(REDUCED_FADE_MILLIS)) togetherWith fadeOut(tween(REDUCED_FADE_MILLIS)) using
                        SizeTransform(clip = false) { _, _ -> snap() }
                }
            },
            contentAlignment = Alignment.Center,
            label = "open",
        ) { running ->
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("Opening…")
                }
            } else {
                Text("Open on device")
            }
        }
    }
}

/**
 * The last run's outcome: expands (400 ms) when one arrives and folds away when it goes (a new link); colored by
 * outcome. A failure shakes it once (400 ms), each time a run fails, unless motion is reduced.
 */
@Composable
private fun ResultStrip(links: LinksState, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    // A plain holder: the outcome stays readable while the strip folds away after it is cleared.
    val last = remember { arrayOf<LinkOutcome?>(null) }
    links.outcome?.let { last[0] = it }
    // Each run that ends in a failure replays the shake (the outcome may be equal to the last one: the run count isn't).
    val shakeKey = links.runs.takeIf { links.outcome?.result?.ok == false }
    val failedRun = remember { intArrayOf(links.runs) }
    if (shakeKey != null) failedRun[0] = shakeKey
    val shake = rememberReplay(failedRun[0], SHAKE_MILLIS, motion)
    AnimatedVisibility(links.outcome != null, enter = expandIn(RESULT_MILLIS, motion), exit = foldOut(RESULT_MILLIS, motion)) {
        val outcome = last[0] ?: return@AnimatedVisibility
        val ok = outcome.result.ok
        val spec = tween<Color>(if (motion) RESULT_MILLIS else 0)
        val container by animateColorAsState(if (ok) colors.primaryContainer else colors.errorContainer, spec)
        val content by animateColorAsState(if (ok) colors.onPrimaryContainer else colors.onErrorContainer, spec)
        Box(Modifier.padding(top = 12.dp).graphicsLayer { translationX = shakeOffset(shake.value) * density }) {
            Row(
                Modifier.testTag(RESULT_TAG).fillMaxWidth().clip(FieldShape).background(container).padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = if (ok) "Opened" else "Not opened" },
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = null,
                    Modifier.padding(top = 1.dp).size(18.dp), tint = content,
                )
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = outcome,
                    transitionSpec = {
                        if (motion) {
                            fadeIn(tween(RESULT_MILLIS)) togetherWith fadeOut(tween(RESULT_MILLIS / 2)) using
                                SizeTransform(clip = false) { _, _ -> tween(RESULT_MILLIS, easing = FastOutSlowInEasing) }
                        } else {
                            fadeIn(tween(REDUCED_FADE_MILLIS)) togetherWith fadeOut(tween(REDUCED_FADE_MILLIS)) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = "result",
                ) { shown ->
                    Column {
                        Text(shown.headline, style = MaterialTheme.typography.bodyMedium, color = content)
                        shown.raw?.let {
                            SelectionContainer {
                                Text(
                                    it, Modifier.padding(top = 2.dp), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                                    style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.9f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "<n> min ago" style: just now, minutes, hours, yesterday, then days. */
internal fun timeAgo(at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** Space above the history: part of what [expandIn] grows, so nothing jumps when the first row comes. */
internal val HISTORY_TOP: Dp = 16.dp

/** Test tags: the link field (its whole box), the result strip, and each history row (see [historyTag]). */
internal const val FIELD_TAG = "links-field"
internal const val RESULT_TAG = "links-result"

internal fun historyTag(item: HistoryItem): String = when (item) {
    HistoryItem.Divider -> "links-divider"
    is HistoryItem.Entry -> "links-row:${item.entry.link}"
}

/**
 * The links file's problems above the form: an error strip while it can't be read or saved, and a dismissible note
 * when a corrupt file was backed up. Each expands and fades in, and folds away when it goes.
 */
@Composable
private fun PersistenceBanners(links: LinksState, motion: Boolean) {
    Banner(links.persistError, motion, BANNER_ERROR_TAG) { ErrorBanner("Links can't be saved: $it") }
    Banner(links.notice, motion, BANNER_NOTICE_TAG) { text ->
        val colors = MaterialTheme.colorScheme
        Surface(color = colors.secondaryContainer, shape = FieldShape, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = colors.onSecondaryContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = links::dismissNotice, Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", Modifier.size(16.dp), tint = colors.onSecondaryContainer)
                }
            }
        }
    }
}

/** [text] in [content] with a margin below, animated in and out; the last text stays on screen while it folds away. */
@Composable
private fun Banner(text: String?, motion: Boolean, tag: String, content: @Composable (String) -> Unit) {
    var last by remember { mutableStateOf(text) }
    if (text != null) last = text
    AnimatedVisibility(text != null, Modifier.testTag(tag), enter = expandIn(OPEN_MILLIS, motion), exit = foldOut(CLOSE_MILLIS, motion)) {
        Box(Modifier.padding(bottom = 12.dp)) { last?.let { content(it) } }
    }
}

internal const val BANNER_ERROR_TAG = "links-error-banner"
internal const val BANNER_NOTICE_TAG = "links-notice-banner"

/** Under 300 dp the Links tab stacks its dropdowns and the extra's fields. */
fun linksStacked(contentWidth: Dp): Boolean = contentWidth < 300.dp
