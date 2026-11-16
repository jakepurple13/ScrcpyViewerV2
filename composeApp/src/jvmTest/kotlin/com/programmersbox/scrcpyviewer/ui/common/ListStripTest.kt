package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class)
class ListStripTest {
    private var selected by mutableStateOf<Any?>("a")
    private var stripWidth by mutableIntStateOf(-1)
    private val picked = mutableListOf<Any>()
    private var shown = 0

    private fun items(second: String) = listOf(
        StripItem("a", "First", { Text("F") }),
        StripItem("b", second, { Text("S") }, badgeColor = Color.Red),
        StripItem("c", "Third", { Text("T") }),
    )

    private fun withStrip(second: String = "Second", block: SceneDriver.() -> Unit) = onEdt {
        val driver = SceneDriver(ImageComposeScene(300, 600, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                MaterialTheme {
                    // No width given: the strip decides its own.
                    Row(Modifier.fillMaxHeight()) {
                        Box(Modifier.onSizeChanged { stripWidth = it.width }) {
                            ListStrip(items(second), selected, { picked += it }, { shown++ }, Modifier.fillMaxHeight())
                        }
                        Box(Modifier.size(1.dp))
                    }
                }
            }
        })
        try {
            driver.frames(5)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.find(description: String): SemanticsNode {
        var found: SemanticsNode? = null
        fun walk(node: SemanticsNode) {
            if (found == null && node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) found = node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return assertNotNull(found, "no node described '$description'")
    }

    private fun SceneDriver.click(description: String) {
        val at: Offset = find(description).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
        frames(3)
    }

    @Test
    fun `clicking an item selects its key and the chevron shows the list`() = withStrip {
        click("Second")
        assertEquals(listOf<Any>("b"), picked)
        click("Show list")
        assertEquals(1, shown)
    }

    @Test
    fun `a very long label leaves the strip at 76`() = withStrip(second = "x".repeat(60)) {
        assertEquals(76, stripWidth)
    }
}
