package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorUiState
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindowHandle
import com.programmersbox.scrcpyviewer.ui.mirror.TopStrip
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.shortcuts.Shortcut
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Rectangle
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ArrangeControlsTest {
    private val gridKeys = Shortcut(KeyEvent.VK_1, alt = true, meta = true).encode()

    // --- Model -------------------------------------------------------------------------------------------------------

    @Test
    fun `the button is there only with two or more mirror windows`() {
        assertNull(arrangeStrip(0, ArrangeState(), emptyMap(), {}, {}))
        assertNull(arrangeStrip(1, ArrangeState(), emptyMap(), {}, {}))
        assertNotNull(arrangeStrip(2, ArrangeState(), emptyMap(), {}, {}))
        assertNotNull(arrangeStrip(5, ArrangeState(), emptyMap(), {}, {}))
    }

    @Test
    fun `the menu lists the five layouts with the check on the current one`() {
        val items = arrangeItems(ArrangeState(), emptyMap())
        assertEquals(listOf("As placed", "Grid", "Side by side", "Focus this window", "Cascade"), items.map { it.label })
        assertEquals(listOf(ArrangeLayout.AsPlaced), items.filter { it.checked }.map { it.layout })
        val cascade = arrangeItems(ArrangeState(layout = ArrangeLayout.Cascade), emptyMap())
        assertEquals(listOf(ArrangeLayout.Cascade), cascade.filter { it.checked }.map { it.layout })
        assertTrue(items.all { it.enabled })
    }

    @Test
    fun `a layout shows its shortcut once set, in the OS's form`() {
        val map = mapOf("arrange-grid" to gridKeys, "arrange-cascade" to "garbage", "follow" to gridKeys)
        val mac = arrangeItems(ArrangeState(), map, mac = true).associate { it.layout to it.shortcut }
        assertEquals("⌥⌘1", mac[ArrangeLayout.Grid])
        assertNull(mac[ArrangeLayout.Cascade], "an unreadable entry shows nothing")
        assertNull(mac[ArrangeLayout.AsPlaced])
        assertEquals("Alt+Win+1", arrangeItems(ArrangeState(), map, mac = false).first { it.layout == ArrangeLayout.Grid }.shortcut)
        // Every layout reads its own action id.
        val all = ArrangeLayout.entries.associate { it.shortcutAction.id to gridKeys }
        assertEquals(setOf("arrange-free", "arrange-grid", "arrange-row", "arrange-focus", "arrange-cascade"), all.keys)
        assertTrue(arrangeItems(ArrangeState(), all, mac = true).all { it.shortcut == "⌥⌘1" })
    }

    @Test
    fun `layouts are disabled when the desktop doesn't let apps move windows`() {
        assertTrue(arrangeItems(ArrangeState(movable = false), emptyMap()).none { it.enabled })
    }

    @Test
    fun `the registry remembers the window focused last, until it closes`() {
        val windows = MirrorWindows()
        assertNull(windows.lastFocused.value)
        val a = entry()
        val b = entry()
        windows.register("A", a)
        windows.register("B", b)
        windows.focused("A")
        windows.focused("B")
        assertEquals("B", windows.lastFocused.value)
        windows.unregister("A", a)
        assertEquals("B", windows.lastFocused.value, "another window closing changes nothing")
        windows.unregister("B", entry())
        assertEquals("B", windows.lastFocused.value, "a stale entry does not unregister B")
        windows.unregister("B", b)
        assertNull(windows.lastFocused.value)
    }

    // --- Menu ------------------------------------------------------------------------------------------------------

    private class Picks {
        val picked = ArrayList<ArrangeLayout>()
        val keep = ArrayList<Boolean>()
        var dismissed = 0
    }

    private fun withMenu(state: ArrangeState, shortcuts: Map<String, String> = emptyMap(), block: SceneDriver.(Picks) -> Unit) = onEdt {
        val picks = Picks()
        var expanded by mutableStateOf(true)
        val strip = ArrangeStrip(state, shortcuts, onPick = { picks.picked += it }, onKeepTiled = { picks.keep += it })
        val driver = SceneDriver(
            ImageComposeScene(500, 500, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize()) {
                        Box { ArrangeMenu(expanded, { expanded = false; picks.dismissed++ }, strip) }
                    }
                }
            },
        )
        try {
            driver.frames(30)
            driver.block(picks)
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            all += n
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.text(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } }

    /** The clickable row holding [t] (the menu item merges its text). */
    private fun SceneDriver.row(t: String): SemanticsNode =
        nodes().first { n -> n.config.getOrNull(SemanticsProperties.Role) != null && n.hasText(t) }

    private fun SemanticsNode.hasText(t: String): Boolean =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } || children.any { it.hasText(t) }

    private fun SceneDriver.selected(t: String): Boolean = row(t).config.getOrNull(SemanticsProperties.Selected) == true

    private fun SceneDriver.enabled(t: String): Boolean = row(t).config.getOrNull(SemanticsProperties.Disabled) == null

    private fun SceneDriver.checkScale(layout: ArrangeLayout): Float {
        val n = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == checkTag(layout) }
        return n.boundsInWindow.width / 18f
    }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInWindow.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    @Test
    fun `the menu shows the title, the layouts, a set shortcut and Keep tiled`() = withMenu(
        ArrangeState(layout = ArrangeLayout.Grid),
        mapOf("arrange-grid" to gridKeys),
    ) {
        assertNotNull(text("ARRANGE MIRROR WINDOWS"))
        for (l in listOf("As placed", "Grid", "Side by side", "Focus this window", "Cascade")) assertNotNull(text(l), l)
        assertNotNull(text(KEEP_TILED))
        assertNotNull(text(Shortcut.decode(gridKeys)!!.label()))
        assertTrue(selected("Grid"))
        assertFalse(selected("As placed"))
        assertNull(text(NOT_MOVABLE_NOTE))
    }

    @Test
    fun `picking a layout moves the check at once, then closes the menu, then applies`() = withMenu(ArrangeState()) { picks ->
        assertEquals(1f, checkScale(ArrangeLayout.AsPlaced), 0.01f)
        click(row("Side by side"))
        frame()
        assertTrue(selected("Side by side"), "the check moved")
        assertFalse(selected("As placed"))
        assertEquals(emptyList(), picks.picked, "the windows wait for the menu")
        assertEquals(0, picks.dismissed)
        // The check scales in, the old one out, over several frames: neither snaps.
        val scales = (1..6).map { frame(); checkScale(ArrangeLayout.SideBySide) to checkScale(ArrangeLayout.AsPlaced) }
        assertTrue(scales.any { (i, _) -> i > 0.05f && i < 0.95f }, "mid scale-in: $scales")
        assertTrue(scales.any { (_, o) -> o > 0.05f && o < 0.95f }, "mid scale-out: $scales")
        assertTrue(scales.zipWithNext().all { (a, b) -> b.first >= a.first && b.second <= a.second }, "monotonic: $scales")
        assertEquals(emptyList(), picks.picked, "still waiting ~100 ms in")
        // A second click while the pick is pending is ignored.
        click(row("Grid"))
        frames(PICK_CLOSE_DELAY_MILLIS.toInt() / 16)
        assertEquals(1, picks.dismissed)
        assertEquals(listOf(ArrangeLayout.SideBySide), picks.picked)
    }

    @Test
    fun `Keep tiled toggles in place`() = withMenu(ArrangeState(layout = ArrangeLayout.Grid)) { picks ->
        click(row(KEEP_TILED))
        frames(20)
        assertEquals(listOf(true), picks.keep)
        assertEquals(0, picks.dismissed, "the menu stays open")
        assertEquals(emptyList(), picks.picked)
    }

    @Test
    fun `when windows cannot move the layouts are disabled under the note`() = withMenu(ArrangeState(movable = false)) { picks ->
        assertNotNull(text(NOT_MOVABLE_NOTE))
        for (l in listOf("As placed", "Grid", "Side by side", "Focus this window", "Cascade", KEEP_TILED)) {
            assertFalse(enabled(l), "$l is disabled")
        }
        click(row("Grid"))
        click(row(KEEP_TILED))
        frames(20)
        assertEquals(emptyList(), picks.picked)
        assertEquals(emptyList(), picks.keep)
    }

    // --- Title bar -------------------------------------------------------------------------------------------------

    private object Bar : AppTitleBarScope {
        override val chrome get() = WindowChrome.Custom
        override fun Modifier.interactive(key: String): Modifier = this
    }

    private val uiState = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(1344, 2992),
        fps = 59.8,
        fittedScale = 0.37f,
        command = "scrcpy -s 39121FDJH004AB",
    )

    private fun withStrip(width: Int, arrange: ArrangeStrip?, block: SceneDriver.() -> Unit) = onEdt {
        val driver = SceneDriver(
            ImageComposeScene(width, 40, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) { Box(Modifier.fillMaxSize()) { Bar.TopStrip(uiState, null, arrange) {} } }
            },
        )
        try {
            driver.frames(30)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.arrangeButton(): SemanticsNode? = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(ARRANGE_LABEL) == true && it.boundsInWindow.width >= 1f
    }

    @Test
    fun `the title bar shows the Arrange button when wide, and not without a second window`() {
        val strip = arrangeStrip(2, ArrangeState(), emptyMap(), {}, {})
        withStrip(900, strip) { assertNotNull(arrangeButton()) }
        withStrip(900, null) { assertNull(arrangeButton()) }
        // Narrow: it moves into the overflow menu with Fit.
        withStrip(480, strip) { assertNull(arrangeButton()) }
    }

    @Test
    fun `the Arrange button grows in and shrinks out over the strip's 200 ms, never in one frame`() = onEdt {
        var arrange by mutableStateOf<ArrangeStrip?>(null)
        val strip = arrangeStrip(2, ArrangeState(), emptyMap(), {}, {})
        val driver = SceneDriver(
            ImageComposeScene(900, 40, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) { Box(Modifier.fillMaxSize()) { Bar.TopStrip(uiState, null, arrange) {} } }
            },
        )
        try {
            driver.frames(30)
            fun width() = driver.arrangeButton()?.boundsInWindow?.width ?: 0f
            arrange = strip
            val growing = (1..16).map { driver.frame(); width() }
            assertTrue(growing.first() < growing.last(), "grows: $growing")
            assertTrue(growing.zipWithNext().all { (a, b) -> b - a <= 16f }, "no frame jumps: $growing")
            arrange = null
            val shrinking = (1..16).map { driver.frame(); width() }
            assertTrue(shrinking.first() > 0f, "still there as it starts to leave: $shrinking")
            assertTrue(shrinking.zipWithNext().all { (a, b) -> a - b <= 16f }, "no frame jumps: $shrinking")
            assertEquals(0f, shrinking.last())
        } finally {
            driver.scene.close()
        }
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------

    private fun entry() = WindowEntry(
        handle = object : MirrorWindowHandle {
            override var bounds: Rectangle = Rectangle(0, 0, 100, 200)
            override val size: IntSize = IntSize(100, 200)
            override val isShowing: Boolean = true
            override val focused: StateFlow<Boolean> = MutableStateFlow(false)
            override val focusEvents: SharedFlow<Boolean> = MutableSharedFlow()
            override val nsWindow: Pointer? = null
            override fun toFront() = Unit
            override fun screen(): ScreenArea? = null
            override fun contentOrigin(): IntOffset = IntOffset.Zero
        },
        info = { ArrangeWindow("", IntSize(1080, 2400), 0, 40) },
        floating = { true },
        minimized = { false },
        showing = { true },
        arranging = mutableStateOf(false),
    )
}
