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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * After the windows stop being arranged (or the app stops sizing one), how long their own sizing effects get to settle
 * (the aspect correction waits 150 ms) before the arranger takes their bounds as the expected ones. Bounds changes
 * until then are not the user's.
 */
internal const val ARRANGE_SETTLE_MS = 400L

/** A window's move gives up this long after it should have ended (its window closed, or its frame clock stopped). */
private const val MOVE_GRACE_MS = 300L

/** Window units a position or size may be off and still count as where the arranger put it. */
private const val TOLERANCE = 4

/**
 * Window units an app-caused resize (the sheet, a new frame size) must change a window by, against its tile, before Keep
 * tiled lays the windows out again; small aspect corrections stay where they are.
 */
private const val TILE_SLACK = 24

/** Applies in a row whose positions did not take before the arranger gives up (Wayland). */
private const val MISSES_TO_DISABLE = 2

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
 * own sizing effects do not correct it mid-move. A move ends early when its window closes, and gives up
 * [MOVE_GRACE_MS] after it should have ended.
 *
 * Leaving As placed remembers each window's bounds; As placed puts them back. With Keep tiled, the current layout is
 * applied again whenever a window opens or closes, or the app resizes one out of its tile (the quick actions sheet); a
 * move or resize that neither the arranger nor the app caused turns Keep tiled off. When positions do not take
 * (Wayland) [MISSES_TO_DISABLE] applies in a row, [ArrangeState.movable] turns false and [apply] does nothing.
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

    @Volatile
    private var focus: String? = null

    /**
     * Applies started and not yet settled, counted from [apply] itself, so a cancelled apply finishing never opens a gap
     * before the next one starts: bounds changes meanwhile (late native events included) are the arranger's own.
     */
    private val applies = AtomicInteger(0)

    /** True while an apply runs or settles. */
    internal val busy: Boolean get() = applies.get() > 0

    /** Applies in a row whose positions did not take. */
    private val misses = AtomicInteger(0)

    /** Each window's bounds from before it was first arranged, for As placed. */
    private val placed = ConcurrentHashMap<String, Rectangle>()

    /** Each window's place in the current layout. */
    private val tiles = ConcurrentHashMap<String, Rectangle>()

    /** Where each window is expected to be; a window found elsewhere later was moved by the user. */
    private val settled = ConcurrentHashMap<String, Rectangle>()

    /** Windows whose app-caused resize ended and is settling: their bounds changes are not the user's yet. */
    private val appSettling = ConcurrentHashMap.newKeySet<String>()

    init {
        // Keep tiled: a window opened or closed.
        scope.launch {
            windows.entries.map { it.keys }.distinctUntilChanged().drop(1).collect { keys ->
                placed.keys.retainAll(keys)
                tiles.keys.retainAll(keys)
                settled.keys.retainAll(keys)
                val s = _state.value
                if (s.keepTiled && s.layout != ArrangeLayout.AsPlaced && s.movable) apply(s.layout, focus)
            }
        }
        scope.launch {
            windows.entries.collectLatest { map ->
                coroutineScope {
                    for ((serial, entry) in map) {
                        launch { entry.handle.boundsChanges.collect { onUserMoved(serial) } }
                        launch { watchAppSizing(serial, entry) }
                    }
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
            applies.incrementAndGet()
            val next = scope.launch {
                previous?.cancelAndJoin()
                run(layout, focus)
            }
            next.invokeOnCompletion { applies.decrementAndGet() }
            job = next
        }
    }

    /** Turns Keep tiled on (applying the current layout again) or off. */
    fun setKeepTiled(on: Boolean) {
        _state.update { it.copy(keepTiled = on) }
        val s = _state.value
        if (on && s.layout != ArrangeLayout.AsPlaced && s.movable) apply(s.layout, focus)
    }

    /** [serial]'s window moved or resized. Unless the arranger or the app did it, Keep tiled turns off. */
    fun onUserMoved(serial: String) {
        val s = _state.value
        if (busy || !s.keepTiled || s.layout == ArrangeLayout.AsPlaced) return
        val entry = windows.entries.value[serial] ?: return
        if (entry.appSizing.value || serial in appSettling) return
        val expected = settled[serial] ?: return
        scope.launch(entry.uiContext.withoutJob()) {
            if (busy || entry.appSizing.value || serial in appSettling) return@launch
            if (!near(entry.handle.bounds, expected)) _state.update { it.copy(keepTiled = false) }
        }
    }

    /**
     * Once an app-caused resize of [serial]'s window has settled, its bounds are the expected ones; with Keep tiled, a
     * window it took out of its tile has the layout applied again.
     */
    private suspend fun watchAppSizing(serial: String, entry: WindowEntry) {
        entry.appSizing.drop(1).collectLatest { sizing ->
            if (sizing) return@collectLatest
            appSettling += serial
            try {
                delay(ARRANGE_SETTLE_MS)
                if (busy) return@collectLatest // the apply saves the bounds itself
                val bounds = withContext(entry.uiContext.withoutJob()) { entry.handle.bounds }
                if (settled.containsKey(serial)) settled[serial] = bounds
                val s = _state.value
                val tile = tiles[serial]
                if (s.keepTiled && s.layout != ArrangeLayout.AsPlaced && s.movable && tile != null &&
                    (abs(bounds.width - tile.width) > TILE_SLACK || abs(bounds.height - tile.height) > TILE_SLACK)
                ) {
                    apply(s.layout, focus)
                }
            } finally {
                appSettling -= serial
            }
        }
    }

    private suspend fun run(layout: ArrangeLayout, focus: String?) {
        val eligible = windows.entries.value.filterValues { it.floating() && !it.minimized() && it.showing() }
        if (eligible.isEmpty()) return
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
        // The expected bounds are the new ones from the start: a late event from an earlier move is compared to them.
        if (layout == ArrangeLayout.AsPlaced) {
            tiles.clear()
            settled.clear()
        } else {
            for (serial in moving.keys) {
                tiles[serial] = targets.getValue(serial)
                settled[serial] = targets.getValue(serial)
            }
        }
        val animate = animateWindows() && !reduceMotion()
        val starts = ConcurrentHashMap<String, Rectangle>()
        try {
            moving.values.forEach { it.arranging.value = true }
            coroutineScope {
                moving.entries.forEachIndexed { i, (serial, entry) ->
                    launch {
                        val limit = ARRANGE_MILLIS + ARRANGE_STAGGER_MILLIS * i + MOVE_GRACE_MS
                        val move = launch(entry.uiContext.withoutJob()) {
                            withTimeoutOrNull(limit) {
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
                        // A window that closes mid-move: its frame clock may never tick again.
                        val closed = launch {
                            windows.entries.first { it[serial] !== entry }
                            move.cancel()
                        }
                        move.join()
                        closed.cancel()
                    }
                }
            }
            delay(POSITION_CHECK_MS)
            val open = windows.entries.value
            val stuck = withContext(ui) {
                val needed = moving.keys.filter { serial ->
                    open[serial] === moving[serial] &&
                        starts[serial]?.let { !nearPosition(it, targets.getValue(serial)) } == true
                }
                needed.isNotEmpty() &&
                    needed.all { !nearPosition(moving.getValue(it).handle.bounds, targets.getValue(it)) }
            }
            if (stuck) {
                // Where the windows really are is what a later change is compared to.
                withContext(ui) {
                    for ((serial, entry) in moving) if (open[serial] === entry) settled[serial] = entry.handle.bounds
                }
                if (misses.incrementAndGet() >= MISSES_TO_DISABLE) {
                    _state.update { it.copy(movable = false, keepTiled = false) }
                }
                return
            }
            misses.set(0)
        } finally {
            moving.values.forEach { it.arranging.value = false }
        }
        if (layout == ArrangeLayout.AsPlaced) {
            // Windows that were not put back (minimized, say) keep theirs for a later As placed.
            placed.keys.removeAll(moving.keys)
            return
        }
        delay(ARRANGE_SETTLE_MS)
        withContext(ui) {
            val open = windows.entries.value
            for ((serial, entry) in moving) if (open[serial] === entry) settled[serial] = entry.handle.bounds
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
