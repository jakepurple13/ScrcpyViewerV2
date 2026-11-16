package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.SpanKind

/**
 * Logcat's fixed colors: level badges and message text, the 8 tag hues and the query syntax colors. Each has a light
 * and a dark set (the approved mockup's `--lv-*`, `--m-*`, `--t0..7` and `--q-*` tokens), picked by the scheme's
 * surface luminance so every palette and style reads the same.
 */
object LogcatColors {
    // Mockup `--q-op` and `--q-str`; the field test checks them.
    val QueryOperatorLight = Color(0xFF8A4B08)
    val QueryOperatorDark = Color(0xFFF0A65A)
    val QueryStringLight = Color(0xFF4A55A2)
    val QueryStringDark = Color(0xFFA6B0FF)

    private val levelLight = mapOf(
        LogLevel.Verbose to (Color(0xFFE3E9E8) to Color(0xFF3F4948)),
        LogLevel.Debug to (Color(0xFF2F6F9F) to Color.White),
        LogLevel.Info to (Color(0xFF3F7F3A) to Color.White),
        LogLevel.Warn to (Color(0xFFE8C33A) to Color(0xFF2B2100)),
        LogLevel.Error to (Color(0xFFC4352B) to Color.White),
        LogLevel.Assert to (Color(0xFF7B3FA3) to Color.White),
    )
    private val levelDark = mapOf(
        LogLevel.Verbose to (Color(0xFF3F4948) to Color(0xFFDDE4E3)),
        LogLevel.Debug to (Color(0xFF2C5D80) to Color(0xFFE6F2FF)),
        LogLevel.Info to (Color(0xFF3D6B37) to Color(0xFFEAFFE4)),
        LogLevel.Warn to (Color(0xFFC9A227) to Color(0xFF1D1600)),
        LogLevel.Error to (Color(0xFFB3261E) to Color.White),
        LogLevel.Assert to (Color(0xFF6E3B93) to Color.White),
    )
    private val messageLight = mapOf(
        LogLevel.Verbose to Color(0xFF5D6767),
        LogLevel.Debug to Color(0xFF1F5F8F),
        LogLevel.Info to Color(0xFF2F6B2B),
        LogLevel.Warn to Color(0xFF7D5F00),
        LogLevel.Error to Color(0xFFB3261E),
        LogLevel.Assert to Color(0xFF7B3FA3),
    )
    private val messageDark = mapOf(
        LogLevel.Verbose to Color(0xFFA9B4B3),
        LogLevel.Debug to Color(0xFF8CC2EC),
        LogLevel.Info to Color(0xFFA5D394),
        LogLevel.Warn to Color(0xFFE8C768),
        LogLevel.Error to Color(0xFFFF8A80),
        LogLevel.Assert to Color(0xFFD6A6FF),
    )
    private val tagsLight = listOf(
        0xFF0B6E6E, 0xFF8A4B08, 0xFF4A55A2, 0xFF9A3A6B, 0xFF2F7A3A, 0xFF6B5A00, 0xFFA03E2A, 0xFF3C6E8F,
    ).map { Color(it) }
    private val tagsDark = listOf(
        0xFF4CDADA, 0xFFF0A65A, 0xFFA6B0FF, 0xFFF08CC0, 0xFF8FD694, 0xFFE3D15C, 0xFFFF9C85, 0xFF8CC8F0,
    ).map { Color(it) }

    /** True when the scheme's surface is dark. */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** The level badge's background and letter colors. */
    @Composable
    @ReadOnlyComposable
    fun level(l: LogLevel): Pair<Color, Color> = (if (isDark()) levelDark else levelLight).getValue(l)

    /** The message text color for a line of level [l]. */
    @Composable
    @ReadOnlyComposable
    fun message(l: LogLevel): Color = (if (isDark()) messageDark else messageLight).getValue(l)

    /** One of 8 fixed hues for [tag], the same for a tag in every window and run. */
    @Composable
    @ReadOnlyComposable
    fun tag(tag: String): Color = (if (isDark()) tagsDark else tagsLight)[tagHue(tag)]

    /** The filter field's color for [kind]: keys primary, `-` error, operators orange, quoted values blue. */
    @Composable
    @ReadOnlyComposable
    fun query(kind: SpanKind): Color {
        val scheme = MaterialTheme.colorScheme
        val dark = isDark()
        return when (kind) {
            SpanKind.Key -> scheme.primary
            SpanKind.Negation, SpanKind.Error -> scheme.error
            SpanKind.Operator -> if (dark) QueryOperatorDark else QueryOperatorLight
            SpanKind.Quoted -> if (dark) QueryStringDark else QueryStringLight
        }
    }
}

/** The mockup's tag hash: `a = a * 31 + char` from 7, unsigned 32 bit, mod 8. */
internal fun tagHue(tag: String): Int {
    var a = 7u
    for (c in tag) a = a * 31u + c.code.toUInt()
    return (a % 8u).toInt()
}
