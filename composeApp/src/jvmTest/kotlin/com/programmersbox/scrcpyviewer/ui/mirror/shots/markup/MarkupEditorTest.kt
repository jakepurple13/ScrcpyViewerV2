package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.shots.COPIED_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's window content: toolbar, canvas fitted at the screenshot's aspect ratio, footer, tools, swatches,
 * drawing, undo, Copy, Save, closing and the discard prompt, Esc and Ctrl/Cmd+Z, the wake, and each motion row
 * sampled frame by frame (16 ms). Then the temporary host over the mirror window. Motion tests pass `motion = true`
 * themselves: CI's macOS and Windows runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MarkupEditorTest {
    private val scheduler = TestCoroutineScheduler()
    private val timerScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val leases = ScreenshotThumbnailState(timerScope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("caps", "Pixel 8 Pro.png")
    private val width = 1000
    private val height = 800
    private val gray = Color(0xFFDDDDDD)

    @AfterTest
    fun tearDown() {
        leases.dispose()
        timerScope.cancel()
    }

    private fun solid(color: Color, w: Int = 400, h: Int = 800): ImageBitmap =
        Bitmap().apply { allocN32Pixels(w, h); erase(color.toArgb()) }.asComposeImageBitmap()

    /** The window content at [w] x [h] (changeable: the window resizes), in a larger scene. */
    private inner class Content(
        motion: Boolean,
        w: Int = width,
        h: Int = height,
        bitmap: ImageBitmap = solid(gray),
        val copyWorks: Boolean = true,
        val saveWorks: Boolean = true,
    ) {
        val shot: Shot = assertNotNull(leases.keep(file, bitmap, leases.begin()))
        var w by mutableIntStateOf(w)
        var h by mutableIntStateOf(h)
        var shown by mutableStateOf(true)
        var wake by mutableFloatStateOf(1f)
        var active by mutableStateOf(true)
        val copies = mutableListOf<List<Mark>>()
        val saves = mutableListOf<List<Mark>>()
        var closes = 0
        var state: MarkupEditorState? = null
        val d = SceneDriver(ImageComposeScene(1400, 1000, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                // Not straight in the Surface: it would stretch the content to the scene (min constraints).
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(this@Content.w.dp, this@Content.h.dp)) {
                        if (shown) {
                            val s = rememberMarkupEditorState(shot)
                            state = s
                            MarkupEditorContent(
                                shot, motion,
                                onCopy = { _, marks -> copies += marks; copyWorks },
                                onSave = { _, marks -> saves += marks; saveWorks },
                                onClose = { closes++ },
                                leases = leases,
                                modifier = Modifier.fillMaxSize(),
                                state = s,
                                wake = { wake },
                                active = active,
                            )
                        }
                    }
                    }
                }
            }
        }).also { it.frames(10) }

        fun requestClose(): Boolean = assertNotNull(state).requestClose().also { d.frames(2) }

        fun close() = d.scene.close()
    }

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

    private fun SceneDriver.texted(text: String): SemanticsNode? = nodes().firstOrNull { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun SceneDriver.tagged(tag: String): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    private fun SemanticsNode.selected(): Boolean = config.getOrNull(SemanticsProperties.Selected) == true

    private fun SemanticsNode.enabled(): Boolean = config.getOrNull(SemanticsProperties.Disabled) == null

    /** The Undo button (the clickable node around the icon). */
    private fun SceneDriver.undoButton(): SemanticsNode {
        val icon = assertNotNull(described("Undo"))
        var n: SemanticsNode? = icon.parent
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) n = n.parent
        return assertNotNull(n)
    }

    private fun SceneDriver.click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(2)
    }

    private fun SceneDriver.click(node: SemanticsNode) = click(node.boundsInRoot.center)

    private fun SceneDriver.canvas(): Rect = assertNotNull(described("Screenshot")).boundsInRoot

    /** Drags on the canvas from [from] to [to], fractions of its size. */
    private fun SceneDriver.drawOn(from: Offset, to: Offset, steps: Int = 8) {
        val r = canvas()
        fun at(f: Offset) = Offset(r.left + r.width * f.x, r.top + r.height * f.y)
        scene.sendPointerEvent(PointerEventType.Move, at(from), type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at(from), type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        for (i in 1..steps) {
            val f = Offset(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
            scene.sendPointerEvent(PointerEventType.Move, at(f), type = PointerType.Mouse)
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, at(to), type = PointerType.Mouse, button = PointerButton.Primary)
        frames(2)
    }

    private fun SceneDriver.key(key: Key, meta: Boolean = false, ctrl: Boolean = false): Boolean {
        val down = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = meta, isCtrlPressed = ctrl))
        val up = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isMetaPressed = meta, isCtrlPressed = ctrl))
        frames(2)
        return down && up
    }

    // Layout.

    @Test
    fun `the toolbar reads Pen, Arrow, Box, Pixelate, Undo, the four swatches, then Save and Copy at its right`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val order = listOf("Pen", "Arrow", "Box", "Pixelate", "Undo", "Red", "Yellow", "Blue", "Black")
            .map { assertNotNull(d.described(it), it).boundsInRoot } +
            listOf("Save", "Copy").map { assertNotNull(d.texted(it), it).boundsInRoot }
        assertTrue(order.zipWithNext().all { (a, b) -> a.right <= b.left + 0.5f }, "left to right: $order")
        val row = order.first().center.y
        assertTrue(order.all { abs(it.center.y - row) < 4f }, "one row: $order")
        // Save and Copy sit at the right, past a gap after the swatches.
        assertTrue(order.last().right > width - 40f, "Copy at the right edge: ${order.last()}")
        assertTrue(order[9].left - order[8].right > 100f, "a spacer before Save: $order")
        // Above the canvas; no title and no Close button in the content (the window has them).
        assertTrue(order.all { it.bottom <= d.canvas().top }, "above the canvas")
        assertNull(d.texted("Close"))
        assertNull(d.texted("Edit screenshot"))
        assertTrue(d.described("Pen")!!.selected())
        assertTrue(listOf("Arrow", "Box", "Pixelate").none { d.described(it)!!.selected() })
        assertTrue(d.described("Red")!!.selected())
        e.close()
    }

    @Test
    fun `the footer shows the hint and the image size below the canvas`() = onEdt {
        val e = Content(motion = true, bitmap = solid(gray, 1080, 2340))
        val d = e.d
        val hint = assertNotNull(d.texted("Pixelate hides private text such as emails or order numbers.")).boundsInRoot
        val size = assertNotNull(d.texted("1080 × 2340")).boundsInRoot
        val c = d.canvas()
        assertTrue(hint.top >= c.bottom && size.top >= c.bottom, "below the canvas: $hint $size $c")
        assertTrue(hint.right <= size.left, "hint, then the size at the right: $hint $size")
        assertTrue(size.right > width - 40f && size.bottom <= height.toFloat(), "at the bottom right: $size")
        e.close()
    }

    @Test
    fun `the screenshot fills the rest at its aspect ratio and scales with the window`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        for ((w, h) in listOf(1000 to 800, 1300 to 600, 500 to 950, 700 to 700)) {
            e.w = w
            e.h = h
            d.frames(3)
            val c = d.canvas()
            assertEquals(0.5f, c.width / c.height, 0.01f, "aspect at $w x $h: $c")
            val toolbarBottom = d.described("Pen")!!.boundsInRoot.bottom
            val footerTop = d.texted("400 × 800")!!.boundsInRoot.top
            assertTrue(c.top >= toolbarBottom && c.bottom <= footerTop, "between toolbar and footer at $w x $h: $c")
            assertEquals(w / 2f, c.center.x, 1f, "centred across at $w x $h")
            // It touches the area's padding on one axis: as large as fits.
            // Tall: only the 12 dp padding plus the toolbar's and footer's own insets (under 70 px) above and below it.
            val tall = (c.top - toolbarBottom) + (footerTop - c.bottom) < 70f
            val wide = c.width >= w - 2 * 12f - 1f
            assertTrue(tall || wide, "fills one way at $w x $h: $c")
        }
        e.close()
    }

    @Test
    fun `a very small window keeps the toolbar off the canvas and Save and Copy on screen`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        for ((w, h) in listOf(360 to 420, 240 to 200)) {
            e.w = w
            e.h = h
            d.frames(3)
            val c = d.canvas()
            val copy = d.texted("Copy")!!.boundsInRoot
            val save = d.texted("Save")!!.boundsInRoot
            assertTrue(c.top >= d.described("Pen")!!.boundsInRoot.bottom - 0.5f, "toolbar above the canvas at $w x $h: $c")
            assertTrue(c.top >= copy.bottom - 0.5f, "Copy above the canvas at $w x $h")
            assertTrue(copy.right <= w + 0.5f && save.right <= copy.left, "Save and Copy in the window at $w x $h: $save $copy")
            assertTrue(c.left >= 0f && c.right <= w + 0.5f && c.bottom <= h + 0.5f, "inside at $w x $h: $c")
            if (c.height > 1f) assertEquals(0.5f, c.width / c.height, 0.02f)
        }
        // At 360 the tools and swatches do not all fit: they scroll, clipped before Save, rather than overlap it.
        e.w = 360
        e.h = 420
        d.frames(3)
        val px = d.snapshot()
        val save = d.texted("Save")!!.boundsInRoot
        val black = d.described("Black")!!.boundsInRoot // clipped bounds
        assertTrue(black.right <= save.left + 0.5f, "clipped before Save: $black $save")
        val spot = px[(save.left - 2).toInt(), save.center.y.toInt()]
        assertTrue(spot.red > 0.8f && spot.green > 0.8f, "nothing drawn over Save's side: $spot")
        e.close()
    }

    // The wake.

    @Test
    fun `the canvas draws through the wake, black when off, a bright line early on, the screenshot when on`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val c = d.canvas() // at 1: its full bounds
        fun px(fx: Float, fy: Float) = d.snapshot()[(c.left + c.width * fx).toInt(), (c.top + c.height * fy).toInt()]
        e.wake = 0f
        d.frames(2)
        val off = px(0.5f, 0.5f)
        assertTrue(off.red < 0.05f && off.green < 0.05f, "black at 0: $off")
        assertTrue(px(0.5f, 0.2f).red < 0.05f)
        e.wake = 0.1f
        val line = px(0.5f, 0.5f)
        assertTrue(line.red > 0.8f && line.green > 0.8f, "a bright line across the middle: $line")
        assertTrue(px(0.5f, 0.3f).red < 0.05f, "only a line: black above it")
        assertTrue(px(0.05f, 0.5f).red < 0.05f, "narrower than the screen at first")
        e.wake = 0.6f
        val opening = px(0.5f, 0.5f)
        assertTrue(opening.red > gray.red + 0.02f, "still brightened while it opens: $opening")
        e.wake = 1f
        val on = px(0.5f, 0.5f)
        assertEquals(gray.red, on.red, 0.02f, "the screenshot at 1")
        assertEquals(gray.red, px(0.5f, 0.05f).red, 0.02f)
        e.close()
    }

    // Behavior.

    @Test
    fun `picking a tool or a color selects it alone`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.described("Pixelate")!!)
        assertTrue(d.described("Pixelate")!!.selected())
        assertFalse(d.described("Pen")!!.selected())
        d.click(d.described("Blue")!!)
        assertTrue(d.described("Blue")!!.selected())
        assertFalse(d.described("Red")!!.selected())
        e.close()
    }

    @Test
    fun `drawing a box marks the screenshot in the picked color and enables Undo`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        assertFalse(d.undoButton().enabled())
        d.click(d.described("Box")!!)
        d.click(d.described("Blue")!!)
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.8f, 0.5f))
        assertTrue(d.undoButton().enabled())
        val c = d.canvas()
        val px = d.snapshot()
        val onEdge = px[(c.left + c.width * 0.5f).toInt(), (c.top + c.height * 0.2f).toInt()]
        assertTrue(onEdge.blue > 0.7f && onEdge.red < 0.4f, "the box's top edge is blue: $onEdge")
        val inside = px[(c.left + c.width * 0.5f).toInt(), (c.top + c.height * 0.35f).toInt()]
        assertEquals(gray.red, inside.red, 0.02f)
        // Saved in image pixels: the box spans the image, whatever the view's scale.
        d.click(d.texted("Save")!!)
        val box = assertIs<Mark.Box>(e.saves.single().single())
        assertEquals(MarkupColor.Blue.argb, box.color)
        assertEquals(80f, box.from.x, 2f)
        assertEquals(160f, box.from.y, 2f)
        assertEquals(320f, box.to.x, 2f)
        assertEquals(400f, box.to.y, 2f)
        e.close()
    }

    @Test
    fun `a pen stroke shows while it is drawn, before the button is released`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val c = d.canvas()
        fun at(fx: Float, fy: Float) = Offset(c.left + c.width * fx, c.top + c.height * fy)
        d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f, 0.5f), type = PointerType.Mouse)
        d.scene.sendPointerEvent(PointerEventType.Press, at(0.2f, 0.5f), type = PointerType.Mouse, button = PointerButton.Primary)
        d.frame()
        for (i in 1..6) {
            d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f + 0.1f * i, 0.5f), type = PointerType.Mouse)
            d.frame()
        }
        // Still held: the stroke so far is on screen, not only its first point.
        val px = d.snapshot()
        val along = px[at(0.65f, 0.5f).x.toInt(), at(0.65f, 0.5f).y.toInt()]
        assertTrue(along.red > 0.7f && along.green < 0.4f, "the held stroke is drawn red along its path: $along")
        d.scene.sendPointerEvent(PointerEventType.Release, at(0.8f, 0.5f), type = PointerType.Mouse, button = PointerButton.Primary)
        d.frames(2)
        e.close()
    }

    @Test
    fun `each tool adds its mark`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        for (tool in listOf("Pen", "Arrow", "Box", "Pixelate")) {
            d.click(d.described(tool)!!)
            d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        }
        d.click(d.texted("Save")!!)
        val marks = e.saves.single()
        assertIs<Mark.Pen>(marks[0])
        assertTrue((marks[0] as Mark.Pen).points.size > 2)
        assertIs<Mark.Arrow>(marks[1])
        assertIs<Mark.Box>(marks[2])
        assertIs<Mark.Pixelate>(marks[3])
        e.close()
    }

    @Test
    fun `a click without a drag adds nothing`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.described("Box")!!)
        d.click(d.canvas().center)
        assertFalse(d.undoButton().enabled())
        e.close()
    }

    @Test
    fun `Undo removes the last mark and is disabled with none left`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.drawOn(Offset(0.2f, 0.6f), Offset(0.6f, 0.8f))
        d.click(d.undoButton())
        assertTrue(d.undoButton().enabled())
        d.click(d.undoButton())
        assertFalse(d.undoButton().enabled())
        e.close()
    }

    @Test
    fun `Ctrl or Cmd+Z undoes and is consumed`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.drawOn(Offset(0.2f, 0.6f), Offset(0.6f, 0.8f))
        assertTrue(d.key(Key.Z, meta = true), "consumed")
        assertTrue(d.undoButton().enabled())
        assertTrue(d.key(Key.Z, ctrl = true), "consumed")
        assertFalse(d.undoButton().enabled())
        e.close()
    }

    @Test
    fun `a close request without marks closes at once`() = onEdt {
        val e = Content(motion = true)
        assertTrue(e.requestClose())
        assertEquals(1, e.closes)
        assertNull(e.d.texted("Discard your marks?"))
        e.close()
    }

    @Test
    fun `a close request with marks asks first, Keep editing keeps them, Discard closes`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        assertTrue(e.state!!.dirty)
        assertFalse(e.requestClose())
        d.frames(30)
        assertEquals(0, e.closes)
        assertTrue(e.state!!.confirming)
        assertNotNull(d.texted("Discard your marks?"))
        assertNotNull(d.texted("Your marks aren't saved or copied yet."))
        d.click(d.texted("Keep editing")!!)
        d.frames(30)
        assertNull(d.texted("Discard your marks?"))
        assertFalse(e.state!!.confirming)
        assertTrue(d.undoButton().enabled(), "the marks stay")
        e.requestClose()
        d.frames(30)
        d.click(d.texted("Discard")!!)
        assertEquals(1, e.closes)
        e.close()
    }

    @Test
    fun `the discard prompt covers the content and takes the pointer`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        e.requestClose()
        d.frames(30)
        // A click on Pixelate under the prompt's scrim does nothing.
        d.click(d.described("Pixelate")!!)
        assertFalse(d.described("Pixelate")!!.selected())
        val px = d.snapshot()
        val corner = px[3, 3]
        assertTrue(corner.red < 0.9f, "the scrim dims the toolbar too: $corner")
        e.close()
    }

    @Test
    fun `Esc closes, asks with marks, and dismisses the prompt`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        assertTrue(d.key(Key.Escape), "consumed")
        d.frames(30)
        assertNotNull(d.texted("Discard your marks?"))
        assertEquals(0, e.closes)
        // Undo does nothing while the prompt asks.
        d.key(Key.Z, meta = true)
        assertTrue(d.undoButton().enabled())
        assertTrue(d.key(Key.Escape))
        d.frames(30)
        assertNull(d.texted("Discard your marks?"))
        assertEquals(0, e.closes)
        d.key(Key.Z, meta = true)
        assertTrue(d.key(Key.Escape))
        assertEquals(1, e.closes, "no marks left: closes at once")
        e.close()
    }

    @Test
    fun `while it leaves nothing acts`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        e.active = false
        d.frames(2)
        assertFalse(e.requestClose())
        d.key(Key.Escape)
        d.click(d.texted("Save")!!)
        d.click(d.texted("Copy")!!)
        assertTrue(e.saves.isEmpty() && e.copies.isEmpty())
        assertEquals(0, e.closes)
        assertNull(d.texted("Discard your marks?"))
        e.close()
    }

    @Test
    fun `after a copy a close request closes at once, and new marks ask again`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Copy")!!)
        assertEquals(1, e.copies.size)
        assertFalse(e.state!!.dirty)
        d.drawOn(Offset(0.2f, 0.6f), Offset(0.6f, 0.8f))
        e.requestClose()
        d.frames(30)
        assertNotNull(d.texted("Discard your marks?"))
        d.click(d.texted("Keep editing")!!)
        d.frames(30)
        assertEquals(0, e.closes)
        e.close()
    }

    @Test
    fun `Copy shows Copied with a check for a second and a half and keeps the editor open`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Copy")!!)
        assertEquals(1, e.copies.single().size)
        assertNotNull(d.texted("Copied"))
        assertNull(d.texted("Copy"))
        d.frames(20)
        assertNotNull(d.tagged("copiedIcon"))
        assertNull(d.tagged("copyIcon"))
        assertEquals(0, e.closes)
        // The check stays 1.5 s (frame time), then turns back.
        d.frames((COPIED_MILLIS / 16 - 25).toInt())
        assertNotNull(d.texted("Copied"))
        d.frames(30)
        assertNotNull(d.texted("Copy"))
        assertNotNull(d.tagged("copyIcon"))
        assertTrue(e.requestClose(), "copied: nothing to discard")
        assertEquals(1, e.closes)
        e.close()
    }

    @Test
    fun `a failed copy shows no check and keeps the marks unsaved`() = onEdt {
        val e = Content(motion = true, copyWorks = false)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Copy")!!)
        d.frames(10)
        assertNull(d.texted("Copied"))
        assertFalse(e.requestClose())
        assertEquals(0, e.closes)
        e.close()
    }

    @Test
    fun `Save closes the editor, a failed save keeps it open with its marks`() = onEdt {
        val ok = Content(motion = true)
        ok.d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        ok.d.click(ok.d.texted("Save")!!)
        assertEquals(1, ok.closes)
        ok.close()

        val failing = Content(motion = true, saveWorks = false)
        val d = failing.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Save")!!)
        d.frames(10)
        assertEquals(1, failing.saves.size)
        assertEquals(0, failing.closes)
        assertNotNull(d.described("Screenshot"))
        assertTrue(d.undoButton().enabled())
        failing.close()
    }

    @Test
    fun `the content holds the shot's bitmap while it draws it, and lets it close once it leaves`() = onEdt {
        val bitmap = solid(gray)
        val e = Content(motion = true, bitmap = bitmap)
        e.d.frames(2)
        assertFalse(bitmap.asSkiaBitmap().isClosed)
        e.shown = false
        e.d.frames(2)
        assertTrue(bitmap.asSkiaBitmap().isClosed)
        e.close()
    }

    // Motion rows, sampled per frame (16 ms).

    @Test
    fun `a picked tool's background crossfades in 200 ms`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val box = d.described("Box")!!.boundsInRoot
        // Below the icon, inside the circle.
        val at = Offset(box.center.x, box.center.y + box.height * 0.35f)
        val before = d.snapshot()[at.x.toInt(), at.y.toInt()]
        val target = d.described("Box")!!.boundsInRoot.center
        d.scene.sendPointerEvent(PointerEventType.Move, target, type = PointerType.Mouse)
        d.scene.sendPointerEvent(PointerEventType.Press, target, type = PointerType.Mouse, button = PointerButton.Primary)
        d.scene.sendPointerEvent(PointerEventType.Release, target, type = PointerType.Mouse, button = PointerButton.Primary)
        val samples = (1..18).map { d.snapshot()[at.x.toInt(), at.y.toInt()] }
        val after = samples.last()
        fun delta(c: Color) = abs(c.red - before.red) + abs(c.green - before.green) + abs(c.blue - before.blue)
        val total = delta(after)
        assertTrue(total > 0.05f, "the background changes: $before -> $after")
        assertTrue(samples.any { delta(it) > total * 0.15f && delta(it) < total * 0.85f }, "crossfades, not snaps: $samples")
        // 200 ms plus the frames until the click composes (the press ripple, fading on its own, may still tint it).
        assertTrue(delta(samples[15]) > total - 0.05f, "done by 200 ms: $samples")
        e.close()
    }

    @Test
    fun `a picked swatch grows to 112 percent in 250 ms`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val base = d.described("Blue")!!.boundsInRoot.width
        d.click(d.described("Blue")!!)
        val widths = (1..20).map { d.frame(); d.described("Blue")!!.boundsInRoot.width }
        assertTrue(widths.zipWithNext().all { (a, b) -> b >= a - 0.01f }, "only grows: $widths")
        assertEquals(base * SWATCH_SCALE, widths.last(), 0.3f)
        assertTrue(widths[2] < base * SWATCH_SCALE - 0.3f, "animates: $widths")
        assertEquals(widths.last(), widths[16], 0.3f)
        // The one left shrinks back.
        assertEquals(base, d.described("Red")!!.boundsInRoot.width, 0.3f)
        e.close()
    }

    @Test
    fun `reduced motion keeps swatches their size`() = onEdt {
        val e = Content(motion = false)
        val d = e.d
        val before = d.described("Blue")!!.boundsInRoot.width
        d.click(d.described("Blue")!!)
        d.frames(30)
        assertEquals(before, d.described("Blue")!!.boundsInRoot.width, 0.01f)
        e.close()
    }

    @Test
    fun `an undone mark fades out in 150 ms`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.described("Box")!!)
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.8f, 0.5f))
        val c = d.canvas()
        val at = Offset(c.left + c.width * 0.5f, c.top + c.height * 0.2f)
        val red = d.snapshot()[at.x.toInt(), at.y.toInt()]
        assertTrue(red.red > 0.8f && red.green < 0.4f, "the box is red: $red")
        d.click(d.undoButton())
        val samples = (1..12).map { d.snapshot()[at.x.toInt(), at.y.toInt()] }
        assertTrue(samples.zipWithNext().all { (a, b) -> b.green >= a.green - 0.01f }, "only fades: ${samples.map { it.green }}")
        assertTrue(samples.any { it.green > red.green + 0.1f && it.green < gray.green - 0.1f }, "fades, not snaps: $samples")
        assertEquals(gray.green, samples.last().green, 0.02f)
        e.close()
    }

    @Test
    fun `the Copy icon crossfades to the check in 200 ms`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.texted("Copy")!!)
        d.frames(4)
        assertNotNull(d.tagged("copiedIcon"))
        assertNotNull(d.tagged("copyIcon"), "copy still fading out")
        d.frames(10)
        assertNull(d.tagged("copyIcon"))
        e.close()
    }

    @Test
    fun `the discard prompt fades in and grows, and fades out`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        assertNotNull(e.state).requestClose()
        val widths = (1..22).mapNotNull { d.frame(); d.texted("Keep editing")?.boundsInRoot?.width }
        assertTrue(widths.first() < widths.last() - 0.5f, "grows: $widths")
        assertEquals(widths.last(), widths[widths.size - 3], 0.3f)
        d.click(d.texted("Keep editing")!!)
        val gone = (1..20).map { d.frame(); d.texted("Discard your marks?") }
        assertNull(gone.last())
        e.close()
    }

    // The temporary host over the mirror window (until the editor has its own window).

    private inner class Host(motion: Boolean, shot: Shot? = null) {
        var shot by mutableStateOf(shot)
        var closes = 0
        val d = SceneDriver(ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    MarkupEditor(
                        this.shot, motion,
                        onCopy = { _, _ -> true },
                        onSave = { _, _ -> true },
                        onClose = {
                            closes++
                            this.shot = null
                        },
                        leases = leases,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }).also { it.frames(2) }

        fun open(bitmap: ImageBitmap = solid(gray)): Shot {
            val s = assertNotNull(leases.keep(file, bitmap, leases.begin()))
            shot = s
            return s
        }

        fun opened(): Host {
            open()
            d.frames(40)
            return this
        }

        fun close() = d.scene.close()
    }

    @Test
    fun `the host shows the content in a card, and a click on the scrim asks to close`() = onEdt {
        val e = Host(motion = true)
        assertNull(e.d.texted(EDITOR_HINT))
        e.opened()
        val d = e.d
        assertNotNull(d.texted(EDITOR_HINT))
        val c = d.canvas()
        assertEquals(0.5f, c.width / c.height, 0.01f)
        assertTrue(d.texted("Copy")!!.boundsInRoot.right < width - 20f, "the card stays inside the window")
        // A click on the card does not close it.
        d.click(d.texted(EDITOR_HINT)!!)
        assertEquals(0, e.closes)
        // A click on the scrim, with marks: the prompt asks.
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(Offset(5f, 5f))
        d.frames(30)
        assertNotNull(d.texted("Discard your marks?"))
        d.click(d.texted("Discard")!!)
        assertEquals(1, e.closes)
        d.frames(30)
        assertNull(d.texted(EDITOR_HINT))
        e.close()
    }

    @Test
    fun `the host's scrim takes the pointer, nothing under the editor is clicked`() = onEdt {
        var clicked = 0
        val shot = assertNotNull(leases.keep(file, solid(gray), leases.begin()))
        val scene = ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) if (awaitPointerEvent().type == PointerEventType.Press) clicked++
                            }
                        },
                    )
                    MarkupEditor(shot, true, { _, _ -> true }, { _, _ -> true }, {}, leases, Modifier.fillMaxSize())
                }
            }
        }
        val d = SceneDriver(scene)
        d.frames(40)
        d.click(Offset(10f, 10f))
        assertEquals(0, clicked)
        scene.close()
    }

    @Test
    fun `the shot's bitmap stays open while the host animates out, and is closed once it has left`() = onEdt {
        val e = Host(motion = true)
        val bitmap = solid(gray)
        e.open(bitmap)
        e.d.frames(40)
        assertTrue(e.d.key(Key.Escape))
        assertEquals(1, e.closes)
        assertFalse(bitmap.asSkiaBitmap().isClosed, "still drawn while it animates out")
        e.d.frames(30)
        assertTrue(bitmap.asSkiaBitmap().isClosed)
        e.close()
    }

    @Test
    fun `the host opens fading the scrim in over 300 ms and growing the card from 88 percent while it rises, 450 ms`() = onEdt {
        val e = Host(motion = true)
        e.open()
        val d = e.d
        val widths = mutableListOf<Float>()
        val tops = mutableListOf<Float>()
        val scrim = mutableListOf<Float>()
        repeat(32) {
            val px = d.snapshot()
            scrim += px[5, 5].red
            d.described("Screenshot")?.boundsInRoot?.let {
                widths += it.width
                tops += it.top
            }
        }
        val full = widths.last()
        assertTrue(widths.first() < full * 0.92f && widths.first() > full * 0.85f, "starts near 88%: $widths")
        assertTrue(widths.zipWithNext().all { (a, b) -> b >= a - 0.01f }, "only grows: $widths")
        assertTrue(widths[10] > full * 0.97f, "emphasized decelerate: most of it early: $widths")
        assertEquals(full, widths[28], 0.5f)
        val settledTop = tops.last()
        assertTrue(tops.first() > settledTop + 3f, "rises: $tops")
        // The scrim darkens the white surface, done by 300 ms.
        assertTrue(scrim[2] < 0.99f && scrim[2] > scrim[20] + 0.05f, "fades in: $scrim")
        assertEquals(scrim[22], scrim.last(), 0.01f)
        e.close()
    }

    @Test
    fun `the host closes shrinking the card toward 95 percent and fading it with the scrim in 200 ms`() = onEdt {
        val e = Host(motion = true).opened()
        val d = e.d
        val full = d.canvas().width
        e.shot = null
        val widths = (1..20).map { d.frame(); d.described("Screenshot")?.boundsInRoot?.width }
        val moving = widths.filterNotNull()
        assertTrue(moving.zipWithNext().all { (a, b) -> b <= a + 0.01f }, "only shrinks: $widths")
        assertTrue(moving.last() < full * 0.97f && moving.last() >= full * 0.94f, "toward 95%: $widths")
        assertTrue(moving.size in 11..16, "about 200 ms (plus the frames until the change composes): $widths")
        assertNull(widths.last())
        e.close()
    }

    @Test
    fun `the host under reduced motion only fades, in 150 ms`() = onEdt {
        val e = Host(motion = false)
        e.open()
        val d = e.d
        val widths = (1..14).mapNotNull { d.frame(); d.described("Screenshot")?.boundsInRoot?.width }
        assertTrue(widths.distinct().size == 1, "no scale: $widths")
        val corner = (1..3).map { d.snapshot()[5, 5].red }
        d.frames(10)
        assertEquals(corner.last(), d.snapshot()[5, 5].red, 0.01f)
        e.shot = null
        val gone = (1..16).map { d.frame(); d.described("Screenshot")?.boundsInRoot?.width }
        assertTrue(gone.filterNotNull().distinct().size <= 1, "no scale out: $gone")
        assertTrue(gone.count { it != null } <= 13, "gone in 150 ms: $gone")
        e.close()
    }
}
