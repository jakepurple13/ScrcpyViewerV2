package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
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
import kotlinx.coroutines.CompletableDeferred
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
 * sampled frame by frame (16 ms). Motion tests pass `motion = true`
 * themselves: CI's macOS and Windows runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MarkupEditorTest {
    @Composable
    private fun MaybeSurface(surface: Boolean, content: @Composable () -> Unit) {
        if (surface) Surface(Modifier.fillMaxSize(), content = content)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
    }

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
        /** When set, Save waits for it, as a slow disk would. */
        val saveGate: CompletableDeferred<Unit>? = null,
        dark: Boolean = false,
        /** False: no Surface around the content, as in the editor window (whose root sets no content color). */
        surface: Boolean = true,
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
            ScrcpyViewerTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                // Not straight in the Surface: it would stretch the content to the scene (min constraints).
                MaybeSurface(surface) {
                    Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(this@Content.w.dp, this@Content.h.dp)) {
                        if (shown) {
                            val s = rememberMarkupEditorState(shot)
                            state = s
                            MarkupEditorContent(
                                shot, motion,
                                onCopy = { _, marks -> copies += marks; copyWorks },
                                onSave = { _, marks -> saves += marks; saveGate?.await(); saveWorks },
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
    fun `the toolbar reads Pen, Arrow, Box, Circle, Pixelate, Undo, the four swatches, then Save and Copy at its right`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val order = d.toolbarItems()
        assertTrue(order.zipWithNext().all { (a, b) -> a.right <= b.left + 0.5f }, "left to right: $order")
        val row = order.first().center.y
        assertTrue(order.all { abs(it.center.y - row) < 4f }, "one row: $order")
        // Save and Copy sit at the right, past a gap after the swatches.
        assertTrue(order.last().right > width - 40f, "Copy at the right edge: ${order.last()}")
        // Relative, not in px: fonts differ between runners.
        assertTrue(order[10].left - order[9].right > order[0].width * 2, "a spacer before Save: $order")
        // Above the canvas; no title and no Close button in the content (the window has them).
        assertTrue(order.all { it.bottom <= d.canvas().top }, "above the canvas")
        assertNull(d.texted("Close"))
        assertNull(d.texted("Edit screenshot"))
        assertTrue(d.described("Pen")!!.selected())
        assertTrue(listOf("Arrow", "Box", "Circle", "Pixelate").none { d.described(it)!!.selected() })
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
            // The toolbar's bottom (it wraps when the window is narrow; a wrapping group may still be gliding to its row).
            val toolbarBottom = assertNotNull(d.described("Markup tools")).boundsInRoot.bottom
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

    private val toolbarNames = listOf("Pen", "Arrow", "Box", "Circle", "Pixelate", "Undo", "Red", "Yellow", "Blue", "Black")

    /** Every toolbar item's bounds: the tools, Undo, the swatches, Save and Copy. */
    private fun SceneDriver.toolbarItems(): List<Rect> =
        toolbarNames.map { assertNotNull(described(it), it).boundsInRoot } +
            listOf("Save", "Copy").map { assertNotNull(texted(it), it).boundsInRoot }

    @Test
    fun `at the window's opening width the whole toolbar shows on one row`() = onEdt {
        val e = Content(motion = true, w = EDITOR_TOOLBAR_WIDTH, h = 900)
        val d = e.d
        val items = d.toolbarItems()
        assertTrue(items.all { it.left >= 0f && it.right <= EDITOR_TOOLBAR_WIDTH + 0.5f && it.width > 1f }, "inside: $items")
        val row = items.first().center.y
        assertTrue(items.all { abs(it.center.y - row) < 4f }, "one row: $items")
        assertTrue(items.zipWithNext().all { (a, b) -> a.right <= b.left + 0.5f }, "none overlap: $items")
        e.close()
    }

    @Test
    fun `a narrow window wraps the toolbar onto a second row, Save and Copy at its right end, nothing hidden`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val min = EDITOR_WINDOW_MIN_SIZE.width.value.toInt() to EDITOR_WINDOW_MIN_SIZE.height.value.toInt()
        for ((w, h) in listOf(min, 480 to 600)) {
            e.w = w
            e.h = h
            d.frames(30)
            val items = d.toolbarItems()
            assertTrue(items.all { it.left >= 0f && it.right <= w + 0.5f && it.width > 1f }, "all inside at $w: $items")
            val rows = items.map { it.center.y }.distinctBy { (it / 8f).toInt() }
            assertTrue(rows.size >= 2, "wrapped at $w: $items")
            val c = d.canvas()
            assertTrue(items.all { it.bottom <= c.top + 0.5f }, "the canvas never overlaps the toolbar at $w: $c $items")
            assertTrue(c.height > 1f, "the canvas still shows at $w x $h: $c")
            // Save and Copy end the last row, at its right.
            val copy = items.last()
            val save = items[items.size - 2]
            assertTrue(copy.center.y >= items.maxOf { it.center.y } - 4f, "Copy on the last row at $w")
            assertTrue(copy.right > w * 0.85f && save.right <= copy.left + 0.5f, "Save, Copy at the right at $w: $save $copy")
            assertEquals(0.5f, c.width / c.height, 0.02f)
        }
        e.close()
    }

    @Test
    fun `the toolbar's height follows a wrap on the layout spring`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        val oneRow = d.canvas().top
        e.w = EDITOR_WINDOW_MIN_SIZE.width.value.toInt()
        val tops = (1..60).map { d.frame(); d.canvas().top }
        assertTrue(tops.last() > oneRow + 20f, "a row more: $tops")
        assertTrue(tops.any { it > tops.first() + 2f && it < tops.last() - 2f }, "moves down over frames, not a snap: $tops")
        assertTrue(tops.takeLast(10).all { abs(it - tops.last()) < 0.5f }, "settled: $tops")
        e.close()
    }

    /** Each frame's unclipped position of the toolbar group tagged [tag] while the window narrows from 1000 to [narrow] (or back). */
    private fun groupPath(motion: Boolean, tag: String, narrow: Int = EDITOR_WINDOW_MIN_SIZE.width.value.toInt(), widen: Boolean = false): List<Offset> {
        val e = Content(motion = motion, w = if (widen) narrow else width)
        try {
            val d = e.d
            d.frames(30)
            val before = assertNotNull(d.tagged(tag), tag).positionInRoot
            e.w = if (widen) width else narrow
            return listOf(before) + (1..60).map { d.frame(); assertNotNull(d.tagged(tag), tag).positionInRoot }
        } finally {
            e.close()
        }
    }

    private fun List<Offset>.distance(a: Int, b: Int) = (this[a] - this[b]).getDistance()

    @Test
    fun `a wrapping group glides to its new row over several frames`() = onEdt {
        for ((tag, widen) in listOf(TOOLBAR_SWATCHES_TAG to false, TOOLBAR_ACTIONS_TAG to false, TOOLBAR_SWATCHES_TAG to true)) {
            val path = groupPath(motion = true, tag = tag, widen = widen)
            val total = path.distance(0, path.size - 1)
            assertTrue(total > 20f, "$tag moves to another row (widen $widen): $path")
            // In between for several frames, never most of the way in one frame, then settled.
            val between = path.indices.count { i -> path.distance(0, i) > total * 0.1f && path.distance(i, path.size - 1) > total * 0.1f }
            assertTrue(between >= 4, "$tag glides over several frames (widen $widen): $path")
            assertTrue((1 until path.size).all { path.distance(it - 1, it) < total * 0.6f }, "$tag no jump (widen $widen): $path")
            assertTrue(path.distance(path.size - 11, path.size - 1) < 0.5f, "$tag lands (widen $widen): $path")
        }
    }

    @Test
    fun `dragging the window narrower and back, the groups keep up with no jump and land`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.frames(30)
        val tags = listOf(TOOLBAR_TOOLS_TAG, TOOLBAR_UNDO_TAG, TOOLBAR_SWATCHES_TAG, TOOLBAR_ACTIONS_TAG)
        fun positions() = tags.map { assertNotNull(d.tagged(it), it).positionInRoot }
        val min = EDITOR_WINDOW_MIN_SIZE.width.value.toInt()
        val sweep = (width downTo min step 8) + (min..width step 8)
        var last = positions()
        for (w in sweep) {
            e.w = w
            d.frame()
            val now = positions()
            now.zip(last).forEachIndexed { i, (a, b) ->
                // A wrap moves a group a whole row's width: never in one frame. Following the edge moves 8 px.
                assertTrue((a - b).getDistance() < 200f, "${tags[i]} jumped at $w: $b -> $a")
            }
            last = now
        }
        d.frames(20)
        val settled = positions()
        d.frames(10)
        assertEquals(settled, positions(), "landed, nothing stuck moving")
        val actions = assertNotNull(d.tagged(TOOLBAR_ACTIONS_TAG))
        assertEquals(width - 10f, actions.positionInRoot.x + actions.size.width, 0.5f, "Save + Copy back at the right end")
        e.close()
    }

    @Test
    fun `without motion a wrapping group lands at once`() = onEdt {
        for (tag in listOf(TOOLBAR_SWATCHES_TAG, TOOLBAR_ACTIONS_TAG)) {
            val path = groupPath(motion = false, tag = tag)
            assertTrue(path.distance(0, path.size - 1) > 20f, "$tag moves: $path")
            assertTrue(path.distance(1, path.size - 1) < 0.5f, "$tag lands in the first frame: $path")
        }
    }

    @Test
    fun `no toolbar row starts with a separator`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        for (w in listOf(width, 640, 560, 480, 420, EDITOR_WINDOW_MIN_SIZE.width.value.toInt())) {
            e.w = w
            d.frames(30)
            val groups = listOf(TOOLBAR_TOOLS_TAG, TOOLBAR_UNDO_TAG, TOOLBAR_SWATCHES_TAG).map { assertNotNull(d.tagged(it), it).boundsInRoot }
            // A group that starts a row sits at the toolbar's left inset (10 dp), not after a "|" (6 + 1 + 6 dp + gaps).
            val rowStarts = groups.filterIndexed { i, g -> i == 0 || abs(g.center.y - groups[i - 1].center.y) > 4f }
            assertTrue(rowStarts.all { it.left < 12f }, "rows start with a group, not a separator, at $w: $groups")
            // On one row, the separator sits between groups: more than the 4 dp gap between them.
            groups.zipWithNext().filter { (a, b) -> abs(a.center.y - b.center.y) <= 4f }.forEach { (a, b) ->
                assertTrue(b.left - a.right > 15f, "a separator between groups on a row at $w: $a $b")
            }
        }
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
    fun `drawing a circle marks a ring in image pixels, not its inside`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.described("Circle")!!)
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.8f, 0.5f))
        val c = d.canvas()
        val px = d.snapshot()
        // The ellipse touches the drag rectangle's sides at their middles; its center is untouched.
        val top = px[(c.left + c.width * 0.5f).toInt(), (c.top + c.height * 0.2f).toInt()]
        assertTrue(top.red > 0.7f && top.green < 0.4f, "the ring's top is red: $top")
        val left = px[(c.left + c.width * 0.2f).toInt(), (c.top + c.height * 0.35f).toInt()]
        assertTrue(left.red > 0.7f && left.green < 0.4f, "the ring's left is red: $left")
        val center = px[(c.left + c.width * 0.5f).toInt(), (c.top + c.height * 0.35f).toInt()]
        assertEquals(gray.green, center.green, 0.02f, "not filled: $center")
        // The drag rectangle's corner is off the ellipse.
        val corner = px[(c.left + c.width * 0.2f).toInt() + 3, (c.top + c.height * 0.2f).toInt() + 3]
        assertEquals(gray.green, corner.green, 0.02f, "corners stay clear: $corner")
        d.click(d.texted("Save")!!)
        val circle = assertIs<Mark.Circle>(e.saves.single().single())
        assertEquals(MarkupColor.Red.argb, circle.color)
        assertEquals(80f, circle.from.x, 2f)
        assertEquals(160f, circle.from.y, 2f)
        assertEquals(320f, circle.to.x, 2f)
        assertEquals(400f, circle.to.y, 2f)
        e.close()
    }

    @Test
    fun `holding Shift makes a circle round and a box square`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        for (tool in listOf("Circle", "Box")) {
            d.click(d.described(tool)!!)
            val c = d.canvas()
            fun at(fx: Float, fy: Float) = Offset(c.left + c.width * fx, c.top + c.height * fy)
            val shift = PointerKeyboardModifiers(isShiftPressed = true)
            d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f, 0.2f), type = PointerType.Mouse)
            d.scene.sendPointerEvent(PointerEventType.Press, at(0.2f, 0.2f), type = PointerType.Mouse, button = PointerButton.Primary)
            d.frame()
            for (i in 1..6) {
                d.scene.sendPointerEvent(
                    PointerEventType.Move, at(0.2f + 0.1f * i, 0.2f + 0.05f * i), type = PointerType.Mouse, keyboardModifiers = shift,
                )
                d.frame()
            }
            d.scene.sendPointerEvent(
                PointerEventType.Release, at(0.8f, 0.5f), type = PointerType.Mouse, button = PointerButton.Primary, keyboardModifiers = shift,
            )
            d.frames(2)
        }
        d.click(d.texted("Save")!!)
        val (circle, box) = e.saves.single()
        for (m in listOf(circle, box)) {
            val (from, to) = when (m) {
                is Mark.Circle -> m.from to m.to
                is Mark.Box -> m.from to m.to
                else -> error("$m")
            }
            assertEquals(abs(to.x - from.x), abs(to.y - from.y), 0.5f, "square: $m")
            assertEquals(240f, to.x - from.x, 3f, "as long as the longer side (0.6 of 400 px): $m")
        }
        assertIs<Mark.Circle>(circle)
        assertIs<Mark.Box>(box)
        e.close()
    }

    @Test
    fun `Undo is drawn light on the dark theme, without a Surface around the content as in the window`() = onEdt {
        val e = Content(motion = true, dark = true, surface = false)
        val d = e.d
        d.click(d.described("Box")!!)
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        assertTrue(d.undoButton().enabled())
        val r = assertNotNull(d.described("Undo")).boundsInRoot
        val px = d.snapshot()
        var brightest = 0f
        for (x in r.left.toInt() until r.right.toInt()) for (y in r.top.toInt() until r.bottom.toInt()) {
            brightest = maxOf(brightest, px[x, y].luminance())
        }
        assertTrue(brightest > 0.4f, "the Undo icon is readable on the dark toolbar, brightest luminance $brightest")
        e.close()
    }

    @Test
    fun `Shift held only at the release still squares the mark`() = onEdt {
        val e = Content(motion = true)
        val d = e.d
        d.click(d.described("Box")!!)
        val c = d.canvas()
        fun at(fx: Float, fy: Float) = Offset(c.left + c.width * fx, c.top + c.height * fy)
        d.scene.sendPointerEvent(PointerEventType.Move, at(0.2f, 0.2f), type = PointerType.Mouse)
        d.scene.sendPointerEvent(PointerEventType.Press, at(0.2f, 0.2f), type = PointerType.Mouse, button = PointerButton.Primary)
        d.frame()
        d.scene.sendPointerEvent(PointerEventType.Move, at(0.8f, 0.5f), type = PointerType.Mouse)
        d.frame()
        d.scene.sendPointerEvent(
            PointerEventType.Release, at(0.8f, 0.5f), type = PointerType.Mouse, button = PointerButton.Primary,
            keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true),
        )
        d.frames(2)
        d.click(d.texted("Save")!!)
        val box = assertIs<Mark.Box>(e.saves.single().single())
        assertEquals(abs(box.to.x - box.from.x), abs(box.to.y - box.from.y), 0.5f, "square: $box")
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
        for (tool in listOf("Pen", "Arrow", "Box", "Circle", "Pixelate")) {
            d.click(d.described(tool)!!)
            d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        }
        d.click(d.texted("Save")!!)
        val marks = e.saves.single()
        assertIs<Mark.Pen>(marks[0])
        assertTrue((marks[0] as Mark.Pen).points.size > 2)
        assertIs<Mark.Arrow>(marks[1])
        assertIs<Mark.Box>(marks[2])
        assertIs<Mark.Circle>(marks[3])
        assertIs<Mark.Pixelate>(marks[4])
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
    fun `a mark drawn while Save runs keeps the editor open and unsaved`() = onEdt {
        val gate = CompletableDeferred<Unit>()
        val e = Content(motion = true, saveGate = gate)
        val d = e.d
        d.drawOn(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f))
        d.click(d.texted("Save")!!)
        assertEquals(1, e.saves.single().size)
        d.drawOn(Offset(0.2f, 0.6f), Offset(0.6f, 0.8f))
        gate.complete(Unit)
        d.frames(10)
        assertEquals(0, e.closes, "the new mark is not in the saved file: stay open")
        assertTrue(e.state!!.dirty, "the new mark is unsaved")
        assertEquals(2, e.state!!.doc.marks.size)
        e.close()
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
}
