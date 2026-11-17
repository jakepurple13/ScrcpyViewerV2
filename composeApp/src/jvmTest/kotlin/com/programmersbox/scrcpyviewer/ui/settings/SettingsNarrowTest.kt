package com.programmersbox.scrcpyviewer.ui.settings

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Settings screen at narrow widths, laid out offscreen (1 px = 1 dp) and read off the semantics tree: two panes in
 * a 1000 dp window; below 840 dp the category list first, a picked category pushed over it with "Back to list"; rows
 * that keep their text's room; Launch & quit tiles that shrink to the row and glide when they wrap. Motion is passed
 * explicitly: CI runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsNarrowTest {
    private var nav by mutableStateOf(SettingsNav())
    private var width by mutableIntStateOf(1000)
    private var windowWidth = 1000
    private lateinit var panes: ListDetailPanes

    /**
     * Runs [block] on a scene [windowWidth] wide whose content is [startWidth] wide, on [selected]; [pushed] as after
     * a click on it in the list (not pushed, as at launch).
     */
    private fun scene(
        startWidth: Int,
        windowWidth: Int = startWidth,
        pushed: Boolean = false,
        selected: SettingsCategoryId = SettingsCategoryId.Appearance,
        settings: Settings = Settings(),
        reduceMotion: Boolean = false,
        block: SceneDriver.() -> Unit,
    ) = onEdt {
        nav = SettingsNav(selected = selected)
        width = startWidth
        this.windowWidth = windowWidth
        val actions = SettingsActions(onSelectCategory = { nav = nav.select(it) }, onQuery = { nav = nav.withQuery(it) })
        val driver = SceneDriver(ImageComposeScene(windowWidth, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    Surface {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            panes = rememberListDetailPanes()
                            remember(panes) { if (pushed) panes.push() }
                            SettingsContent(SettingsUiState(settings = settings), UpdateUiState(), nav, actions, panes = panes)
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
    private fun SemanticsNode.has(label: String): Boolean = label in texts() || described(label) || children.any { it.has(label) }

    /** Bounds not clipped by a scroll container: rows below the fold keep their size. */
    private fun SemanticsNode.rect() = Rect(positionInRoot, size.toSize())

    /** On screen: placed with a size, inside the content's current width. */
    private fun SemanticsNode.shown() = rect().let { it.width > 0f && it.height > 0f && it.left >= 0f && it.right <= width }

    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() && it.shown() }
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) && it.shown() }

    private fun SceneDriver.click(label: String) {
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.shown() && it.has(label) }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(150)
    }

    /** The Launch & quit tiles in enum order. */
    private fun SceneDriver.tiles(): List<Rect> = LaunchQuitAnimation.entries.map { anim ->
        assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton && it.has(anim.label) && it.has(anim.description) },
            "tile ${anim.label}",
        ).rect()
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun SceneDriver.key(key: Key) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key = key, type = type))
            frame()
        }
    }

    @Test
    fun `a 1000 dp window shows the category list and Appearance side by side`() = scene(1000) {
        assertTrue(text("Mirroring").isNotEmpty(), "the category list")
        assertTrue(text("Theme").isNotEmpty(), "Appearance's rows beside it")
        assertTrue(described("Back to list").isEmpty(), "no back arrow in two panes")
    }

    @Test
    fun `the default 800 dp window shows only the category list, 1000 shows two panes`() {
        scene(800) {
            assertTrue(text("Mirroring").isNotEmpty(), "the category list")
            assertTrue(text("Theme").isEmpty(), "no detail at 800 (below the 840 dp two-pane window)")
            assertTrue(described("Back to list").isEmpty())
        }
        scene(1000) {
            assertTrue(text("Mirroring").isNotEmpty(), "the category list")
            assertTrue(text("Theme").isNotEmpty(), "the detail beside it")
        }
    }

    @Test
    fun `a 600 dp window shows the category list first`() = scene(600) {
        for (name in listOf("Appearance", "Mirroring", "Updates", "Help")) assertTrue(text(name).isNotEmpty(), "$name in the list")
        assertTrue(text("Theme").isEmpty(), "no detail")
        assertTrue(described("Back to list").isEmpty())
        assertTrue(nodes().none { it.described("Show list") || it.described("Switch item") }, "no strip or chip")
    }

    @Test
    fun `clicking Appearance pushes the detail, Back returns to the list`() = scene(600) {
        click("Appearance")
        assertTrue(panes.pushed)
        assertTrue(described("Back to list").isNotEmpty(), "the back arrow")
        val back = described("Back to list").first().rect()
        val title = text("Appearance").maxBy { it.rect().top }.rect()
        assertTrue(back.right <= title.left && back.bottom > title.top && back.top < title.bottom, "arrow $back before the title $title")
        click("Back to list")
        assertTrue(!panes.pushed)
        assertTrue(text("Theme").isEmpty(), "the detail left")
        assertTrue(text("Mirroring").isNotEmpty(), "the list again")
        assertEquals(SettingsCategoryId.Appearance, nav.selected, "the selection stays")
    }

    @Test
    fun `Esc goes back to the list`() = scene(600, pushed = true) {
        assertTrue(text("Theme").isNotEmpty())
        key(Key.Escape)
        frames(90)
        assertTrue(!panes.pushed)
        assertTrue(text("Theme").isEmpty())
    }

    @Test
    fun `at 600 the pushed Theme text keeps 160 dp beside the segments`() = scene(600) {
        click("Appearance")
        val theme = assertNotNull(text("Theme").firstOrNull(), "Theme").rect()
        val system = assertNotNull(text("System").firstOrNull(), "the System segment").rect()
        // The text's room ends 16 dp (the row's gap) before the segments, whose label sits further in.
        assertTrue(system.top < theme.bottom && system.left - theme.left >= 160f + 16f, "segments $system beside 160 dp for $theme")
    }

    @Test
    fun `at 440 the Theme segments go under the text`() = scene(440, pushed = true) {
        val theme = assertNotNull(text("Theme").firstOrNull(), "Theme").rect()
        val light = assertNotNull(text("Light").firstOrNull(), "the Light segment").rect()
        assertTrue(light.top >= theme.bottom, "segments $light under the text $theme")
    }

    @Test
    fun `at 360 Theme is a menu button showing System`() = scene(360, pushed = true) {
        assertTrue(text("System").isNotEmpty(), "the menu button")
        assertTrue(text("Light").isEmpty() && text("Dark").isEmpty(), "no segments")
    }

    @Test
    fun `typing in the narrow list filters and stays on the list`() = scene(600) {
        val field = assertNotNull(nodes().firstOrNull { SemanticsActions.SetText in it.config }, "the search field")
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("dark"))
        frames(150)
        assertTrue(!panes.pushed, "still the list")
        assertTrue(text("Appearance").isNotEmpty(), "Appearance matches")
        assertTrue(text("Theme").isEmpty(), "no detail")
    }

    @Test
    fun `launch and quit tiles share one row at 1000`() = scene(1000) {
        val tiles = tiles()
        assertEquals(1, tiles.map { it.top }.distinct().size, "one top: $tiles")
    }

    @Test
    fun `launch and quit tiles fit the row at 360`() = scene(360, pushed = true) {
        // The detail's 28 dp padding and the row's 16 dp.
        val rowRight = 360f - 28f - 16f
        for (tile in tiles()) {
            assertTrue(tile.right <= rowRight + 0.5f, "tile $tile inside $rowRight")
            assertTrue(tile.width <= 200f, "tile $tile at most 200 wide")
        }
    }

    @Test
    fun `a wrapping launch tile glides to its new row`() = scene(800, pushed = true) {
        val before = tiles()
        assertEquals(1, before.map { it.top }.distinct().size, "one row at 800: $before")
        width = 520
        frames(3)
        val mid = tiles()[2]
        frames(150)
        val after = tiles()
        assertTrue(after[2].top > before[2].top, "the third tile wrapped: $after")
        assertTrue(after[0].width == 200f, "tiles stay 200 wide")
        assertTrue(mid.left > after[2].left + 1f && mid.left < before[2].left - 1f, "mid-glide x ${mid.left} between ${before[2].left} and ${after[2].left}")
    }

    @Test
    fun `under reduced motion a wrapping tile jumps`() = scene(800, pushed = true, reduceMotion = true) {
        width = 520
        frames(2)
        val mid = tiles()
        frames(150)
        assertEquals(tiles(), mid)
    }

    @Test
    fun `tile widths follow a drag directly`() = scene(260, windowWidth = 400, pushed = true) {
        // Below 200 + padding the tiles take the row's width; each step of the drag lands at once.
        for (w in listOf(250, 240, 230)) {
            width = w
            frame()
            assertEquals(w - 28f * 2 - 16f * 2, tiles()[0].width, "tile width at $w")
        }
    }

    @Test
    fun `a wide window's Theme segments follow a drag directly`() = scene(1100, windowWidth = 1280) {
        for (w in listOf(1090, 1080, 1070)) {
            width = w
            frame()
            val dark = assertNotNull(text("Dark").firstOrNull(), "the Dark segment").rect()
            // The row ends at the detail's 28 dp padding and the row's 16 dp.
            assertTrue(dark.right in (w - 44f - 40f)..(w - 44f), "Dark $dark at the row's end at $w")
            val before = dark.right
            frame()
            assertEquals(before, text("Dark").first().rect().right, "no glide after the drag step at $w")
        }
    }

    @Test
    fun `custom palette sliders keep 120 dp, else their labels go above`() {
        val custom = Settings().copy(themePalette = "Custom")
        scene(1000, settings = custom) {
            val label = assertNotNull(text("Vividness").firstOrNull(), "Vividness").rect()
            val slider = sliders()[1]
            assertTrue(slider.left > label.right && slider.top < label.bottom && slider.bottom > label.top, "inline: $label, $slider")
            assertTrue(slider.width >= 120f)
        }
        scene(360, pushed = true, settings = custom) {
            val label = assertNotNull(text("Vividness").firstOrNull(), "Vividness").rect()
            val slider = sliders()[1]
            assertTrue(slider.top >= label.bottom, "stacked: $label above $slider")
            assertTrue(slider.width >= 120f)
        }
    }

    /** The Custom palette sliders, top to bottom. */
    private fun SceneDriver.sliders(): List<Rect> =
        nodes().filter { SemanticsActions.SetProgress in it.config && it.rect().width > 0f }.map { it.rect() }.sortedBy { it.top }
}
