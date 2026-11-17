package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Settings screen at narrow widths, laid out offscreen (1 px = 1 dp) and read off the semantics tree: the category
 * sidebar always beside the page, 200 dp from an 840 dp window, narrowing with the window to 136 dp at 480 dp, then a
 * 64 dp column of icons; rows that keep their text's room; Launch & quit tiles that shrink to the row and glide when
 * they wrap. Motion is passed explicitly: CI runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsNarrowTest {
    private var nav by mutableStateOf(SettingsNav())
    private var width by mutableIntStateOf(1000)
    private var window by mutableIntStateOf(1000)
    private var height by mutableIntStateOf(800)

    /** The window the content sees: an offscreen scene's own never changes size. */
    private val windowInfo = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize get() = IntSize(window, 800)
    }

    /**
     * Runs [block] in a window [windowWidth] wide whose content is [startWidth] wide, on [selected]. The scene is
     * 1280 dp wide, so a resize never clips.
     */
    private fun scene(
        startWidth: Int,
        windowWidth: Int = startWidth,
        selected: SettingsCategoryId = SettingsCategoryId.Appearance,
        settings: Settings = Settings(),
        reduceMotion: Boolean = false,
        block: SceneDriver.() -> Unit,
    ) = onEdt {
        nav = SettingsNav(selected = selected)
        width = startWidth
        window = windowWidth
        height = 800
        val actions = SettingsActions(onSelectCategory = { nav = nav.select(it) }, onQuery = { nav = nav.withQuery(it) })
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion, LocalWindowInfo provides windowInfo) {
                    Surface {
                        Box(Modifier.width(width.dp).height(height.dp)) {
                            SettingsContent(SettingsUiState(settings = settings), UpdateUiState(), nav, actions)
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

    /** Resizes the window, and the content with it, to [w]; no frame. */
    private fun resize(w: Int) {
        width = w
        window = w
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
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

    /** The sidebar's bounds. */
    private fun SceneDriver.sidebar(): Rect =
        assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == SettingsSidebarTag }, "the sidebar").rect()

    /** The search fields on screen. */
    private fun SceneDriver.fields() = nodes().filter { SemanticsActions.SetText in it.config && it.shown() }

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
    fun `the sidebar is 200 dp from 840, 136 to 200 across 480 to 840, then 64 dp of icons`() {
        assertEquals(200.dp, settingsSidebarWidth(840.dp))
        assertEquals(200.dp, settingsSidebarWidth(1280.dp))
        val at839 = settingsSidebarWidth(839.dp)
        assertTrue(at839 < 200.dp && at839 > 199.dp, "839: $at839")
        assertEquals(168.dp, settingsSidebarWidth(660.dp))
        assertEquals(136.dp, settingsSidebarWidth(480.dp))
        assertEquals(64.dp, settingsSidebarWidth(479.dp))
        assertEquals(64.dp, settingsSidebarWidth(360.dp))
        assertTrue(settingsSidebarIcons(479.dp))
        assertTrue(!settingsSidebarIcons(480.dp))
    }

    @Test
    fun `a 1000 dp window shows the 200 dp category list and Appearance side by side`() = scene(1000) {
        assertEquals(200f, sidebar().width)
        assertTrue(text("Mirroring").isNotEmpty(), "the category list")
        assertTrue(text("Theme").isNotEmpty(), "Appearance's rows beside it")
        assertTrue(described("Back to list").isEmpty(), "no back arrow")
    }

    @Test
    fun `a 600 dp window keeps the sidebar beside the Appearance page`() = scene(600) {
        val bar = sidebar()
        assertEquals(settingsSidebarWidth(600.dp).value, bar.width, 0.5f)
        for (name in listOf("Appearance", "Mirroring", "Updates", "Help")) {
            assertTrue(text(name).any { it.rect().right <= bar.right }, "$name in the sidebar")
        }
        assertTrue(text("Theme").isNotEmpty(), "the page beside it")
        assertTrue(described("Back to list").isEmpty(), "no back arrow")
        assertEquals(1, fields().size, "the inline search field")
    }

    @Test
    fun `typing in the inline field filters the sidebar in place`() = scene(600) {
        val field = assertNotNull(fields().firstOrNull(), "the search field")
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("dark"))
        frames(150)
        val bar = sidebar()
        assertTrue(text("Appearance").any { it.rect().right <= bar.right }, "Appearance matches")
        assertTrue(text("Mirroring").isEmpty(), "Mirroring is filtered out")
        assertTrue(text("Theme").isNotEmpty(), "the page stays")
    }

    @Test
    fun `a 400 dp window shows a 64 dp column of category icons`() = scene(400) {
        val bar = sidebar()
        assertEquals(64f, bar.width)
        for (name in listOf("Appearance", "Mirroring", "Updates", "Help")) {
            assertTrue(text(name).none { it.rect().right <= bar.right }, "no $name label in the sidebar")
            assertTrue(described(name).any { it.rect().right <= bar.right }, "the $name icon is described")
        }
        assertTrue(text("Theme").isNotEmpty(), "the Appearance page beside it")
        assertTrue(fields().isEmpty(), "search is a button")
        assertTrue(described("Search settings").isNotEmpty())
        click("Updates")
        assertEquals(SettingsCategoryId.Updates, nav.selected)
        assertTrue(text("App updates").isNotEmpty(), "the Updates page")
    }

    @Test
    fun `an icon's name shows as a tooltip on hover`() = scene(400) {
        val icon = described("Mirroring").first { it.rect().right <= 64f }.rect().center
        assertTrue(text("Mirroring").isEmpty())
        scene.sendPointerEvent(PointerEventType.Move, icon, type = PointerType.Mouse)
        frames(2)
        scene.sendPointerEvent(PointerEventType.Move, icon.copy(x = icon.x + 1f), type = PointerType.Mouse)
        val until = System.currentTimeMillis() + 3_000
        while (text("Mirroring").isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            frame()
        }
        assertTrue(text("Mirroring").isNotEmpty(), "the tooltip names the category")
    }

    @Test
    fun `Tab and Enter select an icon`() = scene(400) {
        // The search button first, then Appearance, then Mirroring.
        repeat(3) { key(Key.Tab) }
        key(Key.Enter)
        frames(60)
        assertEquals(SettingsCategoryId.Mirroring, nav.selected)
    }

    @Test
    fun `the search button opens the field over the page, typing filters, Esc closes it`() = scene(400) {
        click("Search settings")
        val field = assertNotNull(fields().singleOrNull(), "the search field")
        assertTrue(field.rect().right > 64f, "over the page: ${field.rect()}")
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("dark"))
        frames(150)
        assertTrue(described("Appearance").any { it.rect().right <= 64f }, "Appearance matches")
        assertTrue(described("Mirroring").none { it.rect().right <= 64f }, "Mirroring is filtered out")
        key(Key.Escape)
        frames(60)
        assertTrue(fields().isEmpty(), "Esc closed the field")
        assertEquals("dark", nav.query, "the query stays")
        // Wider again: the inline field shows the same query.
        resize(600)
        frames(150)
        val inline = assertNotNull(fields().singleOrNull(), "the inline field")
        assertEquals("dark", inline.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test
    fun `within the list form the sidebar follows a resize in the same frame`() = scene(700) {
        assertEquals(settingsSidebarWidth(700.dp).value, sidebar().width, 0.5f)
        for (w in listOf(600, 560, 520)) {
            resize(w)
            frame()
            assertEquals(settingsSidebarWidth(w.dp).value, sidebar().width, 0.5f, "at $w")
        }
    }

    @Test
    fun `list to icons springs the sidebar`() = scene(500) {
        val before = sidebar().width
        assertEquals(settingsSidebarWidth(500.dp).value, before, 0.5f)
        resize(460)
        frames(4)
        val mid = sidebar().width
        assertTrue(mid > 64f && mid < before, "mid-spring $mid between 64 and $before")
        frames(150)
        assertEquals(64f, sidebar().width)
        assertTrue(fields().isEmpty(), "icons now")
    }

    @Test
    fun `icons with no match show one muted No settings match icon`() = scene(400) {
        click("Search settings")
        val field = assertNotNull(fields().singleOrNull(), "the search field")
        field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("zzz"))
        frames(150)
        val none = described("No settings match").filter { it.rect().right <= 64f }
        assertEquals(1, none.size, "one no-match icon")
        for (name in listOf("Appearance", "Mirroring", "Help")) assertTrue(described(name).none { it.rect().right <= 64f }, "no $name icon")
    }

    @Test
    fun `a short window scrolls the icons to the selected category`() = scene(400) {
        height = 200
        frames(30)
        val before = described("Help").first { it.rect().right <= 64f }.rect()
        assertTrue(before.bottom > 200f, "Help starts below the fold: $before")
        nav = nav.select(SettingsCategoryId.Help)
        frames(120)
        val after = described("Help").first { it.rect().right <= 64f }.rect()
        assertTrue(after.top >= 0f && after.bottom <= 200f, "Help scrolled into view: $after")
    }

    @Test
    fun `a short window scrolls the list to the selected category`() = scene(600) {
        height = 200
        frames(30)
        nav = nav.select(SettingsCategoryId.Help)
        frames(120)
        val help = text("Help").first { it.rect().right <= sidebar().right }.rect()
        assertTrue(help.top >= 0f && help.bottom <= 200f, "Help scrolled into view: $help")
    }

    @Test
    fun `under reduced motion list to icons snaps`() = scene(500, reduceMotion = true) {
        resize(460)
        frame()
        assertEquals(64f, sidebar().width)
        frames(30)
        assertTrue(fields().isEmpty(), "icons after the fade")
    }

    @Test
    fun `at 800 the Theme text keeps 160 dp beside the segments`() = scene(800) {
        val theme = assertNotNull(text("Theme").firstOrNull(), "Theme").rect()
        val system = assertNotNull(text("System").firstOrNull(), "the System segment").rect()
        // The text's room ends 16 dp (the row's gap) before the segments, whose label sits further in.
        assertTrue(system.top < theme.bottom && system.left - theme.left >= 160f + 16f, "segments $system beside 160 dp for $theme")
    }

    @Test
    fun `at 600 the Theme segments go under the text`() = scene(600) {
        val theme = assertNotNull(text("Theme").firstOrNull(), "Theme").rect()
        val light = assertNotNull(text("Light").firstOrNull(), "the Light segment").rect()
        assertTrue(light.top >= theme.bottom, "segments $light under the text $theme")
    }

    @Test
    fun `at 360 Theme is a menu button showing System`() = scene(360) {
        assertTrue(text("System").isNotEmpty(), "the menu button")
        assertTrue(text("Light").isEmpty() && text("Dark").isEmpty(), "no segments")
    }

    @Test
    fun `launch and quit tiles share one row at 1000`() = scene(1000) {
        val tiles = tiles()
        assertEquals(1, tiles.map { it.top }.distinct().size, "one top: $tiles")
    }

    @Test
    fun `launch and quit tiles fit the row at 360`() = scene(360) {
        // The page's 28 dp padding and the row's 16 dp.
        val rowRight = 360f - 28f - 16f
        for (tile in tiles()) {
            assertTrue(tile.right <= rowRight + 0.5f, "tile $tile inside $rowRight")
            assertTrue(tile.width <= 200f, "tile $tile at most 200 wide")
        }
    }

    @Test
    fun `a wrapping launch tile glides to its new row`() = scene(1280) {
        val before = tiles()
        assertEquals(1, before.map { it.top }.distinct().size, "one row at 1280: $before")
        width = 840
        frames(3)
        val mid = tiles()[2]
        frames(150)
        val after = tiles()
        assertTrue(after[2].top > before[2].top, "the third tile wrapped: $after")
        assertTrue(after[0].width == 200f, "tiles stay 200 wide")
        assertTrue(mid.left > after[2].left + 1f && mid.left < before[2].left - 1f, "mid-glide x ${mid.left} between ${before[2].left} and ${after[2].left}")
    }

    @Test
    fun `under reduced motion a wrapping tile jumps`() = scene(1280, reduceMotion = true) {
        width = 840
        frames(2)
        val mid = tiles()
        frames(150)
        assertEquals(tiles(), mid)
    }

    @Test
    fun `tile widths follow a drag directly`() = scene(400) {
        // Beside the 64 dp icons and the 1 dp divider, below 200 + padding the tiles take the row's width; each step
        // of the drag lands at once.
        for (w in listOf(350, 340, 330)) {
            width = w
            frame()
            assertEquals(w - 65f - 28f * 2 - 16f * 2, tiles()[0].width, "tile width at $w")
        }
    }

    @Test
    fun `a wide window's Theme segments follow a drag directly`() = scene(1100, windowWidth = 1280) {
        for (w in listOf(1090, 1080, 1070)) {
            width = w
            frame()
            val dark = assertNotNull(text("Dark").firstOrNull(), "the Dark segment").rect()
            // The row ends at the page's 28 dp padding and the row's 16 dp.
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
        scene(360, settings = custom) {
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
