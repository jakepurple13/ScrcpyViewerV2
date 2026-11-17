package com.programmersbox.scrcpyviewer.ui.mirror.links

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.links.Extra
import com.programmersbox.scrcpyviewer.links.ExtraType
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.gif.gifPreviewWidth
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/** The Links tab in a narrow sheet: the two dropdowns and the extra stack, and glide when the width changes. */
@OptIn(ExperimentalComposeUiApi::class)
class LinksNarrowTest {
    private class Runner : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
        override suspend fun run(cmd: String, timeoutMs: Long) = ShellResult("Status: ok\nComplete", 0, 1)
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @Test
    fun `the tab stacks under 300 dp and the gif preview leaves 340 dp for the controls`() {
        assertTrue(linksStacked(299.dp))
        assertFalse(linksStacked(300.dp))
        assertEquals(196.dp, gifPreviewWidth(552.dp, 1014.dp))
        assertEquals(1014.dp, gifPreviewWidth(1400.dp, 1014.dp))
        assertEquals(0.dp, gifPreviewWidth(300.dp, 1014.dp))
    }

    private inner class Ui(val motion: Boolean, startWidth: Int) {
        val width: MutableState<Int> = mutableStateOf(startWidth)
        val links = LinksState(
            "d1", Runner(), LinkStore(Files.createTempDirectory("links-narrow").resolve("links.json")), { emptyList() }, scope, clock = { 1L },
        ).also {
            it.onEvent(LinksEvent.ToggleDetails)
            it.onEvent(LinksEvent.SetExtra(Extra(ExtraType.String, "source", "qa")))
        }
        val d = SceneDriver(ImageComposeScene(700, 1200, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxHeight()) {
                    CompositionLocalProvider(LocalReduceMotion provides !motion) {
                        Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                            LinksTabContent(links, AppsUiState(apps = emptyList()), enabled = true, onEvent = links::onEvent, motion = motion, now = { 1L })
                        }
                    }
                }
            }
        }).also { it.frames(30) }

        fun nodes(): List<SemanticsNode> {
            val all = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) {
                all += n
                n.children.forEach(::walk)
            }
            d.scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return all
        }

        /** Where the node is laid out, whatever its ancestors clip. */
        private fun SemanticsNode.unclipped(): Rect =
            Rect(layoutInfo.coordinates.positionInRoot(), Size(size.width.toFloat(), size.height.toFloat()))

        fun text(t: String): Rect = assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { x -> x.text == t } == true }, "\"$t\" on screen",
        ).unclipped()

        fun described(t: String): Rect = assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(t) == true }, "$t on screen",
        ).unclipped()
    }

    @Test
    fun `at 260 the dropdowns stack and the extra's key and value take the full width`() = onEdt {
        val ui = Ui(motion = false, startWidth = 260)
        assertTrue(ui.text("Before opening").top >= ui.described("Open with").bottom, "Before opening is below Open with")
        assertTrue(ui.described("Extra key").width >= 200f, "key ${ui.described("Extra key")}")
        assertTrue(ui.described("Extra value").width >= 200f, "value ${ui.described("Extra value")}")
        assertTrue(ui.described("Extra key").bottom <= ui.described("Extra value").top, "value under key")
        assertTrue(ui.described("Extra type").bottom <= ui.described("Extra key").top, "key under type")
        val open = ui.text("Open on device")
        assertTrue(open.left >= 0f && open.right <= 260f, "Open on device inside the tab: $open")
        ui.d.scene.close()
    }

    @Test
    fun `at 520 the dropdowns share one top and the extra stays on one line`() = onEdt {
        val ui = Ui(motion = false, startWidth = 520)
        assertEquals(ui.described("Open with").top, ui.described("Before opening").top, 0.5f)
        assertEquals(ui.described("Extra type").center.y, ui.described("Extra key").center.y, 0.5f)
        assertEquals(ui.described("Extra key").center.y, ui.described("Extra value").center.y, 0.5f)
        ui.d.scene.close()
    }

    @Test
    fun `narrowing with motion glides the second dropdown down over several frames`() = onEdt {
        val ui = Ui(motion = true, startWidth = 520)
        val before = ui.text("Before opening").top
        ui.width.value = 260
        val path = listOf(before) + List(40) {
            ui.d.frame()
            ui.text("Before opening").top
        }
        val total = abs(path.last() - before)
        val steps = path.zipWithNext { a, b -> abs(b - a) }
        assertTrue(total > 40f, "moves down: $path")
        assertTrue(steps.max() <= total * 0.5f, "no single-frame jump: $path")
        assertTrue(steps.count { it > 0.5f } >= 4, "over several frames: $path")
        ui.d.scene.close()
    }

    @Test
    fun `a plain drag above 300 dp moves the weighted fields in the same frame, even with motion on`() = onEdt {
        val ui = Ui(motion = true, startWidth = 520)
        ui.width.value = 400
        ui.d.frame()
        assertEquals((400f - 32f - 12f) / 2f, ui.described("Before opening").width, 0.5f)
        assertEquals(ui.described("Open with").top, ui.described("Before opening").top, 0.5f)
        val key = ui.described("Extra key").width
        ui.width.value = 340
        ui.d.frame()
        assertEquals(key - 30f, ui.described("Extra key").width, 1f)
        ui.d.scene.close()
    }

    @Test
    fun `at 300 and 320 the dropdowns and the extra's three fields still share their rows`() = onEdt {
        for (w in listOf(300, 320, 340)) {
            val ui = Ui(motion = false, startWidth = w)
            assertEquals(ui.described("Open with").top, ui.described("Before opening").top, 0.5f, "dropdowns at $w")
            val type = ui.described("Extra type").center.y
            assertEquals(type, ui.described("Extra key").center.y, 0.5f, "key at $w")
            assertEquals(type, ui.described("Extra value").center.y, 0.5f, "value at $w")
            ui.d.scene.close()
        }
    }

    @Test
    fun `during a flip glide the two dropdowns never overlap`() = onEdt {
        val ui = Ui(motion = true, startWidth = 520)
        for (to in listOf(260, 520)) {
            ui.width.value = to
            repeat(60) {
                ui.d.frame()
                val open = ui.described("Open with")
                val before = ui.described("Before opening")
                val overlap = open.intersect(before)
                assertTrue(overlap.width <= 0.5f || overlap.height <= 0.5f, "frame $it to $to: $open overlaps $before")
            }
        }
        ui.d.scene.close()
    }

    @Test
    fun `narrowing with reduced motion lands in the same frame`() = onEdt {
        val ui = Ui(motion = false, startWidth = 520)
        val wide = ui.text("Before opening").top
        ui.width.value = 260
        ui.d.frames(2)
        val settled = ui.text("Before opening").top
        ui.d.frames(20)
        assertTrue(settled > wide + 40f, "moved down at once: $wide -> $settled")
        assertEquals(settled, ui.text("Before opening").top, 0.5f)
        ui.d.scene.close()
    }
}
