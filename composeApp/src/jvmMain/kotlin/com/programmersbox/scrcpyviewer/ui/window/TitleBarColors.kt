package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.programmersbox.scrcpyviewer.ui.theme.StyleTokens
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle

/** Title bar colours, taken from the app's Material 3 scheme so the bar matches the nav rail / mirror strip. */
data class BarColors(val background: Color, val inactiveBackground: Color, val content: Color, val border: Color)

/**
 * The bar for [scheme] in [tokens]' style. Glass lays its see-through rail color over the wallpaper's base, since
 * the bar itself can't be see-through; Retro uses the Windows 95 title colors, gray when the window is inactive.
 */
fun barColors(scheme: ColorScheme, tokens: StyleTokens? = null, desktopBlur: Boolean = false): BarColors = when (tokens?.style) {
    // The window is see-through: tint the bar like the rail so the blurred desktop shows through it too.
    ThemeStyle.Glass if desktopBlur -> BarColors(
        background = scheme.surfaceContainerLow,
        inactiveBackground = scheme.surfaceContainerLowest,
        content = scheme.onSurface,
        border = Color.Transparent,
    )
    ThemeStyle.Retro -> BarColors(
        background = tokens.titleStart,
        inactiveBackground = Color(0xFF808080),
        content = tokens.onTitle,
        border = tokens.titleStart,
    )
    ThemeStyle.Glass -> {
        val base = tokens.wallpaper.firstOrNull() ?: scheme.surface
        BarColors(
            background = scheme.surfaceContainerLow.compositeOver(base),
            inactiveBackground = scheme.surfaceContainerLowest.compositeOver(base),
            content = scheme.onSurface,
            border = scheme.outlineVariant,
        )
    }
    else -> BarColors(
        background = scheme.surfaceContainer,
        inactiveBackground = scheme.surfaceContainerLow,
        content = scheme.onSurface,
        border = scheme.outlineVariant,
    )
}
