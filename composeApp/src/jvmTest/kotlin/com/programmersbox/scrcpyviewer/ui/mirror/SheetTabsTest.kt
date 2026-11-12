package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.Role
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sheet's four-segment tab control, its slide between tabs and the Files badge. */
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
    fun `four tabs, Files selects, and the header follows the tab`() = onEdt {
        val events = mutableListOf<QuickActionEvent>()
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        val driver = panel({ state }, events)

        val tabs = driver.nodes(merged = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(
            listOf("Actions", "Apps", "Shell", "Files"),
            tabs.map { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: error("tab without text") },
        )
        assertTrue("Runs adb shell on this device" in driver.texts())

        driver.click(tabs.last())
        assertEquals(listOf<QuickActionEvent>(QuickActionEvent.SelectTab(SheetTab.Files)), events)

        state = state.copy(tab = SheetTab.Files)
        driver.frames(5)
        assertTrue("Browse and move files on this device" in driver.texts())
        assertNull(driver.texts()["Runs adb shell on this device"])
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
    fun `the highlight sits under the selected segment`() = onEdt {
        var state by mutableStateOf(QuickActionsUiState(open = true, enabled = true))
        val driver = panel({ state }, mutableListOf())
        state = state.copy(tab = SheetTab.Files)
        driver.frames(60)
        val files = checkNotNull(driver.texts()["Files"]).boundsInRoot
        val image = driver.scene.render(10_000_000_000L)
        // The surface-colored highlight (white in the light theme) is under Files' label, not under Actions'.
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        fun px(at: Offset) = bitmap.getColor(at.x.toInt(), (files.center.y).toInt())
        val actions = checkNotNull(driver.texts()["Actions"]).boundsInRoot
        assertTrue(px(Offset(files.left - 4f, 0f)) != px(Offset(actions.left - 4f, 0f)))
    }
}
