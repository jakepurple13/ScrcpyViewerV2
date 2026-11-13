package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * Lifts this element off the surface in the current style: a light edge and soft shadow (Glass), paired light and
 * dark shadows (Soft), or a raised bevel (Retro). [pressed] shows the pushed-in state. Nothing in Material.
 * Apply it outside any clip, since the Soft shadows fall outside the element.
 */
@Composable
fun Modifier.styleRaised(shape: Shape, pressed: Boolean = false): Modifier {
    val t = LocalStyleTokens.current
    return when (t.style) {
        ThemeStyle.Material -> this
        ThemeStyle.Glass -> this
            .dropShadow(shape) {
                radius = 18.dp.toPx(); offset = Offset(0f, 6.dp.toPx())
                color = Color.Black.copy(alpha = if (t.dark) 0.35f else 0.12f)
            }
            .border(1.dp, t.edge, shape)
        ThemeStyle.Soft -> if (pressed) softInset(shape, t) else softLift(shape, t, 5f)
        ThemeStyle.Retro -> drawWithContent {
            drawContent()
            drawBevel(t.bevel, raised = !pressed)
        }
    }
}

/**
 * Sinks this element into the surface: fields, switch tracks and selected rows. A light edge in Glass, an inner
 * shadow in Soft, a sunken bevel in Retro. Nothing in Material.
 */
@Composable
fun Modifier.styleSunken(shape: Shape): Modifier {
    val t = LocalStyleTokens.current
    return when (t.style) {
        ThemeStyle.Material -> this
        ThemeStyle.Glass -> border(1.dp, t.edge, shape)
        ThemeStyle.Soft -> softInset(shape, t)
        ThemeStyle.Retro -> drawWithContent {
            drawContent()
            drawBevel(t.bevel, raised = false)
        }
    }
}

/**
 * Marks a card or grouped section: a light edge (Glass), a broad raised shadow (Soft), or an etched group-box line
 * (Retro). Nothing in Material.
 */
@Composable
fun Modifier.stylePane(shape: Shape): Modifier {
    val t = LocalStyleTokens.current
    return when (t.style) {
        ThemeStyle.Material -> this
        ThemeStyle.Glass -> border(1.dp, t.edge, shape)
        ThemeStyle.Soft -> softLift(shape, t, 7f)
        ThemeStyle.Retro -> drawBehind { drawEtch(t.bevel) }
    }
}

/**
 * Marks the selected row of a list: a primary outline (Material), a brighter frosted fill (Glass), pressed in
 * (Soft), or a sunken white field (Retro). Apply it before any clip, so Soft's shadows aren't cut off.
 */
@Composable
fun Modifier.selectedRow(shape: Shape): Modifier {
    val t = LocalStyleTokens.current
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    return when (t.style) {
        ThemeStyle.Material -> border(2.dp, scheme.primary, shape)
        ThemeStyle.Glass -> styleRaised(shape).background(scheme.surfaceContainerHighest, shape)
        ThemeStyle.Soft -> styleSunken(shape)
        ThemeStyle.Retro -> background(scheme.surfaceContainerLowest).styleSunken(shape)
    }
}

/**
 * A list row's fill, clipped to [shape]: Material's secondary-container highlight when [selected], or the style's
 * [selectedRow] look in the other styles.
 */
@Composable
fun Modifier.listRow(selected: Boolean, shape: Shape): Modifier {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    if (currentStyle == ThemeStyle.Material) {
        return clip(shape).background(if (selected) scheme.secondaryContainer else scheme.surface)
    }
    return (if (selected) selectedRow(shape) else this).clip(shape)
}

/**
 * The window's backdrop: Glass paints its wallpaper here unless [transparent] (the OS blurs the desktop behind
 * the window instead). Other styles paint nothing; their surfaces are opaque.
 */
@Composable
fun Modifier.styleBackdrop(transparent: Boolean = false): Modifier {
    val t = LocalStyleTokens.current
    if (t.style != ThemeStyle.Glass || transparent || t.wallpaper.size < 4) return this
    val (base, a, b, c) = t.wallpaper
    return background(base).drawBehind {
        fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawRect(
            Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset(size.width * cx, size.height * cy), r),
        )
        val r = maxOf(size.width, size.height)
        blob(a, 0.12f, 0.2f, r * 0.55f)
        blob(b, 0.9f, 0.15f, r * 0.5f)
        blob(c, 0.6f, 1.0f, r * 0.6f)
    }
}

private fun Modifier.softLift(shape: Shape, t: StyleTokens, distance: Float): Modifier = this
    .dropShadow(shape) {
        radius = (distance * 2).dp.toPx(); offset = Offset(distance.dp.toPx(), distance.dp.toPx()); color = t.softDark
    }
    .dropShadow(shape) {
        radius = (distance * 2).dp.toPx(); offset = Offset(-distance.dp.toPx(), -distance.dp.toPx()); color = t.softLight
    }

private fun Modifier.softInset(shape: Shape, t: StyleTokens): Modifier = this
    .innerShadow(shape) { radius = 6.dp.toPx(); offset = Offset(3.dp.toPx(), 3.dp.toPx()); color = t.softDark }
    .innerShadow(shape) { radius = 6.dp.toPx(); offset = Offset(-3.dp.toPx(), -3.dp.toPx()); color = t.softLight }

/** Two 1px rings like Windows 95: raised is light top-left and dark bottom-right; sunken is the reverse. */
fun DrawScope.drawBevel(bevel: Bevel, raised: Boolean, topLeft: Offset = Offset.Zero, area: Size = size) {
    val px = 1.dp.toPx().coerceAtLeast(1f)
    val (outerTl, outerBr, innerTl, innerBr) = if (raised) {
        listOf(bevel.light, bevel.dark, bevel.highlight, bevel.shadow)
    } else {
        listOf(bevel.shadow, bevel.highlight, bevel.dark, bevel.light)
    }
    ring(topLeft, area, 0f, px, outerTl, outerBr)
    ring(topLeft, area, px, px, innerTl, innerBr)
}

/** A group box's etched line: a shadow ring with a highlight ring just inside-below it. */
internal fun DrawScope.drawEtch(bevel: Bevel) {
    val px = 1.dp.toPx().coerceAtLeast(1f)
    ring(Offset.Zero, size, 0f, px, bevel.shadow, bevel.highlight)
    ring(Offset.Zero, size, px, px, bevel.highlight, bevel.shadow)
}

/** A sunken field's thin edge, like a Windows 95 status-bar box: one ring, shadow top-left and highlight bottom-right. */
internal fun DrawScope.drawFieldEdge(bevel: Bevel) {
    ring(Offset.Zero, size, 0f, 1.dp.toPx().coerceAtLeast(1f), bevel.shadow, bevel.highlight)
}

/**
 * Soft's raised and pressed looks blended, under a [fill] in [shape]: at [depth] 0 the element is lifted (4 dp paired
 * shadows), at 1 it is pressed in (inner shadows); between, the outer shadows fade and close in as the inner ones grow,
 * so an animated [depth] shows the element sinking into the surface. Apply it outside any clip.
 */
@Composable
internal fun Modifier.softPress(shape: Shape, fill: Color, depth: () -> Float): Modifier {
    val t = LocalStyleTokens.current
    return this
        .dropShadow(shape) {
            val k = 1f - depth()
            radius = 9.dp.toPx(); offset = Offset(4.dp.toPx() * k, 4.dp.toPx() * k); color = t.softDark; alpha = k
        }
        .dropShadow(shape) {
            val k = 1f - depth()
            radius = 9.dp.toPx(); offset = Offset(-4.dp.toPx() * k, -4.dp.toPx() * k); color = t.softLight; alpha = k
        }
        .background(fill, shape)
        .innerShadow(shape) {
            val k = depth()
            radius = 6.dp.toPx(); offset = Offset(3.dp.toPx() * k, 3.dp.toPx() * k); color = t.softDark; alpha = k
        }
        .innerShadow(shape) {
            val k = depth()
            radius = 6.dp.toPx(); offset = Offset(-3.dp.toPx() * k, -3.dp.toPx() * k); color = t.softLight; alpha = k
        }
}

private fun DrawScope.ring(origin: Offset, area: Size, inset: Float, px: Float, topLeft: Color, bottomRight: Color) {
    val w = area.width - inset * 2
    val h = area.height - inset * 2
    if (w <= 0f || h <= 0f) return
    val x = origin.x + inset
    val y = origin.y + inset
    drawRect(bottomRight, Offset(x, y + h - px), Size(w, px))
    drawRect(bottomRight, Offset(x + w - px, y), Size(px, h))
    drawRect(topLeft, Offset(x, y), Size(w - px, px))
    drawRect(topLeft, Offset(x, y), Size(px, h - px))
}
