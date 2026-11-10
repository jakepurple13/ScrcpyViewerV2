package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Holds the theme a window shows while it moves to a new one. A change of palette, light/dark or pure black
 * snapshots the window, switches the colors underneath, and uncovers them in a growing circle from
 * [ThemeRevealOrigin.next] (or the centre). Custom slider edits switch at once. [shown] always has an explicit
 * Light or Dark mode, so a System flip is revealed too.
 */
@Stable
class ThemeRevealState internal constructor(initial: AppTheme, val origin: ThemeRevealOrigin) {
    var shown by mutableStateOf(initial)
        private set

    internal lateinit var layer: GraphicsLayer
    internal val progress = Animatable(1f)
    internal var revealing by mutableStateOf(false)
    internal var center = Offset.Zero
    internal var rootPosition = Offset.Zero
    internal var capture: CompletableDeferred<Unit>? = null
    internal var drawTick by mutableIntStateOf(0)

    internal suspend fun moveTo(target: AppTheme) {
        if (target == shown) return
        val sameLook = target.palette == shown.palette && target.mode == shown.mode && target.pureBlack == shown.pureBlack
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
        center = origin.next?.minus(rootPosition) ?: center
        origin.next = null
        shown = target
        revealing = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(REVEAL_MILLIS, easing = Emphasized))
        revealing = false
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
        drawContent()
        if (state.revealing) {
            val c = state.center
            val far = hypot(max(c.x, size.width - c.x), max(c.y, size.height - c.y))
            val r = far * state.progress.value
            val hole = Path().apply { addOval(Rect(c, r)) }
            clipPath(hole, ClipOp.Difference) { drawLayer(state.layer) }
        }
    }
