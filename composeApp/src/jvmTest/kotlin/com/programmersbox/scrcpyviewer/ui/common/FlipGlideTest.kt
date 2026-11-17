package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.devices.info.InfoGrid
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [glideOnFlip] through the info pane's masonry grid ([InfoGrid], 220 dp columns 12 dp apart): a change of column count
 * glides the cards on the layout spring; a resize within one column count moves them with the window in the same frame.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FlipGlideTest {
    private var width by mutableIntStateOf(800)
    private val cards = Array(4) { mutableStateOf(Rect.Zero) }

    private fun scene(reduceMotion: Boolean) = SceneDriver(ImageComposeScene(900, 900, Density(1f)) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
            Box(Modifier.width(width.dp)) {
                InfoGrid(4) { index, sizing ->
                    Box(sizing.height((60 + index * 10).dp).onGloballyPositioned { cards[index].value = it.boundsInRoot() })
                }
            }
        }
    }).also { it.frames(30) }

    private fun card(i: Int) = cards[i].value

    @Test
    fun `a resize within one column count moves the cards in the same frame`() = onEdt {
        val d = scene(reduceMotion = false)
        try {
            // Three columns from 684 dp: a drag from 800 down to 700.
            for (w in 790 downTo 700 step 10) {
                width = w
                d.frame()
                val column = (w - 24) / 3 // whole pixels, as the grid lays them out
                assertEquals(column.toFloat(), card(1).width, 0.5f, "card width follows at $w")
                assertEquals(column + 12f, card(1).left, 0.5f, "second column follows at $w")
                assertEquals(2f * (column + 12) + column, card(2).right, 0.5f, "third column follows at $w")
            }
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `a change of column count glides the cards, then they settle`() = onEdt {
        val d = scene(reduceMotion = false)
        try {
            val before = card(3).topLeft
            width = 600 // two columns: card 3 moves from under card 0 to under card 1
            val path = List(60) { d.frame(); card(3).topLeft }
            val after = path.last()
            assertTrue(after.x > before.x + 100f, "card 3 changes column: $before -> $after")
            val mid = path[4]
            assertTrue(mid.x > before.x + 1f && mid.x < after.x - 1f, "between its columns a few frames in: $before $path")
            assertTrue(path.takeLast(10).all { (it - after).getDistance() < 0.5f }, "settled: $path")
            assertEquals((600 - 12) / 2f, card(3).width, 1f, "at its new width")
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `under reduced motion a change of column count lands in one frame`() = onEdt {
        val d = scene(reduceMotion = true)
        try {
            width = 600
            d.frame()
            val oneFrame = card(3)
            d.frames(60)
            assertEquals(card(3), oneFrame)
        } finally {
            d.scene.close()
        }
    }

    /** Two boxes side by side from 300 px, else stacked; each box is half or all of the width. */
    private class PairPolicy(flips: LayoutFlips) : FlipNotingPolicy(flips) {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 600 // an intrinsic query: never stacked
            val stacked = w < 300
            noteShape(stacked)
            val each = if (stacked) w else w / 2
            val placeables = measurables.map { it.measure(Constraints.fixed(each, 40)) }
            return layout(w, if (stacked) 80 else 40) {
                placeables[0].place(0, 0)
                placeables[1].place(if (stacked) 0 else each, if (stacked) 40 else 0)
            }
        }
    }

    /** One box, narrow under 220 px: notes the shape only. */
    private class InnerPolicy(flips: LayoutFlips) : FlipNotingPolicy(flips) {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            noteShape(constraints.maxWidth < 220)
            val p = measurables.single().measure(constraints)
            return layout(p.width, p.height) { p.place(0, 0) }
        }
    }

    @Test
    fun `intrinsic queries and in-between widths during a glide count no extra flips`() = onEdt {
        var outerWidth by mutableIntStateOf(600)
        val outer = LayoutFlips()
        val inner = LayoutFlips()
        var second by mutableStateOf(Rect.Zero)
        val d = SceneDriver(ImageComposeScene(700, 300, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                // Asks the pair for its intrinsic width on every pass (infinite width: never stacked), then lays it out.
                Layout({
                    LookaheadScope {
                        Layout(
                            content = {
                                Box(Modifier.glideOnFlip(this@LookaheadScope, outer)) {
                                    LookaheadScope {
                                        Layout({ Box(Modifier.fillMaxWidth().height(40.dp)) }, measurePolicy = remember { InnerPolicy(inner) })
                                    }
                                }
                                Box(Modifier.glideOnFlip(this@LookaheadScope, outer).onGloballyPositioned { second = it.boundsInRoot() })
                            },
                            measurePolicy = remember { PairPolicy(outer) },
                        )
                    }
                }, Modifier.width(outerWidth.dp)) { measurables, constraints ->
                    measurables.single().maxIntrinsicWidth(0)
                    val p = measurables.single().measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                }
            }
        })
        try {
            d.frames(30)
            assertEquals(0, outer.count)
            val before = second.topLeft
            outerWidth = 200 // stacked: the first box narrows from 300 to 200 through the inner layout's 220
            val path = List(60) { d.frame(); second.topLeft }
            assertEquals(1, outer.count, "one flip of the pair")
            assertEquals(1, inner.count, "one flip of the inner layout, not one per in-between width")
            val after = path.last()
            val mid = path[3]
            assertTrue((mid - before).getDistance() > 1f && (mid - after).getDistance() > 1f, "gliding: $before $path")
            // One glide, never restarted: the distance left only shrinks (a spring's small overshoot aside).
            val left = path.map { (it - after).getDistance() }
            val overshoot = (before - after).getDistance() * 0.03f
            assertTrue(left.zipWithNext().all { (a, b) -> b <= a + overshoot }, "no restart: $path")
            assertTrue(path.takeLast(10).all { (it - after).getDistance() < 0.5f }, "settled: $path")
        } finally {
            d.scene.close()
        }
    }
}
