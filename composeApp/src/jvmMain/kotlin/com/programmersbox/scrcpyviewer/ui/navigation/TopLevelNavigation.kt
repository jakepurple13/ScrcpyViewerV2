package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import kotlinx.serialization.Serializable
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The destinations of the navigation rail. */
@Serializable
sealed interface TopLevelRoute : NavKey {
    @Serializable
    data object Devices : TopLevelRoute

    @Serializable
    data object Presets : TopLevelRoute

    @Serializable
    data object Actions : TopLevelRoute

    @Serializable
    data object Settings : TopLevelRoute

    companion object {
        /** Rail order. */
        val all: List<TopLevelRoute> get() = listOf(Devices, Presets, Actions, Settings)
    }
}

/**
 * Owns the Navigation 3 back stack for the rail. Every rail destination stays in the stack so its
 * saved UI state (scroll positions, drafts, open dialogs) survives switching away and back;
 * selecting a destination moves it to the top. Rail destinations are peers, so there is no
 * top-level back (see [TabSceneStrategy]).
 */
@Stable
class TopLevelNavigator(start: TopLevelRoute = TopLevelRoute.Devices) {
    val backStack: SnapshotStateList<TopLevelRoute> =
        mutableStateListOf<TopLevelRoute>().apply { addAll(TopLevelRoute.all - start); add(start) }

    val current: TopLevelRoute get() = backStack.last()

    fun select(route: TopLevelRoute) {
        if (route == current) return
        // Rotate the list in place; one atomic snapshot so NavDisplay never sees the route missing.
        Snapshot.withMutableSnapshot {
            backStack.remove(route)
            backStack.add(route)
        }
    }
}

/**
 * Shows only the top entry and reports no previous entries, so NavDisplay never enables a back
 * action between rail destinations while the other entries keep their saved state.
 */
class TabSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> =
        TabScene(entries.last().contentKey, entries.last())
}

private data class TabScene<T : Any>(override val key: Any, val entry: NavEntry<T>) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val previousEntries: List<NavEntry<T>> = emptyList()
    override val content: @Composable () -> Unit = { entry.Content() }
}

/** NavDisplay for the rail: saved state per destination, no back, fade-through between destinations. */
@Composable
fun TopLevelNavDisplay(
    navigator: TopLevelNavigator,
    modifier: Modifier = Modifier,
    content: @Composable (TopLevelRoute) -> Unit,
) {
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        // Rail destinations are peers; there is no top-level back.
        onBack = {},
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        sceneStrategies = listOf(TabSceneStrategy()),
        transitionSpec = fadeThrough(),
        popTransitionSpec = fadeThrough(),
        predictivePopTransitionSpec = { fadeThrough<TopLevelRoute>()() },
        entryProvider = { route -> NavEntry(route) { content(route) } },
    )
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Fade-through duration: the outgoing screen fades first, then the incoming one fades and settles. */
const val FADE_THROUGH_MILLIS = 300
private const val FADE_OUT_MILLIS = 90

/**
 * Material fade-through for switching between rail destinations: the old screen fades out quickly,
 * the new one fades in with a slight scale-up. Only alpha and scale animate (graphics layer), so the
 * screens are not re-laid out per frame.
 */
fun <T : Any> fadeThrough(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    val incoming = FADE_THROUGH_MILLIS - FADE_OUT_MILLIS
    (
        fadeIn(tween(incoming, delayMillis = FADE_OUT_MILLIS, easing = EmphasizedDecelerate)) +
            scaleIn(tween(incoming, delayMillis = FADE_OUT_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.96f)
        ) togetherWith fadeOut(tween(FADE_OUT_MILLIS, easing = EmphasizedAccelerate))
}

/** One edge's move in the rail indicator's stretch: how long it takes and how long it waits. */
data class EdgeTiming(val durationMillis: Int, val delayMillis: Int)

/** The edge facing the move goes first; the trailing edge follows later and slower, so the pill stretches. */
val LeadingEdge = EdgeTiming(durationMillis = 220, delayMillis = 0)
val TrailingEdge = EdgeTiming(durationMillis = 380, delayMillis = 60)

/** (top, bottom) timings for a move down the rail ([down]) or up it: moving down, the bottom edge leads. */
fun indicatorEdgeTimings(down: Boolean): Pair<EdgeTiming, EdgeTiming> =
    if (down) TrailingEdge to LeadingEdge else LeadingEdge to TrailingEdge

/**
 * The rail's selection pill, drawn behind the items instead of each item's own indicator, so it can travel between
 * destinations with a "caterpillar" stretch. Items report their icon centers ([centers], in the coordinates of the
 * box that draws the pill); [top] and [bottom] are the pill's animated edges, read only while drawing.
 */
@Stable
class RailIndicator {
    val centers = mutableStateMapOf<Any, Offset>()
    val top = Animatable(0f)
    val bottom = Animatable(0f)
    var centerX by mutableFloatStateOf(0f)
        private set
    var placed by mutableStateOf(false)
        private set
    private var shown: Any? = null

    /** Moves the pill to [key]'s item at [center] with half-height [halfHeight]; snaps when there is no travel to show. */
    suspend fun moveTo(key: Any, center: Offset, halfHeight: Float) {
        val newTop = center.y - halfHeight
        val newBottom = center.y + halfHeight
        centerX = center.x
        if (!placed || key == shown) {
            // First placement, or the same item moved (a layout change).
            shown = key
            top.snapTo(newTop)
            bottom.snapTo(newBottom)
            placed = true
            return
        }
        shown = key
        // Measured from where the pill is now, so a reversal mid-move picks the right leading edge.
        val (topTiming, bottomTiming) = indicatorEdgeTimings(down = center.y > (top.value + bottom.value) / 2)
        coroutineScope {
            launch { top.animateTo(newTop, topTiming.spec()) }
            launch { bottom.animateTo(newBottom, bottomTiming.spec()) }
        }
    }

    private fun EdgeTiming.spec() = tween<Float>(durationMillis, delayMillis = delayMillis, easing = EmphasizedDecelerate)
}
