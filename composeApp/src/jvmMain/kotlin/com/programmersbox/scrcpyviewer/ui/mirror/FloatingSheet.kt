package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The floating sheet's shadow over the stage. */
internal val FLOATING_SHEET_ELEVATION = 8.dp

/** The stage scrim behind the floating sheet fades in and out over this long. */
internal const val STAGE_SCRIM_MILLIS = 200

/** The floating sheet's fade in and out under reduced motion. */
internal const val REDUCED_FADE_MILLIS = 120

/**
 * What the mirror content remembers of the sheet's placement between compositions: a plain holder, written and read
 * only in composition.
 */
internal class SheetPlaceMemo {
    var placement: SheetPlacement = SheetPlacement.Docked
    var wasOpen: Boolean = false
    var first: Boolean = true

    /** The sheet floats and its enter animation has finished (or it started shown). */
    var floatingShown: Boolean = false
}

/**
 * A horizontal glide for one element across a placement change: where it was last placed (window x, px) under which
 * key, and the offset it is drawn at while it glides to its new place.
 */
internal class Glide(val scope: CoroutineScope) {
    var key: Any? = null
    var x: Float = Float.NaN
    val offset = mutableFloatStateOf(0f)
    var job: Job? = null

    /** Forgets where the element was (it left, or opens anew): its next placement starts where it is put. */
    fun forget() {
        job?.cancel()
        job = null
        key = null
        x = Float.NaN
        offset.floatValue = 0f
    }
}

/**
 * What the window does as the sheet starts covering the stage ([update] from MirrorContent's onStageCovered): the
 * mouse capture ends and the keys the device still holds are released, as no input reaches it from then on. The
 * buttons are released by MirrorContent itself (onInputDetached). [covered] is read by the key dispatcher.
 */
internal class StageCoverRelease(private val endCapture: () -> Unit, var releaseKeys: () -> Unit = {}) {
    var covered: Boolean = false
        private set

    fun update(covered: Boolean) {
        if (covered && !this.covered) {
            endCapture()
            releaseKeys()
        }
        this.covered = covered
    }
}

@Composable
internal fun rememberGlide(): Glide {
    val scope = rememberCoroutineScope()
    return remember { Glide(scope) }
}

/**
 * When [key] changes, the element glides from where it was drawn to its new place with the layout spring (at once
 * without [animate]); every other move (a window resize) follows at once. Measured before its own translation, in
 * window coordinates, so it can glide between parents (the sheet moving between its docked and floating slots).
 */
internal fun Modifier.glide(glide: Glide, key: Any, animate: Boolean): Modifier =
    onPlaced { coordinates ->
        val x = coordinates.positionInRoot().x
        val changed = glide.key != null && glide.key != key && !glide.x.isNaN()
        if (changed && animate) {
            val from = glide.x + glide.offset.floatValue - x
            glide.job?.cancel()
            glide.offset.floatValue = from
            glide.job = glide.scope.launch {
                animate(from, 0f, animationSpec = layoutSpring()) { value, _ -> glide.offset.floatValue = value }
            }
        } else if (changed || !animate) {
            glide.job?.cancel()
            glide.offset.floatValue = 0f
        }
        glide.key = key
        glide.x = x
    }.graphicsLayer { translationX = glide.offset.floatValue }

/**
 * The scrim over the stage while the sheet floats: `scrim` at 0.32 alpha, fading over [STAGE_SCRIM_MILLIS]. It takes
 * every pointer event, so nothing reaches the device; a press on it does not close the sheet (the docked sheet does not
 * close on a press on the stage either: the close button, the toolbar's quick actions button and Esc close it).
 */
@Composable
internal fun StageScrim(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(STAGE_SCRIM_MILLIS)),
        exit = fadeOut(tween(STAGE_SCRIM_MILLIS)),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
        )
    }
}
