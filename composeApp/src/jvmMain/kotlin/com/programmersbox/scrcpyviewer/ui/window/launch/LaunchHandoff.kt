package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The launch hand-off between the overlay and the real main window. With an overlay: [Step.Playing] (real window
 * hidden) → [overlayEnded] → [Step.ShowReal] (real window shown, overlay still up) → [realWindowDrew] →
 * [Step.Done] (overlay closed). Without one, it starts at [Step.Done]. [cancel] (a quit during the launch) jumps to
 * [Step.Done] at once.
 */
class LaunchHandoff(val effect: LaunchEffect) {
    enum class Step { Playing, ShowReal, RealDrawn, Done }

    private val _step = MutableStateFlow(if (effect is LaunchEffect.Overlay) Step.Playing else Step.Done)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _realVisible = MutableStateFlow(_step.value != Step.Playing)
    /** False only while the overlay is playing. */
    val realVisible: StateFlow<Boolean> = _realVisible.asStateFlow()

    private val _overlayOpen = MutableStateFlow(_step.value != Step.Done)
    /** True until the real window has drawn after being shown. */
    val overlayOpen: StateFlow<Boolean> = _overlayOpen.asStateFlow()

    /** The overlay's last frame is on screen: show the real window. */
    fun overlayEnded() {
        if (!_step.compareAndSet(Step.Playing, Step.ShowReal)) return
        _realVisible.value = true
    }

    /** The real window has drawn since it was shown: close the overlay. Ignored before [overlayEnded]. */
    fun realWindowDrew() {
        if (!_step.compareAndSet(Step.ShowReal, Step.RealDrawn)) return
        finish()
    }

    /** Ends the launch now: the real window shows and the overlay closes. */
    fun cancel() = finish()

    private fun finish() {
        _realVisible.value = true
        _overlayOpen.value = false
        _step.value = Step.Done
    }
}

/**
 * On the launch that played Icon unfold: the main window's rows fade up in a stagger once the real window shows.
 * [shown] turns true when it does. Rows first composed more than [WINDOW_MILLIS] after that don't animate (they are
 * ordinary list changes by then, animated by the list itself).
 */
class LaunchEntrance(val shown: StateFlow<Boolean>) {
    @Volatile internal var shownAtNanos: Long = 0L

    companion object {
        const val STAGGER_MILLIS = 40
        const val MILLIS = 220
        const val WINDOW_MILLIS = 400
    }
}

/** Null on every launch but an Icon unfold one. */
val LocalLaunchEntrance = compositionLocalOf<LaunchEntrance?> { null }

private val EntranceEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Row [index] of the main window fades up 8 dp, [LaunchEntrance.STAGGER_MILLIS] after the row above it. */
@Composable
fun Modifier.launchEntrance(index: Int): Modifier {
    val entrance = LocalLaunchEntrance.current ?: return this
    val progress = remember { Animatable(if (entrance.isLate()) 1f else 0f) }
    LaunchedEffect(entrance) {
        if (progress.value >= 1f) return@LaunchedEffect
        entrance.shown.first { it }
        if (entrance.shownAtNanos == 0L) entrance.shownAtNanos = System.nanoTime()
        delay(index.coerceAtMost(12) * LaunchEntrance.STAGGER_MILLIS.toLong())
        progress.animateTo(1f, tween(LaunchEntrance.MILLIS, easing = EntranceEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

private fun LaunchEntrance.isLate(): Boolean {
    val at = shownAtNanos
    return at != 0L && System.nanoTime() - at > LaunchEntrance.WINDOW_MILLIS * 1_000_000L
}
