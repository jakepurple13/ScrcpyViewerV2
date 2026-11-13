package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Strip widths at which the mirror title bar's parts collapse into the overflow menu, without following. */
data class StripMins(val details: Dp, val copy: Dp, val fit: Dp, val zoom: Dp)

/** Measured widths of the model name and the follower chip's parts (pill padding and gaps included). */
data class ChipWidths(val model: Dp, val following: Dp, val differs: Dp, val differsIcon: Dp)

/** Where the "Send input to…" control is. */
enum class SendPlace { None, Words, Icon, Overflow }

/** Which title bar parts show at one strip width. */
data class StripRoom(
    val details: Boolean,
    val copy: Boolean,
    val fit: Boolean,
    val zoom: Boolean,
    val send: SendPlace,
    /** "Layout differs" is down to its warning icon: even without the controls there is no room for its words. */
    val compactWarning: Boolean,
) {
    val overflow: Boolean get() = !copy || !fit || !zoom || send == SendPlace.Overflow
}

/** The send button keeps its words from this strip width. */
val SEND_WORDS_MIN = 760.dp

/** Room the button takes from the details (with words) and from the copy button (icon only). */
val SEND_ROOM = 150.dp
val SEND_ICON_ROOM = 60.dp

/** Everything in a follower's strip but the model and the chip once every control is gone: padding, dot, gaps, ⋮. */
val FOLLOWER_FIXED = 98.dp

/** The model keeps at least this much (or its whole name if shorter) before the chip may take more. */
val MODEL_KEEP = 96.dp

private val GAP = 10.dp

/**
 * The title bar's layout at strip width [width].
 *
 * - No other mirror window open ([FollowStrip.others] empty), no [follow], or a `no-control` window
 *   ([FollowStrip.control] off: it has no input to send): exactly [mins], no send control.
 * - Leading or idle with other windows: the button with words from [SEND_WORDS_MIN], icon only down to the Fit
 *   cutoff, then in the overflow menu; the copy button and details give it room first.
 * - Following: no send control. Every cutoff moves up by the chip's width ([chip]), so copy, then Fit, then zoom go
 *   before the chip has to ellipsize; once they are all gone and there is still no room, "Layout differs" turns into
 *   its icon, and only then does the leader's name ellipsize.
 */
fun stripRoom(width: Dp, mins: StripMins, hasDetails: Boolean, follow: FollowStrip?, chip: ChipWidths?): StripRoom {
    val following = follow?.role is FollowRole.Follower
    if (following && chip != null) {
        val differs = follow.layoutDiffers
        val compact = differs && width < FOLLOWER_FIXED + chip.model + chip.following + chip.differs
        val extra = chip.following + (if (!differs) 0.dp else if (compact) chip.differsIcon else chip.differs) + GAP
        return StripRoom(
            details = hasDetails && width >= mins.details + extra,
            copy = width >= mins.copy + extra,
            fit = width >= mins.fit + extra,
            zoom = width >= mins.zoom + extra,
            send = SendPlace.None,
            compactWarning = compact,
        )
    }
    val canSend = follow != null && !following && follow.control && follow.others.isNotEmpty()
    if (!canSend) {
        return StripRoom(
            details = hasDetails && width >= mins.details,
            copy = width >= mins.copy,
            fit = width >= mins.fit,
            zoom = width >= mins.zoom,
            send = SendPlace.None,
            compactWarning = false,
        )
    }
    val fit = width >= mins.fit
    return StripRoom(
        details = hasDetails && width >= mins.details + SEND_ROOM,
        copy = width >= mins.copy + SEND_ICON_ROOM,
        fit = fit,
        zoom = width >= mins.zoom,
        send = when {
            !fit -> SendPlace.Overflow
            width >= SEND_WORDS_MIN -> SendPlace.Words
            else -> SendPlace.Icon
        },
        compactWarning = false,
    )
}

/** [model]'s width in [modelStyle] and the follower chip's parts for [follow], measured once per text. */
@Composable
fun rememberChipWidths(model: String, modelStyle: TextStyle, follow: FollowStrip?): ChipWidths? {
    val title = follow?.leaderTitle?.takeIf { follow.role is FollowRole.Follower } ?: return null
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chipStyle = MaterialTheme.typography.labelMedium
    return remember(model, modelStyle, title, chipStyle, density) {
        fun w(text: String, style: TextStyle): Dp =
            with(density) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp() }
        ChipWidths(
            model = w(model, modelStyle),
            following = minOf(w(followingText(title), chipStyle) + PILL_PADDING, FOLLOWING_MAX),
            differs = CHIP_GAP + w(LAYOUT_DIFFERS, chipStyle) + PILL_PADDING,
            differsIcon = CHIP_GAP + WARNING_ICON,
        )
    }
}

/** A title bar part appearing or leaving: expand / shrink horizontally + fade, 200 ms (a 150 ms fade when reduced). */
fun stripEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing))
    else expandHorizontally(tween<IntSize>(STRIP_MILLIS, easing = FastOutSlowInEasing)) + fadeIn(tween(STRIP_MILLIS, easing = FastOutSlowInEasing))

fun stripExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(tween(FollowMotion.REDUCED_FADE_MILLIS, easing = LinearEasing))
    else shrinkHorizontally(tween<IntSize>(STRIP_MILLIS, easing = FastOutSlowInEasing)) + fadeOut(tween(STRIP_MILLIS, easing = FastOutSlowInEasing))

const val STRIP_MILLIS = 200

/** Model titleSmall + Medium, as the title bar shows it. */
@Composable
fun stripModelStyle(): TextStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium)
