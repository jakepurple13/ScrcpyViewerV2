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
                .map { AppTheme(palette = ThemePalette.Custom, custom = it) } +
            ThemePalette.entries.map { AppTheme(palette = it, neon = true) } +
            listOf(CustomNeon(), CustomNeon(60f, 120f, 1f), CustomNeon(240f, 0f, 0f), CustomNeon(100f, 100f, 0.5f))
                .map { AppTheme(palette = ThemePalette.CustomNeon, customNeon = it) }
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
    fun `neon comes only from the switch, and only custom neon changes the glow`() {
        assertEquals(0f, AppTheme().glow)
        assertEquals(CustomNeon.DEFAULT_GLOW, AppTheme(neon = true).glow)
        assertEquals(CustomNeon.DEFAULT_GLOW, AppTheme(palette = ThemePalette.Cyber, neon = true).glow)
        assertEquals(0.3f, AppTheme(palette = ThemePalette.CustomNeon, neon = true, customNeon = CustomNeon(glow = 0.3f)).glow)
        assertEquals(1f, AppTheme(palette = ThemePalette.CustomNeon, neon = true, customNeon = CustomNeon(glow = 4f)).glow)
        // A neon palette with the switch turned off is not neon.
        assertEquals(0f, AppTheme(palette = ThemePalette.CustomNeon).glow)
        assertEquals(0f, AppTheme(palette = ThemePalette.Synthwave).glow)
        // Custom neon's sliders only matter while it is the palette.
        assertEquals(CustomNeon.DEFAULT_GLOW, AppTheme(palette = ThemePalette.Rose, neon = true, customNeon = CustomNeon(glow = 0.1f)).glow)
    }

    @Test
    fun `neon switch makes teal neon instead of its hand-tuned scheme`() {
        val neonTeal = colorSchemeFor(AppTheme(neon = true), dark = true)
        assertTrue(neonTeal != DarkColors)
        assertTrue(neonTeal.surface.luminance() < DarkColors.surface.luminance(), "neon dark surfaces are darker")
    }

    @Test
    fun `custom neon uses its two hues directly`() {
        val theme = AppTheme(palette = ThemePalette.CustomNeon, neon = true, customNeon = CustomNeon(glowHue = 40f, accentHue = 280f))
        val seed = theme.seed!!
        assertEquals(40f, seed.hue)
        assertEquals(280f, seed.tertiaryHue)
        assertEquals(NEON_CHROMA, seed.chroma)
        // Switched off: same hues at a regular palette's vividness, regular scheme.
        val off = theme.copy(neon = false)
        assertEquals(40f, off.seed!!.hue)
        assertTrue(off.seed!!.chroma <= CustomPalette.MAX_CHROMA)
        assertEquals(generateScheme(off.seed!!, dark = true).surface, colorSchemeFor(off, dark = true).surface)
    }

    @Test
    fun `unknown palette name falls back to teal`() {
        assertEquals(ThemePalette.Teal, ThemePalette.fromName("Neon"))
        assertEquals(ThemePalette.Teal, ThemePalette.fromName(null))
        assertEquals(ThemePalette.Rose, ThemePalette.fromName("Rose"))
    }
}
