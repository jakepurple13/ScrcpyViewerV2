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
 * wide, the strip beside the open device below the two-pane threshold (240 + 16 + 420 dp of content), the full list
 * from "Show list", and folding bars at phone width. Motion is on and passed explicitly: CI runners report reduce
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

    /** Runs [block] on a scene [startWidth] wide whose device is open (as after a click in the list). */
    private fun scene(startWidth: Int, startState: DevicesUiState = base, block: SceneDriver.() -> Unit) = onEdt {
        state = startState
        width = startWidth
        val driver = SceneDriver(ImageComposeScene(1000, 700, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            panes = rememberListDetailPanes()
                            remember(panes) { panes.navigation.openDetail() }
                            DevicesContent(
                                state, onSelect = {}, onOpen = {}, onQuery = {}, onGroup = {}, onToggle = { _, _ -> }, onValue = { _, _ -> },
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
            node.children.forEach(::walk)
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
        assertTrue(described("Show list").isEmpty(), "no strip in two panes")
        assertTrue(described("Switch item").isEmpty(), "no switcher chip in two panes")
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

    @Test
    fun `the switcher chip shares the tab line when it fits and takes its own line when not`() = scene(640) {
        fun chip() = described("Switch item").first().boundsInRoot
        // The selected tab: a segment, or the menu button once the pane is narrower than 344 dp.
        fun tabs() = text("Options").first().boundsInRoot
        assertTrue(chip().bottom > tabs().top && chip().top < tabs().bottom, "one line at 640: chip ${chip()}, tabs ${tabs()}")
        width = 360
        frames(150)
        assertTrue(chip().bottom <= tabs().top, "own line at 360: chip ${chip()}, tabs ${tabs()}")
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
    fun `below two panes - strip, switcher chip, Mirror and no back button`() = scene(640) {
        assertTrue(described("Show list").isNotEmpty(), "the strip's Show list button")
        assertTrue(described("Switch item").isNotEmpty(), "the switcher chip")
        assertTrue(text("Mirror").isNotEmpty(), "Mirror")
        assertTrue(nodes().none { it.described("Back to list") }, "no back button")
        assertTrue(described("Pair over Wi-Fi").isNotEmpty(), "the strip's Pair over Wi-Fi icon")
    }

    @Test
    fun `Show list brings back the full list and closes the device`() = scene(640) {
        click("Show list")
        for (name in listOf("Pixel 8 Pro", "SM X710", "ZY22H8KLMN")) {
            assertTrue(text(name).any { it.fullyShown() }, "$name shows in full")
        }
        assertTrue(text("Mirror").isEmpty(), "the detail is gone")
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
        assertTrue(described("Switch item").isNotEmpty())
        state = state.copy(devices = listOf(tablet, pending), selectedSerial = tablet.serial)
        frames(150)
        assertTrue(described("Switch item").isEmpty(), "item view closed")
        assertTrue(described("Show list").isEmpty(), "no strip in the list view")
        assertTrue(text("SM X710").any { it.fullyShown() }, "the full list")
    }

    @Test
    fun `option columns - two in a wide pane, one in a narrow one`() = scene(1000, base.copy(advanced = true)) {
        fun header(name: String) = assertNotNull(nodes().firstOrNull { name.uppercase() in it.texts() }, "header $name").rect()
        val groups = OptionCatalog.all.map { it.group }.distinct()
        val lefts = groups.map { header(it.label).left }.distinct()
        assertEquals(2, lefts.size, "two columns: $lefts")
        width = 640
        frames(150)
        val narrowLefts = groups.map { header(it.label).left }.distinct()
        assertEquals(1, narrowLefts.size, "one column: $narrowLefts")
    }
}
