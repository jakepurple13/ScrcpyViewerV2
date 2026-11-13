package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lays out a real AdaptiveRow in offscreen scenes (1 px = 1 dp). */
@OptIn(ExperimentalComposeUiApi::class)
class AdaptiveRowSceneTest {
    /** What one scene records; each scene gets its own. */
    private class Probe {
        var textWidth by mutableIntStateOf(-1)
        var textHeight by mutableIntStateOf(-1)
        var rowHeight by mutableIntStateOf(-1)
        var controlOffset by mutableStateOf(Offset(-1f, -1f))
        var controlSize by mutableStateOf(IntSize.Zero)
        var liveControls by mutableIntStateOf(0)
    }

    private val textHeight = 24
    private val inlineHeight = 40 // the 300 x 40 control is the taller slot
    private val stackedControlY = 24 + 8 // text, then the 8 dp stacked gap
    private val stackedHeight = stackedControlY + 40

    @Composable
    private fun TestRow(probe: Probe, fillControlWhenStacked: Boolean = false, textModifier: Modifier = Modifier.fillMaxWidth()) {
        AdaptiveRow(
            modifier = Modifier.onSizeChanged { probe.rowHeight = it.height },
            fillControlWhenStacked = fillControlWhenStacked,
            text = {
                Box(
                    textModifier.height(textHeight.dp).onSizeChanged {
                        probe.textWidth = it.width
                        probe.textHeight = it.height
                    },
                )
            },
            control = {
                DisposableEffect(Unit) {
                    probe.liveControls++
                    onDispose { probe.liveControls-- }
                }
                Box(
                    Modifier.size(300.dp, 40.dp).onGloballyPositioned {
                        probe.controlOffset = it.positionInRoot()
                        probe.controlSize = it.size
                    },
                )
            },
        )
    }

    private fun <T> withScene(width: Int, content: @Composable () -> Unit, block: (SceneDriver) -> T): T {
        val scene = SceneDriver(ImageComposeScene(width, 300, Density(1f), content = content))
        try {
            return block(scene)
        } finally {
            scene.scene.close()
        }
    }

    @Test
    fun `text never gets less than its minimum`() = onEdt {
        val wide = Probe()
        withScene(600, { TestRow(wide) }) { scene ->
            scene.frames(60)
            assertEquals(600 - 300 - 16, wide.textWidth, "inline text width")
            assertEquals(0f, wide.controlOffset.y, "inline control y")
        }

        val narrow = Probe()
        withScene(400, { TestRow(narrow) }) { scene ->
            scene.frames(60)
            assertEquals(400, narrow.textWidth, "stacked text width")
            assertTrue(
                narrow.controlOffset.y >= narrow.textHeight,
                "control ${narrow.controlOffset.y} sits under the text (${narrow.textHeight})",
            )
        }
    }

    /** A row in a box whose width the test changes. */
    private fun resizable(probe: Probe, reduceMotion: Boolean, width: () -> Int): @Composable () -> Unit = {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
            Box(Modifier.width(width().dp)) { TestRow(probe) }
        }
    }

    @Test
    fun `control glides under the text, or moves at once under reduced motion`() = onEdt {
        for (reduceMotion in listOf(false, true)) {
            val probe = Probe()
            var width by mutableIntStateOf(600)
            withScene(600, resizable(probe, reduceMotion) { width }) { scene ->
                scene.frames(30)
                assertEquals(0f, probe.controlOffset.y, "inline first")
                width = 400
                // The width change recomposes on the first frame; the spring starts on the next.
                scene.frames(4)
                if (reduceMotion) {
                    assertEquals(stackedControlY.toFloat(), probe.controlOffset.y, "instant under reduced motion")
                } else {
                    val y = probe.controlOffset.y
                    assertTrue(y > 0f && y < stackedControlY, "mid-glide y $y is between 0 and $stackedControlY")
                }
                scene.frames(90)
                assertEquals(stackedControlY.toFloat(), probe.controlOffset.y, "settled under the text")
                assertEquals(0f, probe.controlOffset.x, "start-aligned")
            }
        }
    }

    @Test
    fun `row height springs, or changes at once under reduced motion`() = onEdt {
        for (reduceMotion in listOf(false, true)) {
            val probe = Probe()
            var width by mutableIntStateOf(600)
            withScene(600, resizable(probe, reduceMotion) { width }) { scene ->
                scene.frames(30)
                assertEquals(inlineHeight, probe.rowHeight, "inline height first")
                width = 400
                if (reduceMotion) {
                    scene.frame()
                    assertEquals(stackedHeight, probe.rowHeight, "instant under reduced motion")
                } else {
                    scene.frames(4)
                    val h = probe.rowHeight
                    assertTrue(h in (inlineHeight + 1) until stackedHeight, "mid-spring height $h is between $inlineHeight and $stackedHeight")
                }
                scene.frames(90)
                assertEquals(stackedHeight, probe.rowHeight, "settled at the stacked height")
            }
        }
    }

    @Test
    fun `stacked control fills the row when asked`() = onEdt {
        val probe = Probe()
        withScene(400, { TestRow(probe, fillControlWhenStacked = true) }) { scene ->
            scene.frames(30)
            assertEquals(400, probe.controlSize.width, "full-width stacked control")
            assertEquals(stackedControlY.toFloat(), probe.controlOffset.y)
        }
    }

    @Test
    fun `unbounded width stays inline without probing the control`() = onEdt {
        val probe = Probe()
        val content: @Composable () -> Unit = {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TestRow(probe, textModifier = Modifier.width(100.dp))
            }
        }
        withScene(400, content) { scene ->
            scene.frames(30)
            assertEquals(Offset(100f + 16f, 0f), probe.controlOffset, "inline after the text and the gap")
            assertEquals(1, probe.liveControls, "only the placed control is composed")
        }
    }

    @Test
    fun `bounded width probes the control once`() = onEdt {
        val probe = Probe()
        withScene(600, { TestRow(probe) }) { scene ->
            scene.frames(30)
            // Full fits, so only the Full probe and the placed control exist.
            assertEquals(2, probe.liveControls)
        }
    }
}
