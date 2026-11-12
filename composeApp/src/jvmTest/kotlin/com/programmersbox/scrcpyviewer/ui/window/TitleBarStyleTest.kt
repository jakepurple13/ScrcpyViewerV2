package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.colorSchemeFor
import com.programmersbox.scrcpyviewer.ui.theme.styleTokens
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nucleus' AWT window sets the frame's background to the title-bar style's background from a LaunchedEffect. AWT
 * throws for a see-through background on a decorated frame, and that failure cancels the window's Recomposer (the
 * window stops updating). Every style AppWindow can hand Nucleus must therefore be opaque, Glass with blur included.
 */
class TitleBarStyleTest {
    @Test
    fun `the title-bar style given to Nucleus is opaque for every style, mode and blur`() {
        for (style in ThemeStyle.entries) for (dark in listOf(false, true)) for (blur in listOf(false, true))
            for (palette in listOf(ThemePalette.Teal, ThemePalette.Ocean)) for (pureBlack in listOf(false, true)) {
                val theme = AppTheme(style = style, desktopBlur = blur, palette = palette, pureBlack = pureBlack)
                val scheme = colorSchemeFor(theme, dark)
                val bar = barColors(scheme, styleTokens(theme, scheme, dark), desktopBlur = blur)
                val colors = nucleusTitleBarStyle(bar, 38.dp).colors
                val what = "$style dark=$dark blur=$blur $palette pureBlack=$pureBlack"
                assertEquals(1f, colors.background.alpha, "background, $what")
                assertEquals(1f, colors.inactiveBackground.alpha, "inactive background, $what")
            }
    }
}
