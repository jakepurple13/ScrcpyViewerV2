package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** Where the next palette reveal in this window starts, in root coordinates. Set just before changing the theme. */
class ThemeRevealOrigin {
    var next: Offset? = null
}

/** The current window's reveal origin; null outside an [rememberThemeRevealState] window (tests, previews). */
val LocalThemeRevealOrigin = staticCompositionLocalOf<ThemeRevealOrigin?> { null }

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val REVEAL_MILLIS = 500

/** Overshoots a little before settling, so Soft's panes seem to puff up out of the surface. */
private val Inflate = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)
private const val FROST_MILLIS = 600
private const val INFLATE_MILLIS = 520
private const val REPAINT_BANDS = 8
private const val REPAINT_BAND_MILLIS = 55

/**
 * How the new theme arrives. A style change uses that style's own entrance; anything else (palette, light/dark,
 * pure black, neon, or a change to Material) grows in a circle.
 */
internal enum class RevealKind {
    /** A growing circle from the click point. */
    Circle,

    /** Glass: the new look starts blurred and sharpens into focus as the old one fades. */
    Frost,

    /** Soft: the new look grows from slightly small and fades in over the old one. */
    Inflate,

    /** Retro 95: the new look replaces the old in chunky bands, top to bottom, like an old PC redrawing. */
    Repaint;

    companion object {
        fun between(from: AppTheme, to: AppTheme): RevealKind = when {
            to.style != from.style -> when (to.style) {
                ThemeStyle.Glass -> Frost
                ThemeStyle.Soft -> Inflate
                ThemeStyle.Retro -> Repaint
                ThemeStyle.Material -> Circle
            }
            to.style == ThemeStyle.Retro && to.retroPalette != from.retroPalette -> Repaint
            else -> Circle
        }
    }
}

/**
 * Holds the theme a window shows while it moves to a new one. A change of palette, light/dark, pure black or style
 * snapshots the window, switches the theme underneath, and brings it in with a [RevealKind]: a growing circle from
 * [ThemeRevealOrigin.next] (or the centre), or the new style's own entrance. Custom slider edits switch at once. [shown] always has an explicit
 * Light or Dark mode, so a System flip is revealed too.
 */
@Stable
class ThemeRevealState internal constructor(initial: AppTheme, val origin: ThemeRevealOrigin) {
    var shown by mutableStateOf(initial)
        private set

    internal lateinit var layer: GraphicsLayer
    /**
     * The old frame as pixels. The recorded [layer] alone isn't enough: it replays child layers (scrolling panes,
     * animated rows) live, so they would already show the new theme.
     */
    internal var snapshot: ImageBitmap? = null
    /** The new frame, recorded each draw while Frost or Inflate run, so it can be blurred or scaled. */
    internal lateinit var live: GraphicsLayer
    internal var kind by mutableStateOf(RevealKind.Circle)
    internal val progress = Animatable(1f)
    internal var revealing by mutableStateOf(false)
    internal var center = Offset.Zero
    internal var rootPosition = Offset.Zero
    internal var capture: CompletableDeferred<Unit>? = null
    internal var drawTick by mutableIntStateOf(0)

    internal suspend fun moveTo(target: AppTheme) {
        if (target == shown) return
        val sameLook = target.palette == shown.palette && target.mode == shown.mode &&
            target.pureBlack == shown.pureBlack && target.neon == shown.neon &&
            target.style == shown.style && target.retroPalette == shown.retroPalette
        if (sameLook) {
            shown = target
            return
        }
        val captured = CompletableDeferred<Unit>()
        capture = captured
        drawTick++
        // A hidden window never draws: switch without the reveal.
        if (withTimeoutOrNull(250) { captured.await() } == null) {
            capture = null
            revealing = false
            shown = target
            return
        }
        snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
        if (snapshot == null) {
            revealing = false
            shown = target
            return
        }
        center = origin.next?.minus(rootPosition) ?: center
        origin.next = null
        kind = RevealKind.between(shown, target)
        shown = target
        revealing = true
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            when (kind) {
                RevealKind.Circle -> tween(REVEAL_MILLIS, easing = Emphasized)
                RevealKind.Frost -> tween(FROST_MILLIS, easing = Emphasized)
                RevealKind.Inflate -> tween(INFLATE_MILLIS, easing = Inflate)
                // Linear; the drawing snaps it to whole bands.
                RevealKind.Repaint -> tween(REPAINT_BANDS * REPAINT_BAND_MILLIS, easing = LinearEasing)
            },
        )
        revealing = false
        snapshot = null
    }
}

/** Remembers this window's reveal state and moves it to [target] (resolved to Light or Dark here). */
@Composable
fun rememberThemeRevealState(target: AppTheme): ThemeRevealState {
    val resolved = target.copy(mode = if (target.mode.isDark()) ThemeMode.Dark else ThemeMode.Light)
    val state = remember { ThemeRevealState(resolved, ThemeRevealOrigin()) }
    LaunchedEffect(resolved) { state.moveTo(resolved) }
    return state
}

/**
 * Draws [state]'s reveal over this element: the old frame, with a growing hole showing the new one. Call it inside
 * the window's content: the snapshot layer needs the window's graphics context, which the application level lacks.
 */
@Composable
fun Modifier.themeReveal(state: ThemeRevealState): Modifier {
    state.layer = rememberGraphicsLayer()
    state.live = rememberGraphicsLayer()
    return revealDrawing(state)
}

private fun Modifier.revealDrawing(state: ThemeRevealState): Modifier = this
    .onGloballyPositioned {
        state.rootPosition = it.positionInRoot()
        if (!state.revealing) state.center = Offset(it.size.width / 2f, it.size.height / 2f)
    }
    .drawWithContent {
        state.drawTick // read so a capture request redraws
        val request = state.capture
        if (request != null) {
            state.layer.record { this@drawWithContent.drawContent() }
            drawLayer(state.layer)
            state.capture = null
            request.complete(Unit)
            return@drawWithContent
        }
        if (!state.revealing) {
            drawContent()
            return@drawWithContent
        }
        val p = state.progress.value
        val old = state.snapshot
        if (old == null) {
            drawContent()
            return@drawWithContent
        }
        when (state.kind) {
            RevealKind.Circle -> {
                drawContent()
                val c = state.center
                val far = hypot(max(c.x, size.width - c.x), max(c.y, size.height - c.y))
                val hole = Path().apply { addOval(Rect(c, far * p)) }
                clipPath(hole, ClipOp.Difference) { drawImage(old) }
            }
            RevealKind.Frost -> {
                val live = state.live
                live.record { this@drawWithContent.drawContent() }
                val blur = 18.dp.toPx() * (1f - p)
                live.renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Clamp) else null
                drawLayer(live)
                drawImage(old, alpha = (1f - p).coerceIn(0f, 1f))
            }
            RevealKind.Inflate -> {
                drawImage(old)
                val live = state.live
                live.record { this@drawWithContent.drawContent() }
                val scale = 0.97f + 0.03f * p
                live.scaleX = scale
                live.scaleY = scale
                live.pivotOffset = Offset(size.width / 2f, size.height / 2f)
                live.alpha = p.coerceIn(0f, 1f)
                drawLayer(live)
            }
            RevealKind.Repaint -> {
                drawContent()
                val bands = floor(p * REPAINT_BANDS) / REPAINT_BANDS
                val top = size.height * bands
                if (top < size.height) clipRect(top = top) { drawImage(old) }
            }
        }
    }
