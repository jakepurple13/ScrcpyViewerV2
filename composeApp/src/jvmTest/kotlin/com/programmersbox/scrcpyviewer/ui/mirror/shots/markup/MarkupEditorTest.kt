package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
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
 * The editor dialog: tools, swatches, drawing, undo, Copy, Save, Close and the discard prompt, Esc and Ctrl/Cmd+Z, and
 * each motion row sampled frame by frame (16 ms). Motion tests pass `motion = true` themselves: CI's macOS and Windows
 * runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MarkupEditorTest {
    private val scheduler = TestCoroutineScheduler()
    private val timerScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val leases = ScreenshotThumbnailState(timerScope, clock = { scheduler.currentTime })
    private val file: Path = Paths.get("/tmp/caps/Pixel 8 Pro.png")
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

    private inner class Editor(motion: Boolean, shot: Shot? = null, val copyWorks: Boolean = true, val saveWorks: Boolean = true) {
        var shot by mutableStateOf(shot)
        val copies = mutableListOf<List<Mark>>()
        val saves = mutableListOf<List<Mark>>()
        var closes = 0
        val d = SceneDriver(ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    MarkupEditor(
                        this.shot, motion,
                        onCopy = { _, marks -> copies += marks; copyWorks },
                        onSave = { _, marks -> saves += marks; saveWorks },
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

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun Editor.opened(): Editor {
        open()
        d.frames(40)
        return this
    }

    // Behavior.

    @Test
    fun `opens titled Edit screenshot with the four tools, Pen and Red picked, the hint and the actions`() = onEdt {
        val e = Editor(motion = true)
        assertNull(e.d.texted("Edit screenshot"))
        e.opened()
        val d = e.d
        assertNotNull(d.texted("Edit screenshot"))
        for (label in listOf("Pen", "Arrow", "Box", "Pixelate", "Undo", "Red", "Yellow", "Blue", "Black")) {
            assertNotNull(d.described(label), label)
        }
        assertNotNull(d.texted("Draw on the screenshot. Pixelate hides private text such as emails or order numbers."))
        for (label in listOf("Copy", "Save", "Close")) assertNotNull(d.texted(label), label)
        assertTrue(d.described("Pen")!!.selected())
        assertTrue(listOf("Arrow", "Box", "Pixelate").none { d.described(it)!!.selected() })
        assertTrue(d.described("Red")!!.selected())
        // The screenshot is scaled to fit: 62% of the window's height, at its aspect ratio, left of the tools.
        val c = d.canvas()
        assertEquals(height * 0.62f, c.height, 1.5f)
        assertEquals(c.height / 2, c.width, 1.5f)
        assertTrue(c.right < d.described("Pen")!!.boundsInRoot.left)
        e.close()
    }

    @Test
    fun `a short window still shows the screenshot at least 320 dp tall`() = onEdt {
        val e = Editor(motion = true)
        e.d.scene.close()
        val scene = ImageComposeScene(1000, 400, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                MarkupEditor(e.shot, true, { _, _ -> true }, { _, _ -> true }, {}, leases, Modifier.fillMaxSize())
            }
        }
        val d = SceneDriver(scene)
        e.shot = assertNotNull(leases.keep(file, solid(gray), leases.begin()))
        d.frames(40)
        assertEquals(320f, d.canvas().height, 1.5f)
        scene.close()
    }

    @Test
    fun `picking a tool or a color selects it alone`() = onEdt {
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
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
    fun `each tool adds its mark`() = onEdt {
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
        val d = e.d
        d.click(d.described("Box")!!)
        d.click(d.canvas().center)
        assertFalse(d.undoButton().enabled())
        e.close()
    }

    @Test
    fun `Undo removes the last mark and is disabled with none left`() = onEdt {
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
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
    fun `Close without marks closes at once`() = onEdt {
        val e = Editor(motion = true).opened()
        e.d.click(e.d.texted("Close")!!)
        assertEquals(1, e.closes)
        e.d.frames(30)
        assertNull(e.d.texted("Edit screenshot"))
        e.close()
    }

    @Test
    fun `Close with marks asks first, Keep editing keeps them, Discard closes`() = onEdt {
        val e = Editor(motion = true).opened()
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Close")!!)
        d.frames(30)
        assertEquals(0, e.closes)
        assertNotNull(d.texted("Discard your marks?"))
        assertNotNull(d.texted("Your marks aren't saved or copied yet."))
        d.click(d.texted("Keep editing")!!)
        d.frames(30)
        assertNull(d.texted("Discard your marks?"))
        assertTrue(d.undoButton().enabled(), "the marks stay")
        d.click(d.texted("Close")!!)
        d.frames(30)
        d.click(d.texted("Discard")!!)
        assertEquals(1, e.closes)
        e.close()
    }

    @Test
    fun `Esc closes, asks with marks, and dismisses the prompt`() = onEdt {
        val e = Editor(motion = true).opened()
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
    fun `after a copy Close closes at once, and new marks ask again`() = onEdt {
        val e = Editor(motion = true).opened()
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Copy")!!)
        assertEquals(1, e.copies.size)
        d.drawOn(Offset(0.2f, 0.6f), Offset(0.6f, 0.8f))
        d.click(d.texted("Close")!!)
        d.frames(30)
        assertNotNull(d.texted("Discard your marks?"))
        d.click(d.texted("Keep editing")!!)
        d.frames(30)
        assertEquals(0, e.closes)
        e.close()
    }

    @Test
    fun `Copy shows Copied with a check for a second and a half and keeps the editor open`() = onEdt {
        val e = Editor(motion = true).opened()
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
        d.click(d.texted("Close")!!)
        assertEquals(1, e.closes, "copied: nothing to discard")
        e.close()
    }

    @Test
    fun `a failed copy shows no check and keeps the marks unsaved`() = onEdt {
        val e = Editor(motion = true, copyWorks = false).opened()
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Copy")!!)
        d.frames(10)
        assertNull(d.texted("Copied"))
        d.click(d.texted("Close")!!)
        assertEquals(0, e.closes)
        e.close()
    }

    @Test
    fun `Save closes the editor, a failed save keeps it open with its marks`() = onEdt {
        val ok = Editor(motion = true).opened()
        ok.d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        ok.d.click(ok.d.texted("Save")!!)
        assertEquals(1, ok.closes)
        ok.close()

        val failing = Editor(motion = true, saveWorks = false).opened()
        val d = failing.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Save")!!)
        d.frames(10)
        assertEquals(1, failing.saves.size)
        assertEquals(0, failing.closes)
        assertNotNull(d.texted("Edit screenshot"))
        assertTrue(d.undoButton().enabled())
        failing.close()
    }

    @Test
    fun `the scrim takes the pointer, nothing under the editor is clicked`() = onEdt {
        var clicked = 0
        val shot = assertNotNull(leases.keep(file, solid(gray), leases.begin()))
        val scene = ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(
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
    fun `the shot's bitmap stays open while the editor draws it, and is closed once it has left`() = onEdt {
        val e = Editor(motion = true)
        val bitmap = solid(gray)
        e.open(bitmap)
        e.d.frames(40)
        e.d.click(e.d.texted("Close")!!)
        e.d.frame()
        assertFalse(bitmap.asSkiaBitmap().isClosed, "still drawn while it animates out")
        e.d.frames(30)
        assertTrue(bitmap.asSkiaBitmap().isClosed)
        e.close()
    }

    // Motion rows, sampled per frame (16 ms).

    @Test
    fun `opening fades the scrim in over 300 ms and grows the dialog from 88 percent while it rises, 450 ms`() = onEdt {
        val e = Editor(motion = true)
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
    fun `closing shrinks the dialog toward 95 percent and fades it with the scrim in 200 ms`() = onEdt {
        val e = Editor(motion = true).opened()
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
    fun `reduced motion only fades, in 150 ms`() = onEdt {
        val e = Editor(motion = false)
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
        // Swatches do not grow.
        val e2 = Editor(motion = false).opened()
        val before = e2.d.described("Blue")!!.boundsInRoot.width
        e2.d.click(e2.d.described("Blue")!!)
        e2.d.frames(30)
        assertEquals(before, e2.d.described("Blue")!!.boundsInRoot.width, 0.01f)
        e2.close()
        e.close()
    }

    @Test
    fun `a picked tool's background crossfades in 200 ms`() = onEdt {
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
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
    fun `an undone mark fades out in 150 ms`() = onEdt {
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
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
        val e = Editor(motion = true).opened()
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Close")!!)
        val widths = (1..22).mapNotNull { d.frame(); d.texted("Keep editing")?.boundsInRoot?.width }
        assertTrue(widths.first() < widths.last() - 0.5f, "grows: $widths")
        assertEquals(widths.last(), widths[widths.size - 3], 0.3f)
        d.click(d.texted("Keep editing")!!)
        val gone = (1..20).map { d.frame(); d.texted("Discard your marks?") }
        assertNull(gone.last())
        e.close()
    }
}
