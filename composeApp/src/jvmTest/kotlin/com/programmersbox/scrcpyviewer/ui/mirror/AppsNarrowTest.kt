package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Apps tab in a narrow sheet: one filter chip with a menu, stacked version boxes, a 2 x 2 action grid. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AppsNarrowTest {
    private val apps = listOf(
        DeviceApp("com.android.chrome", "Chrome", false, "131", 677),
        DeviceApp("com.android.settings", "Settings", true, "14", 34),
    )

    private class Content(widthDp: Int, apps: List<DeviceApp>, reduceMotion: Boolean) {
        var width by mutableStateOf(widthDp)
        val driver = SceneDriver(ImageComposeScene(480, 800, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        // The outer box frees the inner one from the scene's tight constraints.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            Box(Modifier.width(width.dp).height(800.dp)) {
                                AppsTabContent(AppsUiState(apps = apps), enabled = true, onEvent = {})
                            }
                        }
                    }
                }
            }
        }).also { it.frames(5) }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.texts(text: String): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == text }

    private fun SceneDriver.text(text: String): SemanticsNode? = texts(text).firstOrNull()

    /** Unclipped bounds: where the node is laid out, whatever its ancestors clip. */
    private fun SemanticsNode.unclipped(): Rect =
        Rect(layoutInfo.coordinates.positionInRoot(), Size(size.width.toFloat(), size.height.toFloat()))

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.unclipped().center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        frame()
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(60)
    }

    /** The sheet is bottom-aligned, so its title (the lower of the two "Chrome" texts) moves as its height changes. */
    private fun sheetTop(d: SceneDriver): Float = d.texts("Chrome").maxOf { it.unclipped().top }

    private fun assertInside(node: SemanticsNode?, width: Float, what: String) {
        assertNotNull(node, "$what is there")
        val r = node.unclipped()
        assertTrue(r.width > 0f && r.left >= -0.5f && r.right <= width + 0.5f, "$what at $r inside 0..$width")
    }

    @Test
    fun `the breakpoints are 480 and 360`() {
        assertTrue(appsFiltersCompact(479.dp))
        assertFalse(appsFiltersCompact(480.dp))
        assertTrue(appOptionsCompact(359.dp))
        assertFalse(appOptionsCompact(360.dp))
    }

    @Test
    fun `at 280 the filters are one chip that opens a menu at the chip`() = onEdt {
        val d = Content(280, apps, reduceMotion = true).driver
        assertEquals(1, d.texts("All").size)
        assertTrue(d.texts("User").isEmpty() && d.texts("System").isEmpty(), "no other filter before the click")
        assertInside(d.text("All"), 280f, "All")
        val reload = d.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Reload app list") == true }
        assertInside(reload, 280f, "Reload")
        val chip = checkNotNull(d.text("All")).unclipped()
        d.click(checkNotNull(d.text("All")))
        val user = d.text("User")
        assertNotNull(user, "User in the menu")
        assertNotNull(d.text("System"), "System in the menu")
        val menu = user.unclipped()
        assertTrue(menu.left >= chip.left - 40f && menu.left <= chip.right, "menu opens at the chip: $menu vs $chip")
        assertTrue(menu.top >= chip.top, "menu opens at or below the chip's top: $menu vs $chip")
    }

    @Test
    fun `at 480 the three filter chips stay`() = onEdt {
        val d = Content(480, apps, reduceMotion = true).driver
        val all = checkNotNull(d.text("All")).unclipped()
        val user = checkNotNull(d.text("User")).unclipped()
        val system = checkNotNull(d.text("System")).unclipped()
        assertEquals(all.top, user.top, 0.5f)
        assertEquals(all.top, system.top, 0.5f)
        assertTrue(all.left < user.left && user.left < system.left)
    }

    @Test
    fun `narrowing animates from three chips to one`() = onEdt {
        val c = Content(480, apps, reduceMotion = false)
        c.width = 280
        c.driver.frames(120)
        assertEquals(1, c.driver.texts("All").size)
        assertTrue(c.driver.texts("User").isEmpty())
    }

    @Test
    fun `at 280 the options sheet stacks the boxes and the actions form a grid`() = onEdt {
        val d = Content(280, apps, reduceMotion = true).driver
        d.click(checkNotNull(d.text("Chrome")))
        val launch = d.text("Launch")
        assertInside(launch, 280f, "Launch")
        assertInside(d.text("Uninstall"), 280f, "Uninstall")
        val l = checkNotNull(launch).unclipped()
        val copy = checkNotNull(d.text("Copy package")).unclipped()
        val stop = checkNotNull(d.text("Force stop")).unclipped()
        assertTrue(copy.top > l.bottom - 0.5f, "Copy below Launch: $copy vs $l")
        assertEquals(l.top, stop.top, 0.5f)
        assertTrue(stop.left > l.right - 0.5f, "Force stop beside Launch")
        val name = checkNotNull(d.text("Version name")).unclipped()
        val code = checkNotNull(d.text("Version code")).unclipped()
        assertTrue(code.top >= name.bottom - 0.5f, "Version code below Version name: $code vs $name")
        assertEquals(name.left, code.left, 0.5f)
    }

    @Test
    fun `narrowing with the options open glides the sheet height and the boxes`() = onEdt {
        val c = Content(480, apps, reduceMotion = false)
        val d = c.driver
        d.click(checkNotNull(d.text("Chrome")))
        d.frames(60)
        val codeBefore = checkNotNull(d.text("Version code")).unclipped()
        val logcatBefore = sheetTop(d)
        c.width = 280
        d.frames(4)
        val mid = checkNotNull(d.text("Version code")).unclipped()
        val logcatMid = sheetTop(d)
        d.frames(150)
        val codeAfter = checkNotNull(d.text("Version code")).unclipped()
        val logcatAfter = sheetTop(d)
        fun between(mid: Float, from: Float, to: Float) = mid > minOf(from, to) + 0.5f && mid < maxOf(from, to) - 0.5f
        assertTrue(kotlin.math.abs(codeAfter.top - codeBefore.top) > 1f, "Version code moved: $codeBefore -> $codeAfter")
        assertTrue(between(mid.top, codeBefore.top, codeAfter.top), "a box mid-glide: ${codeBefore.top} / ${mid.top} / ${codeAfter.top}")
        assertTrue(between(mid.left, codeBefore.left, codeAfter.left), "its left mid-glide: ${codeBefore.left} / ${mid.left} / ${codeAfter.left}")
        val a = minOf(logcatBefore, logcatAfter)
        val b = maxOf(logcatBefore, logcatAfter)
        assertTrue(logcatMid > a + 0.5f && logcatMid < b - 0.5f, "the sheet's height is mid-change: its title at $logcatBefore, $logcatMid, $logcatAfter")
    }

    @Test
    fun `at 480 the options keep a row of four actions and side by side boxes`() = onEdt {
        val d = Content(480, apps, reduceMotion = true).driver
        d.click(checkNotNull(d.text("Chrome")))
        val l = checkNotNull(d.text("Launch")).unclipped()
        val copy = checkNotNull(d.text("Copy package")).unclipped()
        assertEquals(l.top, copy.top, 0.5f)
        val name = checkNotNull(d.text("Version name")).unclipped()
        val code = checkNotNull(d.text("Version code")).unclipped()
        assertEquals(name.top, code.top, 0.5f)
    }
}
