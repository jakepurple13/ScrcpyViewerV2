package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.window.barColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StylesTest {
    private fun scheme(style: ThemeStyle, dark: Boolean = false, retroPalette: Boolean = false, palette: ThemePalette = ThemePalette.Indigo) =
        colorSchemeFor(AppTheme(palette = palette, style = style, retroPalette = retroPalette), dark)

    @Test
    fun `material style leaves the palette scheme unchanged`() {
        val plain = colorSchemeFor(AppTheme(palette = ThemePalette.Indigo), false)
        val styled = scheme(ThemeStyle.Material)
        assertEquals(plain.toString(), styled.toString())
    }

    @Test
    fun `glass panes are see-through and dialogs mostly opaque`() {
        val s = scheme(ThemeStyle.Glass, dark = true)
        assertTrue(s.surface.alpha < 0.5f)
        assertTrue(s.surfaceContainerLow.alpha < 0.5f)
        assertTrue(s.surfaceContainerHigh.alpha > 0.8f)
    }

    @Test
    fun `glass opacity runs from nearly clear to nearly solid`() {
        fun glass(o: Float) = colorSchemeFor(AppTheme(style = ThemeStyle.Glass, glassOpacity = o), true)
        assertEquals(0f, glass(0f).surface.alpha)
        assertTrue(glass(1f).surface.alpha > 0.5f)
        assertTrue(glass(0f).surfaceContainerHigh.alpha > 0.75f, "dialogs stay readable")
        assertTrue(glass(0.2f).surfaceContainerLow.alpha < glass(0.8f).surfaceContainerLow.alpha)
        assertEquals(glass(1f).surface.alpha, glass(3f).surface.alpha)
    }

    @Test
    fun `soft uses one opaque surface color everywhere`() {
        for (dark in listOf(false, true)) {
            val s = scheme(ThemeStyle.Soft, dark)
            val all = listOf(s.background, s.surface, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            assertEquals(1, all.toSet().size, "dark=$dark")
            assertEquals(1f, s.surface.alpha)
        }
    }

    @Test
    fun `classic retro is windows 95 gray and navy whatever the palette`() {
        val s = scheme(ThemeStyle.Retro, palette = ThemePalette.Rose)
        assertEquals(Color(0xFFC0C0C0), s.surface)
        assertEquals(Color(0xFF000080), s.primaryContainer)
        assertEquals(Color.White, s.surfaceContainerLowest)
        val ocean = scheme(ThemeStyle.Retro, palette = ThemePalette.Ocean)
        assertEquals(ocean.surface, s.surface)
        assertEquals(ocean.primary, s.primary)
    }

    @Test
    fun `classic retro dark is high contrast black`() {
        val s = scheme(ThemeStyle.Retro, dark = true)
        assertEquals(Color.Black, s.surface)
        assertEquals(Color.White, s.onSurface)
    }

    @Test
    fun `retro can follow the palette`() {
        val rose = scheme(ThemeStyle.Retro, retroPalette = true, palette = ThemePalette.Rose)
        val ocean = scheme(ThemeStyle.Retro, retroPalette = true, palette = ThemePalette.Ocean)
        assertNotEquals(rose.primaryContainer, ocean.primaryContainer)
        assertNotEquals(Color(0xFFC0C0C0), rose.surface)
    }

    @Test
    fun `retro ignores pure black`() {
        val theme = AppTheme(style = ThemeStyle.Retro, pureBlack = true, retroPalette = true)
        assertNotEquals(Color.Black, colorSchemeFor(theme, true).surface)
    }

    @Test
    fun `retro shapes are square and others rounded`() {
        assertEquals(0.dp, (shapesFor(ThemeStyle.Retro).medium.topStart as androidx.compose.foundation.shape.CornerSize).toPx(androidx.compose.ui.geometry.Size(100f, 100f), androidx.compose.ui.unit.Density(1f)).dp)
        assertNotEquals(RectangleShape, shapesFor(ThemeStyle.Soft).medium)
    }

    @Test
    fun `retro title bar is navy and glass blur bar is see-through`() {
        val retro = AppTheme(style = ThemeStyle.Retro)
        val retroScheme = colorSchemeFor(retro, false)
        assertEquals(Color(0xFF000080), barColors(retroScheme, styleTokens(retro, retroScheme, false)).background)

        val glass = AppTheme(style = ThemeStyle.Glass)
        val glassScheme = colorSchemeFor(glass, true)
        val tokens = styleTokens(glass, glassScheme, true)
        assertTrue(barColors(glassScheme, tokens, desktopBlur = true).background.alpha < 1f)
        assertEquals(1f, barColors(glassScheme, tokens, desktopBlur = false).background.alpha)
    }

    @Test
    fun `unknown style names fall back to material`() {
        assertEquals(ThemeStyle.Material, ThemeStyle.fromName("Aero"))
        assertEquals(ThemeStyle.Material, ThemeStyle.fromName(null))
        assertEquals(ThemeStyle.Retro, ThemeStyle.fromName("Retro"))
    }
}
