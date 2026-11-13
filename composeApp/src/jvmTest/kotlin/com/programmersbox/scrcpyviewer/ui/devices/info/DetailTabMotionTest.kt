package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailTabMotionTest {

    // --- The numbers each switch uses -------------------------------------------------------------------------------

    @Test
    fun `options to info slides the new tab in from the right and the old one out to the left`() {
        val s = detailTabSwitch(DetailTab.Options, DetailTab.Info, reduced = false)
        assertEquals(AxisSwitch.Axis.Horizontal, s.axis)
        assertEquals(36.dp, s.enterOffset)
        assertEquals((-36).dp, s.exitOffset)
    }

    @Test
    fun `info to options mirrors it`() {
        val s = detailTabSwitch(DetailTab.Info, DetailTab.Options, reduced = false)
        assertEquals((-36).dp, s.enterOffset)
        assertEquals(36.dp, s.exitOffset)
    }

    @Test
    fun `the old tab leaves in 160 ms, then the new one arrives over 320 ms`() {
        val s = detailTabSwitch(DetailTab.Options, DetailTab.Info, reduced = false)
        assertEquals(160, s.exitMillis)
        assertEquals(320, s.enterMillis)
        assertEquals(160, s.enterDelayMillis)
    }

    @Test
    fun `reduced motion switches tabs with a plain fade`() {
        val s = detailTabSwitch(DetailTab.Options, DetailTab.Info, reduced = true)
        assertEquals(0.dp, s.enterOffset)
        assertEquals(0.dp, s.exitOffset)
        assertEquals(0, s.enterDelayMillis)
        assertEquals(REDUCED_FADE_MILLIS, s.enterMillis)
        assertEquals(REDUCED_FADE_MILLIS, s.exitMillis)
    }

    @Test
    fun `moving to a later device moves the content up`() {
        val s = deviceSwitch(fromIndex = 0, toIndex = 2, reduced = false)
        assertEquals(AxisSwitch.Axis.Vertical, s.axis)
        assertEquals(18.dp, s.enterOffset)
        assertEquals((-14).dp, s.exitOffset)
        assertEquals(160, s.exitMillis)
        assertEquals(340, s.enterMillis)
        assertEquals(160, s.enterDelayMillis)
    }

    @Test
    fun `moving to an earlier device moves the content down`() {
        val s = deviceSwitch(fromIndex = 2, toIndex = 1, reduced = false)
        assertEquals((-18).dp, s.enterOffset)
        assertEquals(14.dp, s.exitOffset)
    }

    @Test
    fun `a device not in the list counts as moving forward`() {
        assertEquals(18.dp, deviceSwitch(fromIndex = -1, toIndex = 0, reduced = false).enterOffset)
        assertEquals(18.dp, deviceSwitch(fromIndex = 1, toIndex = -1, reduced = false).enterOffset)
    }

    @Test
    fun `reduced motion switches devices with a plain fade`() {
        val s = deviceSwitch(fromIndex = 0, toIndex = 1, reduced = true)
        assertEquals(0.dp, s.enterOffset)
        assertEquals(0.dp, s.exitOffset)
        assertEquals(REDUCED_FADE_MILLIS, s.enterMillis)
    }

    // --- What the built transforms do on screen ---------------------------------------------------------------------

    /** Each frame's offset of every shown child, by its state; a child missing from a frame has left. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> track(from: T, to: T, transform: (T, T) -> ContentTransform): List<Map<T, Offset>> {
        var target by mutableStateOf(from)
        val shown = LinkedHashMap<T, Offset>()
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            AnimatedContent(target, transitionSpec = { transform(initialState, targetState) }) { s ->
                DisposableEffect(s) { onDispose { shown.remove(s) } }
                Box(Modifier.size(100.dp).onGloballyPositioned { shown[s] = it.positionInRoot() })
            }
        }
        try {
            val driver = SceneDriver(scene)
            driver.frames(5)
            target = to
            return List(50) {
                driver.frame()
                LinkedHashMap(shown)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `options to info on screen - old tab slides left and leaves, new tab waits at +36 then lands at 0`() {
        val frames = track(DetailTab.Options, DetailTab.Info) { a, b -> detailTabTransform(a, b, reduced = false, Density(1f)) }
        val outX = frames.mapNotNull { it[DetailTab.Options]?.x }
        assertTrue(outX.all { it in -36.5f..0.5f }, "outgoing stays between 0 and -36: $outX")
        assertTrue(outX.last() < -10f, "outgoing moved left: $outX")
        // Gone after its 160 ms (10 frames), give or take a frame.
        val outFrames = frames.count { DetailTab.Options in it }
        assertTrue(outFrames in 9..12, "outgoing shown for $outFrames frames")
        val inX = frames.mapNotNull { it[DetailTab.Info]?.x }
        // While the old tab leaves, the new one waits 36 dp to the right.
        assertTrue(inX.take(8).all { it == 36f }, "incoming waits at +36: $inX")
        assertTrue(inX.all { it in -0.5f..36.5f }, "incoming moves only between +36 and 0: $inX")
        assertEquals(0f, inX.last())
        // Lands after 160 + 320 ms (30 frames), not long before.
        val landed = inX.indexOfFirst { it == 0f }
        assertTrue(landed in 24..32, "incoming landed at frame $landed: $inX")
    }

    @Test
    fun `info to options on screen moves the other way`() {
        val frames = track(DetailTab.Info, DetailTab.Options) { a, b -> detailTabTransform(a, b, reduced = false, Density(1f)) }
        assertTrue(frames.mapNotNull { it[DetailTab.Info]?.x }.last() > 10f)
        assertEquals(-36f, frames.first()[DetailTab.Options]?.x)
    }

    @Test
    fun `reduced tab switch on screen never slides`() {
        val frames = track(DetailTab.Options, DetailTab.Info) { a, b -> detailTabTransform(a, b, reduced = true, Density(1f)) }
        assertTrue(frames.all { f -> f.values.all { it == Offset.Zero } }, "no slide: $frames")
        assertEquals(setOf(DetailTab.Info), frames.last().keys)
    }

    @Test
    fun `device switch on screen - later device moves the content up`() {
        val frames = track(0, 1) { a, b -> deviceSwitchTransform(a, b, reduced = false, Density(1f)) }
        val outY = frames.mapNotNull { it[0]?.y }
        assertTrue(outY.all { it in -14.5f..0.5f } && outY.last() < -4f, "outgoing lifts toward -14: $outY")
        val inY = frames.mapNotNull { it[1]?.y }
        assertEquals(18f, inY.first())
        assertTrue(inY.all { it in -0.5f..18.5f }, "incoming rises from +18: $inY")
        assertEquals(0f, inY.last())
        assertTrue(frames.all { f -> f.values.all { it.x == 0f } })
    }

    @Test
    fun `selecting a device or none cross-fades without sliding`() {
        val frames = track<String?>(null, "a") { _, _ -> selectionFade(reduced = false) }
        assertTrue(frames.all { f -> f.values.all { it == Offset.Zero } })
        // Both are shown together while the old content fades out (100 ms), then only the device.
        val together = frames.count { it.size == 2 }
        assertTrue(together in 5..8, "both shown for $together frames")
        assertEquals(setOf<String?>("a"), frames.last().keys)
    }

    // --- The detail pane's wiring --------------------------------------------------------------------------------------

    @OptIn(ExperimentalComposeUiApi::class)
    private class PaneHarness {
        var page by mutableStateOf<String?>("a")
        var tab by mutableStateOf(DetailTab.Options)
        val active = ArrayList<Boolean>()
        var counter: androidx.compose.runtime.MutableIntState? = null
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            DetailPane(
                page, { it }, listOf("a", "b"), tab, DeviceInfoUiState(serial = page), { active += it }, {},
                reduceMotion = true,
                empty = {},
            ) { _, _ ->
                counter = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
            }
        }
        val driver = SceneDriver(scene)
        fun settle() = driver.frames(40)
    }

    @Test
    fun `info is active only while a device shows the info tab`() {
        val h = PaneHarness()
        try {
            h.settle()
            assertEquals(emptyList(), h.active)
            h.tab = DetailTab.Info
            h.settle()
            assertEquals(listOf(true), h.active)
            // Another device keeps Info on screen: no false in between.
            h.page = "b"
            h.settle()
            assertEquals(listOf(true), h.active)
            h.tab = DetailTab.Options
            h.settle()
            assertEquals(listOf(true, false), h.active)
            h.tab = DetailTab.Info
            h.settle()
            h.page = null
            h.settle()
            assertEquals(listOf(true, false, true, false), h.active)
        } finally {
            h.scene.close()
        }
    }

    @Test
    fun `options keep their saved state across a tab switch`() {
        val h = PaneHarness()
        try {
            h.settle()
            h.counter!!.intValue = 7
            h.tab = DetailTab.Info
            h.settle()
            h.tab = DetailTab.Options
            h.settle()
            assertEquals(7, h.counter!!.intValue)
        } finally {
            h.scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `clicking the info segment asks for the info tab`() {
        val picked = ArrayList<DetailTab>()
        val scene = ImageComposeScene(400, 56, Density(1f)) {
            DetailTabRow(selected = true, tab = DetailTab.Options, onTab = { picked += it }, onBack = null, reduceMotion = true)
        }
        try {
            val driver = SceneDriver(scene)
            driver.frames(30)
            // The control is centered; Info is its right half (each segment is at least 120 wide).
            val at = Offset(260f, 28f)
            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
            driver.frames(5)
            assertEquals(listOf(DetailTab.Info), picked)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `segment labels`() {
        assertEquals("Options", DetailTab.Options.label())
        assertEquals("Info", DetailTab.Info.label())
    }

    @Test
    fun `device switch on screen - earlier device moves the content down`() {
        val frames = track(1, 0) { a, b -> deviceSwitchTransform(a, b, reduced = false, Density(1f)) }
        assertTrue(frames.mapNotNull { it[1]?.y }.last() > 4f)
        assertEquals(-18f, frames.first()[0]?.y)
    }
}
