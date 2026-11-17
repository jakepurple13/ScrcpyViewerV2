package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Apps grid while the sheet is resized: within one column count the cards follow the width in the same frame; a
 * change of column count (128 dp cards, 8 dp apart, 12 dp side padding: 3 columns from 424 dp) glides them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AppsGridMotionTest {
    private val apps = listOf("Alpha", "Bravo", "Charlie", "Delta").mapIndexed { i, name ->
        DeviceApp("com.example.${name.lowercase()}", name, false, "1", i + 1L)
    }
    private var width by mutableIntStateOf(470)

    private fun scene() = SceneDriver(ImageComposeScene(480, 700, Density(1f)) {
        CompositionLocalProvider(LocalReduceMotion provides false) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.width(width.dp).height(700.dp)) {
                            AppsTabContent(AppsUiState(apps = apps), enabled = true, onEvent = {})
                        }
                    }
                }
            }
        }
    }).also { it.frames(120) }

    private fun SceneDriver.card(name: String): Offset {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            all += n
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return assertNotNull(all.firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == name }, name)
            .layoutInfo.coordinates.positionInRoot()
    }

    @Test
    fun `the column count follows the shared rule`() {
        assertEquals(3, appGridColumns(424, 24, 128, 8))
        assertEquals(2, appGridColumns(423, 24, 128, 8))
        assertEquals(1, appGridColumns(100, 24, 128, 8))
    }

    @Test
    fun `a resize within one column count moves the cards in the same frame`() = onEdt {
        val d = scene()
        try {
            for (w in 460 downTo 430 step 10) {
                width = w
                d.frame()
                val now = d.card("Bravo")
                d.frames(60)
                assertEquals(d.card("Bravo"), now, "Bravo lands in the frame of the resize to $w")
            }
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `dragging across a column count glides the cards, then they settle`() = onEdt {
        val d = scene()
        try {
            for (w in listOf(460, 450, 440, 430)) {
                width = w
                d.frame()
            }
            val before = d.card("Charlie") // third column of the first row
            width = 410 // two columns: Charlie moves to the second row
            val path = List(60) { d.frame(); d.card("Charlie") }
            val after = path.last()
            assertTrue((after - before).getDistance() > 50f, "Charlie changes place: $before -> $after")
            val mid = path[3]
            assertTrue(
                (mid - before).getDistance() > 1f && (mid - after).getDistance() > 1f,
                "between its places mid-glide: $before $path",
            )
            assertTrue(path.takeLast(10).all { (it - after).getDistance() < 0.5f }, "settled: $path")
        } finally {
            d.scene.close()
        }
    }
}
