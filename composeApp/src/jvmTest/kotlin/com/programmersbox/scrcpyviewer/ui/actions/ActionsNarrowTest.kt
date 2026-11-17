package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Actions screen at narrow widths, laid out offscreen (1 px = 1 dp) and read off the semantics tree. The widths are
 * the screen's content width: two panes from 260 + 16 + 420 dp, else the full list first and the pushed action after a
 * click. Motion is on and passed explicitly: CI runners report reduce motion on.
 */
private const val SCENE_WIDTH = 1100

/** A 360 dp window less the 60 dp narrow nav rail and its 1 dp divider. */
private const val PHONE = 360 - 60 - 1

/** Just under two panes (260 + 16 + 420 = 696 dp): one pane at a time. 700 dp still has two panes. */
private const val ITEM_VIEW = 680

@OptIn(ExperimentalComposeUiApi::class)
class ActionsNarrowTest {
    private val awake = ToggleAction(
        "awake", "Stay awake", icon = "battery_full", on = "svc power stayon true", off = "svc power stayon false",
        read = "settings get global stay_on_while_plugged_in", onWhen = OnWhen(MatchMode.Equals, "7"),
    )
    private val logcat = RunOnceAction("logcat", "Logcat errors", icon = "terminal", command = "logcat *:E")

    private val toggleDraft = ActionDraft(
        id = "awake", type = ActionType.Toggle, name = "Stay awake", icon = "battery_full",
        on = awake.on, off = awake.off, read = awake.read.orEmpty(), matchValue = "7",
    )

    private val editing = ActionsUiState(
        actions = listOf(awake, logcat),
        selectedId = "awake",
        draft = toggleDraft,
        editorKey = 1,
        devices = listOf("R5CT123ABC"),
        testDevice = "R5CT123ABC",
    )

    private var state by mutableStateOf(editing)
    private var highlightColor = Color.Unspecified
    private var surfaceColor = Color.Unspecified
    private var width = 0
    private lateinit var panes: ListDetailPanes

    /**
     * Runs [block] on a screen [screenWidth] wide showing [start]; when [pushed] (as after a click in the list) a narrow
     * screen shows the open action, else (as at launch) the list.
     */
    private fun scene(screenWidth: Int, start: ActionsUiState = editing, pushed: Boolean = true, block: SceneDriver.() -> Unit) = onEdt {
        state = start
        width = screenWidth
        val driver = SceneDriver(ImageComposeScene(SCENE_WIDTH, 900, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    highlightColor = MaterialTheme.colorScheme.secondaryContainer
                    surfaceColor = MaterialTheme.colorScheme.surface
                    Surface {
                        Box(Modifier.width(screenWidth.dp).fillMaxHeight()) {
                            panes = rememberListDetailPanes()
                            remember(panes) { if (pushed) panes.push() }
                            ActionsContent(
                                state,
                                onEvent = { event ->
                                    if (event is ActionsEvent.Select) {
                                        val action = state.actions.first { it.id == event.id }
                                        state = state.copy(
                                            selectedId = action.id,
                                            draft = ActionDraft(id = action.id, type = ActionType.RunOnce, name = action.name, icon = "terminal"),
                                            editorKey = state.editorKey + 1,
                                        )
                                    }
                                    if (event is ActionsEvent.Delete) {
                                        // As the view model does: the editor closes and the action leaves the list.
                                        state = state.copy(
                                            actions = state.actions.filter { it.id != event.id },
                                            selectedId = null, draft = null, test = null,
                                        )
                                    }
                                    if (event is ActionsEvent.Move) {
                                        // Every test action is in Your actions, so the group index is the list index.
                                        val moved = state.actions.first { it.id == event.id }
                                        state = state.copy(actions = (state.actions - moved).toMutableList().apply { add(event.toIndex, moved) })
                                    }
                                },
                                panes = panes,
                            )
                        }
                    }
                }
            }
        })
        try {
            driver.frames(150)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            // A cleared subtree (the list covered by a pushed detail) is out of the semantics tree.
            if (!node.config.isClearingSemantics) node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.described(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true

    /** On screen: placed with a size, inside the screen's width (or, for [popup] layers, the scene's). */
    private fun SemanticsNode.shown(popup: Boolean = false) =
        boundsInRoot.let { it.width > 0f && it.height > 0f && it.left >= 0f && it.right <= if (popup) SCENE_WIDTH else width }

    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() && it.shown() }
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) && it.shown() }

    /** Clicks the last clickable node (the topmost layer) whose subtree carries [label] as text or description. */
    private fun SceneDriver.click(label: String) {
        fun SemanticsNode.has(): Boolean = label in texts() || described(label) || children.any { it.has() }
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.shown(popup = true) && it.has() }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(150)
    }

    @Test
    fun `wide - both panes`() = scene(1000) {
        assertTrue(text("Logcat errors").isNotEmpty(), "the list shows its rows")
        assertTrue(text("Save").isNotEmpty(), "the editor shows beside the list")
        assertTrue(described("Back to list").isEmpty(), "no back arrow in two panes")
        assertNoStripOrChip()
    }

    /** No node of the removed strip, switcher chip or list overlay exists anywhere, on screen or off. */
    private fun SceneDriver.assertNoStripOrChip() {
        for (label in listOf("Show list", "Switch item", "Close list")) {
            assertTrue(nodes().none { it.described(label) }, "no $label node")
        }
    }

    @Test
    fun `below two panes - the pushed action shows the back arrow, Save and Test run`() = scene(ITEM_VIEW) {
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertNoStripOrChip()
        val run = assertNotNull(text("Test run").firstOrNull(), "Test run").boundsInRoot
        val save = assertNotNull(text("Save").firstOrNull(), "Save").boundsInRoot
        assertTrue(run.right <= save.left, "Test run $run before Save $save")
        // The arrow starts the header row, before the title.
        val back = described("Back to list").first().boundsInRoot
        val title = assertNotNull(text("Edit action").firstOrNull(), "the title").boundsInRoot
        assertTrue(back.right <= title.left && back.bottom > title.top && back.top < title.bottom, "arrow $back before the title $title")
    }

    @Test
    fun `launch - a narrow window shows the full list even with an open action`() = scene(ITEM_VIEW, pushed = false) {
        for (name in listOf("Stay awake", "Logcat errors")) assertTrue(text(name).isNotEmpty(), "$name shows")
        assertTrue(text("Save").isEmpty(), "no editor")
        assertTrue(described("Back to list").isEmpty())
        assertNoStripOrChip()
    }

    @Test
    fun `clicking an action pushes its editor, Back returns to the list`() = scene(ITEM_VIEW, pushed = false) {
        click("Logcat errors")
        assertTrue(panes.pushed)
        assertEquals("logcat", state.selectedId)
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertTrue(text("Save").isNotEmpty(), "the editor")
        assertNoStripOrChip()
        click("Back to list")
        assertTrue(!panes.pushed)
        assertTrue(text("Save").isEmpty(), "the editor left")
        assertTrue(text("Stay awake").isNotEmpty(), "the full list again")
        assertEquals("logcat", state.selectedId, "the selection stays")
    }

    @Test
    fun `the list's New menu opens from its own button in a narrow window`() = scene(ITEM_VIEW, pushed = false) {
        click("More ways to create an action")
        assertTrue(nodes().any { "New blank action" in it.texts() && it.shown(popup = true) }, "New blank action")
        assertTrue(nodes().any { "From template…" in it.texts() && it.shown(popup = true) }, "From template")
    }

    // In item view at 680 the editor (552 dp) keeps Test read beside Value; at phone width Value would get far less than 160 dp.
    @Test
    fun `narrow editor - Test read moves under Value`() = scene(PHONE) {
        val value = assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "7" && it.shown() }, "Value").boundsInRoot
        val read = assertNotNull(text("Test read").firstOrNull(), "Test read").boundsInRoot
        assertTrue(read.top >= value.bottom, "Test read $read under Value $value")
    }

    @Test
    fun `phone window - title, Test run and Save all show inside the editor`() = scene(PHONE) {
        val title = assertNotNull(text("Edit action").firstOrNull(), "the title").boundsInRoot
        val run = button("Test run")
        val save = button("Save")
        // The editor's bars end at its 22 dp end padding.
        val edge = PHONE - 22
        assertTrue(title.width > 0f && title.right <= edge, "title $title")
        assertTrue(run.right <= save.left && save.right <= edge, "Test run $run, Save $save inside $edge")
    }

    /**
     * The unclipped bounds (boundsInRoot is clipped by the bar) of the clickable node whose subtree carries [label] as
     * text or description.
     */
    private fun SceneDriver.button(label: String): Rect {
        fun SemanticsNode.has(): Boolean = label in texts() || described(label) || children.any { it.has() }
        val node = assertNotNull(nodes().firstOrNull { SemanticsActions.OnClick in it.config && it.has() }, "$label button")
        return Rect(node.positionInRoot, node.size.toSize())
    }

    @Test
    fun `phone window - the test output header keeps its label, exit code and line count inside`() = scene(
        PHONE,
        start = editing.copy(
            test = TestResult(running = false, output = "line\n".repeat(5), exitCode = 137, note = "Stopped after 30 s", shownLines = 200),
        ),
    ) {
        // The header sits in the full-width editor's 22 dp side padding.
        val left = 22f
        val right = PHONE - 22f
        val parts = listOf("TEST OUTPUT", "exit 137", "Showing last 200 lines").map { label ->
            val node = assertNotNull(nodes().firstOrNull { label in it.texts() }, label)
            label to Rect(node.positionInRoot, node.size.toSize())
        }
        for ((label, r) in parts) assertTrue(r.width > 0f && r.left >= left && r.right <= right, "$label $r inside $left..$right")
        assertTrue(parts[0].second.right <= parts[1].second.left, "the label before the exit code")
    }

    @Test
    fun `phone window - On when output is a menu button showing equals`() = scene(PHONE) {
        val equals = assertNotNull(text("equals").firstOrNull(), "equals").boundsInRoot
        assertTrue(text("contains").isEmpty(), "no segments")
        assertTrue(equals.right <= PHONE, "equals $equals inside")
        click("equals")
        assertTrue(nodes().any { "matches regex" in it.texts() && it.shown(popup = true) }, "the menu lists every mode")
    }

    private fun SceneDriver.emptyPageButtonsShow() {
        for (label in listOf("+ New", "Browse templates", "Import")) {
            val node = assertNotNull(text(label).firstOrNull(), "$label shows").boundsInRoot
            assertTrue(node.width > 0f && node.right <= width, "$label $node inside $width")
        }
    }

    // The empty page is the detail with nothing open: narrow windows show the list instead, so check it at the
    // narrowest two-pane width, where the detail is at its 420 dp minimum.
    @Test
    fun `empty page - all three buttons show beside the list`() = scene(700, start = ActionsUiState()) { emptyPageButtonsShow() }

    @Test
    fun `phone window with no actions - the list shows its empty text, + New and Import`() = scene(PHONE, start = ActionsUiState()) {
        assertTrue(text("No actions yet. Create one with + New, or import a file.").isNotEmpty(), "the list's empty text")
        for (label in listOf("+ New", "Import")) {
            val node = assertNotNull(text(label).firstOrNull(), "$label shows").boundsInRoot
            assertTrue(node.width > 0f && node.right <= width, "$label $node inside $width")
        }
        assertTrue(described("Back to list").isEmpty(), "nothing pushed")
    }

    /** The colour at ([x], [y]) of the next frame. */
    private fun SceneDriver.pixel(x: Float, y: Float): Color = snapshot()[x.toInt(), y.toInt()]

    private fun close(a: Color, b: Color) =
        kotlin.math.abs(a.red - b.red) < 0.02f && kotlin.math.abs(a.green - b.green) < 0.02f && kotlin.math.abs(a.blue - b.blue) < 0.02f

    /**
     * Material: while the selected row is dragged, the sliding highlight (which reads resting places) is gone and the
     * row carries its own fill; after the drop the highlight is back at the row's new place at once, not sliding there.
     */
    @Test
    fun `dragging the selected row - the highlight moves with it, then snaps to its new place`() = scene(
        1000,
        start = editing.copy(actions = listOf(awake, logcat, RunOnceAction("extra", "Extra", command = "echo"))),
    ) {
        val handles = nodes().filter { it.described("Drag to reorder") }.map { it.boundsInRoot.center }.sortedBy { it.y }
        assertEquals(3, handles.size)
        val step = handles[1].y - handles[0].y
        // Inside the rows, clear of their icons and text.
        val x = 18f
        assertTrue(close(pixel(x, handles[0].y), highlightColor), "the selected (first) row is highlighted")
        val start = handles[0]
        scene.sendPointerEvent(PointerEventType.Move, start, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        for (i in 1..20) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(start.x, start.y + step * i / 20), type = PointerType.Mouse)
            frame()
        }
        frames(30)
        // The second row has moved up into the first slot; the dragged row sits on the second.
        assertTrue(close(pixel(x, handles[0].y), surfaceColor), "no highlight left at the dragged row's resting place")
        // Under the pointer the row also carries its hover layer, so only check that it is filled.
        assertTrue(!close(pixel(x, handles[1].y), surfaceColor), "the dragged row keeps its fill")
        scene.sendPointerEvent(PointerEventType.Release, Offset(start.x, start.y + step), type = PointerType.Mouse, button = PointerButton.Primary)
        // Off the list, so no hover layer covers the rows.
        scene.sendPointerEvent(PointerEventType.Move, Offset(900f, 600f), type = PointerType.Mouse)
        frames(2)
        assertEquals(listOf("logcat", "awake", "extra"), state.actions.map { it.id })
        assertTrue(close(pixel(x, handles[1].y), highlightColor), "the highlight is at the new place at once")
        assertTrue(close(pixel(x, handles[0].y), surfaceColor), "and not left behind")
    }

    @Test
    fun `deleting the open action goes back to the list view`() = scene(ITEM_VIEW) {
        assertTrue(described("Back to list").isNotEmpty())
        if (text("Delete").isEmpty()) click("More")
        click("Delete")
        // The confirmation's Delete (the dialog is the topmost layer).
        click("Delete")
        assertTrue(described("Back to list").isEmpty(), "the pushed editor closed")
        assertTrue(!panes.pushed)
        assertTrue(text("Logcat errors").isNotEmpty(), "the full list")
        assertTrue(text("Save").isEmpty(), "no editor")
    }
}
