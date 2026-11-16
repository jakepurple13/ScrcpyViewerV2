package com.programmersbox.scrcpyviewer.ui.theme

import com.programmersbox.scrcpyviewer.ui.pixelsAndClose
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeRevealTest {
    private val teal = AppTheme(mode = ThemeMode.Light)
    private val rose = teal.copy(palette = ThemePalette.Rose)
    private val tealPrimary = colorSchemeFor(teal, dark = false).primary
    private val rosePrimary = colorSchemeFor(rose, dark = false).primary

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    private class Harness {
        val target = mutableStateOf(AppTheme(mode = ThemeMode.Light))
        lateinit var state: ThemeRevealState
        var time = 0L
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            state = rememberThemeRevealState(target.value)
            ScrcpyViewerTheme(state.shown) {
                Box(Modifier.fillMaxSize().themeReveal(state).background(MaterialTheme.colorScheme.primary))
            }
        }

        fun frame(): androidx.compose.ui.graphics.PixelMap {
            time += 16_000_000
            return scene.render(time).pixelsAndClose()
        }
    }

    @Test
    fun `palette change uncovers the new colors from the origin outward`() = onEdt {
        val h = Harness()
        try {
            repeat(3) { h.frame() }
            h.state.origin.next = Offset(10f, 10f)
            h.target.value = rose
            var px = h.frame()
            repeat(8) { px = h.frame() }
            assertTrue(close(rosePrimary, px[12, 12]), "near the origin shows the new palette: ${px[12, 12]}")
            assertTrue(close(tealPrimary, px[195, 195]), "the far corner still shows the old palette: ${px[195, 195]}")
            repeat(40) { px = h.frame() }
            assertTrue(close(rosePrimary, px[195, 195]), "fully revealed: ${px[195, 195]}")
        } finally {
            h.scene.close()
        }
    }

    @Test
    fun `a same-look change mid-reveal drops the frozen old frame`() = onEdt {
        val h = Harness()
        try {
            repeat(3) { h.frame() }
            h.state.origin.next = Offset(10f, 10f)
            h.target.value = rose
            repeat(4) { h.frame() }
            assertTrue(h.state.revealing, "the reveal is running")
            // Only glass opacity changes: the reveal is cancelled and the new move needs no reveal of its own.
            h.target.value = rose.copy(glassOpacity = 0.9f)
            repeat(3) { h.frame() }
            assertTrue(!h.state.revealing, "not stuck revealing")
            assertTrue(h.state.snapshot == null, "the old frame is dropped")
            val px = h.frame()
            assertTrue(close(rosePrimary, px[195, 195]), "the far corner shows the new palette: ${px[195, 195]}")
        } finally {
            h.scene.close()
        }
    }

    @Test
    fun `custom slider edits switch at once without a reveal`() = onEdt {
        val h = Harness()
        try {
            h.target.value = teal.copy(palette = ThemePalette.Custom)
            repeat(40) { h.frame() }
            val edited = teal.copy(palette = ThemePalette.Custom, custom = CustomPalette(hue = 140f))
            h.target.value = edited
            h.frame()
            val px = h.frame()
            val expected = colorSchemeFor(edited, dark = false).primary
            assertTrue(close(expected, px[195, 195]), "corner already new: ${px[195, 195]}")
            assertTrue(close(expected, px[12, 12]))
        } finally {
            h.scene.close()
        }
    }
}
