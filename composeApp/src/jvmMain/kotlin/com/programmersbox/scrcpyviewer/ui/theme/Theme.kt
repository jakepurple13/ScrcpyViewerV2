package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode

enum class ThemeMode { System, Light, Dark }

/** Whether this mode resolves to the dark scheme right now ([ThemeMode.System] follows the OS). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> systemInDarkMode()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * The OS setting, from Nucleus' detector (the macOS user default, the Windows registry, the Linux portal), the same
 * value inside every window and outside them. Compose's own [isSystemInDarkTheme] reads the app's effective
 * appearance inside a window, which Nucleus' window changes to match its title bar, so it would follow the app's
 * own theme back. Falls back to Compose's where the detector cannot load.
 */
@Composable
private fun systemInDarkMode(): Boolean = if (nucleusDarkModeUsable) isSystemInDarkMode() else isSystemInDarkTheme()

private val nucleusDarkModeUsable: Boolean by lazy { runCatching { getPlatformDarkModeDetector().isDark() }.isSuccess }

/** The theme the nearest [ScrcpyViewerTheme] applies; null outside one. */
val LocalAppTheme = staticCompositionLocalOf<AppTheme?> { null }

@Composable
fun ScrcpyViewerTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val dark = theme.mode.isDark()
    val scheme = remember(theme, dark) { colorSchemeFor(theme, dark) }
    val tokens = remember(theme, scheme, dark) { styleTokens(theme, scheme, dark) }
    // Light mode keeps the neon colors with a softer glow. Retro never glows.
    val glow = theme.glow.takeIf { it > 0f && theme.style != ThemeStyle.Retro }
        ?.let { NeonGlow(scheme.primary, if (dark) it else it * 0.6f) }
    CompositionLocalProvider(
        LocalAppTheme provides theme, LocalNeonGlow provides glow, LocalStyleTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = remember(theme.style) { shapesFor(theme.style) },
            typography = remember(theme.style) { typographyFor(theme.style) },
            content = content,
        )
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
