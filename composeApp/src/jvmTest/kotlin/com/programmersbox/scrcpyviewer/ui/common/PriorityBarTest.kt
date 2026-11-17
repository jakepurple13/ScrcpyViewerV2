package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldBarTest {
    @Test
    fun `folds the highest priority first`() {
        assertEquals(setOf(0), foldBar(400, listOf(100, 72, 260), listOf(2, 0, 1), 8, 36))
    }

    @Test
    fun `keeps folding until it fits`() {
        assertEquals(setOf(0, 2), foldBar(200, listOf(100, 72, 260), listOf(2, 0, 1), 8, 36))
    }

    @Test
    fun `priority zero never folds`() {
        assertEquals(emptySet(), foldBar(10, listOf(100, 72, 260), listOf(0, 0, 0), 8, 36))
    }

    @Test
    fun `devices footer folds the duplicate then the first button`() {
        val folded = foldBar(420, listOf(150, 60, 150, 0, 170, 100), listOf(3, 2, 5, 0, 1, 0), 8, 36)
        assertEquals(listOf(2, 0), folded.toList(), "fold order")
    }

    @Test
    fun `nothing folds when it fits`() {
        assertEquals(emptySet(), foldBar(500, listOf(100, 72, 260), listOf(2, 0, 1), 8, 36))
    }

    @Test
    fun `ties fold the later index first`() {
        assertEquals(setOf(2), foldBar(300, listOf(100, 100, 100), listOf(1, 1, 1), 8, 36))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
class PriorityBarTest {
    private var clicked = ""
    private var barWidth by mutableIntStateOf(300)

    private fun item(key: String, priority: Int) = BarItem(
        key = key,
        priority = priority,
        menuLabel = key,
        onMenuClick = { clicked = key },
    ) { Box(Modifier.width(100.dp).semantics { contentDescription = "item:$key" }) { Text(key) } }

    private val three get() = listOf(item("Edit", 2), item("Duplicate", 5), item("Apply", 0))

    private fun scene(
        width: Int,
        items: List<Any> = three,
        reduceMotion: Boolean = false,
        forced: Set<String>? = null,
        fixedWidth: Boolean = true,
    ): SceneDriver {
        barWidth = width
        return SceneDriver(ImageComposeScene(600, 200, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                PriorityBar(items, if (fixedWidth) Modifier.width(barWidth.dp) else Modifier, forcedFolded = forced)
            }
        })
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            out += n
            n.children.forEach(::walk)
        }
        semanticsOwners.forEach { walk(it.rootSemanticsNode) }
        return out
    }

    private fun ImageComposeScene.labels(): Set<String> = nodes().flatMap {
        (it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text }) +
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.toSet()

    /** Visible (clipped) bounds of every bar item, by key. */
    private fun ImageComposeScene.edges(): Map<String, Rect> = nodes().mapNotNull { n ->
        val d = n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith("item:") }
        d?.let { it.removePrefix("item:") to n.boundsInWindow }
    }.toMap()

    private fun Rect?.visibleWidth() = if (this == null) 0f else width

    private fun SceneDriver.click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(60)
    }

    /** Builds the scene on the Swing thread, like every other scene test, and always closes it. */
    private fun run(create: () -> SceneDriver, block: SceneDriver.() -> Unit) = onEdt {
        val driver = create()
        try {
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `narrow bar keeps the primary action and folds the rest into More`() {
        clicked = ""
        run({ scene(300) }) {
            frames(60)
            val labels = scene.labels()
            assertTrue("Apply" in labels, "Apply stays: $labels")
            assertTrue("More" in labels, "More appears: $labels")
            assertFalse("Duplicate" in labels, "Duplicate folded: $labels")

            val more = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("More") == true }
            click(more.boundsInRoot.center)
            assertTrue("Duplicate" in scene.labels(), "menu lists Duplicate")

            val entry = scene.nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Duplicate" } == true }
            click(entry.boundsInRoot.center)
            assertEquals("Duplicate", clicked)
        }
    }

    @Test
    fun `wide bar shows everything and no More`() {
        run({ scene(600) }) {
            frames(60)
            val labels = scene.labels()
            assertTrue(setOf("Edit", "Duplicate", "Apply").all { it in labels }, "all shown: $labels")
            assertFalse("More" in labels, "no More: $labels")
        }
    }

    @Test
    fun `narrowing animates the fold and nothing passes the bar edge`() {
        run({ scene(600) }) {
            frames(30)
            barWidth = 300
            var sawMidShrink = false
            repeat(80) { n ->
                frame()
                val e = scene.edges()
                e.forEach { (key, r) -> assertTrue(r.right <= 301f, "frame $n: $key right edge ${r.right} <= 300") }
                val dup = e["Duplicate"].visibleWidth()
                if (n in 1..40 && dup > 1f && dup < 99f) sawMidShrink = true
            }
            assertTrue(sawMidShrink, "Duplicate shrinks through intermediate widths")
            assertEquals(0f, scene.edges()["Duplicate"].visibleWidth())
            assertTrue("More" in scene.labels())
        }
    }

    @Test
    fun `widening unfolds with motion and More fades out instead of vanishing`() {
        run({ scene(300) }) {
            frames(60)
            assertTrue("More" in scene.labels())
            barWidth = 600
            frame()
            assertTrue("More" in scene.labels(), "More still present one frame later")
            var sawMidGrow = false
            repeat(5) {
                frame()
                val dup = scene.edges()["Duplicate"].visibleWidth()
                if (dup > 1f && dup < 99f) sawMidGrow = true
            }
            assertTrue(sawMidGrow, "Duplicate grows through intermediate widths")
            frames(120)
            assertFalse("More" in scene.labels(), "More gone once settled")
            assertEquals(100f, scene.edges()["Duplicate"].visibleWidth())
        }
    }

    @Test
    fun `forcedFolded folds the named key and never a priority zero one`() {
        run({ scene(600, forced = setOf("Duplicate", "Apply")) }) {
            frames(60)
            val labels = scene.labels()
            assertFalse("Duplicate" in labels, "Duplicate folded: $labels")
            assertTrue("Apply" in labels, "Apply (priority 0) stays: $labels")
            assertTrue("Edit" in labels && "More" in labels, "Edit stays, More shown: $labels")
        }
    }

    @Test
    fun `a grower to its natural width stops there and the spacer takes the rest`() {
        val name = BarItem("Name", priority = 0, menuLabel = "Name", onMenuClick = null, minWidth = 40.dp, grow = true, growToNatural = true) {
            Box(Modifier.width(100.dp).height(20.dp).semantics { contentDescription = "item:Name" })
        }
        run({ scene(500, items = listOf(name, item("Edit", 1), BarSpacer, item("Apply", 0))) }) {
            frames(60)
            val e = scene.edges()
            assertEquals(100f, e.getValue("Name").width, 1f)
            assertEquals(108f, e.getValue("Edit").left, 1f, "Edit right after the name and the gap")
            assertEquals(500f, e.getValue("Apply").right, 1f)
        }
        run({ scene(180, items = listOf(name, item("Apply", 0))) }) {
            frames(60)
            val e = scene.edges()
            assertEquals(0f, e.getValue("Name").left, 1f)
            assertEquals(72f, e.getValue("Name").width, 1f, "the name shrinks to the room left (180 - Apply 100 - the 8 dp gap)")
            assertEquals(180f, e.getValue("Apply").right, 1f)
        }
    }

    private fun nameItem(natural: Int) =
        BarItem("Name", priority = 0, menuLabel = "Name", onMenuClick = null, minWidth = 110.dp, grow = true, growToNatural = true) {
            Box(Modifier.width(natural.dp).height(20.dp).semantics { contentDescription = "item:Name" })
        }

    @Test
    fun `a name shorter than its min width keeps the next item right after it`() {
        run({ scene(500, items = listOf(nameItem(30), item("Edit", 1), BarSpacer, item("Apply", 0))) }) {
            frames(60)
            val e = scene.edges()
            assertEquals(30f, e.getValue("Name").width, 1f)
            assertEquals(e.getValue("Name").right + 8f, e.getValue("Edit").left, 1f, "Edit right after the name and the gap")
        }
        // Name 30 + Edit 100 + Apply 100 and two gaps fit 250: nothing folds although 110 + 216 would not.
        run({ scene(250, items = listOf(nameItem(30), item("Edit", 1), item("Apply", 0))) }) {
            frames(60)
            assertFalse("More" in scene.labels(), "nothing folds: ${scene.labels()}")
        }
    }

    @Test
    fun `with everything folded the name shrinks below its min width so the priority zero items fit`() {
        run({ scene(170, items = listOf(nameItem(200), item("Edit", 1), item("Apply", 0))) }) {
            frames(120)
            val e = scene.edges()
            assertTrue("More" in scene.labels(), "Edit folded")
            assertEquals(0f, e.getValue("Name").left, 1f)
            assertTrue(e.getValue("Name").width in 1f..109f, "the name shrinks: ${e["Name"]}")
            assertTrue(e.getValue("Apply").right <= e.getValue("Name").right + 8f + 100f + 1f, "Apply right after the name")
            val more = scene.nodes().first { "More" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInWindow
            assertTrue(more.right <= 170f + 0.5f && more.width > 0f, "More inside the bar: $more")
        }
    }

    @Test
    fun `a spacer pushes trailing items to the right edge`() {
        run({ scene(500, items = listOf(item("Edit", 1), BarSpacer, item("Apply", 0))) }) {
            frames(60)
            val e = scene.edges()
            assertEquals(0f, e.getValue("Edit").left, 1f)
            assertEquals(500f, e.getValue("Apply").right, 1f)
        }
    }

    @Test
    fun `without a spacer the bar wraps its content`() {
        run({ scene(600, fixedWidth = false) }) {
            frames(60)
            assertEquals(316f, scene.edges().getValue("Apply").right, 1f)
        }
    }

    @Test
    fun `reduced motion folds without a width animation`() {
        run({ scene(600, reduceMotion = true) }) {
            frames(30)
            assertEquals(100f, scene.edges().getValue("Duplicate").width, "Duplicate is full width before narrowing")
            barWidth = 300
            repeat(40) { n ->
                frame()
                // Folded at once: the node is either still full width or already gone, never partway.
                val dup = scene.edges()["Duplicate"]
                assertTrue(dup == null || dup.width == 100f, "frame $n: Duplicate width ${dup?.width} is never in between")
            }
            assertEquals(null, scene.edges()["Duplicate"], "Duplicate folded")
            assertTrue("More" in scene.labels(), "More visible")
        }
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            run({ scene(300, items = listOf(item("Edit", 1), item("Edit", 2))) }) { frames(2) }
        }
    }
}
