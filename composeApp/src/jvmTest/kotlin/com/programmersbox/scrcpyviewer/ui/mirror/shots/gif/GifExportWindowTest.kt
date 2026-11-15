package com.programmersbox.scrcpyviewer.ui.mirror.shots.gif

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_SHRINK_IN_PLACE
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_IN_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_OUT_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_SLEEP_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_WAKE_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shots.markup.EditorWindowPlan
import com.programmersbox.scrcpyviewer.ui.mirror.shrunkAroundCenter
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import java.awt.Rectangle
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A window that only records what is done to it. */
private class FakeWindow(initial: Rectangle) : MirrorWindowHandle {
    private var current = Rectangle(initial)
    override var bounds: Rectangle
        get() = Rectangle(current)
        set(value) {
            current = Rectangle(value)
        }
    override val size: IntSize get() = IntSize(current.width, current.height)
    override val isShowing = true
    override val focused: StateFlow<Boolean> = MutableStateFlow(true)
    override val focusEvents: SharedFlow<Boolean> = MutableSharedFlow()
    override val nsWindow: Pointer? = null
    override fun toFront() = Unit
    override fun screen(): ScreenArea? = null
    override fun contentOrigin(): IntOffset = IntOffset(current.x, current.y)
}

/** One bounds animation asked for. */
private data class Move(val to: Rectangle, val millis: Int, val grow: Boolean)

/**
 * The Export GIF window: its size and title, one window per recording thumbnail (focus again), the thumbnail held and
 * marked while it is open, the grow / wake / sleep / shrink, closing while exporting (cancelled, said) and with the
 * mirror window (cancelled, cleaned up, said nothing). The native window is a fake; motion tests pass `motion = true`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GifExportWindowTest {
    private val scheduler = TestCoroutineScheduler()
    private val timerScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val thumbnail = ScreenshotThumbnailState(timerScope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("caps", "Pixel 8 Pro #2 ü.mp4")
    private val video = RecordingInfo(18_000, 1080, 2340)
    private val screen = Rectangle(0, 25, 1440, 850)
    private val gifButton = Rectangle(1240, 760, 40, 40)
    private val target = Rectangle(387, 180, 665, 540)

    @AfterTest
    fun tearDown() {
        thumbnail.dispose()
        timerScope.cancel()
    }

    private fun solid(w: Int = 108, h: Int = 234): ImageBitmap =
        Bitmap().apply { allocN32Pixels(w, h); erase(Color(0xFF336699).toArgb()) }.asComposeImageBitmap()

    private fun recording(): Shot {
        thumbnail.showRecording(file, solid(), video)
        return thumbnail.shown.value!!
    }

    @Test
    fun `the title names the device`() {
        assertEquals("Export GIF · Pixel 8 Pro", gifWindowTitle("Pixel 8 Pro"))
    }

    @Test
    fun `it opens centred, as tall as set and as wide as the preview at the clip's shape plus the controls`() {
        val portrait = gifWindowBounds(1080, 2340, screen)
        val previewW = Math.round((GIF_WINDOW_HEIGHT - GIF_CHROME_HEIGHT) * 1080f / 2340f)
        assertEquals(Rectangle(screen.x + (screen.width - portrait.width) / 2, screen.y + (screen.height - GIF_WINDOW_HEIGHT) / 2, previewW + GIF_SIDE_WIDTH + GIF_CHROME_WIDTH, GIF_WINDOW_HEIGHT), portrait)
        assertEquals(target, portrait)
        val landscape = gifWindowBounds(2340, 1080, screen)
        assertEquals(GIF_PREVIEW_MAX_WIDTH + GIF_SIDE_WIDTH + GIF_CHROME_WIDTH, landscape.width, "the preview is held to its widest")
        val tiny = gifWindowBounds(1080, 2340, Rectangle(0, 0, 500, 400))
        assertEquals(Rectangle(0, 0, 500, 400), tiny, "never past the screen")
        assertTrue(gifWindowBounds(100, 10_000, screen).width >= GIF_WINDOW_MIN_SIZE.width.value.toInt())
    }

    // One window per recording thumbnail.

    @Test
    fun `Export GIF opens one window per recording, holds the timer and marks it, again only focuses it`() {
        val windows = GifExportWindowsState(thumbnail)
        val shot = recording()
        windows.open(shot, gifButton)
        assertEquals(1, windows.windows.size)
        val w = windows.windows.single()
        assertEquals(gifButton, w.origin)
        assertEquals(video, w.video)
        assertTrue(thumbnail.held.value)
        assertEquals(setOf(shot.id), thumbnail.gifShotIds.value)
        windows.open(shot, Rectangle(0, 0, 9, 9))
        windows.open(shot, gifButton)
        assertEquals(1, windows.windows.size)
        assertEquals(2, w.raises)
        // A screenshot has no GIF window; another recording gets its own.
        windows.open(Shot(file, solid(), 99), gifButton)
        assertEquals(1, windows.windows.size)
        windows.open(Shot(file, solid(), 100, video = video), gifButton)
        assertEquals(2, windows.windows.size)
        windows.closed(w)
        windows.closed(w)
        assertEquals(setOf(100L), thumbnail.gifShotIds.value)
        windows.closed(windows.windows.single())
        assertFalse(thumbnail.held.value)
        assertTrue(thumbnail.gifShotIds.value.isEmpty())
    }

    @Test
    fun `a press while it closes does not raise it, and closeAll closes every window quietly`() {
        val windows = GifExportWindowsState(thumbnail)
        val shot = recording()
        windows.open(shot, gifButton)
        windows.open(Shot(file, solid(), 100, video = video), null)
        val w = windows.windows.first()
        w.leave()
        windows.open(shot, gifButton)
        assertEquals(0, w.raises)
        windows.closeAll()
        assertTrue(windows.windows.all { it.leaving })
        assertTrue(windows.windows.last().quiet)
    }

    // The window's content, its motion and its close paths.

    private inner class Window(val full: Boolean = true) {
        val windows = GifExportWindowsState(thumbnail)
        val events = mutableListOf<String>()
        val moves = mutableListOf<Move>()
        val notes = mutableListOf<String>()
        val export = FakeExport()
        val native = FakeWindow(target)
        var thumbAt by mutableStateOf<Rectangle?>(Rectangle(1250, 640, 150, 300))
        var composed by mutableStateOf(true)
        val d = SceneDriver(ImageComposeScene(target.width, target.height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    if (composed) windows.windows.toList().forEach { w ->
                        key(w) {
                            GifExportWindowBody(
                                windows, w, native,
                                EditorWindowPlan(if (full) w.origin else null, target, full),
                                motion = full,
                                show = {}, alpha = {}, floating = { true },
                                minimum = { events += "min $it" },
                                thumbnail = { thumbAt },
                                export = export.fn,
                                frames = FakeFrames(),
                                onReveal = {},
                                notify = { notes += it },
                                leases = thumbnail,
                                animateBounds = { win, to, millis, grow ->
                                    moves += Move(to, millis, grow)
                                    events += if (grow) "grow" else "shrink"
                                    win.bounds = to
                                },
                                decode = EmptyCoroutineContext,
                            )
                        }
                    }
                }
            }
        }).also { it.frames(2) }

        fun open(): Shot {
            val shot = recording()
            windows.open(shot, gifButton)
            d.frames(60)
            return shot
        }

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

        fun described(text: String): SemanticsNode? = nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }

        fun click(text: String) {
            val at = assertNotNull(texted(text), text).boundsInRoot.center
            d.scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            d.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.frames(2)
        }

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
    fun `it grows out of the GIF button over 380 ms, then the preview switches on over 320 ms`() = onEdt {
        val w = Window()
        val shot = recording()
        w.windows.open(shot, gifButton)
        val heights = mutableListOf<Float>()
        repeat(60) {
            w.d.frame()
            w.described("Preview")?.let { heights += it.boundsInRoot.height }
        }
        assertEquals(Move(target, MIRROR_ZOOM_IN_MILLIS, grow = true), w.moves.first())
        assertEquals(listOf("grow", "min true"), w.events, "the minimum only once it has grown")
        val full = heights.last()
        val ramp = heights.dropWhile { it < full * 0.01f }.let { r -> r.subList(0, r.indexOfFirst { it == full } + 1) }
        assertTrue(kotlin.math.abs(ramp.size - SCREEN_WAKE_MILLIS / 16) <= 3, "wakes about 320 ms: ${ramp.size} $heights")
        assertTrue(heights.first() < full * 0.05f, "a line first: $heights")
        assertNotNull(w.texted("Export GIF"))
        w.close()
    }

    @Test
    fun `Close sleeps the preview, shrinks it into the thumbnail and the timer restarts`() = onEdt {
        val w = Window()
        val shot = w.open()
        assertTrue(thumbnail.held.value)
        w.click("Close")
        assertTrue(w.windows.windows.single().leaving)
        val heights = mutableListOf<Float>()
        repeat(SCREEN_SLEEP_MILLIS / 16 + 4) {
            w.d.frame()
            w.described("Preview")?.let { heights += it.boundsInRoot.height }
        }
        assertTrue(heights.zipWithNext().all { (a, b) -> b <= a } && heights.last() < heights.first() * 0.05f, "sleeps to a line: $heights")
        w.d.frames(40)
        assertTrue(w.windows.windows.isEmpty())
        assertEquals(Move(w.thumbAt!!, MIRROR_ZOOM_OUT_MILLIS, grow = false), w.moves.last())
        assertEquals(listOf("grow", "min true", "min false", "shrink"), w.events)
        assertFalse(thumbnail.held.value)
        assertTrue(thumbnail.gifShotIds.value.isEmpty())
        assertTrue(thumbnail.shown.value === shot)
        w.close()
    }

    @Test
    fun `with no thumbnail on screen it shrinks in place`() = onEdt {
        val w = Window()
        w.open()
        w.thumbAt = null
        w.windows.windows.single().leave()
        w.d.frames(60)
        assertEquals(Move(shrunkAroundCenter(target, MIRROR_SHRINK_IN_PLACE), MIRROR_ZOOM_OUT_MILLIS, grow = false), w.moves.last())
        assertTrue(w.windows.windows.isEmpty())
        w.close()
    }

    @Test
    fun `under reduced motion it neither grows nor shrinks`() = onEdt {
        val w = Window(full = false)
        w.open()
        assertTrue(w.moves.isEmpty())
        assertNotNull(w.texted("Export GIF"))
        w.windows.windows.single().leave()
        w.d.frames(40)
        assertTrue(w.moves.isEmpty())
        assertTrue(w.windows.windows.isEmpty())
        w.close()
    }

    @Test
    fun `closing it while exporting cancels the export and the mirror window says the partial GIF was deleted`() = onEdt {
        val w = Window()
        w.open()
        w.click("Export")
        assertEquals(1, w.export.runs.size)
        w.windows.windows.single().leave()
        w.until { w.windows.windows.isEmpty() }
        assertEquals(1, w.export.cancelled)
        assertEquals(listOf("Export cancelled. The partial GIF was deleted."), w.notes)
        w.close()
    }

    @Test
    fun `the mirror window closing closes it, cancels the export and says nothing`() = onEdt {
        val w = Window()
        w.open()
        w.click("Export")
        w.windows.closeAll()
        w.until { w.windows.windows.isEmpty() }
        assertEquals(1, w.export.cancelled)
        assertTrue(w.notes.isEmpty())
        w.close()
    }

    @Test
    fun `the mirror window's composition going while it exports cancels the export and lets the window go`() = onEdt {
        val w = Window()
        w.open()
        w.click("Export")
        assertEquals(0, w.export.cancelled)
        w.composed = false // the mirror window's composition is gone: these windows go with it, at once
        w.d.frames(2)
        assertEquals(1, w.export.cancelled)
        assertTrue(w.windows.windows.isEmpty())
        assertFalse(thumbnail.held.value)
        w.close()
    }
}
