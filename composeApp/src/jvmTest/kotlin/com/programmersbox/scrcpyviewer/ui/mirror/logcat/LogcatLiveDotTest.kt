package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.skia.Bitmap

/** The shared live dot: one 1.6 s blink, solid when not blinking or under reduced motion. */
class LogcatLiveDotTest {
    /** The dot's center pixel (black dot on white) at each of [millis]. */
    private fun samples(blinking: Boolean, motion: Boolean, millis: List<Long>): List<Int> {
        val scene = ImageComposeScene(20, 20, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                LogcatLiveDot(Color.Black, 20.dp, blinking, motion = motion)
            }
        }
        try {
            // Frames every 16 ms from 0, so the infinite transition runs from its start like on screen.
            val out = mutableListOf<Int>()
            var t = 0L
            for (target in millis) {
                while (t < target) {
                    t = minOf(t + 16, target)
                    scene.render(t * 1_000_000)
                }
                out += Bitmap.makeFromImage(scene.render(t * 1_000_000)).getColor(10, 10)
            }
            return out
        } finally {
            scene.close()
        }
    }

    @Test
    fun `blinks down at half the 1600 ms cycle and is back up at the full cycle`() {
        onEdt {
            val (start, low, back) = samples(blinking = true, motion = true, listOf(16, 16 + LIVE_BLINK_MILLIS / 2L, 16 + LIVE_BLINK_MILLIS.toLong()))
            assertNotEquals(start, low, "the dot fades halfway through the blink")
            // Near-solid again a full cycle later (within a frame's worth of easing).
            val lowGray = low and 0xFF
            val backGray = back and 0xFF
            val startGray = start and 0xFF
            kotlin.test.assertTrue(lowGray > startGray + 100, "low point is light: $lowGray vs $startGray")
            kotlin.test.assertTrue(backGray < startGray + 20, "back to solid: $backGray vs $startGray")
        }
    }

    @Test
    fun `stays solid under reduced motion and when not blinking`() {
        onEdt {
            val times = listOf(16L, 400, 800, 1_200)
            val reduced = samples(blinking = true, motion = false, times)
            assertEquals(1, reduced.toSet().size, "no blink under reduced motion: $reduced")
            assertEquals(0xFF000000.toInt(), reduced.first())
            val off = samples(blinking = false, motion = true, times)
            assertEquals(listOf(0xFF000000.toInt()), off.toSet().toList())
        }
    }
}
