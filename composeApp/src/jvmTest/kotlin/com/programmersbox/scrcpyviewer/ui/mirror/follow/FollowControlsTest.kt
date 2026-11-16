package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class FollowControlsTest {
    private val pixel = FollowCandidate("P9", "Pixel 9 · emulator")
    private val tablet = FollowCandidate("TAB", "Pixel Tablet · emulator")
    private val scenes = ArrayList<ImageComposeScene>()

    @AfterTest
    fun tearDown() = scenes.forEach { it.close() }

    private fun ui(content: @Composable () -> Unit): SceneDriver {
        val scene = ImageComposeScene(900, 700, Density(1f)) { ScrcpyViewerTheme(ThemeMode.Light) { content() } }
        scenes += scene
        return SceneDriver(scene).also { it.frames(5) }
    }

    private fun SceneDriver.nodes(merged: Boolean = false): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            out += n
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
        return out
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun SceneDriver.texts() = nodes().flatMap { it.texts() }

    private fun SceneDriver.click(text: String) {
        val node = nodes().first { text in it.texts() }
        val at = node.boundsInWindow.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(20)
    }

    // --- Pure pieces ------------------------------------------------------------------------------------------------

    @Test
    fun `label reads Send input to when idle and Sending to N while leading`() {
        assertEquals("Send input to…", sendInputLabel(FollowRole.None))
        assertEquals("Send input to…", sendInputLabel(FollowRole.Follower("A")))
        assertEquals("Sending to 2", sendInputLabel(FollowRole.Leader(setOf("B", "C"))))
    }

    @Test
    fun `a row toggles its window and All toggles every window`() {
        val others = listOf(pixel, tablet)
        assertEquals(setOf("P9"), toggledFollowers(emptySet(), others, "P9"))
        assertEquals(emptySet(), toggledFollowers(setOf("P9"), others, "P9"))
        assertEquals(setOf("P9", "TAB"), toggledFollowers(setOf("P9"), others, null))
        assertEquals(emptySet(), toggledFollowers(setOf("P9", "TAB"), others, null))
    }

    @Test
    fun `follower flags come from that window's own options`() {
        assertEquals(FollowerFlags(), followerFlags(OptionValues(emptyMap())))
        assertEquals(FollowerFlags(control = false), followerFlags(OptionValues(mapOf("no-control" to ""))))
        assertEquals(FollowerFlags(keyboard = false), followerFlags(OptionValues(mapOf("keyboard" to "disabled"))))
        assertEquals(FollowerFlags(mouse = false), followerFlags(OptionValues(mapOf("mouse" to "disabled"))))
        // UHID modes on the follower still take plain injected keys and touches.
        assertEquals(FollowerFlags(), followerFlags(OptionValues(mapOf("keyboard" to "uhid", "mouse" to "uhid"))))
    }

    // --- The button and its menu ------------------------------------------------------------------------------------

    @Test
    fun `button shows Send input to when idle and Sending to 2 while leading`() {
        var role by mutableStateOf<FollowRole>(FollowRole.None)
        val d = ui { SendInputButton(role, listOf(pixel, tablet), captureOn = false, onChange = {}) }
        assertTrue("Send input to…" in d.texts(), d.texts().toString())
        role = FollowRole.Leader(setOf("P9", "TAB"))
        d.frames(30)
        assertTrue("Sending to 2" in d.texts(), d.texts().toString())
        assertFalse("Send input to…" in d.texts(), d.texts().toString())
    }

    @Test
    fun `menu lists the other windows and All mirror windows checks them all`() {
        val changes = ArrayList<Set<String>>()
        val d = ui { SendInputButton(FollowRole.None, listOf(pixel, tablet), captureOn = false, onChange = { changes += it }) }
        d.click("Send input to…")
        val texts = d.texts()
        assertTrue("SEND INPUT TO" in texts, texts.toString())
        assertTrue("Pixel 9 · emulator" in texts && "Pixel Tablet · emulator" in texts, texts.toString())
        assertTrue("All mirror windows" in texts, texts.toString())
        assertFalse("Open another mirror window to send input to it" in texts)
        d.click("All mirror windows")
        assertEquals(listOf(setOf("P9", "TAB")), changes)
        d.click("Pixel 9 · emulator")
        assertEquals(setOf("P9"), changes.last())
    }

    @Test
    fun `menu checks the windows already following`() {
        val d = ui { SendInputButton(FollowRole.Leader(setOf("TAB")), listOf(pixel, tablet), captureOn = false, onChange = {}) }
        d.click("Sending to 1")
        fun checked(text: String) = d.nodes(merged = true).first { text in it.texts() }.config
            .getOrNull(SemanticsProperties.ToggleableState)?.name
        assertEquals("On", checked("Pixel Tablet · emulator"))
        assertEquals("Off", checked("Pixel 9 · emulator"))
        assertEquals("Off", checked("All mirror windows"))
    }

    @Test
    fun `with no other window the menu has one disabled hint`() {
        val changes = ArrayList<Set<String>>()
        val d = ui { SendInputButton(FollowRole.None, emptyList(), captureOn = true, onChange = { changes += it }) }
        d.click("Send input to…")
        val hint = d.nodes(merged = true).first { "Open another mirror window to send input to it" in it.texts() }
        assertTrue(SemanticsProperties.Disabled in hint.config, "the hint is disabled")
        assertFalse("All mirror windows" in d.texts())
        assertFalse("SEND INPUT TO" in d.texts())
        d.click("Open another mirror window to send input to it")
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `capture note shows only while the UHID mouse is captured`() {
        val on = ui { SendInputButton(FollowRole.None, listOf(pixel), captureOn = true, onChange = {}) }
        on.click("Send input to…")
        assertTrue("Mouse capture is on, so taps aren't sent to other windows" in on.texts())
        val off = ui { SendInputButton(FollowRole.None, listOf(pixel), captureOn = false, onChange = {}) }
        off.click("Send input to…")
        assertFalse("Mouse capture is on, so taps aren't sent to other windows" in off.texts())
    }

    // --- The follower chip --------------------------------------------------------------------------------------------

    @Test
    fun `chip reads Following and the device, with Layout differs only when flagged`() {
        var differs by mutableStateOf(false)
        val d = ui { FollowingChip("Galaxy A15 5G", layoutDiffers = differs) }
        assertTrue("Following Galaxy A15 5G" in d.texts(), d.texts().toString())
        assertFalse("Layout differs" in d.texts())
        differs = true
        d.frames(30)
        assertTrue("Layout differs" in d.texts())
        differs = false
        d.frames(30)
        assertFalse("Layout differs" in d.texts())
    }

    @Test
    fun `with no room Layout differs is a warning icon described by its words`() {
        val d = ui { FollowingChip("Galaxy A15 5G", layoutDiffers = true, reduceMotion = false, compactWarning = true) }
        assertFalse("Layout differs" in d.texts(), d.texts().toString())
        val described = d.nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("Layout differs" in described, described.toString())
        assertTrue("Following Galaxy A15 5G" in d.texts())
    }

    @Test
    fun `chip expands in over 250 ms without jumps and shrinks out over 200 ms`() {
        var visible by mutableStateOf(false)
        val widths = ArrayList<Int>()
        var width = 0
        val d = ui {
            Row {
                FollowerChip(visible, "Galaxy A15 5G", layoutDiffers = false, reduceMotion = false, modifier = Modifier.onSizeChanged { width = it.width })
            }
        }
        visible = true
        repeat(25) { d.frame(); widths += width }
        val full = widths.last()
        assertTrue(full > 60, "chip has its width: $widths")
        val grown = widths.indexOfFirst { it == full }
        assertTrue(grown in 13..17, "full width after ~250 ms (16 frames), at $grown: $widths")
        val steps = widths.zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it >= 0 }, "grows only: $widths")
        assertTrue(steps.max() <= full * 0.25, "no frame jumps more than a quarter of the width: $widths")
        visible = false
        val shrinking = ArrayList<Int>()
        repeat(20) { d.frame(); shrinking += width }
        val gone = shrinking.indexOfFirst { it == 0 }
        assertTrue(gone in 10..14, "gone after ~200 ms (12 frames), at $gone: $shrinking")
    }

    @Test
    fun `reduced motion chip fades without changing size`() {
        var visible by mutableStateOf(false)
        val widths = ArrayList<Int>()
        var width = 0
        val d = ui {
            Box {
                FollowerChip(visible, "Galaxy A15 5G", layoutDiffers = false, reduceMotion = true, modifier = Modifier.onSizeChanged { width = it.width })
            }
        }
        visible = true
        repeat(15) { d.frame(); widths += width }
        assertTrue(widths.first() > 60 && widths.distinct().size == 1, "full width from the first frame: $widths")
    }

    // --- The border glow -----------------------------------------------------------------------------------------------

    private fun glowFrames(reduceMotion: Boolean, frames: Int = 50): Pair<FollowGlow, List<Pair<Float, Float>>> {
        var active by mutableStateOf(false)
        lateinit var glow: FollowGlow
        val d = ui { glow = rememberFollowGlow(active, reduceMotion) }
        active = true
        val seen = ArrayList<Pair<Float, Float>>()
        repeat(frames) { d.frame(); seen += glow.presence to glow.pulse }
        return glow to seen
    }

    @Test
    fun `follow start fades the border in over 300 ms and pulses the glow once over 600 ms`() {
        val (_, seen) = glowFrames(reduceMotion = false)
        val presence = seen.map { it.first }
        val pulse = seen.map { it.second }
        val inAt = presence.indexOfFirst { it >= 1f }
        assertTrue(inAt in 17..20, "border in after ~300 ms (19 frames), at $inAt: $presence")
        assertTrue(presence.zipWithNext { a, b -> b - a }.all { it in 0f..0.16f }, "border never jumps more than one frame of a 300 ms ease: $presence")
        assertTrue(pulse.max() > 0.9f, "the pulse runs: $pulse")
        val doneAt = pulse.indexOfFirst { it >= 1f }
        assertTrue(doneAt in 35..39, "pulse over after ~600 ms (38 frames), at $doneAt: $pulse")
        // One pulse: it only moves forward.
        assertTrue(pulse.zipWithNext { a, b -> b >= a }.all { it }, "single pulse: $pulse")
    }

    @Test
    fun `reduced motion fades the border in 150 ms with no pulse`() {
        val (glow, seen) = glowFrames(reduceMotion = true)
        val presence = seen.map { it.first }
        val inAt = presence.indexOfFirst { it >= 1f }
        assertTrue(inAt in 8..11, "faded in after ~150 ms, at $inAt: $presence")
        assertTrue(seen.all { it.second == 0f }, "no pulse: ${seen.map { it.second }}")
        assertEquals(0f, glow.pulseAlpha)
    }

    @Test
    fun `follow stop fades the border out over 200 ms`() {
        var active by mutableStateOf(true)
        lateinit var glow: FollowGlow
        val d = ui { glow = rememberFollowGlow(active, reduceMotion = false) }
        d.frames(60)
        assertEquals(1f, glow.presence)
        active = false
        val out = ArrayList<Float>()
        repeat(20) { d.frame(); out += glow.presence }
        val goneAt = out.indexOfFirst { it <= 0f }
        assertTrue(goneAt in 11..14, "gone after ~200 ms, at $goneAt: $out")
        assertTrue(out.zipWithNext { a, b -> abs(b - a) }.all { it <= 0.25f }, "no jump (one frame of a 200 ms accelerate at most): $out")
    }
}
