package com.programmersbox.scrcpyviewer.ui.devices

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
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
 * The Devices screen at narrow widths, laid out offscreen (1 px = 1 dp) and read off the semantics tree: two panes when
 * wide; below the two-pane threshold (240 + 16 + 420 dp of content) the full list first, the pushed device detail after
 * a click with "Back to list" returning; and folding bars at phone width. Motion is on and passed explicitly: CI runners report reduce
 * motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DevicesNarrowTest {
    private val pixel = Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35))
    private val tablet = Device("R52W70ABCDE", DeviceState.Device, "device", "SM_X710", null, 2, false)
    private val pending = Device("ZY22H8KLMN", DeviceState.Unauthorized, "unauthorized", null, null, null, false)
    private val values = OptionValues(mapOf("max-size" to "1920", "no-audio" to null, "video-codec" to "h265"))

    private val base = DevicesUiState(
        devices = listOf(pixel, tablet, pending),
        selectedSerial = pixel.serial,
        visibleOptions = OptionCatalog.all,
        values = values,
        presetLabel = "QA default (modified)",
        command = "scrcpy -s 39121FDJH004AB --max-size=1920",
        canMirror = true,
        adbConnected = true,
        hasBasePreset = true,
        isModified = true,
        advanced = false,
    )

    private var state by mutableStateOf(base)
    private var width by mutableIntStateOf(1000)
    private lateinit var panes: ListDetailPanes

    /**
     * Runs [block] on a scene [startWidth] wide whose device is selected and, when [pushed], open (as after a click in
     * the list); not pushed, as at launch.
     */
    private fun scene(startWidth: Int, startState: DevicesUiState = base, pushed: Boolean = true, block: SceneDriver.() -> Unit) = onEdt {
        state = startState
        width = startWidth
        val driver = SceneDriver(ImageComposeScene(1000, 700, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            panes = rememberListDetailPanes()
                            remember(panes) { if (pushed) panes.push() }
                            DevicesContent(
                                state, onSelect = { serial -> state = state.copy(selectedSerial = serial) }, onOpen = {}, onQuery = {}, onGroup = {}, onToggle = { _, _ -> }, onValue = { _, _ -> },
                                onSave = {}, onSaveAsNew = {}, onMirror = {}, panes = panes, motion = true,
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

    /** Bounds not clipped by a scroll container: rows below the fold keep their size. */
    private fun SemanticsNode.rect() = Rect(positionInRoot, size.toSize())

    /** On screen: placed with a size, inside the scene's width. */
    private fun Rect.shown() = width > 0f && height > 0f && left >= 0f && right <= 1000f

    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() && it.boundsInRoot.shown() }
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) && it.boundsInRoot.shown() }

    /** The whole text is laid out: no line ends in an ellipsis. */
    private fun SemanticsNode.fullyShown(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.isNotEmpty() && layouts.none { l -> (0 until l.lineCount).any(l::isLineEllipsized) }
    }

    /** Clicks the button whose icon (or itself) carries [description]. */
    private fun SceneDriver.click(description: String) {
        fun SemanticsNode.has(): Boolean = described(description) || children.any { it.has() }
        val node = assertNotNull(nodes().firstOrNull { SemanticsActions.OnClick in it.config && it.has() }, "no $description button")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(150)
    }

    @Test
    fun `wide - both panes with the list row texts visible`() = scene(1000) {
        assertTrue(text("Pixel 8 Pro").any { it.boundsInRoot.right <= 300f && it.fullyShown() }, "the list row title shows in full")
        assertTrue(text("SM X710").isNotEmpty(), "the second row shows")
        assertTrue(text("Mirror").isNotEmpty(), "the detail shows beside the list")
        assertTrue(described("Back to list").isEmpty(), "no back arrow in two panes")
        assertNoStripOrChip()
        // The status chip sits right after the name, as before, not at the row's end.
        val title = text("Pixel 8 Pro").minBy { it.boundsInRoot.left }.boundsInRoot
        val chip = text("USB").minBy { it.boundsInRoot.top }.boundsInRoot
        assertTrue(chip.left - title.right in 0f..30f, "chip after the name: title $title, chip $chip")
    }

    private fun SceneDriver.tagged(tag: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /** Clicks the clickable node (a menu item) that shows [label]. */
    private fun SceneDriver.clickText(label: String) {
        fun SemanticsNode.has(): Boolean = label in texts() || children.any { it.has() }
        val node = assertNotNull(nodes().firstOrNull { SemanticsActions.OnClick in it.config && it.has() }, "no $label item")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(60)
    }

    /** No node of the removed strip, switcher chip or list overlay exists anywhere, on screen or off. */
    private fun SceneDriver.assertNoStripOrChip() {
        for (label in listOf("Show list", "Switch item", "Close list")) {
            assertTrue(nodes().none { it.described(label) }, "no $label node")
        }
    }

    @Test
    fun `the back arrow shares the tab line, clear of the tabs`() = scene(640) {
        // The selected tab: a segment, or the menu button once the pane is narrower than 398 dp.
        fun tabs() = text("Options").first().boundsInRoot
        for (w in listOf(640, 360, 299)) {
            width = w
            frames(150)
            val back = assertNotNull(described("Back to list").firstOrNull(), "back arrow at $w").boundsInRoot
            assertTrue(back.bottom > tabs().top && back.top < tabs().bottom, "one line at $w: back $back, tabs ${tabs()}")
            assertTrue(back.right <= tabs().left, "the arrow ends before the tabs at $w: back $back, tabs ${tabs()}")
            assertTrue(back.left >= 0f && tabs().right <= w, "inside $w")
        }
    }

    @Test
    fun `launch - a narrow window shows the full list even with a selection`() = scene(640, pushed = false) {
        for (name in listOf("Pixel 8 Pro", "SM X710", "ZY22H8KLMN")) {
            assertTrue(text(name).any { it.fullyShown() }, "$name shows in full")
        }
        assertTrue(text("Mirror").isEmpty(), "no detail")
        assertTrue(described("Back to list").isEmpty(), "no back arrow")
        assertNoStripOrChip()
    }

    @Test
    fun `clicking a row pushes its detail, Back returns to the list`() = scene(640, pushed = false) {
        clickText("SM X710")
        frames(90)
        assertTrue(panes.pushed, "the click pushed")
        assertEquals(tablet.serial, state.selectedSerial)
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertTrue(text("Mirror").isNotEmpty(), "the detail's Mirror")
        assertNoStripOrChip()
        click("Back to list")
        assertTrue(!panes.pushed)
        assertTrue(text("Mirror").isEmpty(), "the detail left")
        assertTrue(text("Pixel 8 Pro").any { it.fullyShown() }, "the full list again")
        assertEquals(tablet.serial, state.selectedSerial, "the selection stays")
    }

    @Test
    fun `the folded search's popup closes when the window widens`() = scene(360, base.copy(advanced = true)) {
        click("More")
        clickText("Search options")
        assertTrue(tagged("option-search-popup").isNotEmpty(), "the search popup opened")
        width = 1000
        frames(150)
        assertTrue(tagged("option-search-popup").isEmpty(), "the popup closed: the bar shows the field again")
        assertTrue(text("Search ${OptionCatalog.all.size} options").isNotEmpty(), "the inline search field")
    }

    @Test
    fun `below two panes - the pushed device fills the width with Mirror and the back arrow`() = scene(640) {
        assertTrue(text("Mirror").isNotEmpty(), "Mirror")
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        assertTrue(text("Pair over Wi-Fi").isEmpty(), "the covered list's Pair over Wi-Fi is not on screen")
        assertNoStripOrChip()
    }

    @Test
    fun `phone width in advanced - Mirror and More show, option labels keep 140 dp or the field goes under`() =
        scene(360, base.copy(advanced = true)) {
            assertTrue(text("Mirror").isNotEmpty(), "Mirror never folds")
            assertTrue(described("More").isNotEmpty(), "the overflow button")
            val nodes = nodes()
            fun tagged(tag: String) = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.rect()
            val valued = OptionCatalog.all.filter { it.type !is OptionType.Flag }
            assertTrue(valued.isNotEmpty())
            for (spec in valued) {
                val label = assertNotNull(tagged("option-label:${spec.flag}"), "label of ${spec.flag}")
                val field = assertNotNull(tagged("option-field:${spec.flag}"), "field of ${spec.flag}")
                assertTrue(label.width >= 140f || field.top >= label.bottom, "${spec.flag}: label $label, field $field")
            }
        }

    @Test
    fun `the open device leaving the list goes back to the list view`() = scene(640) {
        assertTrue(described("Back to list").isNotEmpty())
        state = state.copy(devices = listOf(tablet, pending), selectedSerial = tablet.serial)
        frames(150)
        assertTrue(described("Back to list").isEmpty(), "the pushed detail closed")
        assertTrue(!panes.pushed)
        assertTrue(text("SM X710").any { it.fullyShown() }, "the full list")
    }

    @Test
    fun `option columns - two in a wide pane, one in a narrow one`() = scene(1000, base.copy(advanced = true)) {
        fun header(name: String) = assertNotNull(nodes().firstOrNull { name.uppercase() in it.texts() }, "header $name").rect()
        val groups = OptionCatalog.all.map { it.group }.distinct()
        val lefts = groups.map { header(it.label).left }.distinct()
        assertEquals(2, lefts.size, "two columns: $lefts")
        width = 520 // the pushed detail fills the width: under the 560 dp two-column threshold
        frames(150)
        val narrowLefts = groups.map { header(it.label).left }.distinct()
        assertEquals(1, narrowLefts.size, "one column: $narrowLefts")
    }
}
