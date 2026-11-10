package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { System, Light, Dark }

/** Whether this mode resolves to the dark scheme right now ([ThemeMode.System] follows the OS). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** The theme the nearest [ScrcpyViewerTheme] applies; null outside one. */
val LocalAppTheme = staticCompositionLocalOf<AppTheme?> { null }

@Composable
fun ScrcpyViewerTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val dark = theme.mode.isDark()
    val scheme = remember(theme, dark) { colorSchemeFor(theme, dark) }
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The Teal palette in [themeMode]. */
@Composable
fun ScrcpyViewerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) =
    ScrcpyViewerTheme(AppTheme(mode = themeMode), content)

/** Re-applies the enclosing window's theme (see [LocalAppTheme]), or the default outside one. */
@Composable
fun ScrcpyViewerTheme(content: @Composable () -> Unit) =
    ScrcpyViewerTheme(LocalAppTheme.current ?: AppTheme(), content)
