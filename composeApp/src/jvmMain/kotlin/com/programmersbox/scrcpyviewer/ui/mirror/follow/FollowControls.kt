package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.Checkbox
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Another open mirror window the leader can send input to: its serial and device title. */
data class FollowCandidate(val serial: String, val title: String)

/** Everything the title bar needs to show following for one window. */
data class FollowStrip(
    val role: FollowRole,
    /** The other open mirror windows, in a stable order. */
    val others: List<FollowCandidate>,
    /** This window uses the UHID mouse: its pointer input is never followed. */
    val captureOn: Boolean,
    /** The leader's device title while this window follows. */
    val leaderTitle: String?,
    /** This window follows a device whose screen shape differs ([FollowSender.layoutDiffers]). */
    val layoutDiffers: Boolean,
    val onChange: (Set<String>) -> Unit,
    /** The system asks for reduced motion: fades instead of movement, no pulse. */
    val reduceMotion: Boolean = false,
)

const val SEND_INPUT_IDLE = "Send input to…"
const val SEND_INPUT_TITLE = "SEND INPUT TO"
const val SEND_INPUT_ALL = "All mirror windows"
const val SEND_INPUT_ALONE = "Open another mirror window to send input to it"
const val SEND_INPUT_CAPTURE_NOTE = "Mouse capture is on, so taps aren't sent to other windows"
const val LAYOUT_DIFFERS = "Layout differs"

/** The motion of following (spec "Motion"). */
object FollowMotion {
    /** The button label's slide-up. */
    const val LABEL_MILLIS = 200

    /** The border's color in as following starts, and out as it stops. */
    const val BORDER_IN_MILLIS = 300
    const val BORDER_OUT_MILLIS = 200

    /** The glow's one outward pulse as following starts. */
    const val PULSE_MILLIS = 600

    /** The chip's expand + fade in, and shrink + fade out. */
    const val CHIP_IN_MILLIS = 250
    const val CHIP_OUT_MILLIS = 200

    /** "Layout differs" expanding or shrinking beside the chip. */
    const val LAYOUT_MILLIS = 200

    /** With reduced motion: a plain fade, no movement, no pulse. */
    const val REDUCED_FADE_MILLIS = 150
}

/** "Send input to…", or "Sending to N" while this window leads. */
fun sendInputLabel(role: FollowRole): String =
    if (role is FollowRole.Leader) "Sending to ${role.followers.size}" else SEND_INPUT_IDLE

/** The follower set after a menu row is picked: [serial] toggles that window, null ("All mirror windows") all of them. */
fun toggledFollowers(current: Set<String>, others: List<FollowCandidate>, serial: String?): Set<String> {
    val all = others.mapTo(LinkedHashSet()) { it.serial }
    return when {
        serial == null -> if (all.all { it in current }) emptySet() else all
        serial in current -> current - serial
        else -> current + serial
    }.filterTo(LinkedHashSet()) { it in all }
}

/** What a follower window's own options let through. UHID modes still take plain injected keys and touches. */
fun followerFlags(values: OptionValues): FollowerFlags {
    val e = values.entries
    return FollowerFlags(
        control = "no-control" !in e,
        keyboard = e["keyboard"] != "disabled",
        mouse = e["mouse"] != "disabled",
    )
}

// --- Leader button and menu -------------------------------------------------------------------------------------------

/**
 * The title bar's tonal "Send input to…" button ("Sending to N" while leading) and its menu. [compact] drops the
 * words: just the icon, and the count while leading.
 */
@Composable
fun SendInputButton(
    role: FollowRole,
    others: List<FollowCandidate>,
    captureOn: Boolean,
    onChange: (Set<String>) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = remember { systemReducesMotion() },
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { open = !open },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(start = 10.dp, end = if (compact) 10.dp else 12.dp),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = if (compact) sendInputLabel(role) else null, Modifier.size(16.dp))
            SendInputLabel(role, compact, reduceMotion)
        }
        SendInputMenu(open, { open = false }, role, others, captureOn, onChange)
    }
}

/**
 * The label, sliding up when the count grows (from idle to sending, or more windows) and down when it shrinks, like
 * the app's other numbers. Its width follows over the same time.
 */
@Composable
private fun SendInputLabel(role: FollowRole, compact: Boolean, reduceMotion: Boolean) {
    val count = (role as? FollowRole.Leader)?.followers?.size ?: 0
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            if (reduceMotion) {
                val fade = tween<Float>(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing)
                (fadeIn(fade) togetherWith fadeOut(fade)).using(SizeTransform(clip = true) { _, _ -> tween(FollowMotion.REDUCED_FADE_MILLIS, easing = FastOutSlowInEasing) })
            } else {
                val slide = tween<androidx.compose.ui.unit.IntOffset>(FollowMotion.LABEL_MILLIS, easing = EmphasizedDecelerate)
                val fade = tween<Float>(FollowMotion.LABEL_MILLIS, easing = EmphasizedDecelerate)
                ((slideInVertically(slide) { it * dir } + fadeIn(fade)) togetherWith (slideOutVertically(slide) { -it * dir } + fadeOut(fade)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(FollowMotion.LABEL_MILLIS, easing = FastOutSlowInEasing) })
            }
        },
        contentAlignment = Alignment.CenterStart,
        label = "sendInputLabel",
    ) { n ->
        val text = when {
            !compact -> if (n > 0) "Sending to $n" else SEND_INPUT_IDLE
            n > 0 -> "$n"
            else -> ""
        }
        if (text.isNotEmpty()) Text(text, Modifier.padding(start = 6.dp), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
    }
}

/** The "Send input to" menu: a check row per other window, then "All mirror windows"; the capture note when it applies. */
@Composable
fun SendInputMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    role: FollowRole,
    others: List<FollowCandidate>,
    captureOn: Boolean,
    onChange: (Set<String>) -> Unit,
) {
    val followers = (role as? FollowRole.Leader)?.followers.orEmpty()
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (others.isEmpty()) {
            DropdownMenuItem(text = { Text(SEND_INPUT_ALONE) }, onClick = {}, enabled = false)
            return@DropdownMenu
        }
        Text(
            SEND_INPUT_TITLE,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        for (c in others) {
            CheckRow(c.title, c.serial in followers) { onChange(toggledFollowers(followers, others, c.serial)) }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
        CheckRow(SEND_INPUT_ALL, others.all { it.serial in followers }) { onChange(toggledFollowers(followers, others, null)) }
        if (captureOn) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).widthIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Mouse, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(SEND_INPUT_CAPTURE_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    )
}

// --- Follower chip ------------------------------------------------------------------------------------------------------

/** "Following <device>", then "Layout differs" in warning colors while [layoutDiffers] (it expands in and out). */
@Composable
fun FollowingChip(
    leaderTitle: String,
    layoutDiffers: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = remember { systemReducesMotion() },
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Weighted: "Layout differs" is measured first, so the device title is what ellipsizes.
        Pill("Following $leaderTitle", colors.primaryContainer, colors.onPrimaryContainer, Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
        val size = tween<androidx.compose.ui.unit.IntSize>(FollowMotion.LAYOUT_MILLIS, easing = FastOutSlowInEasing)
        val fade = tween<Float>(if (reduceMotion) FollowMotion.REDUCED_FADE_MILLIS else FollowMotion.LAYOUT_MILLIS, easing = LinearEasing)
        androidx.compose.animation.AnimatedVisibility(
            visible = layoutDiffers,
            enter = if (reduceMotion) fadeIn(fade) else expandHorizontally(size, expandFrom = Alignment.Start) + fadeIn(fade),
            exit = if (reduceMotion) fadeOut(fade) else shrinkHorizontally(size, shrinkTowards = Alignment.Start) + fadeOut(fade),
        ) {
            val warn = warnColor()
            Row {
                Spacer(Modifier.width(6.dp))
                Pill(LAYOUT_DIFFERS, warn.copy(alpha = 0.16f), warn)
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = styledCorner(10.dp), color = container, contentColor = content) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [FollowingChip] while [visible]: it expands + fades in over 250 ms and shrinks + fades out over 200 ms. */
@Composable
fun FollowerChip(
    visible: Boolean,
    leaderTitle: String?,
    layoutDiffers: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    // The chip keeps what it said while it leaves (the link is already gone). A plain holder.
    val last = remember { arrayOfNulls<String>(1) }
    if (leaderTitle != null) last[0] = leaderTitle
    val inSize = tween<androidx.compose.ui.unit.IntSize>(FollowMotion.CHIP_IN_MILLIS, easing = FastOutSlowInEasing)
    val outSize = tween<androidx.compose.ui.unit.IntSize>(FollowMotion.CHIP_OUT_MILLIS, easing = FastOutSlowInEasing)
    val enter: EnterTransition = if (reduceMotion) {
        fadeIn(tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing))
    } else {
        expandHorizontally(inSize, expandFrom = Alignment.Start) + fadeIn(tween(FollowMotion.CHIP_IN_MILLIS, easing = EmphasizedDecelerate))
    }
    val exit: ExitTransition = if (reduceMotion) {
        fadeOut(tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing))
    } else {
        shrinkHorizontally(outSize, shrinkTowards = Alignment.Start) + fadeOut(tween(FollowMotion.CHIP_OUT_MILLIS, easing = EmphasizedAccelerate))
    }
    androidx.compose.animation.AnimatedVisibility(visible, modifier, enter = enter, exit = exit) {
        FollowingChip(last[0] ?: "", layoutDiffers, reduceMotion = reduceMotion)
    }
}

// --- Border glow ----------------------------------------------------------------------------------------------------------

/**
 * The follower window's accent border and 3 dp glow. [presence] (0..1) fades both in over 300 ms as following starts
 * and out over 200 ms as it stops; [pulse] runs 0 to 1 once over 600 ms (emphasized decelerate) as following starts: a
 * band of glow that spreads in from the border, from nothing to [FollowGlow.PULSE_REACH], fading as it goes (the
 * mockup's box-shadow pulse, drawn inward since nothing can be drawn outside the window). Stopping mid-pulse finishes
 * the pulse with the border's fade. With reduced motion: 150 ms fades and no pulse.
 */
@Stable
class FollowGlow internal constructor(
    private val presenceAnim: Animatable<Float, *>,
    private val pulseAnim: Animatable<Float, *>,
) {
    val presence: Float get() = presenceAnim.value
    val pulse: Float get() = pulseAnim.value

    /** The pulse band's opacity: none before it starts and once it ends. */
    val pulseAlpha: Float get() = pulse.let { if (it <= 0f || it >= 1f) 0f else PULSE_ALPHA * (1f - it) }

    internal companion object {
        const val PULSE_ALPHA = 0.6f
        const val GLOW_ALPHA = 0.35f
        val BORDER: Dp = 1.5.dp
        val GLOW: Dp = 3.dp
        val PULSE_REACH: Dp = 14.dp
    }
}

@Composable
fun rememberFollowGlow(active: Boolean, reduceMotion: Boolean): FollowGlow {
    // A window that is already following when it appears shows the glow at rest.
    val presence = remember { Animatable(if (active) 1f else 0f) }
    val pulse = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active, reduceMotion) {
        if (active) {
            if (presence.value >= 1f) return@LaunchedEffect
            coroutineScope {
                launch {
                    presence.animateTo(
                        1f,
                        if (reduceMotion) tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing)
                        else tween(FollowMotion.BORDER_IN_MILLIS, easing = FastOutSlowInEasing),
                    )
                }
                if (!reduceMotion) {
                    pulse.snapTo(0f)
                    pulse.animateTo(1f, tween(FollowMotion.PULSE_MILLIS, easing = EmphasizedDecelerate))
                }
            }
        } else {
            val out = if (reduceMotion) tween<Float>(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing)
            else tween(FollowMotion.BORDER_OUT_MILLIS, easing = EmphasizedAccelerate)
            coroutineScope {
                launch { presence.animateTo(0f, out) }
                // A pulse still running fades out with the border instead of vanishing.
                if (pulse.value > 0f && pulse.value < 1f) launch { pulse.animateTo(1f, out) }
            }
        }
    }
    return remember(presence, pulse) { FollowGlow(presence, pulse) }
}

/** Draws [glow] over the content's edges in [color]; the values are read at draw time only. */
fun Modifier.followGlow(glow: FollowGlow, color: Color): Modifier = drawWithContent {
    drawContent()
    val p = glow.presence
    if (p <= 0f) return@drawWithContent
    val border = FollowGlow.BORDER.toPx()
    val band = FollowGlow.GLOW.toPx()
    strokeInset(color.copy(alpha = p), inset = 0f, width = border)
    strokeInset(color.copy(alpha = FollowGlow.GLOW_ALPHA * p), inset = border, width = band)
    val pulseAlpha = glow.pulseAlpha
    if (pulseAlpha > 0f) {
        val reach = FollowGlow.PULSE_REACH.toPx() * glow.pulse
        if (reach >= 0.5f) softBand(color, pulseAlpha, inset = border, width = reach)
    }
}

/** A band [width] wide inside [inset], at [alpha] along its outer edge and fading to nothing at its inner edge. */
private fun DrawScope.softBand(color: Color, alpha: Float, inset: Float, width: Float) {
    val steps = SOFT_STEPS
    val step = width / steps
    for (i in 0 until steps) strokeInset(color.copy(alpha = alpha * (1f - i.toFloat() / steps)), inset + i * step, step)
}

private const val SOFT_STEPS = 8

/** The accent border and glow of a following window, animated by [rememberFollowGlow]. */
@Composable
fun Modifier.followGlow(active: Boolean, reduceMotion: Boolean): Modifier =
    followGlow(rememberFollowGlow(active, reduceMotion), MaterialTheme.colorScheme.primary)

/** A rectangle stroke [width] wide whose outer edge is [inset] inside the bounds. */
private fun DrawScope.strokeInset(color: Color, inset: Float, width: Float) {
    val half = width / 2f
    val o = inset + half
    if (size.width <= 2 * o || size.height <= 2 * o) return
    drawRect(color, topLeft = Offset(o, o), size = Size(size.width - 2 * o, size.height - 2 * o), style = Stroke(width))
}
