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

    private fun panel(
        state: () -> QuickActionsUiState,
        events: MutableList<QuickActionEvent>,
        motion: Boolean = true,
    ): SceneDriver =
        SceneDriver(ImageComposeScene(600, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state(), { events += it }, console = null, motion = motion) }
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

    private fun busy(tab: SheetTab, logcatPopped: Boolean) = QuickActionsUiState(
        open = true, enabled = true, tab = tab, consoleRunning = true, filesTransfersRunning = 2,
        logcatPopped = logcatPopped, logcatLive = logcatPopped,
    )

    @Test
    fun `segment labels fit at the Actions width, with the Shell dot and the Files badge`() = onEdt {
        assertSegmentsFit(QUICK_ACTIONS_WIDTH.value.toInt(), busy(SheetTab.Actions, logcatPopped = false))
    }

    @Test
    fun `at the Actions and Shell widths a popped Logcat segment shows the window icon with the live badge instead`() = onEdt {
        assertSegmentsFit(QUICK_ACTIONS_WIDTH.value.toInt(), busy(SheetTab.Actions, logcatPopped = true), logcatIconOnly = true)
        assertSegmentsFit(QUICK_ACTIONS_SHELL_WIDTH.value.toInt(), busy(SheetTab.Shell, logcatPopped = true), logcatIconOnly = true)
    }

    @Test
    fun `segment labels fit at the Logcat width, with the Logcat window icon and live dot beside the label`() = onEdt {
        assertSegmentsFit(QUICK_ACTIONS_LOGCAT_WIDTH.value.toInt(), busy(SheetTab.Logcat, logcatPopped = true))
    }

    private fun assertSegmentsFit(width: Int, state: QuickActionsUiState, logcatIconOnly: Boolean = false) {
        val driver = SceneDriver(ImageComposeScene(width, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) { QuickActionsPanel(state, {}, console = null) }
            }
        })
        driver.frames(60) // the Files badge has scaled in
        val tabs = driver.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(SheetTab.entries.size, tabs.size)
        fun descendants(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + descendants(it) }
        fun SemanticsNode.description() = config.getOrNull(SemanticsProperties.ContentDescription)?.first()
        for (tab in tabs) {
            if (logcatIconOnly && tab === tabs[SheetTab.Logcat.ordinal]) {
                // The label gave way to the window icon, which names the segment; the dot is a badge on its corner.
                val nodes = descendants(tab)
                assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.Text) != null }, "the hidden label is not read out")
                assertTrue(nodes.none { it.description() == "In its own window" }, "no icon beside the label")
                val icon = checkNotNull(nodes.firstOrNull { it.description() == "Logcat, open in its own window" }) { "the window icon" }
                val dot = checkNotNull(nodes.firstOrNull { it.description() == "Logcat running" }) { "the live badge" }
                assertEquals(14f, icon.boundsInRoot.width)
                assertTrue(dot.boundsInRoot.width >= 6f, "the badge is squeezed to ${dot.boundsInRoot.width} px")
                for (n in listOf(icon, dot)) {
                    val b = n.boundsInRoot
                    assertTrue(b.left >= tab.boundsInRoot.left && b.right <= tab.boundsInRoot.right, "${n.description()} spills out at $width")
                    assertTrue(b.top >= tab.boundsInRoot.top, "${n.description()} spills over the top at $width")
                }
                val i = icon.boundsInRoot
                assertTrue(dot.boundsInRoot.center.x > i.center.x && dot.boundsInRoot.center.y < i.center.y, "the badge sits on the top-end corner")
                continue
            }
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
            val extras = descendants(tab).filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
            for (extra in extras) {
                val what = extra.config[SemanticsProperties.ContentDescription].first()
                val minWidth = when (what) { "Command running", "Logcat running" -> 6f; "In its own window" -> 14f; else -> 16f }
                assertTrue(extra.boundsInRoot.width >= minWidth, "$what in $name is squeezed to ${extra.boundsInRoot.width} px")
                assertTrue(extra.boundsInRoot.right <= tab.boundsInRoot.right, "$what spills out of $name")
                assertTrue(extra.boundsInRoot.left >= label.boundsInRoot.right, "$what overlaps the $name label")
            }
            when (name) {
                "Shell" -> assertEquals(listOf("Command running"), extras.map { it.config[SemanticsProperties.ContentDescription].first() })
                "Files" -> assertEquals(listOf("2 transfers running"), extras.map { it.config[SemanticsProperties.ContentDescription].first() })
                "Logcat" -> if (state.logcatPopped) {
                    assertEquals(listOf("Logcat running", "In its own window"), extras.map { it.config[SemanticsProperties.ContentDescription].first() })
                }
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
    fun `a narrow popped Logcat segment cross-fades its label to the icon, and back as the sheet widens`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true)) // Actions: 280 dp
        val driver = panel({ state }, mutableListOf())
        state = state.copy(logcatPopped = true, logcatLive = true)
        driver.frames(3)
        // Mid-fade, the icon is already the segment's name (decided at once, from the width), the label is going.
        assertNotNull(driver.described("Logcat, open in its own window"))
        driver.frames(60)
        assertNotNull(driver.described("Logcat, open in its own window"))
        assertNotNull(driver.described("Logcat running"))
        assertNull(driver.described("In its own window"))

        // To the Logcat tab (560 dp): from the first frame of the sheet's growth, the label comes back with the marks
        // beside it, rather than snapping when the width gets there.
        state = state.copy(tab = SheetTab.Logcat)
        driver.frames(2)
        assertNull(driver.described("Logcat, open in its own window"))
        assertNotNull(driver.described("In its own window"))
        driver.frames(60)
        assertNotNull(driver.described("Logcat running"))
        assertEquals(14f, checkNotNull(driver.described("In its own window")).boundsInRoot.width)
    }

    @Test
    fun `segment shows the window icon, and the live dot only while running`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Logcat))
        val driver = panel({ state }, mutableListOf())
        assertNull(driver.described("In its own window"))
        assertNull(driver.described("Logcat running"))

        state = state.copy(logcatPopped = true)
        driver.frames(3)
        // Mid-way, the icon is still growing from 0 to 14 dp.
        val growing = checkNotNull(driver.described("In its own window")).boundsInRoot.width
        assertTrue(growing < 14f, "the icon grows in, $growing px after 3 frames")
        driver.frames(60)
        assertEquals(14f, checkNotNull(driver.described("In its own window")).boundsInRoot.width)
        assertNull(driver.described("Logcat running"))

        state = state.copy(logcatLive = true)
        driver.frames(60)
        assertNotNull(driver.described("Logcat running"))

        state = state.copy(logcatLive = false)
        driver.frames(60)
        assertNull(driver.described("Logcat running"))
        assertNotNull(driver.described("In its own window"))

        state = state.copy(logcatPopped = false)
        driver.frames(60)
        assertNull(driver.described("In its own window"))
    }

    @Test
    fun `under reduced motion the Logcat segment's icon, live dot and badge snap`() = onEdt {
        // Settled sizes, from a run with motion.
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Logcat))
        val moving = panel({ state }, mutableListOf())
        state = state.copy(logcatPopped = true, logcatLive = true)
        moving.frames(1)
        val dotMidway = checkNotNull(moving.described("Logcat running")).boundsInRoot.width
        moving.frames(60)
        val dotSize = checkNotNull(moving.described("Logcat running")).boundsInRoot.width
        assertTrue(dotMidway < dotSize, "with motion the dot scales in: $dotMidway then $dotSize")
        moving.scene.close()

        var still by mutableStateOf(QuickActionsUiState(open = true, enabled = true, tab = SheetTab.Logcat))
        val driver = panel({ still }, mutableListOf(), motion = false)
        still = still.copy(logcatPopped = true, logcatLive = true)
        driver.frames(2)
        assertEquals(14f, checkNotNull(driver.described("In its own window")).boundsInRoot.width)
        assertEquals(dotSize, checkNotNull(driver.described("Logcat running")).boundsInRoot.width)

        // Narrow (Actions): the label gives way to the icon with its badge at once, and both go at once.
        still = still.copy(tab = SheetTab.Actions)
        driver.frames(2)
        assertNull(driver.described("In its own window"))
        val badge = checkNotNull(driver.described("Logcat running")).boundsInRoot.width
        driver.frames(60)
        assertEquals(badge, checkNotNull(driver.described("Logcat running")).boundsInRoot.width)
        still = still.copy(logcatPopped = false, logcatLive = false)
        driver.frames(2)
        assertNull(driver.described("Logcat running"))
        assertNull(driver.described("Logcat, open in its own window"))
        driver.scene.close()
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
