package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowArrangerTest {
    /**
     * A window that reports a bounds change for every set, as the native window does for the arranger's own moves: at
     * once, or [lateMs] later on [lateScope] (Tao's events arrive asynchronously, possibly after the move ended).
     */
    private class FakeHandle(
        start: Rectangle,
        val ignoresPosition: Boolean = false,
        val lateScope: CoroutineScope? = null,
        val lateMs: Long = 0,
    ) : MirrorWindowHandle {
        var rect = Rectangle(start)
        val sets = mutableListOf<Rectangle>()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

        private fun emitChange() {
            val scope = lateScope
            if (scope == null) {
                changes.tryEmit(Unit)
                return
            }
            scope.launch {
                delay(lateMs)
                changes.emit(Unit)
            }
        }

        /** The app resizes the window itself (the sheet, a new frame size). */
        fun appResize(to: Rectangle) {
            rect = Rectangle(to)
            emitChange()
        }

        override var bounds: Rectangle
            get() = Rectangle(rect)
            set(value) {
                sets += Rectangle(value)
                rect = if (ignoresPosition) Rectangle(rect.x, rect.y, value.width, value.height) else Rectangle(value)
                emitChange()
            }

        /** The user drags the window. */
        fun userMove(to: Rectangle) {
            rect = Rectangle(to)
            changes.tryEmit(Unit)
        }

        override val boundsChanges: SharedFlow<Unit> = changes
        override val size: IntSize get() = IntSize(rect.width, rect.height)
        override val isShowing: Boolean = true
        override val focused: StateFlow<Boolean> = MutableStateFlow(false)
        override val focusEvents: SharedFlow<Boolean> = MutableSharedFlow()
        override val nsWindow: Pointer? = null
        override fun toFront() = Unit
        override fun screen(): ScreenArea? = null
        override fun contentOrigin(): IntOffset = IntOffset(rect.x, rect.y)
    }

    private val screen = ScreenArea(Rectangle(0, 0, 1600, 1000), Rectangle(0, 25, 1600, 975))
    private val geometry = object : ScreenGeometry {
        override fun screens() = listOf(screen)
    }

    private fun entry(
        serial: String,
        handle: FakeHandle,
        floating: Boolean = true,
        minimized: () -> Boolean = { false },
        chromeW: () -> Int = { 0 },
        appSizing: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = WindowEntry(
        handle = handle,
        info = { ArrangeWindow(serial, IntSize(1080, 2400), chromeW(), 40) },
        floating = { floating },
        minimized = minimized,
        showing = { true },
        arranging = mutableStateOf(false),
        appSizing = appSizing,
    )

    /** Runs the arranger (on the test's background scope, which advanceUntilIdle skips) until it has settled. */
    private fun TestScope.settle() {
        advanceTimeBy(3_000)
        runCurrent()
    }

    private class Rig(val windows: MirrorWindows, val arranger: WindowArranger, val animations: MutableList<String>)

    /** An animator that moves in 4 steps over 380 ms of virtual time. */
    private fun TestScope.rig(
        animate: Boolean = true,
        reduceMotion: Boolean = false,
        scope: CoroutineScope = backgroundScope,
        /** Serials whose move never ends (a closed window's frame clock). */
        hang: Set<String> = emptySet(),
    ): Rig {
        val windows = MirrorWindows().apply { mainWindowBounds = { Rectangle(100, 100, 800, 600) } }
        val animations = mutableListOf<String>()
        val arranger = WindowArranger(
            windows = windows,
            screens = geometry,
            animateWindows = { animate },
            reduceMotion = { reduceMotion },
            scope = scope,
            animator = { entry, to, millis ->
                animations += entry.info().serial
                if (entry.info().serial in hang) awaitCancellation()
                val from = entry.handle.bounds
                for (i in 1..4) {
                    delay(millis / 4L)
                    entry.handle.bounds = Rectangle(
                        from.x + (to.x - from.x) * i / 4, from.y + (to.y - from.y) * i / 4,
                        from.width + (to.width - from.width) * i / 4, from.height + (to.height - from.height) * i / 4,
                    )
                }
            },
        )
        return Rig(windows, arranger, animations)
    }

    @Test
    fun gridMovesFloatingWindowsOnly() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(Rectangle(20, 20, 300, 600))
        val full = FakeHandle(Rectangle(0, 0, 1600, 1000))
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.windows.register("c", entry("c", full, floating = false))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()

        val expected = layout(
            ArrangeLayout.Grid,
            listOf(ArrangeWindow("a", IntSize(1080, 2400), 0, 40), ArrangeWindow("b", IntSize(1080, 2400), 0, 40)),
            screen.workArea, null, ARRANGE_GAP,
        )
        assertEquals(expected["a"], a.bounds)
        assertEquals(expected["b"], b.bounds)
        assertTrue(full.sets.isEmpty(), "a fullscreen window is never moved")
        assertEquals(ArrangeLayout.Grid, r.arranger.state.value.layout)
        assertEquals(listOf("a", "b"), r.animations)
    }

    @Test
    fun windowsAreStaggeredAndPausedWhileArranging() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(Rectangle(20, 20, 300, 600))
        val ea = entry("a", a)
        val eb = entry("b", b)
        r.windows.register("a", ea)
        r.windows.register("b", eb)
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        advanceTimeBy(20)
        assertTrue(ea.arranging.value && eb.arranging.value, "sizing effects pause while the windows move")
        assertTrue(r.animations == listOf("a"), "the second window starts ${ARRANGE_STAGGER_MILLIS} ms later")
        settle()
        assertFalse(ea.arranging.value || eb.arranging.value)
    }

    @Test
    fun asPlacedRestoresRememberedBounds() = runTest {
        val r = rig()
        val startA = Rectangle(10, 10, 300, 600)
        val startB = Rectangle(400, 40, 320, 640)
        val a = FakeHandle(startA)
        val b = FakeHandle(startB)
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertNotEquals(startA, a.bounds)
        r.arranger.apply(ArrangeLayout.Cascade, focus = null) // still remembers the bounds from before Grid
        settle()
        r.arranger.apply(ArrangeLayout.AsPlaced, focus = null)
        settle()
        assertEquals(startA, a.bounds)
        assertEquals(startB, b.bounds)
        assertEquals(ArrangeLayout.AsPlaced, r.arranger.state.value.layout)
    }

    @Test
    fun keepTiledReappliesOnOpenAndCloseWithoutCountingItsOwnMoves() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(Rectangle(20, 20, 300, 600))
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.SideBySide, focus = null)
        r.arranger.setKeepTiled(true)
        settle()
        val twoUp = a.bounds

        val c = FakeHandle(Rectangle(30, 30, 300, 600))
        val ec = entry("c", c)
        r.windows.register("c", ec)
        settle()
        assertTrue(c.sets.isNotEmpty(), "the new window is tiled")
        assertNotEquals(twoUp, a.bounds, "the others make room")
        assertTrue(r.arranger.state.value.keepTiled, "its own moves are not the user's")

        r.windows.unregister("c", ec)
        settle()
        assertEquals(twoUp, a.bounds)
        assertTrue(r.arranger.state.value.keepTiled)
    }

    @Test
    fun aManualMoveAfterSettleTurnsKeepTiledOff() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(Rectangle(20, 20, 300, 600))
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        r.arranger.setKeepTiled(true)
        settle()
        assertTrue(r.arranger.state.value.keepTiled)

        a.userMove(Rectangle(a.bounds.x + 80, a.bounds.y + 50, a.bounds.width, a.bounds.height))
        settle()
        assertFalse(r.arranger.state.value.keepTiled)
        assertEquals(ArrangeLayout.Grid, r.arranger.state.value.layout)
    }

    @Test
    fun positionsThatDoNotStickDisableArranging() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600), ignoresPosition = true)
        val b = FakeHandle(Rectangle(20, 20, 300, 600), ignoresPosition = true)
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertTrue(r.arranger.state.value.movable, "one miss is not enough")
        assertTrue(a.sets.isNotEmpty())

        r.arranger.apply(ArrangeLayout.SideBySide, focus = null)
        settle()
        assertFalse(r.arranger.state.value.movable, "two misses in a row")

        a.sets.clear()
        b.sets.clear()
        r.arranger.apply(ArrangeLayout.Cascade, focus = null)
        settle()
        assertTrue(a.sets.isEmpty() && b.sets.isEmpty(), "apply is a no-op once windows cannot be moved")
    }

    @Test
    fun reducedMotionSetsBoundsOnce() = runTest {
        val r = rig(reduceMotion = true)
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(Rectangle(20, 20, 300, 600))
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertEquals(1, a.sets.size)
        assertEquals(1, b.sets.size)
        assertTrue(r.animations.isEmpty())
    }

    @Test
    fun animationsOffSetsBoundsOnce() = runTest {
        val r = rig(animate = false)
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", FakeHandle(Rectangle(20, 20, 300, 600))))
        r.arranger.apply(ArrangeLayout.Focus, focus = "a")
        settle()
        assertEquals(1, a.sets.size)
    }

    @Test
    fun noScreensUsesTheMainWindowBounds() = runTest {
        val windows = MirrorWindows().apply { mainWindowBounds = { Rectangle(100, 100, 800, 600) } }
        val arranger = WindowArranger(
            windows, object : ScreenGeometry { override fun screens() = emptyList<ScreenArea>() },
            animateWindows = { false }, reduceMotion = { false }, scope = backgroundScope,
        )
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        windows.register("a", entry("a", a))
        windows.register("b", entry("b", FakeHandle(Rectangle(20, 20, 300, 600))))
        arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertTrue(Rectangle(100, 100, 800, 600).contains(a.bounds), "${a.bounds}")
    }

    @Test
    fun aMissFollowedByASuccessStartsCountingAgain() = runTest {
        val r = rig()
        val stuckA = entry("a", FakeHandle(Rectangle(10, 10, 300, 600), ignoresPosition = true))
        val stuckB = entry("b", FakeHandle(Rectangle(20, 20, 300, 600), ignoresPosition = true))
        r.windows.register("a", stuckA)
        r.windows.register("b", stuckB)
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        // The desktop lets the next one through.
        val okA = entry("a", FakeHandle(Rectangle(10, 10, 300, 600)))
        val okB = entry("b", FakeHandle(Rectangle(20, 20, 300, 600)))
        r.windows.register("a", okA)
        r.windows.register("b", okB)
        r.arranger.apply(ArrangeLayout.Cascade, focus = null)
        settle()
        r.windows.register("a", stuckA)
        r.windows.register("b", stuckB)
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertTrue(r.arranger.state.value.movable)
    }

    @Test
    fun appCausedResizesNeverTurnKeepTiledOff() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600), lateScope = backgroundScope, lateMs = 60)
        val b = FakeHandle(Rectangle(20, 20, 300, 600), lateScope = backgroundScope, lateMs = 60)
        val sizing = MutableStateFlow(false)
        var sheet = 0
        r.windows.register("a", entry("a", a, chromeW = { sheet }, appSizing = sizing))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        r.arranger.setKeepTiled(true)
        settle()
        val tile = a.bounds

        // A new frame size: the window corrects its size a little, its event arriving after the mark is gone.
        sizing.value = true
        a.appResize(Rectangle(tile.x, tile.y, tile.width + 10, tile.height + 10))
        advanceTimeBy(20)
        sizing.value = false
        settle()
        assertTrue(r.arranger.state.value.keepTiled)
        assertEquals(Rectangle(tile.x, tile.y, tile.width + 10, tile.height + 10), a.bounds, "a small change stays")
        a.userMove(a.bounds) // the same bounds: the app's change is the expected one now
        settle()
        assertTrue(r.arranger.state.value.keepTiled)

        // The sheet opens: the window grows out of its tile, so the layout is applied again around the wider chrome.
        val setsBefore = a.sets.size
        sizing.value = true
        sheet = 280
        a.appResize(Rectangle(tile.x - 100, tile.y, tile.width + 280, tile.height))
        advanceTimeBy(300)
        sizing.value = false
        settle()
        assertTrue(r.arranger.state.value.keepTiled)
        assertTrue(a.sets.size > setsBefore, "re-tiled")

        // And closes again.
        sizing.value = true
        sheet = 0
        a.appResize(Rectangle(a.bounds.x, a.bounds.y, a.bounds.width - 280, a.bounds.height))
        advanceTimeBy(300)
        sizing.value = false
        settle()
        assertTrue(r.arranger.state.value.keepTiled)
        assertFalse(r.arranger.busy)

        // The user's move still counts.
        a.userMove(Rectangle(a.bounds.x + 90, a.bounds.y + 40, a.bounds.width, a.bounds.height))
        settle()
        assertFalse(r.arranger.state.value.keepTiled)
    }

    @Test
    fun lateEventsAcrossOverlappingAppliesAreTheArrangers() = runTest {
        val r = rig()
        val a = FakeHandle(Rectangle(10, 10, 300, 600), lateScope = backgroundScope, lateMs = 150)
        val b = FakeHandle(Rectangle(20, 20, 300, 600), lateScope = backgroundScope, lateMs = 150)
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        r.arranger.setKeepTiled(true)
        settle()
        // Grid to Cascade, then Side by side mid-move: each cut-short move's events land during or after the next.
        r.arranger.apply(ArrangeLayout.Cascade, focus = null)
        advanceTimeBy(200)
        r.arranger.apply(ArrangeLayout.SideBySide, focus = null)
        runCurrent()
        assertTrue(r.arranger.busy)
        settle()
        assertTrue(r.arranger.state.value.keepTiled)
        assertFalse(r.arranger.busy)
        assertEquals(ArrangeLayout.SideBySide, r.arranger.state.value.layout)
    }

    @Test
    fun aWindowClosingMidMoveReleasesTheOthers() = runTest {
        val r = rig(hang = setOf("c"))
        val ea = entry("a", FakeHandle(Rectangle(10, 10, 300, 600)))
        val eb = entry("b", FakeHandle(Rectangle(20, 20, 300, 600)))
        val ec = entry("c", FakeHandle(Rectangle(30, 30, 300, 600)))
        r.windows.register("a", ea)
        r.windows.register("b", eb)
        r.windows.register("c", ec)
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        advanceTimeBy(100)
        r.windows.unregister("c", ec)
        advanceTimeBy(500) // before the move's own time limit (380 + 70 + 300 ms)
        runCurrent()
        assertFalse(ea.arranging.value || eb.arranging.value || ec.arranging.value)
        settle()
        assertFalse(r.arranger.busy)
    }

    @Test
    fun aMoveThatNeverEndsGivesUp() = runTest {
        val r = rig(hang = setOf("b"))
        val ea = entry("a", FakeHandle(Rectangle(10, 10, 300, 600)))
        val eb = entry("b", FakeHandle(Rectangle(20, 20, 300, 600)))
        r.windows.register("a", ea)
        r.windows.register("b", eb)
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        assertFalse(ea.arranging.value || eb.arranging.value)
        assertFalse(r.arranger.busy)
    }

    @Test
    fun asPlacedKeepsBoundsOfWindowsItCouldNotRestore() = runTest {
        val r = rig()
        val startB = Rectangle(400, 40, 320, 640)
        val a = FakeHandle(Rectangle(10, 10, 300, 600))
        val b = FakeHandle(startB)
        var bMinimized = false
        r.windows.register("a", entry("a", a))
        r.windows.register("b", entry("b", b, minimized = { bMinimized }))
        r.arranger.apply(ArrangeLayout.Grid, focus = null)
        settle()
        bMinimized = true
        r.arranger.apply(ArrangeLayout.AsPlaced, focus = null)
        settle()
        assertNotEquals(startB, b.bounds)
        bMinimized = false
        r.arranger.apply(ArrangeLayout.AsPlaced, focus = null)
        settle()
        assertEquals(startB, b.bounds)
    }
}
