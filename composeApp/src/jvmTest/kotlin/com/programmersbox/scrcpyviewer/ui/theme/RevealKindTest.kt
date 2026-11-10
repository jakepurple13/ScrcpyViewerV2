package com.programmersbox.scrcpyviewer.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class RevealKindTest {
    private val material = AppTheme()

    @Test
    fun `each style arrives its own way`() {
        assertEquals(RevealKind.Frost, RevealKind.between(material, material.copy(style = ThemeStyle.Glass)))
        assertEquals(RevealKind.Inflate, RevealKind.between(material, material.copy(style = ThemeStyle.Soft)))
        assertEquals(RevealKind.Repaint, RevealKind.between(material, material.copy(style = ThemeStyle.Retro)))
        assertEquals(RevealKind.Circle, RevealKind.between(material.copy(style = ThemeStyle.Glass), material))
    }

    @Test
    fun `palette and mode changes keep the circle`() {
        val glass = material.copy(style = ThemeStyle.Glass)
        assertEquals(RevealKind.Circle, RevealKind.between(glass, glass.copy(palette = ThemePalette.Rose)))
        assertEquals(RevealKind.Circle, RevealKind.between(glass, glass.copy(mode = ThemeMode.Dark)))
    }

    @Test
    fun `switching retro colors repaints`() {
        val retro = material.copy(style = ThemeStyle.Retro)
        assertEquals(RevealKind.Repaint, RevealKind.between(retro, retro.copy(retroPalette = true)))
    }
}
