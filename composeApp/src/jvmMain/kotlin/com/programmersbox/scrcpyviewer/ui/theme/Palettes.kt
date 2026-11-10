package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/** What a generated palette is built from: main [hue] and [chroma], and the tertiary (accent) hue. */
data class PaletteSeed(val hue: Float, val chroma: Float, val tertiaryHue: Float)

/**
 * The app's color palettes. [seed] is null for [Teal] (the original hand-tuned scheme), [Custom]
 * (built from [CustomPalette]) and [CustomNeon] (from [CustomNeon]). Picking a [neon] palette turns the Neon
 * switch on; it can still be turned off.
 * Persisted by [name], so renaming an entry resets users to Teal.
 */
enum class ThemePalette(val label: String, val seed: PaletteSeed?, val neon: Boolean = false) {
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
    Custom("Custom", null),
    Synthwave("Synthwave", PaletteSeed(350f, NEON_CHROMA, 215f), neon = true),
    Cyber("Cyber", PaletteSeed(205f, NEON_CHROMA, 345f), neon = true),
    Toxic("Toxic", PaletteSeed(135f, NEON_CHROMA, 300f), neon = true),
    // Shown under the Neon heading, so "Custom" is enough and fits the tile.
    CustomNeon("Custom", null, neon = true);

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

/** Chroma every neon palette uses; out-of-gamut tones lose some of it (see [oklch]). */
const val NEON_CHROMA = 0.26f

/** The user's neon palette: the glow (primary) and accent hues set directly, and how strong the glow is (0..1). */
@Serializable
data class CustomNeon(
    val glowHue: Float = 300f,
    val accentHue: Float = 190f,
    val glow: Float = DEFAULT_GLOW,
) {
    val seed: PaletteSeed
        get() = PaletteSeed(glowHue.coerceIn(0f, 359f), NEON_CHROMA, accentHue.coerceIn(0f, 359f))

    companion object {
        const val DEFAULT_GLOW = 0.7f
    }
}

/** Everything that decides the app's colors. [neon] is the Neon switch, which makes any palette neon. */
data class AppTheme(
    val mode: ThemeMode = ThemeMode.System,
    val palette: ThemePalette = ThemePalette.Teal,
    val pureBlack: Boolean = false,
    val custom: CustomPalette = CustomPalette(),
    val neon: Boolean = false,
    val customNeon: CustomNeon = CustomNeon(),
) {
    /** Neon colors and glow: the Neon switch, for any palette. Picking a neon palette turns it on (see settings). */
    val isNeon: Boolean get() = neon

    /** Glow strength 0..1, or 0 when not neon. Only Custom neon changes it. */
    val glow: Float
        get() = when {
            !isNeon -> 0f
            palette == ThemePalette.CustomNeon -> customNeon.glow.coerceIn(0f, 1f)
            else -> CustomNeon.DEFAULT_GLOW
        }

    /**
     * The seed this theme generates from; null only for plain (non-neon) Teal. A neon palette with the switch off
     * keeps its hues at a regular palette's vividness.
     */
    val seed: PaletteSeed?
        get() {
            val s = when (palette) {
                ThemePalette.Teal -> if (neon) TealSeed else null
                ThemePalette.Custom -> custom.seed
                ThemePalette.CustomNeon -> customNeon.seed
                else -> palette.seed
            }
            return if (s != null && palette.neon && !neon) s.copy(chroma = CustomPalette.MAX_CHROMA * 0.8f) else s
        }
}

/** The Material 3 scheme for [theme] in light or [dark] mode. */
fun colorSchemeFor(theme: AppTheme, dark: Boolean): ColorScheme {
    val seed = theme.seed
    val base = when {
        seed != null && theme.isNeon -> generateNeonScheme(seed, dark)
        seed != null -> generateScheme(seed, dark)
        dark -> DarkColors
        else -> LightColors
    }
    return if (dark && theme.pureBlack) base.pureBlack(seed ?: TealSeed) else base
}

/**
 * The neon look: [seed]'s hues at [NEON_CHROMA], whatever its own chroma. Dark mode sits on near-black
 * surfaces with bright primaries; light mode keeps regular surfaces with a vivid primary.
 */
fun generateNeonScheme(seed: PaletteSeed, dark: Boolean): ColorScheme {
    val h = seed.hue
    val th = seed.tertiaryHue
    val base = generateScheme(seed.copy(chroma = NEON_CHROMA), dark)
    fun p(l: Float, c: Float) = oklch(l, c, h)
    fun t(l: Float, c: Float) = oklch(l, c, th)
    return if (dark) base.copy(
        primary = p(0.80f, 0.21f), onPrimary = p(0.15f, 0.05f),
        primaryContainer = p(0.30f, 0.14f), onPrimaryContainer = p(0.90f, 0.12f),
        secondary = p(0.80f, 0.08f), onSecondary = p(0.20f, 0.05f),
        secondaryContainer = p(0.24f, 0.06f), onSecondaryContainer = p(0.90f, 0.1f),
        tertiary = t(0.80f, 0.18f), onTertiary = t(0.18f, 0.08f),
        tertiaryContainer = t(0.32f, 0.16f), onTertiaryContainer = t(0.88f, 0.16f),
        background = p(0.12f, 0.025f), surface = p(0.12f, 0.025f), surfaceDim = p(0.12f, 0.025f),
        surfaceContainerLowest = p(0.09f, 0.02f), surfaceContainerLow = p(0.14f, 0.03f),
        surfaceContainer = p(0.155f, 0.035f), surfaceContainerHigh = p(0.175f, 0.038f),
        surfaceContainerHighest = p(0.19f, 0.04f), surfaceBright = p(0.24f, 0.045f),
        surfaceVariant = p(0.24f, 0.05f),
        outline = p(0.60f, 0.12f), outlineVariant = p(0.32f, 0.09f),
        surfaceTint = p(0.80f, 0.21f), inversePrimary = p(0.55f, 0.24f),
    ) else base.copy(
        primary = p(0.55f, 0.26f), onPrimary = Color.White, surfaceTint = p(0.55f, 0.26f),
        tertiaryContainer = t(0.88f, 0.12f), onTertiaryContainer = t(0.30f, 0.2f),
    )
}

/** Teal's approximate seed, used to tint pure-black surfaces and for neon Teal. */
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
