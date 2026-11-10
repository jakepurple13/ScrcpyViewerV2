package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/** What a generated palette is built from: main [hue] and [chroma], and the tertiary (accent) hue. */
data class PaletteSeed(val hue: Float, val chroma: Float, val tertiaryHue: Float)

/**
 * The app's color palettes. [seed] is null for [Teal] (the original hand-tuned scheme) and [Custom]
 * (built from [CustomPalette]). Persisted by [name], so renaming an entry resets users to Teal.
 */
enum class ThemePalette(val label: String, val seed: PaletteSeed?) {
    Teal("Teal", null),
    Ocean("Ocean", PaletteSeed(245f, 0.13f, 200f)),
    Indigo("Indigo", PaletteSeed(275f, 0.15f, 330f)),
    Orchid("Orchid", PaletteSeed(320f, 0.14f, 20f)),
    Rose("Rose", PaletteSeed(10f, 0.15f, 60f)),
    Ember("Ember", PaletteSeed(50f, 0.15f, 100f)),
    Citrus("Citrus", PaletteSeed(105f, 0.13f, 160f)),
    Droid("Droid", PaletteSeed(145f, 0.16f, 210f)),
    Forest("Forest", PaletteSeed(160f, 0.07f, 90f)),
    Espresso("Espresso", PaletteSeed(60f, 0.05f, 30f)),
    Slate("Slate", PaletteSeed(250f, 0.025f, 250f)),
    Custom("Custom", null);

    companion object {
        /** The palette saved as [name], or [Teal] for a missing or unknown name. */
        fun fromName(name: String?): ThemePalette = entries.firstOrNull { it.name == name } ?: Teal
    }
}

/** The user's own palette. [chroma] runs 0 (grey) to [MAX_CHROMA]; [accentShift] is degrees from [hue]. */
@Serializable
data class CustomPalette(
    val hue: Float = 25f,
    val chroma: Float = 0.14f,
    val accentShift: Float = 60f,
) {
    val seed: PaletteSeed
        get() {
            val h = hue.coerceIn(0f, 359f)
            return PaletteSeed(h, chroma.coerceIn(0f, MAX_CHROMA), (h + accentShift.coerceIn(0f, 180f)) % 360f)
        }

    companion object {
        const val MAX_CHROMA = 0.18f
    }
}

/** Everything that decides the app's colors. */
data class AppTheme(
    val mode: ThemeMode = ThemeMode.System,
    val palette: ThemePalette = ThemePalette.Teal,
    val pureBlack: Boolean = false,
    val custom: CustomPalette = CustomPalette(),
)

/** The Material 3 scheme for [theme] in light or [dark] mode. */
fun colorSchemeFor(theme: AppTheme, dark: Boolean): ColorScheme {
    val seed = when (theme.palette) {
        ThemePalette.Teal -> null
        ThemePalette.Custom -> theme.custom.seed
        else -> theme.palette.seed
    }
    val base = when {
        seed != null -> generateScheme(seed, dark)
        dark -> DarkColors
        else -> LightColors
    }
    return if (dark && theme.pureBlack) base.pureBlack(seed ?: TealSeed) else base
}

/** Teal's approximate seed, used to tint pure-black surfaces. */
private val TealSeed = PaletteSeed(195f, 0.11f, 250f)

/** A full M3 scheme from [seed]: tones picked in OKLCH, neutrals tinted toward the seed hue. */
fun generateScheme(seed: PaletteSeed, dark: Boolean): ColorScheme {
    val (h, c, th) = seed
    fun p(l: Float, k: Float) = oklch(l, c * k, h)
    fun t(l: Float, k: Float) = oklch(l, c * k, th)
    return if (dark) darkColorScheme(
        primary = p(0.80f, 0.85f), onPrimary = p(0.27f, 1f),
        primaryContainer = p(0.36f, 0.75f), onPrimaryContainer = p(0.92f, 0.5f),
        secondary = p(0.80f, 0.25f), onSecondary = p(0.28f, 0.3f),
        secondaryContainer = p(0.33f, 0.25f), onSecondaryContainer = p(0.90f, 0.25f),
        tertiary = t(0.80f, 0.6f), onTertiary = t(0.28f, 1f),
        tertiaryContainer = t(0.36f, 0.6f), onTertiaryContainer = t(0.90f, 0.5f),
        error = DarkColors.error, onError = DarkColors.onError,
        errorContainer = DarkColors.errorContainer, onErrorContainer = DarkColors.onErrorContainer,
        background = p(0.17f, 0.08f), onBackground = p(0.92f, 0.08f),
        surface = p(0.17f, 0.08f), onSurface = p(0.92f, 0.08f),
        surfaceVariant = p(0.32f, 0.12f), onSurfaceVariant = p(0.78f, 0.12f),
        outline = p(0.62f, 0.1f), outlineVariant = p(0.36f, 0.12f),
        scrim = Color.Black,
        inverseSurface = p(0.92f, 0.05f), inverseOnSurface = p(0.27f, 0.08f), inversePrimary = p(0.47f, 1f),
        surfaceTint = p(0.80f, 0.85f),
        surfaceDim = p(0.17f, 0.08f), surfaceBright = p(0.30f, 0.1f),
        surfaceContainerLowest = p(0.13f, 0.06f), surfaceContainerLow = p(0.19f, 0.08f),
        surfaceContainer = p(0.21f, 0.1f), surfaceContainerHigh = p(0.235f, 0.11f),
        surfaceContainerHighest = p(0.255f, 0.12f),
    ) else lightColorScheme(
        primary = p(0.47f, 1f), onPrimary = Color.White,
        primaryContainer = p(0.90f, 0.55f), onPrimaryContainer = p(0.26f, 1f),
        secondary = p(0.45f, 0.3f), onSecondary = Color.White,
        secondaryContainer = p(0.91f, 0.25f), onSecondaryContainer = p(0.28f, 0.35f),
        tertiary = t(0.48f, 1f), onTertiary = Color.White,
        tertiaryContainer = t(0.90f, 0.5f), onTertiaryContainer = t(0.28f, 1f),
        error = LightColors.error, onError = LightColors.onError,
        errorContainer = LightColors.errorContainer, onErrorContainer = LightColors.onErrorContainer,
        background = p(0.985f, 0.07f), onBackground = p(0.22f, 0.12f),
        surface = p(0.985f, 0.07f), onSurface = p(0.22f, 0.12f),
        surfaceVariant = p(0.92f, 0.15f), onSurfaceVariant = p(0.43f, 0.15f),
        outline = p(0.58f, 0.12f), outlineVariant = p(0.84f, 0.12f),
        scrim = Color.Black,
        inverseSurface = p(0.30f, 0.08f), inverseOnSurface = p(0.95f, 0.05f), inversePrimary = p(0.80f, 0.85f),
        surfaceTint = p(0.47f, 1f),
        surfaceDim = p(0.90f, 0.1f), surfaceBright = p(0.985f, 0.07f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = p(0.97f, 0.09f),
        surfaceContainer = p(0.955f, 0.1f), surfaceContainerHigh = p(0.94f, 0.11f),
        surfaceContainerHighest = p(0.925f, 0.12f),
    )
}

/** Black background and surfaces; raised containers stay faintly tinted so layers still read. */
private fun ColorScheme.pureBlack(seed: PaletteSeed): ColorScheme {
    fun n(l: Float, k: Float) = oklch(l, seed.chroma * k, seed.hue)
    return copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = n(0.10f, 0.06f), surfaceContainer = n(0.13f, 0.08f),
        surfaceContainerHigh = n(0.16f, 0.09f), surfaceContainerHighest = n(0.18f, 0.1f),
        surfaceVariant = n(0.24f, 0.1f), surfaceBright = n(0.24f, 0.1f),
        outlineVariant = n(0.30f, 0.1f),
    )
}
