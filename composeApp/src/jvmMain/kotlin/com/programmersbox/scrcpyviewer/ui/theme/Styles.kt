package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How surfaces and controls are drawn, on top of the palette's colors. [Material] is the app's original look.
 * Persisted by [name], so renaming an entry resets users to Material.
 */
enum class ThemeStyle(val label: String, val description: String) {
    Material("Material", "Flat Material 3 surfaces."),
    Glass("Glass", "Frosted, see-through panes with light edges."),
    Soft("Soft", "One surface color; controls raised from it or pressed into it."),
    Retro("Retro 95", "Beveled gray chrome, sharp corners and checkboxes.");

    companion object {
        /** The style saved as [name], or [Material] for a missing or unknown name. */
        fun fromName(name: String?): ThemeStyle = entries.firstOrNull { it.name == name } ?: Material
    }
}

/** The four bevel tones of a Retro 95 edge, lightest first. */
@Immutable
data class Bevel(val highlight: Color, val light: Color, val shadow: Color, val dark: Color)

/**
 * Colors a style draws with besides the Material scheme: Glass edges and wallpaper, Soft's paired shadows, Retro's
 * bevels and title bar. Provided by [ScrcpyViewerTheme] through [LocalStyleTokens].
 */
@Immutable
data class StyleTokens(
    val style: ThemeStyle,
    val dark: Boolean,
    /** Glass: the thin light line around panes and controls. */
    val edge: Color = Color.Transparent,
    /** Glass: the colors of the wallpaper the window paints behind its panes; base first. */
    val wallpaper: List<Color> = emptyList(),
    /** Soft: the light and dark halves of every shadow. */
    val softLight: Color = Color.Transparent,
    val softDark: Color = Color.Transparent,
    /** Retro: edge tones; [titleStart]..[titleEnd] is the title-bar gradient. */
    val bevel: Bevel = Bevel(Color.White, Color.LightGray, Color.Gray, Color.Black),
    val titleStart: Color = Color.Transparent,
    val titleEnd: Color = Color.Transparent,
    val onTitle: Color = Color.White,
)

val LocalStyleTokens = staticCompositionLocalOf { StyleTokens(ThemeStyle.Material, dark = false) }

/** The style the nearest [ScrcpyViewerTheme] applies. */
val currentStyle: ThemeStyle
    @Composable get() = LocalStyleTokens.current.style

/** [theme]'s scheme reshaped for its style: see-through for Glass, one surface for Soft, Win95 grays for Retro. */
internal fun ColorScheme.styled(theme: AppTheme, dark: Boolean): ColorScheme = when (theme.style) {
    ThemeStyle.Material -> this
    ThemeStyle.Glass -> glass(theme.glassOpacity)
    ThemeStyle.Soft -> soft(theme.seed ?: DefaultSeed, dark)
    ThemeStyle.Retro -> if (theme.retroPalette) retroFromPalette(theme.seed ?: DefaultSeed, dark) else retroClassic(dark)
}

/** Teal's approximate seed, for styles that derive their own tones. */
internal val DefaultSeed = PaletteSeed(195f, 0.11f, 250f)

/**
 * Translucent surfaces over the window's backdrop (the blurred desktop or the wallpaper). [opacity] 0..1 runs from
 * nearly clear to nearly solid. Menus and dialogs, which sit over busy content, never go below mostly opaque so
 * their text reads.
 */
private fun ColorScheme.glass(opacity: Float): ColorScheme {
    val o = opacity.coerceIn(0f, 1f)
    fun Color.a(clear: Float, solid: Float) = copy(alpha = clear + (solid - clear) * o)
    return copy(
        background = background.a(0f, 0.6f),
        surface = surface.a(0f, 0.6f),
        surfaceDim = surfaceDim.a(0.02f, 0.62f),
        surfaceBright = surfaceBright.a(0.15f, 0.75f),
        surfaceContainerLowest = surfaceContainerLowest.a(0.04f, 0.62f),
        surfaceContainerLow = surfaceContainerLow.a(0.08f, 0.75f),
        surfaceContainer = surfaceContainer.a(0.45f, 0.92f),
        surfaceContainerHigh = surfaceContainerHigh.a(0.78f, 0.96f),
        surfaceContainerHighest = surfaceContainerHighest.a(0.25f, 0.85f),
        surfaceVariant = surfaceVariant.a(0.25f, 0.85f),
        secondaryContainer = secondaryContainer.a(0.45f, 0.85f),
    )
}

/** The Soft surface tone: every background and container is this one color. */
internal fun softSurface(seed: PaletteSeed, dark: Boolean): Color =
    if (dark) oklch(0.29f, seed.chroma * 0.12f, seed.hue) else oklch(0.925f, seed.chroma * 0.14f, seed.hue)

private fun ColorScheme.soft(seed: PaletteSeed, dark: Boolean): ColorScheme {
    val s = softSurface(seed, dark)
    return copy(
        background = s, surface = s, surfaceDim = s, surfaceBright = s,
        surfaceContainerLowest = s, surfaceContainerLow = s, surfaceContainer = s,
        surfaceContainerHigh = s, surfaceContainerHighest = s,
        // Selected rows and chips sink in rather than fill, so their tint stays faint.
        surfaceVariant = s, secondaryContainer = primary.copy(alpha = 0.14f).compositeOver(s),
        onSecondaryContainer = primary,
    )
}

private val Face = Color(0xFFC0C0C0)
private val Navy = Color(0xFF000080)
private val Plum = Color(0xFF800080)

/** Windows 95's standard scheme, or High Contrast Black in dark mode. */
private fun ColorScheme.retroClassic(dark: Boolean): ColorScheme = if (dark) retro(
    face = Color.Black, field = Color.Black, text = Color.White, subtext = Color(0xFFC0C0C0),
    select = Plum, onSelect = Color.White, accent = Color(0xFF00FFFF), shadow = Color(0xFF808080),
) else retro(
    face = Face, field = Color.White, text = Color.Black, subtext = Color(0xFF404040),
    select = Navy, onSelect = Color.White, accent = Navy, shadow = Color(0xFF808080),
)

/** Retro's layout with the palette's hue: a faintly tinted face and the palette's primary for selection. */
private fun ColorScheme.retroFromPalette(seed: PaletteSeed, dark: Boolean): ColorScheme {
    val (h, c) = seed
    return if (dark) retro(
        face = oklch(0.30f, c * 0.18f, h), field = oklch(0.20f, c * 0.12f, h),
        text = oklch(0.96f, c * 0.05f, h), subtext = oklch(0.78f, c * 0.1f, h),
        select = oklch(0.50f, c, h), onSelect = Color.White, accent = oklch(0.80f, c, h),
        shadow = oklch(0.18f, c * 0.1f, h),
    ) else retro(
        face = oklch(0.80f, c * 0.2f, h), field = Color.White,
        text = Color.Black, subtext = oklch(0.35f, c * 0.2f, h),
        select = oklch(0.36f, c, h), onSelect = Color.White, accent = oklch(0.36f, c, h),
        shadow = oklch(0.55f, c * 0.2f, h),
    )
}

private fun ColorScheme.retro(
    face: Color, field: Color, text: Color, subtext: Color, select: Color, onSelect: Color, accent: Color, shadow: Color,
): ColorScheme = copy(
    primary = accent, onPrimary = if (accent == Color(0xFF00FFFF)) Color.Black else Color.White,
    primaryContainer = select, onPrimaryContainer = onSelect,
    secondary = accent, onSecondary = onSelect,
    secondaryContainer = select, onSecondaryContainer = onSelect,
    tertiary = accent, onTertiary = onSelect,
    tertiaryContainer = select, onTertiaryContainer = onSelect,
    background = face, onBackground = text,
    surface = face, onSurface = text,
    surfaceVariant = face, onSurfaceVariant = subtext,
    surfaceDim = face, surfaceBright = face,
    surfaceContainerLowest = field, surfaceContainerLow = face, surfaceContainer = face,
    surfaceContainerHigh = face, surfaceContainerHighest = face,
    surfaceTint = Color.Transparent,
    outline = shadow, outlineVariant = shadow,
    inverseSurface = text, inverseOnSurface = face, inversePrimary = select,
)

/** The style's extra colors for [scheme] (already [styled]). */
internal fun styleTokens(theme: AppTheme, scheme: ColorScheme, dark: Boolean): StyleTokens {
    val seed = theme.seed ?: DefaultSeed
    return when (theme.style) {
        ThemeStyle.Material -> StyleTokens(ThemeStyle.Material, dark)
        ThemeStyle.Glass -> StyleTokens(
            ThemeStyle.Glass, dark,
            edge = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.7f),
            wallpaper = glassWallpaper(seed, dark),
        )
        ThemeStyle.Soft -> {
            val (h, c) = seed
            StyleTokens(
                ThemeStyle.Soft, dark,
                softLight = if (dark) oklch(0.35f, c * 0.12f, h) else Color.White,
                softDark = if (dark) oklch(0.20f, c * 0.1f, h) else oklch(0.78f, c * 0.16f, h),
            )
        }
        ThemeStyle.Retro -> {
            val face = scheme.surface
            val bevel = when {
                !theme.retroPalette && dark -> Bevel(Color.White, Color(0xFFC0C0C0), Color(0xFF808080), Color.White)
                !theme.retroPalette -> Bevel(Color.White, Color(0xFFDFDFDF), Color(0xFF808080), Color(0xFF0A0A0A))
                dark -> Bevel(
                    oklch(0.55f, seed.chroma * 0.2f, seed.hue), oklch(0.42f, seed.chroma * 0.2f, seed.hue),
                    scheme.outline, Color.Black,
                )
                else -> Bevel(Color.White, face.lighten(0.5f), scheme.outline, Color(0xFF0A0A0A))
            }
            val classicTitle = !theme.retroPalette
            StyleTokens(
                ThemeStyle.Retro, dark,
                bevel = bevel,
                titleStart = when {
                    classicTitle && dark -> Plum
                    classicTitle -> Navy
                    else -> scheme.primaryContainer
                },
                titleEnd = when {
                    classicTitle && dark -> Plum
                    classicTitle -> Color(0xFF1084D0)
                    else -> oklch(0.62f, seed.chroma, seed.hue)
                },
            )
        }
    }
}

private fun Color.lighten(amount: Float): Color = Color.White.copy(alpha = amount).compositeOver(this)

/** Glass wallpaper: a base tone and three soft blobs from the palette's hues. */
private fun glassWallpaper(seed: PaletteSeed, dark: Boolean): List<Color> {
    val c = seed.chroma.coerceAtLeast(0.08f)
    return if (dark) listOf(
        oklch(0.16f, c * 0.3f, seed.hue),
        oklch(0.42f, c * 1.2f, seed.hue),
        oklch(0.40f, c * 1.2f, seed.tertiaryHue),
        oklch(0.32f, c * 1.1f, (seed.hue + 40f) % 360f),
    ) else listOf(
        oklch(0.94f, c * 0.2f, seed.hue),
        oklch(0.82f, c * 0.9f, seed.hue),
        oklch(0.84f, c * 0.9f, seed.tertiaryHue),
        oklch(0.80f, c * 0.8f, (seed.hue + 40f) % 360f),
    )
}

/** Corner sizes per style. Retro is square everywhere. */
internal fun shapesFor(style: ThemeStyle): Shapes = when (style) {
    ThemeStyle.Material -> Shapes()
    ThemeStyle.Glass -> Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
    )
    ThemeStyle.Soft -> Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )
    ThemeStyle.Retro -> RoundedCornerShape(0.dp).let { square ->
        Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)
    }
}

/** Tahoma where it exists (macOS and Windows), the default sans elsewhere. */
@OptIn(ExperimentalTextApi::class)
private val RetroFont: FontFamily by lazy {
    val installed = runCatching {
        "Tahoma" in java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
    }.getOrDefault(false)
    if (installed) FontFamily(SystemFont("Tahoma")) else FontFamily.SansSerif
}

/** Retro sets every text style in [RetroFont]; the other styles keep Material's type. */
internal fun typographyFor(style: ThemeStyle): Typography {
    val base = Typography()
    if (style != ThemeStyle.Retro) return base
    fun TextStyle.f() = copy(fontFamily = RetroFont, letterSpacing = TextStyle.Default.letterSpacing)
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}

/**
 * A rounded corner of [size] in every style but Retro, where it is square. For shapes written inline at call sites
 * instead of taken from [androidx.compose.material3.MaterialTheme.shapes].
 */
@Composable
fun styledCorner(size: Dp): Shape = if (currentStyle == ThemeStyle.Retro) RectangleShape else RoundedCornerShape(size)

/** Like [styledCorner], with a percentage corner (50 = pill). */
@Composable
fun styledCorner(percent: Int): Shape = if (currentStyle == ThemeStyle.Retro) RectangleShape else RoundedCornerShape(percent)

/** A segmented button's shape: Material's pill ends, square in Retro (the pill comes from a token, not the theme). */
@Composable
fun styledSegmentShape(index: Int, count: Int): Shape =
    if (currentStyle == ThemeStyle.Retro) RectangleShape
    else androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, count)
