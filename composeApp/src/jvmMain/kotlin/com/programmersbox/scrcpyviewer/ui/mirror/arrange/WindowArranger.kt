package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import com.programmersbox.scrcpyviewer.ui.mirror.animateWindowBounds
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.programmersbox.scrcpyviewer.ui.window.screenFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/** Each window moves to its place over this long. */
const val ARRANGE_MILLIS = 380

/** Each window starts this much later than the one before it. */
const val ARRANGE_STAGGER_MILLIS = 35L

/** The gap between arranged windows and around them, in window units. */
const val ARRANGE_GAP = 12

/** After the moves end, how long until the arranger reads back where the windows went (did the positions take?). */
private const val POSITION_CHECK_MS = 120L

/**
 * After the windows stop being arranged, how long their own sizing effects get to settle (the aspect correction waits
 * 150 ms) before the arranger takes their bounds as the arranged ones. Bounds changes until then are the arranger's.
 */
internal const val ARRANGE_SETTLE_MS = 400L

/** Window units a position or size may be off and still count as where the arranger put it. */
private const val TOLERANCE = 4

/** The current layout, whether Keep tiled is on, and whether the desktop lets the app move windows at all. */
data class ArrangeState(
    val layout: ArrangeLayout = ArrangeLayout.AsPlaced,
    val keepTiled: Boolean = false,
    val movable: Boolean = true,
)

/** Moves one window to its place with animation, over the given milliseconds. */
fun interface WindowAnimator {
    suspend fun animate(entry: WindowEntry, to: Rectangle, millis: Int)
}

/**
 * Moves the open mirror windows ([windows]) into a layout on the main window's screen (its work area; with no screens,
 * as on Linux, inside the main window's bounds). Only floating windows that are showing and not minimized move.
 *
 * Windows move one after another, [ARRANGE_STAGGER_MILLIS] apart, over [ARRANGE_MILLIS] each ([animator]), or at once
 * when [animateWindows] is off or the OS [reduceMotion]. While a window moves its [WindowEntry.arranging] is set, so its
 * own sizing effects do not correct it mid-move.
 *
 * Leaving As placed remembers each window's bounds; As placed puts them back. With Keep tiled, the current layout is
 * applied again whenever a window opens or closes; a move or resize the arranger did not cause turns Keep tiled off.
 * When a window's position does not take (Wayland), [ArrangeState.movable] turns false and [apply] does nothing.
 */
class WindowArranger(
    private val windows: MirrorWindows,
    private val screens: ScreenGeometry,
    private val animateWindows: () -> Boolean,
    private val reduceMotion: () -> Boolean = ::systemReducesMotion,
    private val scope: CoroutineScope,
    private val animator: WindowAnimator = WindowAnimator { entry, to, millis ->
        animateWindowBounds(entry.handle, to, millis, grow = true, screens)
    },
) {
    private val _state = MutableStateFlow(ArrangeState())
    val state: StateFlow<ArrangeState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var focus: String? = null

    /** True from the start of an apply until its windows' bounds have settled: bounds changes meanwhile are its own. */
    @Volatile
    private var applying = false

    /** Each window's bounds from before it was first arranged, for As placed. */
    private val placed = ConcurrentHashMap<String, Rectangle>()

    /** Where each window settled after the last apply; a window found elsewhere later was moved by the user. */
    private val settled = ConcurrentHashMap<String, Rectangle>()

    init {
        // Keep tiled: a window opened or closed.
        scope.launch {
            windows.entries.map { it.keys }.distinctUntilChanged().drop(1).collect { keys ->
                placed.keys.retainAll(keys)
                settled.keys.retainAll(keys)
                val s = _state.value
                if (s.keepTiled && s.layout != ArrangeLayout.AsPlaced && s.movable) apply(s.layout, focus)
            }
        }
        scope.launch {
            windows.entries.collectLatest { map ->
                coroutineScope {
                    for ((serial, entry) in map) launch { entry.handle.boundsChanges.collect { onUserMoved(serial) } }
                }
            }
        }
    }

    /** Moves the windows into [layout]; [focus] is the serial Focus puts in the large area. No-op when not movable. */
    fun apply(layout: ArrangeLayout, focus: String?) {
        synchronized(lock) {
            if (!_state.value.movable) return
            this.focus = focus
            _state.update { it.copy(layout = layout) }
            val previous = job
            job = scope.launch {
                previous?.cancelAndJoin()
                run(layout, focus)
            }
        }
    }

    /** Turns Keep tiled on (applying the current layout again) or off. */
    fun setKeepTiled(on: Boolean) {
        _state.update { it.copy(keepTiled = on) }
        val s = _state.value
        if (on && s.layout != ArrangeLayout.AsPlaced && s.movable) apply(s.layout, focus)
    }

    /** [serial]'s window moved or resized. Unless the arranger did it, Keep tiled turns off. */
    fun onUserMoved(serial: String) {
        val s = _state.value
        if (applying || !s.keepTiled || s.layout == ArrangeLayout.AsPlaced) return
        val expected = settled[serial] ?: return
        val entry = windows.entries.value[serial] ?: return
        scope.launch(entry.uiContext.withoutJob()) {
            if (!applying && !near(entry.handle.bounds, expected)) _state.update { it.copy(keepTiled = false) }
        }
    }

    private suspend fun run(layout: ArrangeLayout, focus: String?) {
        val eligible = windows.entries.value.filterValues { it.floating() && !it.minimized() && it.showing() }
        if (eligible.isEmpty()) {
            if (layout == ArrangeLayout.AsPlaced) placed.clear()
            return
        }
        val ui = eligible.values.first().uiContext.withoutJob()
        val targets: Map<String, Rectangle> = withContext(ui) {
            if (layout == ArrangeLayout.AsPlaced) {
                placed.filterKeys { it in eligible }
            } else {
                val work = workArea() ?: return@withContext emptyMap()
                for ((serial, entry) in eligible) placed.putIfAbsent(serial, entry.handle.bounds)
                layout(layout, eligible.map { (serial, e) -> e.info().copy(serial = serial) }, work, focus, ARRANGE_GAP)
            }
        }
        val moving = eligible.filterKeys { it in targets }
        val animate = animateWindows() && !reduceMotion()
        val starts = ConcurrentHashMap<String, Rectangle>()
        applying = true
        try {
            try {
                moving.values.forEach { it.arranging.value = true }
                coroutineScope {
                    moving.entries.forEachIndexed { i, (serial, entry) ->
                        launch(entry.uiContext.withoutJob()) {
                            val to = targets.getValue(serial)
                            starts[serial] = entry.handle.bounds
                            if (animate) {
                                delay(i * ARRANGE_STAGGER_MILLIS)
                                animator.animate(entry, to, ARRANGE_MILLIS)
                            } else {
                                entry.handle.bounds = to
                            }
                        }
                    }
                }
                delay(POSITION_CHECK_MS)
                val stuck = withContext(ui) {
                    val needed = moving.keys.filter { !nearPosition(starts.getValue(it), targets.getValue(it)) }
                    needed.isNotEmpty() &&
                        needed.all { !nearPosition(moving.getValue(it).handle.bounds, targets.getValue(it)) }
                }
                if (stuck) {
                    _state.update { it.copy(movable = false, keepTiled = false) }
                    return
                }
            } finally {
                moving.values.forEach { it.arranging.value = false }
            }
            if (layout == ArrangeLayout.AsPlaced) {
                placed.clear()
                settled.clear()
                return
            }
            delay(ARRANGE_SETTLE_MS)
            withContext(ui) { for ((serial, entry) in moving) settled[serial] = entry.handle.bounds }
        } finally {
            applying = false
        }
    }

    /** The work area of the main window's screen; with no screens, the main window's bounds. */
    private fun workArea(): Rectangle? {
        val main = windows.mainWindowBounds()?.takeIf { it.width > 0 && it.height > 0 }
        val list = screens.screens()
        if (list.isEmpty()) return main
        return (main?.let { screenFor(it, list) } ?: list.first()).workArea
    }

    private fun nearPosition(a: Rectangle, b: Rectangle) = abs(a.x - b.x) <= TOLERANCE && abs(a.y - b.y) <= TOLERANCE

    private fun near(a: Rectangle, b: Rectangle) =
        nearPosition(a, b) && abs(a.width - b.width) <= TOLERANCE && abs(a.height - b.height) <= TOLERANCE

    private fun CoroutineContext.withoutJob(): CoroutineContext = minusKey(Job)
}
