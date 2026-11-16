package com.programmersbox.scrcpyviewer.ui.mirror.shots.gif

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import org.jetbrains.skia.Bitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.scrcpyviewer.media.GifExportException
import com.programmersbox.scrcpyviewer.media.GifExporter
import com.programmersbox.scrcpyviewer.media.GifOptions
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.shots.clipLength
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An export that waits for the test to finish it ([gate]); it records its runs and whether it was cancelled. */
internal class FakeExport {
    val runs = mutableListOf<GifOptions>()
    var gate = CompletableDeferred<Result<Path>>()
    var progress: ((GifExporter.Phase, Float) -> Unit)? = null
    var cancelled = 0
    val fn: GifExport = { _, o, onProgress ->
        runs += o
        progress = onProgress
        try {
            gate.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }
}

/** The frames asked for: (start, end, count, width) per call. */
internal class FakeFrames(private val pngs: (count: Int) -> List<ByteArray> = { emptyList() }) : GifFrameSource {
    val calls = mutableListOf<List<Number>>()
    override suspend fun frames(file: Path, startMs: Long, endMs: Long, count: Int, width: Int): List<ByteArray> {
        calls += listOf(startMs, endMs, count, width)
        return pngs(count)
    }
}

/**
 * The Export GIF window's content: copy, trim, width / rate, the estimate, the 60 s gate, export / cancel / failure /
 * done, and the motion rows sampled frame by frame. Motion tests pass `motion = true` themselves (CI's macOS and
 * Windows runners report reduce motion on).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalPathApi::class)
class GifExportContentTest {
    private val tmp: Path = Files.createTempDirectory("gif-content-test")
    private val file: Path = tmp.resolve("Pixel 8 Pro #1 ü.mp4")
    private val video = RecordingInfo(18_000, 1080, 2340)

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    // Copy and numbers.

    @Test
    fun `sizes, times and lengths read as the mockup shows them`() {
        assertEquals("820 KB", gifSize(820L * 1024))
        assertEquals("1 KB", gifSize(10))
        assertEquals("4.3 MB", gifSize((4.3 * 1024 * 1024).toLong()))
        assertEquals("0:03.0", trimTime(3_000))
        assertEquals("1:12.3", trimTime(72_390))
        assertEquals("9.0 s", trimLength(9_000))
        assertEquals("60.5 s", trimLength(60_480))
        assertEquals("0:18", clipLength(18_030))
        assertEquals("1:05", clipLength(65_999))
        val o = GifOptions(3_000, 12_000, 480, 15)
        assertEquals("About 5.1 MB · 480 × 1040", gifEstimateLine(o, video))
        assertEquals("4.1 MB · 480 × 1040 · 9.0 s", GifDone(file, (4.1 * 1024 * 1024).toLong(), 480, 1040, 9_000).line)
        assertEquals("Making the palette…", GifProgress(GifExporter.Phase.Palette, 0.5f).label)
        assertEquals(0.1f, GifProgress(GifExporter.Phase.Palette, 0.5f).overall, 0.0001f)
        assertEquals("Encoding…", GifProgress(GifExporter.Phase.Encode, 0.5f).label)
        assertEquals(0.6f, GifProgress(GifExporter.Phase.Encode, 0.5f).overall, 0.0001f)
    }

    @Test
    fun `the handles keep half a second apart and inside the clip, and each change bumps the estimate`() {
        val s = GifExportState(file, video)
        assertEquals(GifOptions(0, 18_000, 480, 15), s.options)
        s.trimStart(17_900)
        assertEquals(17_500, s.startMs)
        s.trimEnd(0)
        assertEquals(18_000, s.endMs, "pinned by the start")
        s.trimStart(-5)
        assertEquals(0, s.startMs)
        s.trimEnd(400)
        assertEquals(500, s.endMs)
        val changes = s.estimateChanges
        s.pickWidth(320)
        s.pickFps(10)
        s.pickFps(10) // no change
        assertEquals(changes + 2, s.estimateChanges)
        assertEquals(694, s.gifHeight)
    }

    @Test
    fun `a clip over 60 s can be exported only once trimmed to 60 s`() {
        val s = GifExportState(file, RecordingInfo(90_000, 1080, 2340))
        assertFalse(s.canExport)
        s.trimEnd(60_000)
        assertTrue(s.canExport)
        s.trimEnd(60_001)
        assertFalse(s.canExport)
    }

    @Test
    fun `a cancel while the finished GIF's size is read keeps it done and says nothing`() = runBlocking {
        val s = GifExportState(file, video)
        val gif = Files.write(tmp.resolve("done.gif"), ByteArray(10))
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val said = mutableListOf<String>()
        s.export(this, { _, _, _ -> Result.success(gif) }, { said += it }) {
            reading.complete(Unit)
            release.await()
            10L
        }
        withTimeout(5_000) { reading.await() }
        val cancelled = async { s.cancelExport() }
        yield()
        release.complete(Unit)
        assertFalse(withTimeout(5_000) { cancelled.await() }, "not reported as cancelled")
        assertEquals(gif, s.done?.file)
        assertEquals(10L, s.done?.bytes)
        assertTrue(said.isEmpty())
        assertTrue(Files.exists(gif))
        assertFalse(s.exporting)
    }

    @Test
    fun `a cancel while it encodes is reported as cancelled`() = runBlocking {
        val s = GifExportState(file, video)
        val fake = FakeExport()
        val said = mutableListOf<String>()
        s.export(this, fake.fn, { said += it })
        withTimeout(5_000) { while (fake.progress == null) yield() }
        fake.progress!!(GifExporter.Phase.Encode, 0.5f)
        assertTrue(withTimeout(5_000) { s.cancelExport() })
        assertEquals(1, fake.cancelled)
        assertNull(s.done)
        assertTrue(said.isEmpty())
        assertFalse(s.exporting)
    }

    // The composable.

    private inner class Ui(
        val motion: Boolean,
        val state: GifExportState = GifExportState(file, video),
        val frames: FakeFrames = FakeFrames(),
        w: Int = 720,
        h: Int = 520,
    ) {
        val export = FakeExport()
        val said = mutableListOf<String>()
        val revealed = mutableListOf<Path>()
        var closed = 0
        val d = SceneDriver(ImageComposeScene(w, h, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    GifExportContent(
                        state, motion, export.fn, frames,
                        onClose = { closed++ },
                        onReveal = { revealed.add(it) },
                        say = { said += it },
                        modifier = Modifier.fillMaxSize(),
                        decode = EmptyCoroutineContext,
                    )
                }
            }
        }).also { it.frames(4) }

        fun nodes(): List<SemanticsNode> {
            val all = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                all += node
                node.children.forEach(::walk)
            }
            d.scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return all
        }

        fun texted(text: String): SemanticsNode? = nodes().firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

        fun described(text: String): SemanticsNode? = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }

        fun tagged(tag: String): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

        fun click(node: SemanticsNode) {
            val at = node.boundsInRoot.center
            d.scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            d.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.frames(2)
        }

        fun click(text: String) = click(assertNotNull(texted(text), "\"$text\" on screen"))

        /** Frames (with a little real time, for work on other threads) until [done], at most about 2 s. */
        fun until(done: () -> Boolean) {
            repeat(200) {
                if (done()) return
                d.frame()
                Thread.sleep(10)
            }
            assertTrue(done(), "timed out")
        }

        fun close() = d.scene.close()
    }

    @Test
    fun `a window still growing out of its button, narrower than a trim handle, lays out without crashing`() = onEdt {
        // The window grows from the GIF button's size: early frames give the strip less room than a handle.
        for ((w, h) in listOf(24 to 24, 60 to 40, 120 to 90)) {
            val ui = Ui(motion = true, w = w, h = h)
            ui.d.frames(4)
            ui.close()
        }
    }

    @Test
    fun `it shows the choices with their defaults, the range and the estimate`() = onEdt {
        val ui = Ui(motion = true)
        for (t in listOf("Export GIF", "Trim", "Width", "320 px", "480 px", "640 px", "Frame rate", "10 fps", "15 fps", "Close", "Export", "PREVIEW · LOOP")) {
            assertNotNull(ui.texted(t), t)
        }
        assertNotNull(ui.texted("0:00.0"))
        assertNotNull(ui.texted("18.0 s"))
        assertNotNull(ui.texted("0:18.0"))
        assertNotNull(ui.described(gifEstimateLine(ui.state.options, video)))
        assertNull(ui.texted("Show in folder"), "only once done")
        // The strip's 12 frames over the whole clip, small; then the preview's frames for the part at 15 fps.
        assertEquals(listOf<Number>(0L, 18_000L, GIF_STRIP_FRAMES, GIF_STRIP_FRAME_WIDTH), ui.frames.calls[0])
        assertEquals(listOf<Number>(0L, 18_000L, GIF_PREVIEW_MAX_FRAMES.coerceAtMost(18 * 15), GIF_PREVIEW_FRAME_WIDTH), ui.frames.calls[1])
        ui.click("Close")
        assertEquals(1, ui.closed)
        ui.close()
    }

    @Test
    fun `over 60 s the range says so in the error colour and Export does nothing until trimmed`() = onEdt {
        val ui = Ui(motion = true, state = GifExportState(file, RecordingInfo(90_000, 1080, 2340)))
        assertNotNull(ui.texted("Trim to 60 s or less to export"))
        ui.click("Export")
        assertTrue(ui.export.runs.isEmpty(), "disabled")
        ui.state.trimEnd(45_000)
        ui.d.frames(4)
        assertNull(ui.texted("Trim to 60 s or less to export"))
        assertNotNull(ui.texted("45.0 s"))
        ui.click("Export")
        assertEquals(listOf(GifOptions(0, 45_000, 480, 15)), ui.export.runs)
        ui.close()
    }

    @Test
    fun `dragging a handle trims, the frame and the readout follow the pointer`() = onEdt {
        val ui = Ui(motion = true)
        val end = assertNotNull(ui.described("Trim end")).boundsInRoot
        val start = assertNotNull(ui.described("Trim start")).boundsInRoot
        val width = end.right - start.left
        val y = end.center.y
        ui.d.scene.sendPointerEvent(PointerEventType.Move, Offset(end.right - 2, y), type = PointerType.Mouse)
        ui.d.scene.sendPointerEvent(PointerEventType.Press, Offset(end.right - 2, y), type = PointerType.Mouse, button = PointerButton.Primary)
        ui.d.frame()
        val seen = mutableListOf<Long>()
        for (i in 1..10) {
            ui.d.scene.sendPointerEvent(PointerEventType.Move, Offset(end.right - 2 - width * 0.05f * i, y), type = PointerType.Mouse)
            ui.d.frame()
            seen += ui.state.endMs
        }
        ui.d.scene.sendPointerEvent(PointerEventType.Release, Offset(end.right - 2 - width * 0.5f, y), type = PointerType.Mouse, button = PointerButton.Primary)
        ui.d.frames(2)
        assertTrue(seen.zipWithNext().all { (a, b) -> b <= a } && seen.toSet().size >= 8, "follows step by step: $seen")
        assertEquals(9_000.0, ui.state.endMs.toDouble(), 600.0)
        assertEquals(0, ui.state.startMs, "the other handle stays")
        assertNotNull(ui.texted(trimTime(ui.state.endMs)), "the readout follows")
        assertNotNull(ui.described(gifEstimateLine(ui.state.options, video)))
        ui.close()
    }

    /** [pulse] samples from the first frame it leaves 0 to its return to 0. */
    private fun List<Float>.pulse(): List<Float> {
        val from = indexOfFirst { it > 0f }
        val to = (from until size).firstOrNull { this[it] == 0f } ?: size
        return subList(from, to)
    }

    @Test
    fun `picking a width glides the pill over 350 ms and pulses the estimate over 450 ms`() = onEdt {
        val ui = Ui(motion = true)
        val pill = { assertNotNull(ui.tagged("width pill")).boundsInRoot.left }
        val at480 = pill()
        assertEquals(assertNotNull(ui.texted("480 px")).boundsInRoot.left, at480, 14f)
        val target = assertNotNull(ui.texted("320 px")).boundsInRoot.left
        ui.click("320 px")
        assertEquals(320, ui.state.width)
        val xs = mutableListOf<Float>()
        val pulses = mutableListOf<Float>()
        repeat(40) {
            ui.d.frame()
            xs += pill()
            pulses += ui.state.pulse.value
        }
        val moving = xs.indexOfLast { kotlin.math.abs(it - xs.last()) > 0.5f } + 1
        // The floor is loose: with narrower fonts (Windows) the pill travels fewer px, and whole-px bounds look settled
        // a few frames early on the decelerating tail. "A glide, not a jump" below still pins the motion.
        assertTrue(moving in (GIF_PILL_MILLIS / 16 / 2)..(GIF_PILL_MILLIS / 16 + 1), "glides about 350 ms: $moving frames $xs")
        assertTrue(xs.zipWithNext().all { (a, b) -> b <= a + 0.01f }, "only toward the pick: $xs")
        assertTrue(xs.count { it < at480 - 2 && it > xs.last() + 2 } >= 5, "a glide, not a jump: $xs")
        assertEquals(target, xs.last(), 14f)
        val pulse = pulses.pulse()
        assertTrue(kotlin.math.abs(pulse.size - GIF_PULSE_MILLIS / 16) <= 3, "pulses about 450 ms: ${pulse.size} $pulses")
        assertTrue(pulse.max() > 0.9f, "reaches the accent: $pulse")
        assertNotNull(ui.described(gifEstimateLine(GifOptions(0, 18_000, 320, 15), video)))
        // A rate pick pulses it too.
        ui.click("10 fps")
        val again = mutableListOf<Float>()
        repeat(40) {
            ui.d.frame()
            again += ui.state.pulse.value
        }
        assertTrue(again.max() > 0.9f)
        ui.close()
    }

    @Test
    fun `under reduced motion there is no gliding pill and the pulse is a 150 ms fade`() = onEdt {
        val ui = Ui(motion = false)
        assertNull(ui.tagged("width pill"))
        ui.click("640 px")
        val pulses = mutableListOf<Float>()
        repeat(20) {
            ui.d.frame()
            pulses += ui.state.pulse.value
        }
        assertTrue(kotlin.math.abs(pulses.pulse().size - GIF_FADE_MILLIS / 16) <= 3, "$pulses")
        assertEquals(640, ui.state.width)
        ui.close()
    }

    @Test
    fun `Export unfolds the progress row, follows both passes, then folds it and unfolds the done row`() = onEdt {
        val ui = Ui(motion = true)
        val gif = Files.write(tmp.resolve("Pixel 8 Pro #1 ü.gif"), ByteArray(4_300_000))
        ui.click("Export")
        assertEquals(1, ui.export.runs.size)
        assertNotNull(ui.texted("Making the palette…"))
        // The progress row unfolds over 400 ms; the label crossfades to Cancel.
        val heights = mutableListOf<Float>()
        repeat(30) {
            ui.d.frame()
            heights += assertNotNull(ui.tagged("gif progress row")).boundsInRoot.height
        }
        // Emphasized decelerate: most of the height early, the last whole pixel at about 70% of the 400 ms.
        val unfold = heights.indexOfFirst { it == heights.last() } + 2 // the click's own two frames
        assertTrue(unfold in (GIF_ROW_MILLIS / 16 / 2)..(GIF_ROW_MILLIS / 16 + 1), "unfolds within 400 ms: $unfold $heights")
        assertTrue(heights.first() < heights.last() * 0.6f, "from folded: $heights")
        assertTrue(heights.distinct().size >= 6, "an unfold, not a snap: $heights")
        assertTrue(heights.zipWithNext().all { (a, b) -> b >= a }, "only grows: $heights")
        assertNotNull(ui.texted("Cancel"))
        assertNull(ui.texted("Export"))
        ui.export.progress!!(GifExporter.Phase.Palette, 0.5f)
        ui.d.frames(2)
        assertNotNull(ui.texted("10%"))
        ui.export.progress!!(GifExporter.Phase.Encode, 0.5f)
        ui.d.frames(2)
        assertNotNull(ui.texted("Encoding…"))
        assertNotNull(ui.texted("60%"))
        ui.export.gate.complete(Result.success(gif))
        ui.until { ui.texted("Pixel 8 Pro #1 ü.gif") != null }
        // The check pops: 0 to past its size, then back to it.
        val sizes = mutableListOf<Float>()
        repeat(30) {
            ui.d.frame()
            sizes += assertNotNull(ui.described("Done")).boundsInRoot.width
        }
        val full = sizes.last()
        assertTrue(sizes.any { it > full * 1.05f }, "overshoots: $sizes")
        assertTrue(sizes.first() < full, "pops from small: $sizes")
        ui.d.frames(20)
        assertNull(ui.texted("Encoding…"), "the progress row folded")
        assertNotNull(ui.texted("4.1 MB · 480 × 1040 · 18.0 s"))
        assertNotNull(ui.texted("Export again"))
        ui.click("Show in folder")
        assertEquals(listOf(gif), ui.revealed)
        // Export again: the done row folds while the progress row unfolds.
        ui.export.gate = CompletableDeferred()
        ui.click("Export again")
        ui.d.frames(30)
        assertNull(ui.texted("Pixel 8 Pro #1 ü.gif"))
        assertNotNull(ui.texted("Making the palette…"))
        ui.close()
    }

    @Test
    fun `Cancel stops it, says the partial GIF was deleted and goes back to Export`() = onEdt {
        val ui = Ui(motion = true)
        ui.click("Export")
        ui.d.frames(30)
        ui.click("Cancel")
        ui.until { ui.said.isNotEmpty() }
        assertEquals(1, ui.export.cancelled)
        assertEquals(listOf("Export cancelled. The partial GIF was deleted."), ui.said)
        ui.d.frames(40)
        assertNotNull(ui.texted("Export"))
        assertNull(ui.texted("Making the palette…"))
        assertNull(ui.texted("Show in folder"))
        ui.close()
    }

    @Test
    fun `a failure says why and keeps the window ready to try again`() = onEdt {
        val ui = Ui(motion = true)
        ui.click("Export")
        ui.d.frames(4)
        ui.export.gate.complete(Result.failure(GifExportException("moov atom not found")))
        ui.until { ui.said.isNotEmpty() }
        assertEquals(listOf("Couldn't make the GIF: moov atom not found"), ui.said)
        ui.d.frames(40)
        assertNotNull(ui.texted("Export"))
        assertNull(ui.texted("Show in folder"))
        ui.close()
    }

    @Test
    fun `under reduced motion the progress row fades in at its full height`() = onEdt {
        val ui = Ui(motion = false)
        ui.click("Export")
        val heights = mutableListOf<Float>()
        repeat(12) {
            ui.d.frame()
            heights += assertNotNull(ui.tagged("gif progress row")).boundsInRoot.height
        }
        assertEquals(1, heights.toSet().size, "no unfolding: $heights")
        ui.close()
    }

    @Test
    fun `a trim change asks for the preview's frames again once it settles`() = onEdt {
        val ui = Ui(motion = true)
        val before = ui.frames.calls.size
        ui.state.trimStart(3_000)
        ui.d.frames(4)
        ui.state.trimEnd(12_000)
        ui.d.frames(4)
        assertEquals(before, ui.frames.calls.size, "not while it moves")
        ui.d.frames(GIF_PREVIEW_SETTLE_MILLIS / 16 + 2)
        assertEquals(listOf<Number>(3_000L, 12_000L, 9 * 15, GIF_PREVIEW_FRAME_WIDTH), ui.frames.calls.last())
        assertEquals(before + 1, ui.frames.calls.size)
        ui.close()
    }

    private fun tinyBitmap(): ImageBitmap = Bitmap().apply { allocN32Pixels(1, 1) }.asComposeImageBitmap()

    @Test
    fun `frames decoded for a cancelled caller are closed, not dropped`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val made = java.util.Collections.synchronizedList(ArrayList<ImageBitmap>())
            val second = CountDownLatch(1)
            val release = CountDownLatch(1)
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    decodeAll(List(3) { ByteArray(0) }, pool.asCoroutineDispatcher()) {
                        if (made.size == 1) {
                            second.countDown()
                            release.await()
                        }
                        tinyBitmap().also { made += it }
                    }
                }
                assertTrue(second.await(5, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                job.join()
            }
            assertEquals(3, made.size, "the decode finished its frames")
            assertTrue(made.all { it.asSkiaBitmap().isClosed }, "and closed every one")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a frame that fails to decode closes the ones before it`() {
        val made = mutableListOf<ImageBitmap>()
        val failed = runCatching {
            runBlocking {
                decodeAll(List(3) { ByteArray(0) }, EmptyCoroutineContext) {
                    if (made.size == 1) error("bad png")
                    tinyBitmap().also { made += it }
                }
            }
        }
        assertEquals("bad png", failed.exceptionOrNull()?.message)
        assertTrue(made.single().asSkiaBitmap().isClosed)
    }
}
