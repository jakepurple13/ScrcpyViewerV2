package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/** The sheet's five-segment tab control, its slide between tabs and the Files badge. */
@OptIn(ExperimentalComposeUiApi::class)
class SheetTabsTest {
    private fun SceneDriver.nodes(merged: Boolean = false): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.texts(): Map<String, SemanticsNode> = nodes().mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.let { it to node }
    }.toMap()

    private fun SceneDriver.described(description: String): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(5)
    }

    private fun panel(state: () -> QuickActionsUiState, events: MutableList<QuickActionEvent>): SceneDriver =
        SceneDriver(ImageComposeScene(600, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state(), { events += it }, console = null) }
            }
        }).also { it.frames(5) }

    @Test
    fun `slide enters from the right to a later tab and from the left to an earlier one`() = onEdt {
        var tab by mutableStateOf(SheetTab.Actions)
        val driver = SceneDriver(ImageComposeScene(600, 200, Density(1f)) {
            Surface(Modifier.fillMaxSize()) {
                AnimatedContent(tab, transitionSpec = { slideTransform(initialState, targetState, Density(1f)) }) {
                    Text(it.name)
                }
            }
        })
        driver.frames(40)
        val rest = checkNotNull(driver.texts()["Actions"]).positionInRoot.x

        fun enterOffset(to: SheetTab): Float {
            tab = to
            driver.frames(3)
            val entering = checkNotNull(driver.texts()[to.name]).positionInRoot.x - rest
            driver.frames(60)
            return entering
        }

        for (from in SheetTab.entries) for (to in SheetTab.entries) {
            if (from == to) continue
            if (tab != from) enterOffset(from)
            val offset = enterOffset(to)
            if (to.ordinal > from.ordinal) {
                assertTrue(offset > 0f, "$from to $to enters from the right")
            } else {
                assertTrue(offset < 0f, "$from to $to enters from the left")
            }
        }
    }

    @Test
    fun `five tabs, Logcat selects, and the header follows the tab`() = onEdt {
        val events = mutableListOf<QuickActionEvent>()
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        val driver = panel({ state }, events)

        val tabs = driver.nodes(merged = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(
            listOf("Actions", "Apps", "Shell", "Files", "Logcat"),
            tabs.map { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: error("tab without text") },
        )
        assertTrue("Runs adb shell on this device" in driver.texts())

        driver.click(tabs[SheetTab.Files.ordinal])
        assertEquals(listOf<QuickActionEvent>(QuickActionEvent.SelectTab(SheetTab.Files)), events)

        state = state.copy(tab = SheetTab.Files)
        driver.frames(5)
        assertTrue("Browse and move files on this device" in driver.texts())
        assertNull(driver.texts()["Runs adb shell on this device"])
        // No Files tab content: there is no adb, and the tab says so instead of showing nothing.
        assertTrue("adb isn’t available" in driver.texts(), driver.texts().keys.toString())

        events.clear()
        driver.click(tabs[SheetTab.Logcat.ordinal])
        assertEquals(listOf<QuickActionEvent>(QuickActionEvent.SelectTab(SheetTab.Logcat)), events)
        state = state.copy(tab = SheetTab.Logcat)
        driver.frames(40)
        assertTrue("Live log from this device" in driver.texts())
        assertNull(driver.texts()["Browse and move files on this device"])
        // No logcat controller means no adb, said the same way.
        assertTrue("adb isn’t available" in driver.texts(), driver.texts().keys.toString())
    }

    @Test
    fun `segment labels fit at the Actions width, with the Shell dot and the Files badge`() = onEdt {
        val state = QuickActionsUiState(open = true, enabled = true, consoleRunning = true, filesTransfersRunning = 2)
        val driver = SceneDriver(ImageComposeScene(QUICK_ACTIONS_WIDTH.value.toInt(), 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state, {}, console = null) }
            }
        })
        driver.frames(60) // the Files badge has scaled in
        val tabs = driver.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(SheetTab.entries.size, tabs.size)
        for (tab in tabs) {
            val label = tab.children.first { it.config.getOrNull(SemanticsProperties.Text) != null }
            val name = label.config[SemanticsProperties.Text].first().text
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            // Not hasVisualOverflow: the semantics action lays the paragraph out at the full max width, so a label that
            // fits still reports a width overflow. The label's natural width against the width it may take is exact.
            val natural = layout.multiParagraph.intrinsics.maxIntrinsicWidth
            val room = layout.layoutInput.constraints.maxWidth
            assertTrue(natural <= room, "$name is $natural px wide in a $room px segment")
            assertFalse(layout.didOverflowHeight, "$name is cut off")
            // The dot and the badge keep their full size and stay inside the segment, after the label.
            // The badge sits under its AnimatedVisibility's own node: search the segment, not just its children.
            fun descendants(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + descendants(it) }
            val extras = descendants(tab).filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
            for (extra in extras) {
                val what = extra.config[SemanticsProperties.ContentDescription].first()
                val minWidth = if (what == "Command running") 6f else 16f
                assertTrue(extra.boundsInRoot.width >= minWidth, "$what in $name is squeezed to ${extra.boundsInRoot.width} px")
                assertTrue(extra.boundsInRoot.right <= tab.boundsInRoot.right, "$what spills out of $name")
                assertTrue(extra.boundsInRoot.left >= label.boundsInRoot.right, "$what overlaps the $name label")
            }
            when (name) {
                "Shell" -> assertEquals(listOf("Command running"), extras.map { it.config[SemanticsProperties.ContentDescription].first() })
                "Files" -> assertEquals(listOf("2 transfers running"), extras.map { it.config[SemanticsProperties.ContentDescription].first() })
            }
        }
    }

    @Test
    fun `segment labels ease to the larger size as the sheet grows, without a snap`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        val driver = SceneDriver(ImageComposeScene(800, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state, {}, console = null) }
            }
        })
        driver.frames(5)
        fun labelSize(): Float {
            val label = driver.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                .first().children.first { it.config.getOrNull(SemanticsProperties.Text) != null }
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            return layouts.single().layoutInput.style.fontSize.value
        }
        val small = labelSize()
        state = state.copy(tab = SheetTab.Apps) // 280 dp to 480 dp
        driver.frames(6)
        val mid = labelSize()
        driver.frames(60)
        val large = labelSize()
        assertTrue(small < large, "$small then $large")
        assertTrue(mid > small && mid < large, "mid-switch $mid is between $small and $large")
    }

    @Test
    fun `the Files badge shows the running transfer count`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        val driver = panel({ state }, mutableListOf())
        assertNull(driver.described("2 transfers running"))

        state = state.copy(filesTransfersRunning = 2)
        driver.frames(40)
        assertNotNull(driver.described("2 transfers running"))
        assertTrue("2" in driver.texts())

        state = state.copy(filesTransfersRunning = 1)
        driver.frames(40)
        assertNotNull(driver.described("1 transfer running"))

        state = state.copy(filesTransfersRunning = 0)
        driver.frames(60)
        assertNull(driver.described("1 transfer running"))
    }

    @Test
    fun `the Shell dot still shows while a command runs`() = onEdt {
        val driver = panel({ QuickActionsUiState(open = true, enabled = true, consoleRunning = true) }, mutableListOf())
        assertNotNull(driver.described("Command running"))
    }

    @Test
    fun `the highlight sits under the selected segment only`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        var scheme: ColorScheme? = null
        val driver = SceneDriver(ImageComposeScene(600, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                scheme = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state, {}, console = null) }
            }
        })
        driver.frames(5)
        val colors = checkNotNull(scheme)
        for (selected in SheetTab.entries) {
            state = state.copy(tab = selected)
            driver.frames(80)
            val bitmap = Bitmap.makeFromImage(driver.scene.render(100_000_000_000L + selected.ordinal * 1_000_000_000L))
            // A point inside each segment, left of its label, at the label's height.
            for (segment in SheetTab.entries) {
                val label = checkNotNull(driver.texts()[segment.name]).boundsInRoot
                val pixel = bitmap.getColor((label.left - 6f).toInt(), label.center.y.toInt())
                val expected = if (segment == selected) colors.surface else colors.surfaceContainerHighest
                assertEquals(expected.toArgb(), pixel, "$segment segment while $selected is selected")
            }
        }
    }
}
