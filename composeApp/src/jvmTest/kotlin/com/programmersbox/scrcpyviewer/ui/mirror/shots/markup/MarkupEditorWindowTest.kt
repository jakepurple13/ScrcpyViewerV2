package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_SHRINK_IN_PLACE
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_IN_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MIRROR_ZOOM_OUT_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_SLEEP_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.SCREEN_WAKE_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.animateWindowBounds
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.mirror.shrunkAroundCenter
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.ScreenGeometry
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import java.awt.Rectangle
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A window that only records what is done to it. */
private class FakeWindow(initial: Rectangle) : MirrorWindowHandle {
    val sets = mutableListOf<Rectangle>()
    private var current = Rectangle(initial)
    override var bounds: Rectangle
        get() = Rectangle(current)
        set(value) {
            sets += Rectangle(value)
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

/** A virtual clock, 16 ms a frame; [each] runs after every frame. */
private class Frames(val each: () -> Unit = {}) : MonotonicFrameClock {
    var time = 0L
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        time += 16_000_000
        return onFrame(time).also { each() }
    }
}

private object NoScreens : ScreenGeometry {
    override fun screens(): List<ScreenArea> = emptyList()
}

/** One bounds animation asked for. */
private data class Move(val to: Rectangle, val millis: Int, val grow: Boolean)

/**
 * The editor's own window: its size, one window per mirror window, the open and close motion (grown from the Edit or
 * camera button, or shrunk in place, the wake sampled per frame, reduced-motion fades), the close paths and the mirror
 * window's close waiting on it. The native window is a fake; motion tests pass `motion = true` themselves (CI's macOS
 * and Windows runners report reduce motion on).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MarkupEditorWindowTest {
    private val scheduler = TestCoroutineScheduler()
    private val timerScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val thumbnail = ScreenshotThumbnailState(timerScope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("caps", "Pixel 8 Pro.png")
    private val screen = Rectangle(0, 25, 1440, 850)
    private val editButton = Rectangle(1200, 700, 32, 32)
    private val cameraButton = Rectangle(860, 420, 48, 48)
    private val target = Rectangle(500, 60, 440, 800)

    @AfterTest
    fun tearDown() {
        thumbnail.dispose()
        timerScope.cancel()
    }

    /** [actual] frames (16 ms) is about [millis] long: within a frame. */
    private fun assertFrames(millis: Int, actual: Int, message: String? = null) {
        assertTrue(kotlin.math.abs(millis / 16 - actual) <= 1, "${message ?: ""}: $actual frames for $millis ms")
    }

    private fun solid(color: Color = Color(0xFFDDDDDD), w: Int = 400, h: Int = 800): ImageBitmap =
        Bitmap().apply { allocN32Pixels(w, h); erase(color.toArgb()) }.asComposeImageBitmap()

    private fun shown(): Shot {
        thumbnail.show(file, solid())
        return thumbnail.shown.value!!
    }

    // Size and title.

    @Test
    fun `the title names the device`() {
        assertEquals("Edit screenshot · Pixel 8 Pro", editorWindowTitle("Pixel 8 Pro"))
    }

    @Test
    fun `it opens at the screenshot's aspect ratio, the canvas 80 percent of the screen's height, centred`() {
        val r = editorWindowBounds(1600, 2560, screen)
        val canvasH = r.height - EDITOR_CHROME_HEIGHT
        val canvasW = r.width - EDITOR_CHROME_WIDTH
        assertEquals(screen.height * 0.8f, canvasH.toFloat(), 1f)
        assertEquals(1600f / 2560f, canvasW.toFloat() / canvasH, 0.01f)
        assertEquals(screen.x + (screen.width - r.width) / 2, r.x)
        assertEquals(screen.y + (screen.height - r.height) / 2, r.y)
    }

    @Test
    fun `a wide screenshot is held to the screen's width, and a small screen gives the minimum size`() {
        val wide = editorWindowBounds(2340, 1080, screen)
        assertTrue(wide.width <= screen.width, "fits: $wide")
        assertEquals(2340f / 1080f, (wide.width - EDITOR_CHROME_WIDTH).toFloat() / (wide.height - EDITOR_CHROME_HEIGHT), 0.02f)
        val tiny = editorWindowBounds(1080, 2340, Rectangle(0, 0, 500, 400))
        assertEquals(420, tiny.width)
        assertTrue(tiny.height <= 400, "never past the screen: $tiny")
        val small = editorWindowBounds(100, 100, Rectangle(0, 0, 1920, 400))
        assertEquals(Rectangle((1920 - 420) / 2, 0, 420, 400), small)
    }

    // One editor per mirror window.

    @Test
    fun `Edit opens one editor, holds the thumbnail's timer and marks only that shot as edited`() {
        val editor = MarkupEditorWindowState(thumbnail)
        val shot = shown()
        editor.open(shot, editButton)
        assertTrue(editor.isOpen)
        assertEquals(editButton, editor.origin)
        assertTrue(thumbnail.held.value)
        assertEquals(shot.id, thumbnail.editingShotId.value)
        // Two fast presses, or Edit again: still the one window, brought to the front.
        editor.open(shot, editButton)
        editor.open(Shot(file, solid(), 99), cameraButton)
        assertTrue(editor.shot === shot)
        assertEquals(editButton, editor.origin)
        assertEquals(2, editor.raises)
        editor.closed(shot)
        assertFalse(editor.isOpen)
        assertFalse(thumbnail.held.value)
        assertNull(thumbnail.editingShotId.value)
    }

    @Test
    fun `a press while the window closes does nothing, and a stale window going does not close the next one`() {
        val editor = MarkupEditorWindowState(thumbnail)
        val shot = shown()
        editor.open(shot, editButton)
        editor.leave()
        editor.open(shot, editButton)
        assertEquals(0, editor.raises)
        editor.closed(shot)
        val next = Shot(file, solid(), 77)
        editor.open(next, cameraButton)
        editor.closed(shot) // the first window disposed late
        assertTrue(editor.shot === next)
        assertEquals(cameraButton, editor.origin)
    }

    // Opening motion.

    private class Run(val window: FakeWindow) {
        val wake = Animatable(0f)
        val wakes = mutableListOf<Float>()
        val alphas = mutableListOf<Float>()
        val moves = mutableListOf<Move>()
        var shows = 0
        val frames = Frames { wakes += wake.value }
        val animate: AnimateBounds = { w, to, millis, grow ->
            moves += Move(to, millis, grow)
            animateWindowBounds(w, to, millis, grow, NoScreens)
        }

        fun open(plan: EditorWindowPlan) = runBlocking {
            withContext(frames) {
                openEditorWindow(window, plan, wake, show = { shows++ }, alpha = { alphas += it }, animate)
            }
        }

        fun close(full: Boolean, into: Rectangle?) = runBlocking {
            withContext(frames) { closeEditorWindow(window, full, floating = true, { into }, wake, { alphas += it }, animate) }
        }
    }

    /** The wake's samples from its first move off [from] to its first arrival at [to]. */
    private fun List<Float>.ramp(from: Float, to: Float): List<Float> {
        val start = indexOfFirst { it != from }
        val end = indexOfFirst { it == to }
        return subList(start, end + 1)
    }

    @Test
    fun `from the Edit button it grows out of the button over 380 ms, then the screenshot switches on`() {
        val run = Run(FakeWindow(target))
        run.open(EditorWindowPlan(from = editButton, to = target, full = true))
        assertEquals(1, run.shows)
        assertEquals(editButton, run.window.sets.first(), "shown at the button")
        assertEquals(listOf(Move(target, MIRROR_ZOOM_IN_MILLIS, grow = true)), run.moves)
        val growth = run.window.sets.drop(1)
        assertTrue(growth.size >= MIRROR_ZOOM_IN_MILLIS / 16 - 1, "one setBounds per frame: ${growth.size}")
        assertTrue(growth.zipWithNext().all { (a, b) -> b.width >= a.width && b.height >= a.height }, "only grows: $growth")
        assertEquals(target, growth.last())
        assertTrue(run.alphas.isEmpty(), "no fade")
        // The screenshot stays off while the window grows, then wakes on the emphasized-decelerate curve.
        val growFrames = growth.size
        assertTrue(run.wakes.take(growFrames).all { it == 0f })
        val ramp = run.wakes.ramp(0f, 1f)
        assertFrames(SCREEN_WAKE_MILLIS, ramp.size, "over 320 ms: $ramp")
        assertTrue(ramp.zipWithNext().all { (a, b) -> b >= a }, "only rises: $ramp")
        assertTrue(ramp.count { it > 0.05f && it < 0.95f } >= 5, "a wake, not a snap: $ramp")
        assertTrue(ramp[ramp.size / 4] > 0.5f, "decelerates: most of it early: $ramp")
    }

    @Test
    fun `from the camera button it grows out of that button`() {
        val run = Run(FakeWindow(target))
        run.open(EditorWindowPlan(from = cameraButton, to = target, full = true))
        assertEquals(cameraButton, run.window.sets.first())
        assertEquals(Move(target, MIRROR_ZOOM_IN_MILLIS, grow = true), run.moves.single())
        assertEquals(1f, run.wake.value)
    }

    @Test
    fun `without a button it appears at its size, and the screenshot still switches on`() {
        val run = Run(FakeWindow(target))
        run.open(EditorWindowPlan(from = null, to = target, full = true))
        assertTrue(run.moves.isEmpty())
        assertTrue(run.window.sets.isEmpty())
        assertEquals(1, run.shows)
        val ramp = run.wakes.ramp(0f, 1f)
        assertFrames(SCREEN_WAKE_MILLIS, ramp.size, "over 320 ms: $ramp")
    }

    @Test
    fun `under reduced motion it appears at its size and fades in, then the screenshot fades in, 150 ms each`() {
        val run = Run(FakeWindow(target))
        run.open(EditorWindowPlan(from = null, to = target, full = false))
        assertTrue(run.moves.isEmpty(), "no growing")
        assertTrue(run.window.sets.isEmpty())
        assertEquals(0f, run.alphas.first(), "see-through before it shows")
        val fade = run.alphas.drop(2) // the see-through start, and the fade's first frame at 0
        assertFrames(EDITOR_FADE_MILLIS, fade.size, "150 ms: $fade")
        assertTrue(fade.zipWithNext().all { (a, b) -> b >= a } && fade.last() == 1f, "fades in: $fade")
        val ramp = run.wakes.ramp(0f, 1f)
        assertFrames(EDITOR_FADE_MILLIS, ramp.size, "the screenshot fades in, 150 ms: $ramp")
        assertTrue(run.wakes.take(fade.size).all { it == 0f }, "after the window's fade: ${run.wakes}")
    }

    // Closing motion.

    @Test
    fun `it closes switching the screenshot off over 200 ms, then shrinks into the thumbnail over 280 ms`() {
        val thumb = Rectangle(1250, 640, 150, 300)
        val run = Run(FakeWindow(target))
        runBlocking { run.wake.snapTo(1f) }
        run.close(full = true, into = thumb)
        val ramp = run.wakes.ramp(1f, 0f)
        assertFrames(SCREEN_SLEEP_MILLIS, ramp.size, "200 ms: $ramp")
        assertTrue(ramp.zipWithNext().all { (a, b) -> b <= a }, "only falls: $ramp")
        assertTrue(ramp[ramp.size / 4] > 0.85f, "accelerates: slow at first: $ramp")
        assertEquals(listOf(Move(thumb, MIRROR_ZOOM_OUT_MILLIS, grow = false)), run.moves)
        assertEquals(thumb, run.window.sets.last())
        assertTrue(run.window.sets.size >= MIRROR_ZOOM_OUT_MILLIS / 16 - 1, "per frame: ${run.window.sets.size}")
        assertTrue(run.alphas.isEmpty(), "into the thumbnail: no fade")
    }

    @Test
    fun `with no thumbnail on screen it shrinks to 90 percent where it is and fades`() {
        val run = Run(FakeWindow(target))
        runBlocking { run.wake.snapTo(1f) }
        run.close(full = true, into = null)
        val inPlace = shrunkAroundCenter(target, MIRROR_SHRINK_IN_PLACE)
        assertEquals(listOf(Move(inPlace, MIRROR_ZOOM_OUT_MILLIS, grow = false)), run.moves)
        assertEquals(inPlace, run.window.sets.last())
        assertTrue(run.alphas.size >= MIRROR_ZOOM_OUT_MILLIS / 16 - 1, "fades along: ${run.alphas}")
        assertTrue(run.alphas.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0f, run.alphas.last())
    }

    @Test
    fun `under reduced motion it closes fading the screenshot, then the window, 150 ms each`() {
        val run = Run(FakeWindow(target))
        runBlocking { run.wake.snapTo(1f) }
        run.close(full = false, into = Rectangle(1250, 640, 150, 300))
        assertTrue(run.moves.isEmpty(), "no shrinking")
        assertTrue(run.window.sets.isEmpty())
        assertFrames(EDITOR_FADE_MILLIS, run.wakes.ramp(1f, 0f).size)
        assertFrames(EDITOR_FADE_MILLIS, run.alphas.drop(1).size, "${run.alphas}") // its first frame is at 1
        assertEquals(0f, run.alphas.last())
    }

    // The window's content, its close paths and the mirror window's close.

    private inner class Window(full: Boolean = true, val saveWorks: Boolean = true) {
        val editor = MarkupEditorWindowState(thumbnail)
        val window = FakeWindow(target)
        val moves = mutableListOf<Move>()
        val saves = mutableListOf<List<Mark>>()
        var thumbAt by mutableStateOf<Rectangle?>(Rectangle(1250, 640, 150, 300))
        var state: MarkupEditorState? = null
        val plan = EditorWindowPlan(if (full) editButton else null, target, full)
        val mirrorScope = CoroutineScope(Dispatchers.Unconfined)
        var mirrorClosed = 0
        val d = SceneDriver(ImageComposeScene(500, 820, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    val shot = editor.shot
                    if (shot != null) key(shot.id) {
                        val s = rememberMarkupEditorState(shot)
                        state = s
                        MarkupEditorWindowBody(
                            editor, shot, s, window, plan, motion = true,
                            show = {}, alpha = {}, floating = { true }, thumbnail = { thumbAt },
                            onCopy = { _, _ -> true },
                            onSave = { original, marks ->
                                saves += marks
                                if (saveWorks) thumbnail.showEdited(Paths.get("caps", "Pixel 8 Pro-edited.png"), solid(Color.Blue), original.original)
                                saveWorks
                            },
                            leases = thumbnail,
                            animateBounds = { w, to, millis, grow ->
                                moves += Move(to, millis, grow)
                                w.bounds = to
                            },
                        )
                    }
                }
            }
        }).also { it.frames(2) }

        fun open(shot: Shot = shown()): Shot {
            editor.open(shot, editButton)
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

        fun described(text: String): SemanticsNode? = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }

        fun click(node: SemanticsNode) {
            val at = node.boundsInRoot.center
            d.scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            d.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
            d.frames(2)
        }

        fun mark() {
            val r = assertNotNull(described("Screenshot")).boundsInRoot
            fun at(fx: Float, fy: Float) = Offset(r.left + r.width * fx, r.top + r.height * fy)
            d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f, 0.2f), type = PointerType.Mouse)
            d.scene.sendPointerEvent(PointerEventType.Press, at(0.2f, 0.2f), type = PointerType.Mouse, button = PointerButton.Primary)
            d.frame()
            for (i in 1..6) {
                d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f + 0.06f * i, 0.2f + 0.04f * i), type = PointerType.Mouse)
                d.frame()
            }
            d.scene.sendPointerEvent(PointerEventType.Release, at(0.56f, 0.44f), type = PointerType.Mouse, button = PointerButton.Primary)
            d.frames(2)
            assertTrue(assertNotNull(state).dirty)
        }

        fun esc() {
            d.scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            d.scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            d.frames(2)
        }

        /** The mirror window's close button. */
        fun closeMirror() {
            editor.closeThen(mirrorScope) { mirrorClosed++ }
            d.frames(2)
        }

        fun close() {
            d.scene.close()
            mirrorScope.cancel()
        }
    }

    @Test
    fun `Esc with no marks closes it into the thumbnail, and the timer restarts`() = onEdt {
        val w = Window()
        val shot = w.open()
        assertNotNull(w.texted(EDITOR_HINT))
        assertEquals(Move(target, MIRROR_ZOOM_IN_MILLIS, grow = true), w.moves.first())
        w.esc()
        assertTrue(w.editor.leaving)
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        assertEquals(Move(w.thumbAt!!, MIRROR_ZOOM_OUT_MILLIS, grow = false), w.moves.last())
        assertNull(w.texted(EDITOR_HINT))
        assertFalse(thumbnail.held.value)
        assertNull(thumbnail.editingShotId.value)
        assertTrue(thumbnail.shown.value === shot)
        w.close()
    }

    @Test
    fun `the close button with no marks closes it, with marks it asks first`() = onEdt {
        val w = Window()
        w.open()
        // The window's onCloseRequest.
        assertTrue(assertNotNull(w.state).requestClose())
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        w.open()
        w.mark()
        assertFalse(assertNotNull(w.state).requestClose(), "vetoed: it stays open")
        w.d.frames(20)
        assertNotNull(w.texted("Discard your marks?"))
        assertTrue(w.editor.isOpen)
        w.click(assertNotNull(w.texted("Keep editing")))
        w.d.frames(20)
        assertTrue(w.editor.isOpen)
        assertFalse(w.editor.leaving)
        w.close()
    }

    @Test
    fun `Esc with marks asks, Discard closes it, a kept shot's bitmap is closed once the window has gone`() = onEdt {
        val w = Window()
        val bitmap = solid()
        val kept = assertNotNull(thumbnail.keep(file, bitmap, thumbnail.begin()))
        w.thumbAt = null // Open editor mode: no thumbnail on screen
        w.open(kept)
        w.mark()
        w.esc()
        w.d.frames(20)
        assertNotNull(w.texted("Discard your marks?"))
        assertTrue(w.editor.isOpen)
        w.click(assertNotNull(w.texted("Discard")))
        assertFalse(bitmap.asSkiaBitmap().isClosed, "drawn while the window closes")
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        assertEquals(Move(shrunkAroundCenter(target, MIRROR_SHRINK_IN_PLACE), MIRROR_ZOOM_OUT_MILLIS, grow = false), w.moves.last())
        assertTrue(bitmap.asSkiaBitmap().isClosed, "released with the window")
        w.close()
    }

    @Test
    fun `Save closes it into the thumbnail, which shows the edited file`() = onEdt {
        val w = Window()
        w.open()
        w.mark()
        w.click(assertNotNull(w.texted("Save")))
        w.d.frames(40)
        assertEquals(1, w.saves.size)
        assertFalse(w.editor.isOpen)
        val edited = assertNotNull(thumbnail.shown.value)
        assertTrue(edited.edited)
        assertEquals(Move(w.thumbAt!!, MIRROR_ZOOM_OUT_MILLIS, grow = false), w.moves.last())
        assertNull(thumbnail.editingShotId.value)
        w.close()
    }

    @Test
    fun `a failed Save keeps it open with its marks`() = onEdt {
        val w = Window(saveWorks = false)
        w.open()
        w.mark()
        w.click(assertNotNull(w.texted("Save")))
        w.d.frames(40)
        assertTrue(w.editor.isOpen)
        assertTrue(assertNotNull(w.state).dirty)
        w.close()
    }

    @Test
    fun `under reduced motion the screenshot fades in rather than switching on`() = onEdt {
        val w = Window(full = false)
        w.open()
        assertTrue(w.moves.isEmpty(), "no grow")
        assertNotNull(w.texted(EDITOR_HINT))
        w.esc()
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        assertTrue(w.moves.isEmpty(), "no shrink")
        w.close()
    }

    // The mirror window closing.

    @Test
    fun `the mirror window closes at once without an editor`() = onEdt {
        val w = Window()
        w.closeMirror()
        assertEquals(1, w.mirrorClosed)
        w.close()
    }

    @Test
    fun `the mirror window closing closes a clean editor first, then itself`() = onEdt {
        val w = Window()
        w.open()
        w.closeMirror()
        assertEquals(0, w.mirrorClosed, "waits for the editor to go")
        assertTrue(w.editor.leaving)
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        assertEquals(1, w.mirrorClosed)
        w.close()
    }

    @Test
    fun `with marks the mirror window's close asks in the editor, Keep editing keeps both open`() = onEdt {
        val w = Window()
        w.open()
        w.mark()
        val raises = w.editor.raises
        w.closeMirror()
        w.d.frames(20)
        assertNotNull(w.texted("Discard your marks?"))
        assertEquals(raises + 1, w.editor.raises, "the editor comes to the front to ask")
        // Pressed again while it asks: only brought to the front again.
        w.closeMirror()
        assertEquals(raises + 2, w.editor.raises)
        w.click(assertNotNull(w.texted("Keep editing")))
        w.d.frames(40)
        assertTrue(w.editor.isOpen)
        assertEquals(0, w.mirrorClosed)
        // Asked again later, Discard closes both.
        w.closeMirror()
        w.d.frames(20)
        w.click(assertNotNull(w.texted("Discard")))
        assertEquals(0, w.mirrorClosed, "the editor goes first")
        w.d.frames(40)
        assertFalse(w.editor.isOpen)
        assertEquals(1, w.mirrorClosed)
        w.close()
    }

    @Test
    fun `Keep editing from Esc does not cancel a later mirror close`() = onEdt {
        val w = Window()
        w.open()
        w.mark()
        w.esc()
        w.d.frames(20)
        w.click(assertNotNull(w.texted("Keep editing")))
        w.d.frames(20)
        w.closeMirror()
        w.d.frames(20)
        w.click(assertNotNull(w.texted("Discard")))
        w.d.frames(40)
        assertEquals(1, w.mirrorClosed)
        w.close()
    }
}
