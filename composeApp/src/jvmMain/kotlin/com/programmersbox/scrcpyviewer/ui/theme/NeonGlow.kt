package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** The neon glow: [color] at full [strength] (0..1). Provided by [ScrcpyViewerTheme] only for neon themes. */
data class NeonGlow(val color: Color, val strength: Float)

val LocalNeonGlow = staticCompositionLocalOf<NeonGlow?> { null }

/** A soft light around this element's [shape] in a neon theme; nothing otherwise. [color] defaults to the glow's. */
@Composable
fun Modifier.neonGlow(shape: Shape, enabled: Boolean = true, color: Color? = null, size: Float = 1f): Modifier {
    val glow = LocalNeonGlow.current ?: return this
    if (!enabled || glow.strength <= 0f) return this
    val c = (color ?: glow.color).copy(alpha = 0.6f * glow.strength.coerceAtLeast(0.35f))
    return dropShadow(shape) {
        radius = 16.dp.toPx() * glow.strength * size
        this.color = c
    }
}

/**
 * A glowing outline around the mirrored screen at [rect]: widening translucent halos, then a thin line. Drawn
 * before the frame, so the frame covers the inner halos. Halos outside the stage are clipped by it.
 */
fun DrawScope.drawNeonFrame(rect: Rect, glow: NeonGlow) {
    val radius = 6.dp.toPx()
    for (i in 4 downTo 1) {
        val g = i * 4.dp.toPx() * glow.strength
        drawRoundRect(
            glow.color.copy(alpha = 0.16f * glow.strength),
            topLeft = Offset(rect.left - g, rect.top - g),
            size = Size(rect.width + g * 2, rect.height + g * 2),
            cornerRadius = CornerRadius(radius + g),
        )
    }
    drawRoundRect(
        glow.color.copy(alpha = 0.9f), topLeft = rect.topLeft, size = rect.size,
        cornerRadius = CornerRadius(radius), style = Stroke(1.5.dp.toPx()),
    )
}

/** [base] with a glowing text shadow in a neon theme, unchanged otherwise. */
@Composable
fun neonText(base: TextStyle, color: Color): TextStyle {
    val glow = LocalNeonGlow.current ?: return base
    if (glow.strength <= 0f) return base
    return base.copy(color = color, shadow = Shadow(color.copy(alpha = 0.8f), blurRadius = 24f * glow.strength))
}

/** Material's filled button, glowing in a neon theme. Same parameters as [androidx.compose.material3.Button]. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier.neonGlow(shape, enabled),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

/** Material's switch; when on, it glows in a neon theme. Same parameters as [androidx.compose.material3.Switch]. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) = androidx.compose.material3.Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier.neonGlow(CircleShape, enabled && checked, size = 0.7f),
    thumbContent = thumbContent,
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
)
