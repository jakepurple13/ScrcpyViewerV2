package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

/** Simple to Advanced: the Advanced options row grows into the pane while the shared controls glide to the footer. */
internal const val TO_ADVANCED_MILLIS = 450

/** Advanced to Simple: the pane shrinks back into the row while the shared controls glide back up. */
internal const val TO_SIMPLE_MILLIS = 400

/** Material's emphasized curve, for things that move and resize on screen. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Keys for the parts of the device pane that exist in both views and move between them. */
internal enum class SharedPart { Container, Preset, Mirror, Wifi }

/**
 * The scopes one side of the Simple/Advanced switch animates in. [toAdvanced] is the direction of the switch running
 * now, which sets the timing on both sides.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class ViewSwitchMotion(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val toAdvanced: Boolean,
    val anchor: ContainerAnchor,
) {
    val millis: Int get() = if (toAdvanced) TO_ADVANCED_MILLIS else TO_SIMPLE_MILLIS
}

/**
 * Shows [content] for the simple (false) or advanced (true) view, and animates between them: the Advanced options row
 * grows into the advanced pane (and shrinks back), and the preset picker, Mirror and Connect over Wi-Fi glide between
 * their spots in each view.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ViewSwitch(
    advanced: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (advanced: Boolean, motion: ViewSwitchMotion) -> Unit,
) {
    val anchor = remember { ContainerAnchor() }
    SharedTransitionLayout(modifier) {
        AnimatedContent(
            advanced,
            // Each part animates itself (see the modifiers below); the panes as a whole neither fade nor resize.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) },
            label = "device view",
        ) { showsAdvanced ->
            content(showsAdvanced, ViewSwitchMotion(this@SharedTransitionLayout, this, toAdvanced = advanced, anchor = anchor))
        }
    }
}

/** A control present in both views ([part]): it glides and resizes between its two spots, above everything else. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedPart(motion: ViewSwitchMotion?, part: SharedPart): Modifier {
    if (motion == null) return this
    val millis = motion.millis
    return with(motion.shared) {
        this@sharedPart.sharedBounds(
            rememberSharedContentState(part),
            motion.visibility,
            enter = fadeIn(tween(millis / 2, delayMillis = millis / 4)),
            exit = fadeOut(tween(millis / 2)),
            boundsTransform = { _, _ -> tween(millis, easing = Emphasized) },
            zIndexInOverlay = 2f,
        )
    }
}

/**
 * The advanced pane's surface (with [isPane]), or the spot behind the Advanced options row it grows out of and shrinks
 * back into. Fill the pane (or the row) with it, behind the content.
 */
@Composable
internal fun ViewSwitchContainer(motion: ViewSwitchMotion?, isPane: Boolean, modifier: Modifier = Modifier) {
    if (motion == null) return
    val anchor = motion.anchor
    if (!isPane) {
        Box(modifier.onGloballyPositioned { anchor.row = it.boundsInWindow() })
        return
    }
    val millis = motion.millis
    // 0 = the size of the row, 1 = the whole pane.
    val grown by motion.visibility.transition.animateFloat(
        transitionSpec = { tween(millis, easing = Emphasized) },
        label = "container",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    val from = MaterialTheme.colorScheme.surfaceContainerHigh
    val to = MaterialTheme.colorScheme.surface
    var pane by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier
            .onGloballyPositioned { pane = it.boundsInWindow() }
            .drawBehind {
                val full = Rect(Offset.Zero, size)
                val row = anchor.row?.translate(-pane.topLeft) ?: full
                val corner = (1f - grown) * 12.dp.toPx()
                drawRoundRect(
                    color = lerp(from, to, grown),
                    topLeft = lerp(row, full, grown).topLeft,
                    size = lerp(row, full, grown).size,
                    cornerRadius = CornerRadius(corner),
                    // Invisible at the row itself, so the row shows through until the surface starts to grow.
                    alpha = (grown * 8f).coerceAtMost(1f),
                )
            },
    )
}

/** Where the Advanced options row is, so the advanced pane's surface can grow out of it. */
internal class ContainerAnchor {
    var row: Rect? by mutableStateOf(null)
}

/**
 * The advanced pane's own content (search, option grid, footer text): it waits for the surface to mostly grow, then
 * rises in; it fades out first when the pane shrinks away.
 */
@Composable
internal fun Modifier.advancedContentMotion(motion: ViewSwitchMotion?): Modifier {
    if (motion == null) return this
    val millis = motion.millis
    return with(motion.visibility) {
        this@advancedContentMotion.animateEnterExit(
            enter = fadeIn(tween(millis * 11 / 20, delayMillis = millis * 9 / 20, easing = EmphasizedDecelerate)) +
                slideInVertically(tween(millis, easing = EmphasizedDecelerate)) { 12 },
            // Gone before the surface has shrunk much, so the options never spill outside it.
            exit = fadeOut(tween(millis / 4, easing = LinearEasing)),
        )
    }
}

/** The simple pane's own content (device header, hints, the row's text): it shrinks a little as it fades. */
@Composable
internal fun Modifier.simpleContentMotion(motion: ViewSwitchMotion?): Modifier {
    if (motion == null) return this
    val millis = motion.millis
    return with(motion.visibility) {
        this@simpleContentMotion.animateEnterExit(
            enter = fadeIn(tween(millis * 11 / 20, delayMillis = millis * 9 / 20, easing = EmphasizedDecelerate)) +
                scaleIn(tween(millis, easing = EmphasizedDecelerate), initialScale = 0.96f),
            exit = fadeOut(tween(200, easing = EmphasizedAccelerate)) +
                scaleOut(tween(200, easing = EmphasizedAccelerate), targetScale = 0.96f),
        )
    }
}
