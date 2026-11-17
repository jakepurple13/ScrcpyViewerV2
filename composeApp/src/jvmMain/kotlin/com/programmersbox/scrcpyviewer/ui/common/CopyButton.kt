package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/** Where [rememberCopyAction] shows its "Copied" message. `null` outside the app shell (previews, tests). */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

/** An action that copies its text to the system clipboard and shows the "Copied" snackbar. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    return { value ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(StringSelection(value)))
            snackbar?.showSnackbar("Copied")
        }
    }
}

/** How long [CopyButton] shows "Copied" before turning back into a copy button. */
const val COPY_CONFIRM_MILLIS = 1_500L
private const val ICON_MORPH_MILLIS = 300

/**
 * A copy button's confirmation: [copy] writes text to the system clipboard, and [copied] stays true for
 * [COPY_CONFIRM_MILLIS] after the latest copy. Made by [rememberCopyState].
 */
class CopyState internal constructor(private val write: (String) -> Unit) {
    // Bumped per copy; 0 = not copied. A new value restarts the revert timer.
    internal var copies by mutableIntStateOf(0)

    /** True from a copy until [COPY_CONFIRM_MILLIS] after the latest one. */
    val copied: Boolean get() = copies > 0

    /** Copies [value] and (re)starts the confirmation. */
    fun copy(value: String) {
        write(value)
        copies++
    }
}

/** A [CopyState] whose confirmation reverts [COPY_CONFIRM_MILLIS] after the latest copy, counted in frame time. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberCopyState(): CopyState {
    val clipboard by rememberUpdatedState(LocalClipboard.current)
    val scope = rememberCoroutineScope()
    val state = remember { CopyState { value -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(value))) } } }
    LaunchedEffect(state.copies) {
        if (state.copies == 0) return@LaunchedEffect
        delay(COPY_CONFIRM_MILLIS)
        state.copies = 0
    }
    return state
}

/**
 * The copy icon, morphing into a check while [copied]. With [turn] the check also turns in from -45°, so it reads as
 * the copy icon turning into it.
 */
@Composable
private fun CopyMorphIcon(copied: Boolean, size: Dp, contentDescription: String?, turn: Boolean, label: String) {
    AnimatedContent(
        targetState = copied,
        transitionSpec = {
            val morph = tween<Float>(ICON_MORPH_MILLIS, easing = EmphasizedDecelerate)
            (scaleIn(morph, initialScale = 0.3f) + fadeIn(morph)) togetherWith
                (scaleOut(morph, targetScale = 0.3f) + fadeOut(morph)) using SizeTransform(clip = false)
        },
        label = label,
    ) { isCopied ->
        if (isCopied && turn) {
            val rotation by transition.animateFloat({ tween(ICON_MORPH_MILLIS, easing = EmphasizedDecelerate) }, label = "checkTurn") {
                if (it == EnterExitState.Visible) 0f else -45f
            }
            Icon(
                Icons.Filled.Check, contentDescription = contentDescription,
                modifier = Modifier.size(size).graphicsLayer { rotationZ = rotation },
            )
        } else {
            Icon(
                if (isCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * Copies [text] to the system clipboard. The button itself confirms it: the copy icon morphs into a check, the label
 * reads "Copied" and the button tints, then it turns back after [COPY_CONFIRM_MILLIS]. Clicking again copies again
 * and restarts that time.
 */
@Composable
fun CopyButton(text: () -> String, label: String = "Copy", modifier: Modifier = Modifier) {
    val state = rememberCopyState()
    val copied = state.copied
    val tint = tween<Color>(OPEN_MILLIS, easing = EmphasizedDecelerate)
    val container by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tint, label = "copyContainer",
    )
    val content by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary, tint, label = "copyContent",
    )
    TextButton(
        onClick = { state.copy(text()) },
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(containerColor = container, contentColor = content),
    ) {
        CopyMorphIcon(copied, 16.dp, contentDescription = null, turn = true, label = "copyIcon")
        // The label cross-fades while the button's width follows it.
        AnimatedContent(
            targetState = if (copied) "Copied" else label,
            transitionSpec = {
                fadeIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) togetherWith
                    fadeOut(tween(CLOSE_MILLIS / 2, easing = EmphasizedAccelerate)) using
                    SizeTransform(clip = false) { _, _ -> layoutSpring() }
            },
            label = "copyLabel",
        ) {
            Text(it, modifier = Modifier.padding(start = 6.dp), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * [CopyButton] without its label, for tight spaces: a copy icon described and tooltipped by [label]. It confirms the
 * same way: the icon morphs into a check and tints for [COPY_CONFIRM_MILLIS].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyIconButton(text: () -> String, label: String, modifier: Modifier = Modifier) {
    val state = rememberCopyState()
    val copied = state.copied
    val content by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
        tween(OPEN_MILLIS, easing = EmphasizedDecelerate), label = "copyIconContent",
    )
    TooltipArea(tooltip = { LabelTooltip(if (copied) "Copied" else label) }, modifier = modifier) {
        IconButton(
            onClick = { state.copy(text()) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = content),
        ) {
            CopyMorphIcon(copied, 18.dp, contentDescription = label, turn = false, label = "copyIconOnly")
        }
    }
}
