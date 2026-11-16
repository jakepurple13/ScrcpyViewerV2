package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AdaptiveSegmentedTest {
    private enum class Mode { System, Light, Dark }

    private val options = listOf(
        SegmentOption(Mode.System, "System", Icons.Filled.SettingsBrightness),
        SegmentOption(Mode.Light, "Light", Icons.Filled.LightMode),
        SegmentOption(Mode.Dark, "Dark", Icons.Filled.DarkMode),
    )

    private fun SceneDriver.nodes(merged: Boolean = false): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun SceneDriver.texts(): Map<String, SemanticsNode> =
        nodes().mapNotNull { n -> n.text()?.let { it to n } }.toMap()

    private fun SceneDriver.clickables() = nodes(merged = true).filter { it.config.contains(SemanticsActions.OnClick) }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    private class Harness {
        var size by mutableStateOf(ControlSize.Full)
        var selected by mutableStateOf(Mode.System)
        var reduceMotion by mutableStateOf(false)
        val picked = mutableListOf<Mode>()
    }

    private fun scene(h: Harness, options: List<SegmentOption<Mode>> = this.options) =
        SceneDriver(ImageComposeScene(600, 300, Density(1f)) {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalReduceMotion provides h.reduceMotion) {
                        AdaptiveSegmentedButtons(options, h.selected, { h.picked += it; h.selected = it }, h.size)
                    }
                }
            }
        }).also { it.frames(10) }

    @Test
    fun `full shows every label`() = onEdt {
        val driver = scene(Harness())
        try {
            val texts = driver.texts()
            assertTrue(listOf("System", "Light", "Dark").all { it in texts }, "texts ${texts.keys}")
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `icons hides labels and keeps three clickable segments`() = onEdt {
        for (reduce in listOf(false, true)) {
            val h = Harness().apply { reduceMotion = reduce }
            val driver = scene(h)
            try {
                h.size = ControlSize.Icons
                driver.frames(90)
                val texts = driver.texts()
                assertTrue(
                    listOf("System", "Light", "Dark").none { name -> texts[name]?.boundsInRoot?.width?.let { it > 0f } == true },
                    "labels gone (reduce=$reduce): ${texts.keys}",
                )
                assertEquals(3, driver.clickables().size, "clickables (reduce=$reduce)")
            } finally {
                driver.scene.close()
            }
        }
    }

    @Test
    fun `label shrink animates`() = onEdt {
        val h = Harness()
        val driver = scene(h)
        try {
            val full = checkNotNull(driver.texts()["Light"]).boundsInRoot.width
            val fullSegment = driver.clickables().maxOf { it.boundsInRoot.width }
            h.size = ControlSize.Icons
            driver.frames(5)
            val mid = checkNotNull(driver.texts()["Light"]) { "label still present mid-shrink" }.boundsInRoot.width
            assertTrue(mid > 0f && mid < full, "mid label width $mid between 0 and $full")
            val midSegment = driver.clickables().maxOf { it.boundsInRoot.width }
            assertTrue(midSegment < fullSegment, "segment $midSegment narrows with the label (full $fullSegment)")
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `menu shows the selection and picks through the dropdown`() = onEdt {
        val h = Harness().apply { size = ControlSize.Menu }
        val driver = scene(h)
        try {
            driver.frames(60)
            val clickables = driver.clickables()
            assertEquals(1, clickables.size)
            // The closed button shows the current choice and only it.
            assertTrue("System" in driver.texts())
            assertTrue("Dark" !in driver.texts())
            driver.click(clickables.single())
            driver.frames(30)
            driver.click(checkNotNull(driver.texts()["Dark"]))
            assertEquals(listOf(Mode.Dark), h.picked)
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `icons without any option icon renders as full`() = onEdt {
        val h = Harness().apply { size = ControlSize.Icons }
        val driver = scene(h, options.map { it.copy(icon = null) })
        try {
            driver.frames(60)
            assertTrue(listOf("System", "Light", "Dark").all { it in driver.texts() })
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `max intrinsic width shrinks Full then Icons then Menu`() = onEdt {
        val widths = HashMap<ControlSize, Int>()
        val scene = ImageComposeScene(800, 300, Density(1f)) {
            MaterialTheme {
                for (size in ControlSize.entries) {
                    Layout(
                        content = { AdaptiveSegmentedButtons(options, Mode.System, {}, size) },
                        modifier = Modifier.width(800.dp),
                    ) { measurables, _ ->
                        widths[size] = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
                        layout(0, 0) {}
                    }
                }
            }
        }
        try {
            SceneDriver(scene).frames(5)
            val (full, icons, menu) = ControlSize.entries.map { widths.getValue(it) }
            assertTrue(full > icons && icons > menu, "full $full > icons $icons > menu $menu")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `full without icons draws like the plain M3 row`() = onEdt {
        val plainOptions = options.map { it.copy(icon = null) }
        fun render(content: @Composable () -> Unit): ByteArray {
            val scene = ImageComposeScene(400, 100, Density(1f)) { MaterialTheme { Surface { content() } } }
            try {
                SceneDriver(scene).frames(10)
                return scene.render(1_000_000_000L).encodeToData()!!.bytes
            } finally {
                scene.close()
            }
        }
        val ours = render { AdaptiveSegmentedButtons(plainOptions, Mode.Light, {}, ControlSize.Full) }
        val plain = render {
            androidx.compose.material3.SingleChoiceSegmentedButtonRow {
                plainOptions.forEachIndexed { i, o ->
                    SegmentedButton(
                        selected = o.value == Mode.Light,
                        onClick = {},
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, plainOptions.size),
                    ) { androidx.compose.material3.Text(o.label) }
                }
            }
        }
        assertTrue(ours.contentEquals(plain), "Full renders identically to the existing row")
    }
}
