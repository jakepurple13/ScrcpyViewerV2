package com.programmersbox.scrcpyviewer.ui.mirror.shots

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.files.OutboundDragStarter
import com.programmersbox.scrcpyviewer.ui.mirror.files.dragOutSource
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Screenshot taken: a white flash over the video, .9 to 0 opacity. */
const val FLASH_MILLIS = 450
private const val FLASH_ALPHA = 0.9f

/** The thumbnail slides in from the right while it scales up and fades in. */
const val THUMBNAIL_IN_MILLIS = 350

/** The thumbnail slides out to the right while it fades. */
const val THUMBNAIL_OUT_MILLIS = 300

/** With reduced motion the thumbnail only fades, in and out. */
const val THUMBNAIL_FADE_MILLIS = 150

/** A new screenshot's image crossfades over the old one. */
const val THUMBNAIL_SWAP_MILLIS = 250

/** The countdown ring refills when the time left jumps up. */
const val RING_REFILL_MILLIS = 300

/** The Copy icon crossfades into the check and back. */
const val COPY_ICON_MILLIS = 200

private val SLIDE = 40.dp
private const val FROM_SCALE = 0.9f
private val CARD_WIDTH = 150.dp
private val IMAGE_MAX_HEIGHT = 260.dp
private val EDGE = 16.dp

/** How far the close button sits out past the card's top-right corner (its centre is the corner). */
private val CLOSE_SIZE = 24.dp
private val CLOSE_OVERHANG = CLOSE_SIZE / 2

/** Material's standard accelerate: things leaving the screen. */
private val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

/**
 * The screenshot flashes asked for ([flash]) and the last one played. Kept outside [ScreenshotFlash], so a flash that
 * leaves and re-enters the composition does not play an old one again.
 */
@Stable
class ScreenshotFlashState {
    var requested by mutableIntStateOf(0)
        private set
    internal var played = 0

    /** A screenshot was taken: the flash plays once. */
    fun flash() {
        requested++
    }
}

/**
 * The white flash over the video when a screenshot is taken: plays once per [ScreenshotFlashState.flash]. Draws
 * nothing with reduced motion ([motion] false). Takes no input.
 */
@Composable
fun ScreenshotFlash(state: ScreenshotFlashState, motion: Boolean, modifier: Modifier = Modifier) {
    if (!motion) {
        state.played = state.requested // skipped, not owed
        return
    }
    val alpha = remember { Animatable(0f) }
    val requested = state.requested
    LaunchedEffect(requested) {
        if (requested == state.played) return@LaunchedEffect
        state.played = requested
        alpha.snapTo(FLASH_ALPHA)
        alpha.animateTo(0f, tween(FLASH_MILLIS, easing = LinearEasing))
    }
    // Read at draw time: the flash redraws, nothing recomposes.
    Box(modifier.fillMaxSize().drawBehind { if (alpha.value > 0f) drawRect(Color.White, alpha = alpha.value) })
}

/**
 * The screenshot thumbnail: place it over the video area's bottom end; it keeps 16 dp from the edges itself.
 *
 * A 150 dp card with the screenshot and Copy / Show in folder buttons, and a close button on its top-right corner
 * ringed by the countdown. Clicking the image runs [onOpen]; dragging it starts [dragStarter]'s OS drag of the PNG
 * (copy only), with [onOsDrag] told while it runs. [onCopy] copies the image off the UI thread and says whether it
 * worked (the button then shows a check). Copy, reveal and drag restart the timer; a drop elsewhere dismisses it.
 *
 * [motion] false (the OS reduces motion): it fades in and out in 150 ms, the ring jumps instead of refilling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScreenshotThumbnail(
    state: ScreenshotThumbnailState,
    motion: Boolean,
    onCopy: suspend (Path) -> Boolean,
    onReveal: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    dragStarter: OutboundDragStarter,
    modifier: Modifier = Modifier,
    onOsDrag: (Boolean) -> Unit = {},
) {
    val shot by state.shown.collectAsState()
    // The card keeps showing the last screenshot while it leaves. A plain holder.
    val last = remember { arrayOfNulls<Shot>(1) }
    shot?.let { last[0] = it }
    val slide = with(LocalDensity.current) { SLIDE.roundToPx() }
    AnimatedVisibility(
        visible = shot != null,
        modifier = modifier.padding(end = EDGE - CLOSE_OVERHANG, bottom = EDGE),
        enter = if (motion) {
            val spec = tween<Float>(THUMBNAIL_IN_MILLIS, easing = EmphasizedDecelerate)
            slideInHorizontally(tween(THUMBNAIL_IN_MILLIS, easing = EmphasizedDecelerate)) { slide } +
                scaleIn(spec, initialScale = FROM_SCALE) + fadeIn(spec)
        } else {
            fadeIn(tween(THUMBNAIL_FADE_MILLIS))
        },
        exit = if (motion) {
            slideOutHorizontally(tween(THUMBNAIL_OUT_MILLIS, easing = StandardAccelerate)) { slide } +
                fadeOut(tween(THUMBNAIL_OUT_MILLIS, easing = StandardAccelerate))
        } else {
            fadeOut(tween(THUMBNAIL_FADE_MILLIS))
        },
    ) {
        val current = shot ?: last[0] ?: return@AnimatedVisibility
        ThumbnailCard(state, current, motion, onCopy, onReveal, onOpen, dragStarter, onOsDrag)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbnailCard(
    state: ScreenshotThumbnailState,
    shot: Shot,
    motion: Boolean,
    onCopy: suspend (Path) -> Boolean,
    onReveal: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    dragStarter: OutboundDragStarter,
    onOsDrag: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val copied by state.copied.collectAsState()
    val currentShot by rememberUpdatedState(shot)
    val hold = remember(state) { HoverHold(state) }
    // A card that leaves with the pointer on it gets no Exit: it must not hold the next one.
    DisposableEffect(hold) { onDispose(hold::clear) }
    // Only the card and the ring: the strips around the overhanging close button let the device's taps through.
    val hoverModifier = Modifier.pointerInput(hold) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent().type) {
                    PointerEventType.Enter -> hold.enter()
                    PointerEventType.Exit -> scope.launch { hold.exit() }
                }
            }
        }
    }
    Box {
        Surface(
            Modifier.padding(top = CLOSE_OVERHANG, end = CLOSE_OVERHANG).width(CARD_WIDTH).then(hoverModifier),
            shape = RoundedCornerShape(12.dp),
            color = colors.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)) {
                // Bumped when an OS drag ends: the gesture never sees its pointer-up, so it starts over.
                var drags by remember { mutableIntStateOf(0) }
                Crossfade(
                    shot,
                    Modifier.fillMaxWidth().animateContentSize(tween(THUMBNAIL_SWAP_MILLIS, easing = EmphasizedDecelerate)),
                    animationSpec = tween(THUMBNAIL_SWAP_MILLIS),
                    label = "thumbnailImage",
                ) { s ->
                    // This slot draws s's bitmap until it leaves the composition (the crossfade or the card's exit
                    // ended): only then may a retired shot's bitmap be closed. A closed one is never drawn.
                    val open = !s.bitmap.asSkiaBitmap().isClosed
                    remember(s) { ShotLease(state, s) }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (open) Image(
                            s.bitmap,
                            contentDescription = "Screenshot",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(max = IMAGE_MAX_HEIGHT)
                                .aspectRatio(s.bitmap.width.toFloat() / s.bitmap.height.coerceAtLeast(1))
                                .clip(RoundedCornerShape(8.dp))
                                .dragOutSource(dragStarter)
                                .pointerInput(dragStarter, drags) {
                                    detectDragGestures(
                                        onDragStart = { at ->
                                            val file = currentShot.file
                                            state.touch()
                                            // Out of the gesture: the OS drag blocks until the drop. The timer is
                                            // held for the whole drag.
                                            scope.launch {
                                                hold.dragging(true)
                                                onOsDrag(true)
                                                try {
                                                    dragStarter.start(listOf(file.toFile()), at) { dropped ->
                                                        if (dropped) state.dismiss() else state.touch()
                                                    }
                                                } finally {
                                                    onOsDrag(false)
                                                    hold.dragging(false)
                                                    drags++
                                                }
                                            }
                                        },
                                    ) { change, _ -> change.consume() }
                                }
                                .clickable(onClickLabel = "Open") { onOpen(currentShot.file) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tip(if (copied) "Copied" else "Copy") {
                        IconButton(onClick = {
                            val file = currentShot.file
                            state.touch()
                            scope.launch { if (onCopy(file)) state.markCopied() }
                        }) {
                            Crossfade(copied, animationSpec = tween(COPY_ICON_MILLIS), label = "copyIcon") { done ->
                                if (done) {
                                    Icon(Icons.Filled.Check, contentDescription = "Copied", Modifier.size(20.dp), tint = colors.primary)
                                } else {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    Tip("Show in folder") {
                        IconButton(onClick = {
                            state.touch()
                            onReveal(currentShot.file)
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Show in folder", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        CloseRing(state, motion, Modifier.align(Alignment.TopEnd).then(hoverModifier))
    }
}

/** The close button on the card's corner, ringed by the time left; the ring refills when it jumps up. */
@Composable
private fun CloseRing(state: ScreenshotThumbnailState, motion: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // The time left, read every frame and only at draw time.
    var fraction by remember { mutableFloatStateOf(state.remainingFraction) }
    // Held (hovered, dragged): the ring stands still, so no frames are asked for.
    val held by state.held.collectAsState()
    LaunchedEffect(state, held) {
        fraction = state.remainingFraction
        if (held) return@LaunchedEffect
        while (true) withFrameMillis { fraction = state.remainingFraction }
    }
    val refills by state.refills.collectAsState()
    val firstRefills = remember { refills }
    // 0 to 1 over a refill: the ring goes from where it was ([refillFrom]) to the time left.
    val refill = remember { Animatable(1f) }
    var refillFrom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(refills) {
        if (refills == firstRefills || !motion) return@LaunchedEffect
        refillFrom = lerp(refillFrom, fraction, refill.value)
        refill.snapTo(0f)
        refill.animateTo(1f, tween(RING_REFILL_MILLIS, easing = EmphasizedDecelerate))
    }
    Surface(
        modifier.size(CLOSE_SIZE).clip(CircleShape).clickable(onClickLabel = "Close") { state.dismiss() },
        shape = CircleShape,
        color = colors.surfaceContainerHighest,
        shadowElevation = 2.dp,
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val stroke = 2.dp.toPx()
                val shown = lerp(refillFrom, fraction, refill.value).coerceIn(0f, 1f)
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.outlineVariant, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                // From 12 o'clock, clockwise; it empties toward 12 o'clock.
                drawArc(
                    colors.primary, -90f, 360f * shown, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * What holds the timer from the card: the pointer on the card or the ring (an Exit is applied a frame later, so moving
 * from one to the other does not let go in between), or an OS drag. UI thread only.
 */
private class HoverHold(private val state: ScreenshotThumbnailState) {
    private var parts = 0
    private var drag = false

    fun enter() {
        parts++
        apply()
    }

    suspend fun exit() {
        parts = (parts - 1).coerceAtLeast(0)
        withFrameMillis { }
        apply()
    }

    fun dragging(on: Boolean) {
        drag = on
        apply()
    }

    /** The card left the composition: nothing of it holds the timer any more. */
    fun clear() {
        parts = 0
        drag = false
        state.hover(false)
    }

    private fun apply() = state.hover(parts > 0 || drag)
}

/** One slot of the composition drawing [shot]: [ScreenshotThumbnailState.acquire]d while it is remembered. */
private class ShotLease(private val state: ScreenshotThumbnailState, private val shot: Shot) : RememberObserver {
    private var held = false

    override fun onRemembered() {
        held = state.acquire(shot)
    }

    override fun onForgotten() {
        if (held) state.release(shot)
        held = false
    }

    override fun onAbandoned() = Unit
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tip(label: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) { content() }
}
