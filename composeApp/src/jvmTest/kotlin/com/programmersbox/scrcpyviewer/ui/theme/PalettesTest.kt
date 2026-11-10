package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PalettesTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private fun near(expected: Color, actual: Color) {
        val d = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue(d < 0.01f, "expected $expected, got $actual")
    }

    @Test
    fun `oklch matches known sRGB values`() {
        near(Color.White, oklch(1f, 0f, 0f))
        near(Color.Black, oklch(0f, 0f, 0f))
        near(Color(0xFFFF0000), oklch(0.62796f, 0.25768f, 29.234f))
    }

    @Test
    fun `out of gamut chroma is reduced, not clipped per channel`() {
        val c = oklch(0.9f, 0.4f, 260f) // far outside sRGB
        listOf(c.red, c.green, c.blue).forEach { assertTrue(it in 0f..1f) }
        assertTrue(c.blue >= c.red, "hue stays blue: $c")
    }

    @Test
    fun `every palette keeps text readable in both modes`() {
        val themes = ThemePalette.entries.map { AppTheme(palette = it) } +
            listOf(CustomPalette(0f, 0f, 0f), CustomPalette(90f, CustomPalette.MAX_CHROMA, 180f), CustomPalette(300f, 0.18f, 45f))
                .map { AppTheme(palette = ThemePalette.Custom, custom = it) }
        for (theme in themes) for (dark in listOf(false, true)) for (black in listOf(false, true)) {
            val s = colorSchemeFor(theme.copy(pureBlack = black), dark)
            val name = "${theme.palette} ${theme.custom} dark=$dark black=$black"
            assertTrue(contrast(s.onPrimary, s.primary) >= 4.5f, "$name onPrimary")
            assertTrue(contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5f, "$name onPrimaryContainer")
            assertTrue(contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5f, "$name onSecondaryContainer")
            assertTrue(contrast(s.onTertiaryContainer, s.tertiaryContainer) >= 4.5f, "$name onTertiaryContainer")
            assertTrue(contrast(s.onSurface, s.surface) >= 7f, "$name onSurface")
            assertTrue(contrast(s.onSurfaceVariant, s.surfaceContainerHighest) >= 4.5f, "$name onSurfaceVariant")
        }
    }

    @Test
    fun `teal keeps the original hand-tuned scheme`() {
        assertSame(LightColors, colorSchemeFor(AppTheme(), dark = false))
        assertSame(DarkColors, colorSchemeFor(AppTheme(), dark = true))
    }

    @Test
    fun `pure black only changes dark mode`() {
        val ocean = AppTheme(palette = ThemePalette.Ocean, pureBlack = true)
        assertEquals(Color.Black, colorSchemeFor(ocean, dark = true).surface)
        assertEquals(Color.Black, colorSchemeFor(ocean, dark = true).background)
        assertEquals(colorSchemeFor(ocean.copy(pureBlack = false), dark = false).surface, colorSchemeFor(ocean, dark = false).surface)
        assertEquals(Color.Black, colorSchemeFor(AppTheme(pureBlack = true), dark = true).surface)
    }

    @Test
    fun `custom palette clamps out-of-range values`() {
        val seed = CustomPalette(hue = 500f, chroma = 2f, accentShift = 400f).seed
        assertEquals(359f, seed.hue)
        assertEquals(CustomPalette.MAX_CHROMA, seed.chroma)
        assertEquals((359f + 180f) % 360f, seed.tertiaryHue)
    }

    @Test
    fun `unknown palette name falls back to teal`() {
        assertEquals(ThemePalette.Teal, ThemePalette.fromName("Neon"))
        assertEquals(ThemePalette.Teal, ThemePalette.fromName(null))
        assertEquals(ThemePalette.Rose, ThemePalette.fromName("Rose"))
    }
}
