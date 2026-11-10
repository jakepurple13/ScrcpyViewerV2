package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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

/** A Windows 95 push button: face color, raised bevel that sinks while pressed; [isDefault] adds the dark frame. */
@Composable
private fun RetroButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    isDefault: Boolean,
    source: MutableInteractionSource,
    pressed: Boolean,
    content: @Composable RowScope.() -> Unit,
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
            containerColor = scheme.surface, contentColor = scheme.onSurface,
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
