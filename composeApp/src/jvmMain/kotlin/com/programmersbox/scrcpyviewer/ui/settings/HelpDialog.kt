package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleRaised

/** The scrim fades in over this long. */
internal const val HELP_SCRIM_MILLIS = 300

/** The card scales from [HELP_CARD_FROM_SCALE] and rises [HELP_CARD_RISE] over this long. */
internal const val HELP_OPEN_MILLIS = 450
internal const val HELP_CARD_FROM_SCALE = 0.88f
internal val HELP_CARD_RISE = 14.dp

/** The card fades in over this long while it scales. */
internal const val HELP_CARD_FADE_MILLIS = 300

/** Closing: the scrim fades and the card fades while shrinking to [HELP_CARD_CLOSE_SCALE], over this long. */
internal const val HELP_CLOSE_MILLIS = 200
internal const val HELP_CARD_CLOSE_SCALE = 0.95f

/** Reduced motion: plain fades. */
internal const val HELP_REDUCED_MILLIS = 150

private fun <T> reduced(): FiniteAnimationSpec<T> = tween(HELP_REDUCED_MILLIS, easing = LinearEasing)

internal fun helpScrimSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> reduced()
    opening -> tween(HELP_SCRIM_MILLIS, easing = EmphasizedDecelerate)
    else -> tween(HELP_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

internal fun helpCardSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> snap()
    opening -> tween(HELP_OPEN_MILLIS, easing = EmphasizedDecelerate)
    else -> tween(HELP_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

internal fun helpFadeSpec(motion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> = when {
    !motion -> reduced()
    opening -> tween(HELP_CARD_FADE_MILLIS, easing = EmphasizedDecelerate)
    else -> tween(HELP_CLOSE_MILLIS, easing = EmphasizedAccelerate)
}

/**
 * The About and Open-source licenses dialogs' frame. Opening, the scrim fades in over 300 ms while the card scales from
 * .88, rises 14 dp and fades in over 450 ms (emphasized decelerate); closing, both fade over 200 ms while the card
 * shrinks to .95. Reduced motion: 150 ms fades. Esc and a click on the scrim call [onDismiss] once; the caller then
 * sets [visible] false. A real [Dialog] layer, so it covers the window and keeps keyboard focus inside.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HelpDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    motion: Boolean,
    cardModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    val dismiss = { if (shown.targetState) onDismiss() }
    val scrimColor = remember { DialogProperties().scrimColor }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = Color.Transparent,
            animateTransition = false,
        ),
    ) {
        val transition = rememberTransition(shown, label = "help dialog")
        val scrim by transition.animateFloat({ helpScrimSpec(motion, targetState) }, label = "scrim") { if (it) 1f else 0f }
        val card by transition.animateFloat({ helpCardSpec(motion, targetState) }, label = "card") { if (it) 1f else 0f }
        val fade by transition.animateFloat({ helpFadeSpec(motion, targetState) }, label = "fade") { if (it) 1f else 0f }
        val opening = transition.targetState
        val rise = with(LocalDensity.current) { HELP_CARD_RISE.toPx() }
        // Focus starts inside the dialog (a field in the content may take it), so Esc reaches it straight away.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize()
                // Esc closes it wherever focus is inside (the dialog layer's own Esc handling may also call dismiss).
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .drawBehind { drawRect(scrimColor, alpha = scrimColor.alpha * scrim) }
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val style = currentStyle
            val shape = if (style == ThemeStyle.Retro) RectangleShape else MaterialTheme.shapes.extraLarge
            Surface(
                cardModifier
                    .graphicsLayer {
                        val from = if (opening) HELP_CARD_FROM_SCALE else HELP_CARD_CLOSE_SCALE
                        val scale = from + (1f - from) * card
                        scaleX = scale
                        scaleY = scale
                        translationY = if (opening) (1f - card) * rise else 0f
                        alpha = fade
                    }
                    // Taps on the card stay off the scrim, which closes the dialog.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (style == ThemeStyle.Material) Modifier else Modifier.styleRaised(shape)),
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = if (style == ThemeStyle.Material) 6.dp else 0.dp,
                content = content,
            )
        }
    }
}
