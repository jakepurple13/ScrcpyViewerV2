package com.programmersbox.scrcpyviewer.ui.mirror.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.files.OutboundDragStarter
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thumbnail composable: it shows, its actions fire, it leaves on the timer, and each motion row sampled frame by
 * frame. Every motion test passes `motion = true` itself: CI's macOS and Windows runners report reduce motion on.
 */
private const val AREA_END = 100

@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotThumbnailTest {
    private val scheduler = TestCoroutineScheduler()
    private val timerScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val state = ScreenshotThumbnailState(timerScope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("/tmp/caps/Pixel 8 Pro #1.png")
    private val width = 600
    private val height = 800

    /** The video area's right edge, in px (density 1). */
    private val areaRight = width - AREA_END

    @AfterTest
    fun tearDown() = timerScope.cancel()

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun solid(color: Color, w: Int = 100, h: Int = 200): ImageBitmap =
        Bitmap().apply { allocN32Pixels(w, h); erase(color.toArgb()) }.asComposeImageBitmap()

    private class Calls {
        val copied = mutableListOf<Path>()
        val revealed = mutableListOf<Path>()
        val opened = mutableListOf<Path>()
        val osDrag = mutableListOf<Boolean>()
    }

    private class FakeStarter(val dropped: Boolean) : OutboundDragStarter {
        val started = mutableListOf<List<File>>()
        override fun start(files: List<File>, at: Offset, onEnd: (dropped: Boolean) -> Unit): Boolean {
            started += files
            onEnd(dropped)
            return true
        }
    }

    private fun scene(
        motion: Boolean,
        calls: Calls = Calls(),
        starter: OutboundDragStarter = FakeStarter(dropped = true),
        copyWorks: Boolean = true,
    ): SceneDriver = SceneDriver(ImageComposeScene(width, height, Density(1f)) {
        ScrcpyViewerTheme(ThemeMode.Light) {
            Surface(Modifier.fillMaxSize()) {
                // Room to the right, so a sliding card is never clipped by the scene (bounds in root are).
                Box(Modifier.fillMaxSize().background(Color.Black).padding(end = AREA_END.dp)) {
                    ScreenshotThumbnail(
                        state, motion,
                        onCopy = { calls.copied.add(it); copyWorks },
                        onReveal = { calls.revealed.add(it) },
                        onOpen = { calls.opened.add(it) },
                        dragStarter = starter,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        onOsDrag = { calls.osDrag.add(it) },
                    )
                }
            }
        }
    }).also { it.frames(2) }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.described(description: String): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(2)
    }

    /** The close button's centre: the card's top-right corner. */
    private fun SceneDriver.corner(): Offset? = described("Close")?.boundsInRoot?.center

    @Test
    fun `shows after a screenshot, 16 dp from the bottom-right edges`() = onEdt {
        val d = scene(motion = true)
        assertNull(d.described("Screenshot"))
        state.show(file, solid(Color.Red))
        d.frames(30)
        assertNotNull(d.described("Screenshot"))
        val corner = assertNotNull(d.corner())
        assertEquals(areaRight - 16f, corner.x, 1f)
        val card = d.described("Show in folder")!!.boundsInRoot
        assertTrue(card.bottom <= height - 16f && card.bottom > height - 16f - 60f, "the buttons row sits at the card's bottom: $card")
        d.scene.close()
    }

    @Test
    fun `actions fire and count as interaction`() = onEdt {
        val calls = Calls()
        val d = scene(motion = true, calls = calls)
        state.show(file, solid(Color.Red))
        d.frames(30)

        d.click(d.described("Screenshot")!!)
        assertEquals(listOf(file), calls.opened)

        // Pointer off the card, so the timer runs.
        d.scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f), type = PointerType.Mouse)
        d.frames(2)
        advance(3_000)
        d.click(d.described("Show in folder")!!)
        assertEquals(listOf(file), calls.revealed)
        assertEquals(1f, state.remainingFraction)

        d.scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f), type = PointerType.Mouse)
        d.frames(2)
        advance(3_000)
        d.click(d.described("Copy")!!)
        assertEquals(listOf(file), calls.copied)
        assertEquals(1f, state.remainingFraction)
        d.scene.close()
    }

    @Test
    fun `close dismisses it`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        d.click(d.described("Close")!!)
        assertNull(state.shown.value)
        d.frames(30)
        assertNull(d.described("Screenshot"))
        d.scene.close()
    }

    @Test
    fun `leaves after the timer`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        advance(THUMBNAIL_MILLIS - 1)
        d.frames(2)
        assertNotNull(d.described("Screenshot"))
        advance(1)
        d.frames(30)
        assertNull(d.described("Screenshot"))
        d.scene.close()
    }

    @Test
    fun `hovering keeps it`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        d.scene.sendPointerEvent(PointerEventType.Move, d.described("Screenshot")!!.boundsInRoot.center, type = PointerType.Mouse)
        d.frames(2)
        advance(60_000)
        d.frames(2)
        assertNotNull(d.described("Screenshot"))
        d.scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f), type = PointerType.Mouse)
        d.frames(2)
        advance(THUMBNAIL_MILLIS)
        d.frames(30)
        assertNull(d.described("Screenshot"))
        d.scene.close()
    }

    @Test
    fun `dragging the image hands the PNG to the OS drag and a drop dismisses it`() = onEdt {
        val calls = Calls()
        val starter = FakeStarter(dropped = true)
        val d = scene(motion = true, calls = calls, starter = starter)
        state.show(file, solid(Color.Red))
        d.frames(30)
        val from = d.described("Screenshot")!!.boundsInRoot.center
        d.scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
        d.scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, button = PointerButton.Primary)
        d.frame()
        for (i in 1..10) {
            d.scene.sendPointerEvent(PointerEventType.Move, from - Offset(8f * i, 0f), type = PointerType.Mouse)
            d.frame()
        }
        assertEquals(listOf(listOf(file.toFile())), starter.started)
        assertEquals(listOf(true, false), calls.osDrag)
        assertNull(state.shown.value)
        assertEquals(emptyList(), calls.opened, "a drag is not a click")
        d.scene.close()
    }

    @Test
    fun `a drag that drops nothing keeps it and restarts the timer`() = onEdt {
        val starter = FakeStarter(dropped = false)
        val d = scene(motion = true, starter = starter)
        state.show(file, solid(Color.Red))
        d.frames(30)
        advance(4_000)
        val from = d.described("Screenshot")!!.boundsInRoot.center
        d.scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, button = PointerButton.Primary)
        d.frame()
        for (i in 1..10) {
            d.scene.sendPointerEvent(PointerEventType.Move, from - Offset(8f * i, 0f), type = PointerType.Mouse)
            d.frame()
        }
        assertEquals(1, starter.started.size)
        assertNotNull(state.shown.value)
        assertEquals(1f, state.remainingFraction)
        d.scene.close()
    }

    // Motion rows, sampled per frame (16 ms).

    @Test
    fun `appears sliding in from the right while it scales up`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        // Frames before the card is first placed have no corner yet.
        val xs = (1..26).mapNotNull { d.frame(); d.corner()?.x?.takeIf { it > 0f } }
        val end = areaRight - 16f
        assertTrue(xs.first() > end + 15f, "starts to the right: $xs")
        // The slide moves in whole pixels while the scale grows smoothly: allow half a pixel back.
        assertTrue(xs.zipWithNext().all { (a, b) -> b <= a + 0.5f }, "only moves left: $xs")
        // Most of the way after half its 350 ms (emphasized decelerate), there by the end.
        assertTrue(xs[10] - end < (xs.first() - end) * 0.25f, "decelerates: $xs")
        assertEquals(end, xs[22], 0.5f)
        d.scene.close()
    }

    @Test
    fun `leaves sliding right and is gone after 300 ms`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        val end = areaRight - 16f
        state.dismiss()
        val xs = (1..26).map { d.frame(); d.corner()?.x }
        val moving = xs.filterNotNull()
        assertTrue(moving.zipWithNext().all { (a, b) -> b >= a - 0.01f }, "only moves right: $xs")
        assertTrue(moving.last() - end > 20f, "slides most of its 40 dp: $xs")
        // Accelerates: the first half moves less than the second.
        val half = moving.size / 2
        assertTrue(moving[half] - end < (moving.last() - end) / 2, "accelerates out: $xs")
        assertTrue(moving.size in 17..23, "about 300 ms (plus the frames until the change composes): $xs")
        assertNull(xs.last())
        d.scene.close()
    }

    @Test
    fun `reduced motion fades in place in 150 ms`() = onEdt {
        val d = scene(motion = false)
        state.show(file, solid(Color.Red))
        val end = areaRight - 16f
        d.frame()
        assertEquals(end, assertNotNull(d.corner()).x, 0.5f)
        // Fading in: the image's pixel is darker than its own colour at the start, full by 150 ms.
        val at = d.described("Screenshot")!!.boundsInRoot.center
        val early = d.snapshot()[at.x.toInt(), at.y.toInt()]
        d.frames(10)
        val settled = d.snapshot()[at.x.toInt(), at.y.toInt()]
        assertTrue(early.red < settled.red - 0.1f, "fades in: $early -> $settled")
        state.dismiss()
        val xs = (1..16).map { d.frame(); d.corner()?.x }
        assertTrue(xs.filterNotNull().all { abs(it - end) < 0.5f }, "fades in place: $xs")
        assertTrue(xs.count { it != null } <= 13, "gone in 150 ms (plus the frames until the change composes): $xs")
        assertNull(xs.last())
        d.scene.close()
    }

    @Test
    fun `a new screenshot crossfades the image in 250 ms`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        val at = d.described("Screenshot")!!.boundsInRoot.center
        state.show(file, solid(Color.Blue))
        val samples = (1..18).map { d.snapshot()[at.x.toInt(), at.y.toInt()] }
        val mid = samples[7]
        assertTrue(mid.red > 0.2f && mid.blue > 0.2f, "both show half way: $mid")
        assertTrue(samples.zipWithNext().all { (a, b) -> b.blue >= a.blue - 0.01f }, "blue only grows: ${samples.map { it.blue }}")
        val last = samples.last()
        assertTrue(last.blue > 0.95f && last.red < 0.05f, "the new one by the end: $last")
        d.scene.close()
    }

    @Test
    fun `copy turns the icon into a check, then back after a second and a half`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        d.click(d.described("Copy")!!)
        // Mid crossfade (200 ms): both icons are there.
        d.frames(4)
        assertNotNull(d.described("Copied"))
        assertTrue(d.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Copy") }, "copy still fading out")
        d.frames(10)
        assertTrue(d.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Copy") })
        advance(COPIED_MILLIS)
        d.frames(20)
        assertNull(d.described("Copied"))
        assertNotNull(d.described("Copy"))
        d.scene.close()
    }

    @Test
    fun `a failed copy shows no check`() = onEdt {
        val calls = Calls()
        val d = scene(motion = true, calls = calls, copyWorks = false)
        state.show(file, solid(Color.Red))
        d.frames(30)
        d.click(d.described("Copy")!!)
        d.frames(20)
        assertEquals(1, calls.copied.size)
        assertNull(d.described("Copied"))
        d.scene.close()
    }

    @Test
    fun `the flash fades from 90 percent white to nothing in 450 ms`() = onEdt {
        var flash = ScreenshotFlashState()
        var shown by mutableStateOf(true)
        fun flashScene(motion: Boolean) = SceneDriver(ImageComposeScene(100, 100, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { if (shown) ScreenshotFlash(flash, motion) }
        }).also { it.frames(2) }
        val d = flashScene(motion = true)
        assertEquals(0f, d.snapshot()[50, 50].red, 0.01f)
        flash.flash()
        val reds = (1..30).map { d.snapshot()[50, 50].red }
        assertTrue(reds.first() > 0.8f, "starts near .9 white: $reds")
        assertTrue(reds.zipWithNext().all { (a, b) -> b <= a + 0.01f }, "only fades: $reds")
        assertTrue(abs(reds[13] - 0.45f) < 0.15f, "about half at 225 ms: $reds")
        assertEquals(0f, reds.last(), 0.01f)

        // Leaving and re-entering the composition does not play it again.
        shown = false
        d.frames(2)
        shown = true
        val again = (1..10).map { d.snapshot()[50, 50].red }
        assertTrue(again.all { it < 0.01f }, "no replay on re-entry: $again")
        d.scene.close()

        flash = ScreenshotFlashState()
        val still = flashScene(motion = false)
        flash.flash()
        val none = (1..10).map { still.snapshot()[50, 50].red }
        assertTrue(none.all { it < 0.01f }, "no flash with reduced motion: $none")
        still.scene.close()
    }

    @Test
    fun `a replaced screenshot stays open while its crossfade slot draws it, then is closed`() = onEdt {
        val d = scene(motion = true)
        val red = solid(Color.Red)
        state.show(file, red)
        d.frames(30)
        state.show(file, solid(Color.Blue))
        // A stalled window: no frames for far longer than any wall-clock delay.
        advance(60_000)
        d.frames(2)
        assertFalse(red.asSkiaBitmap().isClosed, "still drawn by the crossfade")
        d.frames(30)
        assertTrue(red.asSkiaBitmap().isClosed, "closed once its slot left")
        d.scene.close()
    }

    @Test
    fun `a dismissed screenshot stays open through the leave animation, then is closed`() = onEdt {
        val d = scene(motion = true)
        val red = solid(Color.Red)
        state.show(file, red)
        d.frames(30)
        state.dismiss()
        d.frames(3)
        assertFalse(red.asSkiaBitmap().isClosed, "still drawn while it leaves")
        d.frames(30)
        assertNull(d.described("Screenshot"))
        assertTrue(red.asSkiaBitmap().isClosed)
        d.scene.close()
    }

    @Test
    fun `a card that leaves with the pointer on it does not hold the next one`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        d.scene.sendPointerEvent(PointerEventType.Move, d.described("Screenshot")!!.boundsInRoot.center, type = PointerType.Mouse)
        d.frames(2)
        assertTrue(state.held.value)
        d.click(d.described("Close")!!) // the pointer stays where the card was; no Exit comes
        d.frames(30)
        assertNull(d.described("Screenshot"))
        state.show(file, solid(Color.Blue))
        d.frames(2)
        advance(THUMBNAIL_MILLIS)
        d.frames(30)
        assertNull(state.shown.value, "the timer ran")
        d.scene.close()
    }

    @Test
    fun `the strips beside the overhanging close button do not hold the timer`() = onEdt {
        val d = scene(motion = true)
        state.show(file, solid(Color.Red))
        d.frames(30)
        val close = d.described("Close")!!.boundsInRoot
        // Right of the card, below the ring: in the thumbnail's bounds, outside the card and the ring.
        d.scene.sendPointerEvent(PointerEventType.Move, Offset(close.center.x + 4f, close.bottom + 30f), type = PointerType.Mouse)
        d.frames(2)
        assertFalse(state.held.value)
        // Card to ring and back: held all the way.
        d.scene.sendPointerEvent(PointerEventType.Move, Offset(close.left - 20f, close.bottom + 20f), type = PointerType.Mouse)
        d.frames(2)
        assertTrue(state.held.value)
        d.scene.sendPointerEvent(PointerEventType.Move, close.center, type = PointerType.Mouse)
        d.frames(2)
        assertTrue(state.held.value)
        d.scene.close()
    }

    @Test
    fun `the timer is held for the whole OS drag`() = onEdt {
        var heldDuringDrag: Boolean? = null
        val starter = object : OutboundDragStarter {
            override fun start(files: List<File>, at: Offset, onEnd: (dropped: Boolean) -> Unit): Boolean {
                heldDuringDrag = state.held.value
                onEnd(false)
                return true
            }
        }
        val d = scene(motion = true, starter = starter)
        state.show(file, solid(Color.Red))
        d.frames(30)
        val from = d.described("Screenshot")!!.boundsInRoot.center
        d.scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, button = PointerButton.Primary)
        d.frame()
        for (i in 1..10) {
            d.scene.sendPointerEvent(PointerEventType.Move, from - Offset(8f * i, 0f), type = PointerType.Mouse)
            d.frame()
        }
        assertEquals(true, heldDuringDrag)
        d.scene.close()
    }
}
