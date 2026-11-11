package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
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

/** NavDisplay for the rail: saved state per destination, no back, a vertical shared-axis move between destinations. */
@Composable
fun TopLevelNavDisplay(
    navigator: TopLevelNavigator,
    modifier: Modifier = Modifier,
    content: @Composable (TopLevelRoute) -> Unit,
) {
    val density = LocalDensity.current
    val enterOffset = with(density) { SHARED_AXIS_ENTER_OFFSET.roundToPx() }
    val exitOffset = with(density) { SHARED_AXIS_EXIT_OFFSET.roundToPx() }
    val transition = remember(enterOffset, exitOffset) {
        verticalSharedAxis<TopLevelRoute>(::railIndexOf, enterOffset, exitOffset)
    }
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        // Rail destinations are peers; there is no top-level back.
        onBack = {},
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        sceneStrategies = listOf(TabSceneStrategy()),
        transitionSpec = transition,
        popTransitionSpec = transition,
        predictivePopTransitionSpec = { transition() },
        entryProvider = { route -> NavEntry(route, contentKey = railContentKey(route)) { content(route) } },
    )
}

/** The content key of a rail destination's entry; a scene's key is its entry's content key (see [TabScene]). */
internal fun railContentKey(route: TopLevelRoute): String = route.toString()

/** Rail position of the destination whose scene has key [sceneKey], or -1 when it is not a rail destination. */
internal fun railIndexOf(sceneKey: Any): Int = TopLevelRoute.all.indexOfFirst { railContentKey(it) == sceneKey }

/** +1 when moving down the rail (from [from] to [to]), -1 when moving up; 0 for no move or an unknown position. */
fun railDirection(from: Int, to: Int): Int =
    if (from < 0 || to < 0) 0 else to.compareTo(from).coerceIn(-1, 1)

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** The outgoing screen's lift and fade. */
const val SHARED_AXIS_EXIT_MILLIS = 150

/** The incoming screen waits this long, so the two screens barely overlap. */
const val SHARED_AXIS_ENTER_DELAY_MILLIS = 90

/** The incoming screen's slide and fade. */
const val SHARED_AXIS_ENTER_MILLIS = 300

/** Whole switch, from click to the new screen settling. */
const val SHARED_AXIS_MILLIS = SHARED_AXIS_ENTER_DELAY_MILLIS + SHARED_AXIS_ENTER_MILLIS

private val SHARED_AXIS_ENTER_OFFSET = 60.dp
private val SHARED_AXIS_EXIT_OFFSET = 30.dp

/**
 * Vertical shared axis for switching between rail destinations: content travels the way the rail pill does. Moving
 * down the rail, the old screen lifts and fades out while the new one rises from below and fades in; moving up
 * reverses both. [indexOf] maps a scene key to its rail position. Only offset and alpha animate (graphics layer), so
 * the screens are not re-laid out per frame.
 */
fun <T : Any> verticalSharedAxis(
    indexOf: (Any) -> Int,
    enterOffsetPx: Int,
    exitOffsetPx: Int,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    // Unknown positions fall back to "down", the forward direction.
    val d = railDirection(indexOf(initialState.key), indexOf(targetState.key)).takeIf { it != 0 } ?: 1
    val enter = tween<Float>(SHARED_AXIS_ENTER_MILLIS, SHARED_AXIS_ENTER_DELAY_MILLIS, EmphasizedDecelerate)
    val enterSlide = tween<IntOffset>(SHARED_AXIS_ENTER_MILLIS, SHARED_AXIS_ENTER_DELAY_MILLIS, EmphasizedDecelerate)
    val exit = tween<Float>(SHARED_AXIS_EXIT_MILLIS, easing = EmphasizedAccelerate)
    val exitSlide = tween<IntOffset>(SHARED_AXIS_EXIT_MILLIS, easing = EmphasizedAccelerate)
    (slideInVertically(enterSlide) { d * enterOffsetPx } + fadeIn(enter)) togetherWith
        (slideOutVertically(exitSlide) { -d * exitOffsetPx } + fadeOut(exit))
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
