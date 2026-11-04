package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import kotlin.math.roundToInt

/** The drag bar's width, part of the sheet's width. */
internal val SHEET_DRAG_BAR_WIDTH = 8.dp

private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * The bar on the sheet's left edge, between the side toolbar and the sheet. Dragging it left widens the sheet ([width]
 * is the width shown now), right narrows it; [fit] clamps each new width. [onResize] reports every step,
 * [onResizeDone] the end of a drag or key press (time to save). Double-click or Home calls [onReset]. Focusable:
 * Left and Right move it by [SHEET_KEY_STEP]. The grip turns the accent color while hovered, dragged or focused.
 */
@Composable
internal fun SheetDragBar(
    width: Dp,
    fit: (Dp) -> Dp,
    onResize: (Dp) -> Unit,
    onResizeDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val currentWidth by rememberUpdatedState(width)
    val currentFit by rememberUpdatedState(fit)
    val currentOnReset by rememberUpdatedState(onReset)
    // The pointer's width, unclamped, so the bar stays under the pointer when it comes back from past a limit.
    val dragWidth = remember { FloatArray(1) }
    val dragState = rememberDraggableState { delta ->
        dragWidth[0] -= delta / density.density
        val next = currentFit(dragWidth[0].dp)
        if (next != currentWidth) onResize(next)
    }
    val grip by animateColorAsState(if (hovered || dragged || focused) colors.primary else colors.outlineVariant, tween(120))

    fun step(by: Dp) {
        val next = currentFit(currentWidth + by)
        if (next != currentWidth) {
            onResize(next)
            onResizeDone()
        }
    }

    Box(
        modifier.width(SHEET_DRAG_BAR_WIDTH).fillMaxHeight()
            .background(colors.surfaceContainerHigh)
            .pointerHoverIcon(ResizeCursor)
            .hoverable(interaction)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnReset() }) }
            .draggable(
                dragState, Orientation.Horizontal, interactionSource = interaction,
                onDragStarted = { dragWidth[0] = currentWidth.value },
                onDragStopped = { onResizeDone() },
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> step(SHEET_KEY_STEP)
                    Key.DirectionRight -> step(-SHEET_KEY_STEP)
                    Key.MoveHome -> onReset()
                    else -> return@onKeyEvent false
                }
                true
            }
            .semantics {
                contentDescription = "Resize quick actions"
                stateDescription = "${width.value.roundToInt()} dp wide"
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        // The sheet's divider, against the toolbar.
        Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(colors.outlineVariant))
        Box(Modifier.width(4.dp).height(36.dp).background(grip, CircleShape))
    }
}
