package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import kotlin.math.max

/** A new console line rises [SHELL_RISE_DP] into place while fading in. */
const val SHELL_RISE_MILLIS = 240
const val SHELL_RISE_DP = 8f

/** At most this many lines of one arriving batch animate (the newest ones); older lines of a big batch just show. */
const val SHELL_ANIMATED_BATCH = 50

/** The running caret is on for half of each [SHELL_CARET_MILLIS], then off. */
const val SHELL_CARET_MILLIS = 1_000

/** The exit pill pops from nothing to full size with an overshoot. */
const val SHELL_PILL_MILLIS = 380

/**
 * Which console lines get the arrival animation: only lines appended after the console first showed, at most the
 * newest [cap] of each batch, each once. Not snapshot state: [observe] runs in composition and is idempotent, and a
 * row [claim]s its seq once when it is first composed (a row scrolled away and back does not claim again).
 */
class LineArrivals(private val cap: Int = SHELL_ANIMATED_BATCH) {
    private var seen = -1L
    private var filter: String? = null
    private val pending = HashSet<Long>()

    /** Records that the console now ends before [nextSeq] while showing [filter]; lines past the last call are new. */
    fun observe(nextSeq: Long, filter: String = "") {
        if (seen < 0) {
            // The history already there when the console shows does not animate.
            seen = nextSeq
            this.filter = filter
            return
        }
        // A new filter brings back old lines, which must not animate as they appear.
        if (filter != this.filter) {
            this.filter = filter
            pending.clear()
        }
        if (nextSeq <= seen) return
        // Lines of an earlier batch never composed (scrolled away, paused) stay still when they show later.
        pending.clear()
        for (s in max(seen, nextSeq - cap) until nextSeq) pending += s
        seen = nextSeq
    }

    /** True once for a line that should animate in; the caller composes it with the animation. */
    fun claim(seq: Long): Boolean = pending.remove(seq)

    /** Seqs still waiting to animate, for tests. */
    internal fun pendingSeqs(): Set<Long> = pending.toSet()
}

/** Rises and fades the row in when [animate]; otherwise shows it at once. Reads the animation in the layer only. */
@Composable
fun Modifier.riseIn(animate: Boolean): Modifier {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(p) { p.animateTo(1f, tween(SHELL_RISE_MILLIS, easing = EmphasizedDecelerate)) }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * SHELL_RISE_DP.dp.toPx()
    }
}

/** A block caret that blinks on and off each second, sized to a line of [style]. Shown while a command runs. */
@Composable
fun BlinkingCaret(style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val blink = rememberInfiniteTransition(label = "caret")
    val on by blink.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = SHELL_CARET_MILLIS
                1f at 0 using LinearEasing
                1f at SHELL_CARET_MILLIS / 2 - 1 using LinearEasing
                0f at SHELL_CARET_MILLIS / 2 using LinearEasing
                0f at SHELL_CARET_MILLIS - 1 using LinearEasing
            },
            RepeatMode.Restart,
        ),
        label = "caret",
    )
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier.padding(top = 2.dp).size(width = 7.dp, height = (height - 4.dp).coerceAtLeast(10.dp))
            .drawBehind { drawRect(color, alpha = on) },
    )
}

/**
 * The run's end: "exit N" in a small pill, green-ish for 0 and in the error container otherwise, or a neutral
 * "stopped" pill; [millis] follows as plain text. The pill pops in with an overshoot when [animate].
 */
@Composable
fun ExitPill(code: Int?, millis: Long, style: TextStyle, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val scale = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(scale) { scale.animateTo(1f, tween(SHELL_PILL_MILLIS, easing = OvershootEasing)) }
    val (container, content) = exitPillColors(code, colors.surface.luminance() < 0.5f, colors)
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (code != null) "exit $code" else "stopped",
            Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(container, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 1.dp),
            style = style, color = content, fontWeight = FontWeight.SemiBold,
        )
        if (code != null) Text("  $millis ms", style = style, color = colors.onSurfaceVariant)
    }
}

/** A success green that sits with the theme: pale on light surfaces, deep on dark. */
private val SuccessContainerLight = Color(0xFFC9EBCF)
private val OnSuccessContainerLight = Color(0xFF0E4A1F)
private val SuccessContainerDark = Color(0xFF1D3B25)
private val OnSuccessContainerDark = Color(0xFFA6E3B2)

/** Container and content colors of the exit pill for [code]. */
fun exitPillColors(code: Int?, dark: Boolean, colors: ColorScheme): Pair<Color, Color> = when {
    code == null -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    code != 0 -> colors.errorContainer to colors.onErrorContainer
    dark -> SuccessContainerDark to OnSuccessContainerDark
    else -> SuccessContainerLight to OnSuccessContainerLight
}
