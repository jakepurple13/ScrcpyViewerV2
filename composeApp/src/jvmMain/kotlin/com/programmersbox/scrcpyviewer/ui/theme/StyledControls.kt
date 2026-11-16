package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ripple
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.systemReducesMotion
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/*
 * Drop-in replacements for Material 3 controls that follow the current [ThemeStyle] (and glow in neon themes).
 * Each keeps the Material parameters the app uses, so swapping the import is the only change at a call site. In
 * Material style each one is exactly the Material control (plus the neon glow on Button and Switch).
 *
 * Outside Material, the 48dp minimum touch target is turned off: it pads controls with invisible space, and the
 * style's shadows and bevels would be drawn around that space instead of the visible control.
 */

@Composable
private fun Styled(content: @Composable () -> Unit) {
    if (currentStyle == ThemeStyle.Material) content()
    else CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified, content = content)
}

/** Retro buttons are short like Windows 95's; Material's 40dp minimum is capped. */
private val RetroButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
private val RetroButtonHeight = 30.dp

/** Material's filled button. Same parameters as [androidx.compose.material3.Button]. */
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
) {
    val style = currentStyle
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    // A caller's own colors (e.g. a destructive red button) keep their meaning as the text color.
    val custom = colors != ButtonDefaults.buttonColors()
    Styled {
        when (style) {
            ThemeStyle.Material, ThemeStyle.Glass -> androidx.compose.material3.Button(
                onClick = onClick,
                modifier = modifier
                    .then(if (style == ThemeStyle.Glass && enabled) Modifier.glassGlow(shape, colors.containerColor) else Modifier)
                    .neonGlow(shape, enabled),
                enabled = enabled, shape = shape, colors = colors,
                elevation = if (style == ThemeStyle.Glass) null else elevation,
                border = border ?: if (style == ThemeStyle.Glass) BorderStroke(1.dp, LocalStyleTokens.current.edge) else null,
                contentPadding = contentPadding, interactionSource = source, content = content,
            )
            ThemeStyle.Soft -> androidx.compose.material3.Button(
                onClick = onClick,
                modifier = modifier.styleRaised(shape, pressed && enabled).neonGlow(shape, enabled),
                enabled = enabled, shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.surface,
                    contentColor = if (custom) colors.containerColor else scheme.primary,
                    disabledContainerColor = scheme.surface,
                    disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
                ),
                elevation = null, border = null, contentPadding = contentPadding, interactionSource = source,
                content = content,
            )
            ThemeStyle.Retro -> RetroButton(onClick, modifier, enabled, isDefault = true, source, pressed, content)
        }
    }
}

/** Material's outlined button. Same parameters as [androidx.compose.material3.OutlinedButton]. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val style = currentStyle
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    Styled {
        when (style) {
            ThemeStyle.Material -> androidx.compose.material3.OutlinedButton(
                onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content,
            )
            ThemeStyle.Glass -> androidx.compose.material3.OutlinedButton(
                onClick, modifier, enabled, shape,
                colors.copy(containerColor = scheme.surfaceContainerHighest),
                elevation, BorderStroke(1.dp, LocalStyleTokens.current.edge), contentPadding, source, content,
            )
            ThemeStyle.Soft -> androidx.compose.material3.OutlinedButton(
                onClick, modifier.styleRaised(shape, pressed && enabled), enabled, shape,
                colors.copy(containerColor = scheme.surface), elevation, null, contentPadding, source, content,
            )
            ThemeStyle.Retro -> RetroButton(onClick, modifier, enabled, isDefault = false, source, pressed, content)
        }
    }
}

/** Material's tonal button. Same parameters as [androidx.compose.material3.FilledTonalButton]. */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val style = currentStyle
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    Styled {
        when (style) {
            ThemeStyle.Material -> androidx.compose.material3.FilledTonalButton(
                onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content,
            )
            ThemeStyle.Glass -> androidx.compose.material3.FilledTonalButton(
                onClick, modifier, enabled, shape, colors, null, BorderStroke(1.dp, LocalStyleTokens.current.edge),
                contentPadding, source, content,
            )
            ThemeStyle.Soft -> androidx.compose.material3.FilledTonalButton(
                onClick, modifier.styleRaised(shape, pressed && enabled), enabled, shape,
                colors.copy(containerColor = scheme.surface, contentColor = scheme.primary), null, null,
                contentPadding, source, content,
            )
            ThemeStyle.Retro -> RetroButton(onClick, modifier, enabled, isDefault = false, source, pressed, content)
        }
    }
}

/**
 * Material's text button. Same parameters as [androidx.compose.material3.TextButton]. Retro draws it as a beveled
 * button, since Windows 95 had no flat buttons; the other styles keep it flat.
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (currentStyle == ThemeStyle.Retro) {
        val pressed by source.collectIsPressedAsState()
        Styled { RetroButton(onClick, modifier, enabled, isDefault = false, source, pressed, content) }
    } else {
        androidx.compose.material3.TextButton(
            onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content,
        )
    }
}

/**
 * A Windows 95 push button: face color, raised bevel that sinks while pressed; [isDefault] adds the dark frame.
 * [contentColor], when specified, replaces the usual ink while enabled (Logcat's Play shows the accent while stopped).
 */
@Composable
internal fun RetroButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    isDefault: Boolean,
    source: MutableInteractionSource,
    pressed: Boolean,
    content: @Composable RowScope.() -> Unit,
    contentColor: Color = Color.Unspecified,
) {
    val scheme = MaterialTheme.colorScheme
    val frame = if (isDefault && enabled) {
        Modifier.border(1.dp, LocalStyleTokens.current.bevel.dark, RectangleShape).padding(1.dp)
    } else {
        Modifier
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(max = RetroButtonHeight).then(frame).styleRaised(RectangleShape, pressed && enabled),
        enabled = enabled,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.surface, contentColor = contentColor.takeOrElse { scheme.onSurface },
            disabledContainerColor = scheme.surface, disabledContentColor = scheme.outline,
        ),
        elevation = null,
        contentPadding = RetroButtonPadding,
        interactionSource = source,
        content = content,
    )
}

/** A soft colored glow under a Glass filled button. */
@Composable
private fun Modifier.glassGlow(shape: Shape, color: Color): Modifier {
    val glow = color.copy(alpha = if (LocalStyleTokens.current.dark) 0.55f else 0.4f)
    return dropShadow(shape) { radius = 14.dp.toPx(); offset = Offset(0f, 5.dp.toPx()); this.color = glow }
}

/**
 * Material's switch. Same parameters as [androidx.compose.material3.Switch]. Soft sinks the track; Retro shows a
 * checkbox, since Windows 95 had no switches. When on, it glows in a neon theme.
 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Styled {
        when (currentStyle) {
            ThemeStyle.Retro -> RetroCheck(checked, onCheckedChange, enabled, modifier)
            ThemeStyle.Soft -> androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = modifier.styleSunken(CircleShape).neonGlow(CircleShape, enabled && checked, size = 0.7f),
                thumbContent = thumbContent,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.primary, checkedTrackColor = scheme.surface,
                    checkedBorderColor = Color.Transparent, checkedIconColor = scheme.onPrimary,
                    uncheckedThumbColor = scheme.onSurfaceVariant.copy(alpha = 0.6f), uncheckedTrackColor = scheme.surface,
                    uncheckedBorderColor = Color.Transparent,
                    disabledCheckedTrackColor = scheme.surface, disabledUncheckedTrackColor = scheme.surface,
                    disabledCheckedBorderColor = Color.Transparent, disabledUncheckedBorderColor = Color.Transparent,
                ),
                interactionSource = interactionSource,
            )
            else -> androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = modifier.neonGlow(CircleShape, enabled && checked, size = 0.7f),
                thumbContent = thumbContent,
                enabled = enabled,
                colors = colors,
                interactionSource = interactionSource,
            )
        }
    }
}

/** Material's checkbox. Same parameters as [androidx.compose.material3.Checkbox]; Soft sinks the box. */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Styled {
        when (currentStyle) {
            ThemeStyle.Retro -> RetroCheck(checked, onCheckedChange, enabled, modifier)
            ThemeStyle.Soft -> androidx.compose.material3.Checkbox(
                checked, onCheckedChange, modifier.styleSunken(RoundedCornerShape(5.dp)), enabled,
                CheckboxDefaults.colors(
                    checkedColor = scheme.surface, uncheckedColor = Color.Transparent, checkmarkColor = scheme.primary,
                ),
                interactionSource,
            )
            else -> androidx.compose.material3.Checkbox(
                checked, onCheckedChange, modifier, enabled, interactionSource = interactionSource,
            )
        }
    }
}

/** A Windows 95 check box: a sunken white square with a black tick. */
@Composable
private fun RetroCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tick = if (enabled) scheme.onSurface else scheme.outline
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(
        modifier.then(toggle).padding(2.dp).size(16.dp)
            .background(if (enabled) scheme.surfaceContainerLowest else scheme.surface)
            .styleSunken(RectangleShape),
    ) {
        if (checked) {
            Canvas(Modifier.size(16.dp)) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w * 0.25f, w * 0.5f); lineTo(w * 0.43f, w * 0.68f); lineTo(w * 0.76f, w * 0.3f)
                }
                drawPath(path, tick, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/**
 * Material's outlined text field. The parameters the app uses from [androidx.compose.material3.OutlinedTextField];
 * [colors] null means the style's own field colors.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors? = null,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = if (currentStyle == ThemeStyle.Retro) RectangleShape else shape,
        colors = colors ?: styledFieldColors(),
    )
}

/** Field colors per style: frosted (Glass), pressed-in (Soft), white with a gray frame (Retro). */
@Composable
fun styledFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val t = LocalStyleTokens.current
    return when (t.style) {
        ThemeStyle.Material -> OutlinedTextFieldDefaults.colors()
        ThemeStyle.Glass -> OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerLow,
            unfocusedBorderColor = t.edge,
        )
        ThemeStyle.Soft -> {
            val well = t.softDark.copy(alpha = 0.35f).compositeOver(scheme.surface)
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = well, unfocusedContainerColor = well, disabledContainerColor = well,
                unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
            )
        }
        ThemeStyle.Retro -> OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerLowest, unfocusedContainerColor = scheme.surfaceContainerLowest,
            disabledContainerColor = scheme.surface,
            focusedBorderColor = scheme.onSurface, unfocusedBorderColor = scheme.outline,
            focusedLabelColor = scheme.onSurface, cursorColor = scheme.onSurface,
        )
    }
}

/**
 * Material's alert dialog with the parameters the app uses from [androidx.compose.material3.AlertDialog]. Glass and
 * Soft lift it with the style's edge or shadows; Retro gives it a square, beveled frame.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val style = currentStyle
    val shape = if (style == ThemeStyle.Retro) RectangleShape else MaterialTheme.shapes.extraLarge
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.then(if (style == ThemeStyle.Material) Modifier else Modifier.styleRaised(shape)),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (style == ThemeStyle.Material) androidx.compose.material3.AlertDialogDefaults.TonalElevation else 0.dp,
        properties = properties,
    )
}

/** Test tags: a [FilterChip]'s growing check and its trailing count badge. */
const val FilterChipCheckTag = "FilterChipCheck"
const val FilterChipBadgeTag = "FilterChipBadge"

/** How long a chip's colors, fill and check take to change. */
private const val ChipMillis = 250
private val ChipEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A filter chip in the current style. A check grows in before the label while [selected] (0 to 16 dp, 250 ms), and
 * [trailing] (a count) sits in a small badge after it.
 *
 * - Material: Material's outlined chip, filled with the secondary container when on; colors fade 250 ms.
 * - Glass: a frosted fill and light edge; on, the fill and edge tint toward the accent and a soft glow fades up.
 * - Soft: raised from the surface; on, it sinks in (320 ms) with the label in the accent.
 * - Retro 95: a toolbar toggle button; on, it stays pushed in (sunken bevel, dithered face, label 1 px down-right).
 *
 * Without [motion] every change snaps.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    motion: Boolean = remember { !systemReducesMotion() },
) {
    val badge: @Composable () -> Unit = { ChipTrailingBadge(trailing, motion) }
    if (currentStyle == ThemeStyle.Material) {
        val scheme = MaterialTheme.colorScheme
        val spec = chipColorSpec(motion)
        val container by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.secondaryContainer.copy(alpha = 0f), spec)
        val content by animateColorAsState(if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant, spec)
        val outline by animateColorAsState(if (selected) scheme.outlineVariant.copy(alpha = 0f) else scheme.outlineVariant, spec)
        androidx.compose.material3.FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label()
                    badge()
                }
            },
            modifier = modifier,
            enabled = enabled,
            leadingIcon = { ChipCheck(selected, motion, gap = 0.dp) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = container, selectedContainerColor = container,
                labelColor = content, selectedLabelColor = content,
                iconColor = content, selectedLeadingIconColor = content,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = enabled, selected = selected, borderColor = outline, selectedBorderColor = outline,
            ),
        )
    } else {
        Styled {
            StyledChip(
                selected = selected, onClick = onClick, modifier = modifier, enabled = enabled, motion = motion,
                pressDepth = 0.5f, leading = { ChipCheck(selected, motion, gap = 6.dp) }, label = label, trailing = badge,
            )
        }
    }
}

/**
 * An action chip in the current style: the look of an unselected [FilterChip] with press feedback. Material's assist
 * chip; Glass brightens its frosted fill while pressed; Soft sinks while pressed; Retro's bevel sinks and the label
 * nudges 1 px. Same parameters the app uses from [androidx.compose.material3.AssistChip], plus [motion].
 */
@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    motion: Boolean = remember { !systemReducesMotion() },
) {
    if (currentStyle == ThemeStyle.Material) {
        androidx.compose.material3.AssistChip(
            onClick, label, modifier, enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        )
    } else {
        Styled {
            StyledChip(
                selected = null, onClick = onClick, modifier = modifier, enabled = enabled, motion = motion,
                pressDepth = 1f,
                leading = leadingIcon?.let { icon -> { icon(); Spacer(Modifier.width(6.dp)) } },
                label = label,
                trailing = trailingIcon?.let { icon -> { Spacer(Modifier.width(6.dp)); icon() } },
            )
        }
    }
}

private fun chipColorSpec(motion: Boolean): AnimationSpec<Color> = if (motion) tween(ChipMillis) else snap()

/**
 * The Glass, Soft and Retro chip. [selected] null is an action chip (a button, never on). [pressDepth] is how far Soft
 * sinks while pressed (0..1). [leading] and [label] nudge with Retro's press; [trailing] follows, bringing its own gap.
 */
@Composable
private fun StyledChip(
    selected: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    motion: Boolean,
    pressDepth: Float,
    leading: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val t = LocalStyleTokens.current
    val scheme = MaterialTheme.colorScheme
    val style = t.style
    val on = selected == true
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val down = enabled && pressed
    val retro = style == ThemeStyle.Retro
    val shape = when (style) {
        ThemeStyle.Glass -> RoundedCornerShape(10.dp)
        ThemeStyle.Soft -> RoundedCornerShape(12.dp)
        else -> RectangleShape
    }
    val colorSpec = chipColorSpec(motion)
    // Retro's bevel flips at once, so its label nudge and dither follow quickly, like the real thing.
    val retroSpec: AnimationSpec<Float> = if (motion) tween(70, easing = LinearEasing) else snap()
    val content by animateColorAsState(
        when {
            !enabled -> if (retro) scheme.outline else scheme.onSurface.copy(alpha = 0.38f)
            retro -> scheme.onSurface
            on && style == ThemeStyle.Glass -> scheme.onPrimaryContainer
            on -> scheme.primary
            else -> scheme.onSurfaceVariant
        },
        colorSpec,
    )
    val decoration: Modifier = when (style) {
        ThemeStyle.Glass -> {
            val fill by animateColorAsState(
                when {
                    on -> scheme.primary.copy(alpha = if (t.dark) 0.22f else 0.26f)
                    down -> Color.White.copy(alpha = if (t.dark) 0.14f else 0.45f)
                    else -> Color.White.copy(alpha = if (t.dark) 0.07f else 0.28f)
                },
                // A press brightens quickly, so even a short click shows it.
                if (motion && down) tween(110, easing = ChipEase) else colorSpec,
            )
            val edge by animateColorAsState(if (on) scheme.primary.copy(alpha = if (t.dark) 0.6f else 0.55f) else t.edge, colorSpec)
            val glow by animateFloatAsState(if (on && enabled) 1f else 0f, if (motion) tween(300, easing = ChipEase) else snap())
            val glowColor = scheme.primary.copy(alpha = 0.35f)
            Modifier
                .dropShadow(shape) { radius = 14.dp.toPx(); color = glowColor; alpha = glow }
                .background(fill, shape)
                // A 1 dp light line along the top edge while on, fading with the glow.
                .innerShadow(shape) { radius = 0f; offset = Offset(0f, 1.dp.toPx()); color = Color.White.copy(alpha = 0.35f); alpha = glow }
                .border(1.dp, edge, shape)
        }
        ThemeStyle.Soft -> {
            val depth by animateFloatAsState(
                when {
                    on -> 1f
                    down -> pressDepth
                    else -> 0f
                },
                when {
                    !motion -> snap()
                    down -> tween(120, easing = ChipEase)
                    else -> tween(320, easing = ChipEase)
                },
            )
            Modifier.softPress(shape, scheme.surface) { depth }
        }
        else -> {
            val sunken = on || down
            val dither by animateFloatAsState(if (on) 1f else 0f, retroSpec)
            val ink = scheme.onSurface
            // The dither is the highlight tone, unless that is the ink itself (High Contrast's white on black would
            // swallow the label): then the shadow tone.
            val dots = if (abs(t.bevel.highlight.luminance() - ink.luminance()) < 0.3f) t.bevel.shadow else t.bevel.highlight
            val brush = rememberDither(dots)
            Modifier.background(scheme.surface).drawWithContent {
                if (dither > 0f) drawRect(brush, alpha = dither)
                drawContent()
                drawBevel(t.bevel, raised = !sunken)
                if (focused) {
                    val inset = 3.dp.toPx()
                    drawRect(
                        ink, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 1.dp.toPx()))),
                    )
                }
            }
        }
    }
    val nudge by animateFloatAsState(if (retro && (on || down)) 1f else 0f, retroSpec)
    val indication = if (retro) null else ripple()
    val click = if (selected != null) {
        Modifier.selectable(selected, source, indication, enabled, Role.Checkbox, onClick)
    } else {
        Modifier.clickable(source, indication, enabled, role = Role.Button, onClick = onClick)
    }
    val type = if (retro) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    val soft = style == ThemeStyle.Soft && selected != null
    val weight = if (soft && on) FontWeight.SemiBold else type.fontWeight
    Row(
        modifier.then(decoration).clip(shape).then(click)
            .defaultMinSize(minHeight = if (retro) 26.dp else 32.dp)
            .padding(start = if (retro) 8.dp else 10.dp, end = if (retro) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides content, LocalTextStyle provides type.copy(fontWeight = weight)) {
            Row(
                Modifier.offset {
                    val px = (nudge * 1.dp.toPx()).roundToInt()
                    IntOffset(px, px)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                if (soft) {
                    // Soft's label turns SemiBold when on. It is laid out at the SemiBold width either way (a
                    // hidden bold copy sets the size), so the weight change never moves the chips beside it.
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.alpha(0f).clearAndSetSemantics {}) {
                            CompositionLocalProvider(LocalTextStyle provides type.copy(fontWeight = FontWeight.SemiBold), content = label)
                        }
                        label()
                    }
                } else {
                    label()
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * A filter chip's count badge after a 6 dp gap. It grows in from the end (250 ms) when [trailing] arrives and shrinks
 * away when it goes (the last content is kept to draw the exit); it snaps without [motion].
 */
@Composable
private fun ChipTrailingBadge(trailing: (@Composable () -> Unit)?, motion: Boolean) {
    val last = remember { arrayOfNulls<@Composable () -> Unit>(1) }
    if (trailing != null) last[0] = trailing
    AnimatedVisibility(
        visible = trailing != null,
        enter = if (motion) {
            expandHorizontally(tween(ChipMillis, easing = EmphasizedDecelerate), expandFrom = Alignment.Start) +
                fadeIn(tween(200, delayMillis = 50))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            shrinkHorizontally(tween(200, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Start) + fadeOut(tween(120))
        } else {
            ExitTransition.None
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(6.dp))
            last[0]?.let { ChipBadge(it) }
        }
    }
}

/** The check that grows in before a selected chip's label (0 to 16 dp, then [gap]); snaps without [motion]. */
@Composable
private fun ChipCheck(selected: Boolean, motion: Boolean, gap: Dp) {
    AnimatedVisibility(
        visible = selected,
        modifier = Modifier.testTag(FilterChipCheckTag),
        enter = if (motion) {
            expandHorizontally(tween(ChipMillis, easing = EmphasizedDecelerate)) +
                scaleIn(tween(ChipMillis), initialScale = 0.4f) + fadeIn(tween(160))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            shrinkHorizontally(tween(200, easing = EmphasizedAccelerate)) +
                scaleOut(tween(200), targetScale = 0.4f) + fadeOut(tween(120))
        } else {
            ExitTransition.None
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
            if (gap > 0.dp) Spacer(Modifier.width(gap))
        }
    }
}

/**
 * A chip's count: a pill on the highest container (Material), a frosted pill with an edge (Glass), a small
 * pressed-in well (Soft), or a sunken white field (Retro).
 */
@Composable
private fun ChipBadge(content: @Composable () -> Unit) {
    val t = LocalStyleTokens.current
    val scheme = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(50)
    val inherited = LocalContentColor.current
    val (decoration, color) = when (t.style) {
        ThemeStyle.Material -> Modifier.background(scheme.surfaceContainerHighest, pill) to scheme.onSurfaceVariant
        ThemeStyle.Glass -> Modifier.background(Color.White.copy(alpha = if (t.dark) 0.1f else 0.22f), pill)
            .border(1.dp, t.edge, pill) to inherited
        ThemeStyle.Soft -> Modifier.background(scheme.surface, pill)
            .innerShadow(pill) { radius = 3.dp.toPx(); offset = Offset(1.dp.toPx(), 1.dp.toPx()); color = t.softDark }
            .innerShadow(pill) { radius = 3.dp.toPx(); offset = Offset(-1.dp.toPx(), -1.dp.toPx()); color = t.softLight } to inherited
        ThemeStyle.Retro -> Modifier.background(scheme.surfaceContainerLowest)
            .drawWithContent { drawContent(); drawFieldEdge(t.bevel) } to scheme.onSurface
    }
    Box(
        decoration.testTag(FilterChipBadgeTag).heightIn(min = 18.dp).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides color,
            LocalTextStyle provides LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
            content = content,
        )
    }
}

/** A 2-dp checkerboard of [color] with see-through gaps: Windows 95's dither on a pushed-in toggle button. */
@Composable
private fun rememberDither(color: Color): ShaderBrush {
    val cell = with(LocalDensity.current) { 1.dp.roundToPx().coerceAtLeast(1) }
    return remember(color, cell) {
        val bitmap = ImageBitmap(cell * 2, cell * 2)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val c = cell.toFloat()
        canvas.drawRect(0f, 0f, 2 * c, 2 * c, Paint().apply { this.color = Color.Transparent; blendMode = BlendMode.Src })
        val paint = Paint().apply { this.color = color }
        canvas.drawRect(0f, 0f, c, c, paint)
        canvas.drawRect(c, c, 2 * c, 2 * c, paint)
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}
