package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion

/** One blink of a live dot: down to [LIVE_BLINK_LOW] opacity and back, the mockup's 1.6 s. */
internal const val LIVE_BLINK_MILLIS = 1_600
internal const val LIVE_BLINK_LOW = 0.35f

/**
 * The round "Logcat is live" dot, [size] wide in [color]: the status bar's and the sheet segment's. While [blinking] it
 * fades to [LIVE_BLINK_LOW] and back every [LIVE_BLINK_MILLIS], unless [motion] is off (the OS reduces motion); then it
 * stays solid. The blink is held as State and read in the layer, so it redraws the dot without recomposing.
 */
@Composable
internal fun LogcatLiveDot(
    color: Color,
    size: Dp,
    blinking: Boolean,
    modifier: Modifier = Modifier,
    motion: Boolean = remember { !systemReducesMotion() },
) {
    val blink = if (blinking && motion) {
        rememberInfiniteTransition(label = "logcat live").animateFloat(
            1f, LIVE_BLINK_LOW,
            infiniteRepeatable(tween(LIVE_BLINK_MILLIS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        )
    } else {
        null
    }
    Box(modifier.size(size).graphicsLayer { alpha = blink?.value ?: 1f }.background(color, CircleShape))
}
