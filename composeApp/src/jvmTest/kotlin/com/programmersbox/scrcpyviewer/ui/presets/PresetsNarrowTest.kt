package com.programmersbox.scrcpyviewer.ui.presets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
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
 * The Presets screen at narrow widths, laid out offscreen (1 px = 1 dp) and read off the semantics tree: two panes
 * at 1100; at 700 (below 280 + 16 + 420 dp of content) the full list first and the pushed preset after a click, with
 * "Back to list" returning; one flag column at phone width, and the list view after the open preset is deleted. Motion
 * is on and passed explicitly: CI runners report reduce motion on.
 */
private const val SCENE_WIDTH = 1100

@OptIn(ExperimentalComposeUiApi::class)
class PresetsNarrowTest {
    private val qa = Preset(
        "qa", "QA default", "Sharp video, no audio.",
        OptionValues(mapOf("max-size" to "1920", "max-fps" to "60", "no-audio" to null, "show-touches" to null)),
    )
    private val low = Preset("low", "Low bandwidth", options = OptionValues(mapOf("max-size" to "800", "no-audio" to null)))
    private val demo = Preset("demo", "Record demo", options = OptionValues(mapOf("record" to "demo.mp4")))

    private val base = PresetsUiState(presets = listOf(qa, low, demo), defaultId = "qa", selected = qa, selectedJson = "{}", canApply = true)

    private var state by mutableStateOf(base)
    private var width by mutableIntStateOf(1100)
    private lateinit var panes: ListDetailPanes

    /**
     * Runs [block] on a scene [startWidth] wide whose preset is selected and, when [pushed], open (as after a click in
     * the list); not pushed, as at launch.
     */
    private fun scene(startWidth: Int, pushed: Boolean = true, windowWidth: Int = SCENE_WIDTH, block: SceneDriver.() -> Unit) = onEdt {
        state = base
        width = startWidth
        val driver = SceneDriver(ImageComposeScene(windowWidth, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            panes = rememberListDetailPanes()
                            remember(panes) { if (pushed) panes.push() }
                            PresetsContent(
                                state, onSelect = { id -> state = state.copy(selected = state.presets.first { it.id == id }) }, onNew = { qa }, onEdit = { _, _, _, _ -> }, onDuplicate = {},
                                onDelete = { id ->
                                    // As the view model does: the first remaining preset becomes the selection.
                                    val left = state.presets.filter { it.id != id }
                                    state = state.copy(presets = left, selected = left.firstOrNull())
                                },
                                onSetDefault = {}, onApply = {}, onExport = {}, onExportAll = {}, onImportFile = {}, onImportPaste = {},
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

    /** On screen: placed with a size, inside the screen's current width (or, for [popup] layers, the scene's). */
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

    /** No node of the removed strip, switcher chip or list overlay exists anywhere, on screen or off. */
    private fun SceneDriver.assertNoStripOrChip() {
        for (label in listOf("Show list", "Switch item", "Close list")) {
            assertTrue(nodes().none { it.described(label) }, "no $label node")
        }
    }

    @Test
    fun `the default 800 dp window shows the list alone even with a remembered selection`() = scene(800, pushed = false, windowWidth = 800) {
        assertTrue(text("Low bandwidth").isNotEmpty(), "the list shows")
        assertTrue(text("Apply").isEmpty(), "no detail pane")
        assertTrue(described("Back to list").isEmpty())
    }

    @Test
    fun `a 1000 dp window shows two panes`() = scene(1000, pushed = false, windowWidth = 1000) {
        assertTrue(text("Low bandwidth").isNotEmpty(), "the list shows")
        assertTrue(text("Apply").isNotEmpty(), "the detail shows beside it")
    }

    @Test
    fun `wide - both panes`()= scene(1100) {
        assertTrue(text("Low bandwidth").isNotEmpty(), "the list shows its rows")
        assertTrue(text("Apply").isNotEmpty(), "the detail shows beside the list")
        assertTrue(text("Duplicate").isNotEmpty(), "every header action shows")
        assertTrue(described("Back to list").isEmpty(), "no back arrow in two panes")
        assertNoStripOrChip()
    }

    @Test
    fun `launch - a narrow window shows the full list even with a selection`() = scene(700, pushed = false) {
        for (name in listOf("QA default", "Low bandwidth", "Record demo")) assertTrue(text(name).isNotEmpty(), "$name shows")
        assertTrue(text("Import").isNotEmpty(), "the toolbar's Import")
        assertTrue(text("Apply").isEmpty(), "no detail")
        assertTrue(described("Back to list").isEmpty())
        assertNoStripOrChip()
    }

    @Test
    fun `clicking a preset pushes its detail, Back returns to the list`() = scene(700, pushed = false) {
        click("Low bandwidth")
        assertTrue(panes.pushed)
        assertEquals("low", state.selected?.id)
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertTrue(text("Apply").isNotEmpty(), "the detail")
        assertNoStripOrChip()
        // The arrow starts the header row, before the name.
        val back = described("Back to list").first().boundsInRoot
        val name = text("Low bandwidth").maxBy { it.boundsInRoot.left }.boundsInRoot
        assertTrue(back.right <= name.left && back.bottom > name.top && back.top < name.bottom, "arrow $back before the name $name")
        click("Back to list")
        assertTrue(!panes.pushed)
        assertTrue(text("Apply").isEmpty(), "the detail left")
        assertTrue(text("Import").isNotEmpty(), "the full list again")
        assertEquals("low", state.selected?.id, "the selection stays")
    }

    @Test
    fun `a 600 dp window - Apply and More show and Duplicate is in the menu`() = scene(600) {
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertNoStripOrChip()
        assertTrue(text("Apply").isNotEmpty(), "Apply never folds")
        assertTrue(text("Duplicate").isEmpty(), "Duplicate folded")
        click("More")
        assertTrue(nodes().any { "Duplicate" in it.texts() && it.shown(popup = true) }, "Duplicate in the More menu")
    }

    /** A 360 dp window less the 60 dp narrow nav rail and its 1 dp divider. */
    private val phoneContentWidth = 360 - 60 - 1

    @Test
    fun `phone window - the name, Apply and More all show inside the detail`() = scene(phoneContentWidth) {
        val name = assertNotNull(text("QA default").singleOrNull(), "the header name, the only one on screen").boundsInRoot
        val apply = assertNotNull(text("Apply").firstOrNull(), "Apply").boundsInRoot
        val more = assertNotNull(described("More").firstOrNull(), "More").boundsInRoot
        assertTrue(name.width > 0f && name.right <= apply.left, "name $name before Apply $apply")
        assertTrue(apply.right <= more.left && more.right <= phoneContentWidth, "Apply $apply, More $more inside $phoneContentWidth")
    }

    @Test
    fun `phone window - Copy JSON sits above the JSON text`() = scene(phoneContentWidth) {
        val copy = assertNotNull(text("Copy JSON").firstOrNull() ?: described("Copy JSON").firstOrNull(), "Copy JSON").boundsInRoot
        val json = assertNotNull(nodes().firstOrNull { n -> n.texts().any { it.startsWith("{") } }, "the JSON").boundsInRoot
        assertTrue(copy.bottom <= json.top + 1f, "Copy JSON $copy above the text $json")
    }

    @Test
    fun `phone width - one flag cell per row`() = scene(400) {
        val flags = listOf("--max-size=1920", "--max-fps=60", "--no-audio", "--show-touches")
        val tops = flags.map { f -> assertNotNull(nodes().firstOrNull { f in it.texts() }, "cell $f").boundsInRoot.top }
        assertTrue(tops.distinct().size == flags.size, "no two cells share a row: $tops")
    }

    @Test
    fun `wide - two flag cells per row`() = scene(1100) {
        val tops = listOf("--max-size=1920", "--max-fps=60").map { f -> assertNotNull(text(f).firstOrNull(), "cell $f").boundsInRoot.top }
        assertTrue(tops[0] == tops[1], "the first two cells share a row: $tops")
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun SceneDriver.key(key: Key, meta: Boolean = false, ctrl: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key = key, type = type, isMetaPressed = meta, isCtrlPressed = ctrl))
            frame()
        }
    }

    @Test
    fun `in the editor's name field Esc stays with the field and the bracket shortcut goes back`() = scene(700) {
        click("Edit")
        assertTrue(text("Edit preset").isNotEmpty(), "the editor")
        val field = assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "QA default" },
            "the name field",
        )
        field.config[SemanticsActions.RequestFocus].action?.invoke()
        frames(30)
        assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.getOrNull(SemanticsProperties.EditableText) != null }, "the field has focus")
        key(Key.Escape)
        frames(60)
        assertTrue(panes.pushed, "Esc stayed with the field")
        key(Key.LeftBracket, meta = Os.isMac, ctrl = !Os.isMac)
        frames(90)
        assertTrue(!panes.pushed, "the bracket shortcut went back")
    }

    @Test
    fun `Esc goes back when no text field has focus`() = scene(700) {
        assertTrue(panes.pushed)
        key(Key.Escape)
        frames(90)
        assertTrue(!panes.pushed)
    }

    @Test
    fun `deleting the open preset goes back to the list view`() = scene(700) {
        assertTrue(described("Back to list").isNotEmpty())
        if (text("Delete").isEmpty()) click("More")
        click("Delete")
        // The confirmation's Delete (the dialog is the topmost layer).
        click("Delete")
        assertTrue(described("Back to list").isEmpty(), "the pushed detail closed")
        assertTrue(!panes.pushed)
        assertTrue(text("Low bandwidth").isNotEmpty(), "the full list")
        assertTrue(text("Apply").isEmpty(), "no detail")
    }
}
