package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import kotlin.math.max
import kotlin.math.roundToInt

/** Open: the container grows out of the button (emphasized-decelerate). */
internal const val PAIR_DIALOG_OPEN_MILLIS = 420

/** Close: the container shrinks back into the button (emphasized-accelerate). */
internal const val PAIR_DIALOG_CLOSE_MILLIS = CLOSE_MILLIS + 50

/** The dialog's content waits this long, until the container has mostly grown, before fading in. */
internal const val PAIR_DIALOG_CONTENT_DELAY_MILLIS = 180

/**
 * Where the "Pair over Wi-Fi" button is and whether the pairing dialog is showing, so the dialog can grow out of the
 * button and shrink back into it. The button hides while the dialog covers it: the dialog *is* the button then.
 */
@Stable
class PairDialogAnchor {
    /** The dialog's visibility; its target is whether pairing is open, its current state lags through the motion. */
    val visibility = MutableTransitionState(false)

    /** The button's bounds in window coordinates, updated as it moves. Not snapshot state: read at layout time. */
    internal var bounds: Rect? = null

    /** True while the dialog is open or still opening or closing. */
    val showing: Boolean get() = visibility.currentState || visibility.targetState

    /**
     * True once the dialog's container has drawn over the button, until the dialog leaves. The button hides only
     * then: the dialog layer can take a frame to appear, and hiding the button earlier would blink it out.
     */
    internal var covered by mutableStateOf(false)
}

/** Records this button's window bounds in [anchor] and hides it while the pairing dialog grown from it covers it. */
internal fun Modifier.pairDialogOrigin(anchor: PairDialogAnchor): Modifier =
    onGloballyPositioned { anchor.bounds = it.boundsInWindow() }
        .graphicsLayer { alpha = if (anchor.covered) 0f else 1f }

/** The look of the button the dialog grows from: its container color, corner radius, and face (its label). */
internal class ContainerOrigin(
    val color: Color,
    /** Null for a pill: half the button's height. */
    val cornerRadius: Dp?,
    val face: @Composable () -> Unit,
)

/**
 * A modal dialog that grows out of [anchor]'s button and shrinks back into it: its bounds and corners morph from the
 * button's pill to the dialog card ([cornerRadius], [containerColor]), the scrim fades in, the button's [origin] face
 * fades out while the dialog [content] fades in once the card has mostly grown.
 *
 * Runs in a real [Dialog] layer, so it covers the whole window, closes on Esc and keeps keyboard focus inside like
 * any other dialog here; the morph works in window coordinates, which that layer shares with the window. With no
 * known button bounds it grows from a slightly smaller card in the middle.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ContainerTransformDialog(
    visibleState: MutableTransitionState<Boolean>,
    anchor: PairDialogAnchor?,
    origin: ContainerOrigin,
    containerColor: Color,
    cornerRadius: Dp,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrimColor = remember { DialogProperties().scrimColor }
    // The button shows again in the same frame the dialog leaves.
    DisposableEffect(anchor) { onDispose { anchor?.covered = false } }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = Color.Transparent,
            animateTransition = false,
        ),
    ) {
        val transition = rememberTransition(visibleState, label = "pairing dialog")
        val progress by transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(PAIR_DIALOG_OPEN_MILLIS, easing = EmphasizedDecelerate)
                else tween(PAIR_DIALOG_CLOSE_MILLIS, easing = EmphasizedAccelerate)
            },
            label = "bounds",
        ) { if (it) 1f else 0f }
        val scrim by transition.animateFloat(
            transitionSpec = { if (targetState) tween(OPEN_MILLIS) else tween(PAIR_DIALOG_CLOSE_MILLIS) },
            label = "scrim",
        ) { if (it) 1f else 0f }
        val contentAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(OPEN_MILLIS - 40, delayMillis = PAIR_DIALOG_CONTENT_DELAY_MILLIS)
                else tween(PAIR_DIALOG_CLOSE_MILLIS / 2, easing = EmphasizedAccelerate)
            },
            label = "content",
        ) { if (it) 1f else 0f }
        val faceAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) tween(PAIR_DIALOG_CONTENT_DELAY_MILLIS - 60)
                else tween(PAIR_DIALOG_CLOSE_MILLIS / 2, delayMillis = PAIR_DIALOG_CLOSE_MILLIS / 2)
            },
            label = "face",
        ) { if (it) 0f else 1f }

        val geometry = remember { MorphGeometry() }
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { geometry.layerOrigin = it.positionInWindow() }
                .drawBehind { drawRect(scrimColor, alpha = scrimColor.alpha * scrim) },
        ) {
            Layout(
                contents = listOf(origin.face, content),
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val rect = lerp(geometry.from, geometry.target, progress)
                    val startRadius = origin.cornerRadius?.toPx() ?: (geometry.from.height / 2f)
                    val radius = androidx.compose.ui.util.lerp(startRadius, cornerRadius.toPx(), progress)
                        .coerceAtMost(rect.minDimension / 2f)
                    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
                    clipPath(path) {
                        drawRect(lerp(origin.color, containerColor, progress), rect.topLeft, rect.size)
                        this@drawWithContent.drawContent()
                    }
                    if (anchor != null && !anchor.covered) anchor.covered = true
                },
            ) { (faceMeasurables, contentMeasurables), constraints ->
                val margin = 48.dp.roundToPx()
                val card = contentMeasurables.first().measure(
                    Constraints(
                        maxWidth = max(0, constraints.maxWidth - 2 * margin),
                        maxHeight = max(0, constraints.maxHeight - 2 * margin),
                    ),
                )
                val target = Rect(
                    Offset((constraints.maxWidth - card.width) / 2f, (constraints.maxHeight - card.height) / 2f),
                    Size(card.width.toFloat(), card.height.toFloat()),
                )
                val from = anchor?.bounds?.translate(-geometry.layerOrigin)?.takeIf { it.width > 0f && it.height > 0f }
                    ?: target.scaledAboutCenter(0.85f)
                geometry.from = from
                geometry.target = target
                val rect = lerp(from, target, progress)
                val face = faceMeasurables.first().measure(
                    Constraints.fixed(max(0, rect.width.roundToInt()), max(0, rect.height.roundToInt())),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // The card's content keeps its final size and rides along with the container, clipped by it.
                    card.placeWithLayer((rect.left + (rect.width - card.width) / 2f).roundToInt(), rect.top.roundToInt()) {
                        alpha = contentAlpha
                    }
                    face.placeWithLayer(rect.left.roundToInt(), rect.top.roundToInt()) { alpha = faceAlpha }
                }
            }
        }
    }
}

/** The container's start and end bounds in the dialog layer's coordinates, worked out at layout and read at draw. */
private class MorphGeometry {
    var layerOrigin = Offset.Zero
    var from = Rect.Zero
    var target = Rect.Zero
}

private fun Rect.scaledAboutCenter(scale: Float): Rect {
    val c = center
    return Rect(c.x - width * scale / 2f, c.y - height * scale / 2f, c.x + width * scale / 2f, c.y + height * scale / 2f)
}
