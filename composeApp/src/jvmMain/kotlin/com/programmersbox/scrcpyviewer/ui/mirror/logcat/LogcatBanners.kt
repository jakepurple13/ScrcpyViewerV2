package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

/**
 * "Logcat isn't running" over the list with a big Play calling [onPlay], disabled unless [canPlay]. It fades and
 * scales to 0.97 as it leaves (300 ms).
 */
@Composable
internal fun BoxScope.LogcatStartCard(visible: Boolean, motion: Boolean, canPlay: Boolean, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible,
        Modifier.matchParentSize(),
        enter = if (motion) fadeIn(tween(300)) + scaleIn(tween(300, easing = EmphasizedDecelerate), initialScale = 0.97f) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(300)) + scaleOut(tween(300, easing = EmphasizedDecelerate), targetScale = 0.97f) else ExitTransition.None,
    ) {
        Column(
            Modifier.fillMaxSize().background(scheme.surfaceContainerLowest).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigPlay(motion, canPlay, onPlay)
            Spacer(Modifier.height(6.dp))
            Text("Logcat isn't running", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                "Press Play to read this device's log. It stops when you leave this tab, unless Logcat is in its own window.",
                Modifier.widthIn(max = 320.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The 56 dp round Play; grows to 1.06 on hover (200 ms). Unless [enabled] it shows Material's disabled colors
 * (onSurface at 12 % behind, 38 % on the icon), doesn't grow and keeps the default cursor.
 */
@Composable
private fun BigPlay(motion: Boolean, enabled: Boolean, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered && motion && enabled) 1.06f else 1f, tween(200, easing = EmphasizedDecelerate))
    val container = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.12f)
    val tint = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)
    Box(
        Modifier.size(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(container)
            .hoverable(hover, enabled).then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(interactionSource = hover, indication = null, enabled = enabled, role = Role.Button, onClick = onPlay)
            .semantics { contentDescription = "Start logcat" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = tint)
    }
}

/** The banner text for [run], or null while running or before the first play. */
internal fun pausedBannerText(run: LogcatRun, pausedAt: String?): String? = when (run) {
    LogcatRun.Paused -> "Paused at ${pausedAt.orEmpty()}"
    LogcatRun.StoppedByLeaving -> "Stopped when you left the tab · Resume to catch up"
    LogcatRun.NotStarted, LogcatRun.Running -> null
}

/** The paused banner, sliding down from above the list and fading (320 ms). */
@Composable
internal fun LogcatPausedBanner(text: String?, motion: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Keeps the last text while the banner leaves.
    val last = remember { arrayOf("") }
    if (text != null) last[0] = text
    AnimatedVisibility(
        text != null,
        modifier,
        enter = if (motion) slideInVertically(tween(320, easing = EmphasizedDecelerate)) { -(it * 1.4f).toInt() } + fadeIn(tween(200)) else EnterTransition.None,
        exit = if (motion) slideOutVertically(tween(240, easing = EmphasizedAccelerate)) { -(it * 1.4f).toInt() } + fadeOut(tween(160)) else ExitTransition.None,
    ) {
        Row(
            Modifier.padding(top = 6.dp).clip(CircleShape).background(scheme.inverseSurface).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Pause, null, Modifier.size(14.dp), tint = scheme.inverseOnSurface)
            Spacer(Modifier.width(8.dp))
            Text(text ?: last[0], style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = scheme.inverseOnSurface, maxLines = 1)
        }
    }
}

/** "N new lines" rising 16 dp from the bottom and scaling from 0.8 (300 ms); [onClick] jumps to the end. */
@Composable
internal fun LogcatNewLinesPill(count: Int, visible: Boolean, motion: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val last = remember { intArrayOf(1) }
    if (count > 0) last[0] = count
    val n = if (count > 0) count else last[0]
    val rise = with(LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (motion) {
            slideInVertically(tween(300, easing = EmphasizedDecelerate)) { rise } +
                scaleIn(tween(300, easing = EmphasizedDecelerate), initialScale = 0.8f) + fadeIn(tween(200))
        } else EnterTransition.None,
        exit = if (motion) {
            slideOutVertically(tween(220, easing = EmphasizedAccelerate)) { rise } +
                scaleOut(tween(220, easing = EmphasizedAccelerate), targetScale = 0.8f) + fadeOut(tween(160))
        } else ExitTransition.None,
    ) {
        Row(
            Modifier.padding(bottom = 12.dp).height(32.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(scheme.primary)
                .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ArrowDownward, null, Modifier.size(16.dp), tint = scheme.onPrimary)
            Spacer(Modifier.width(6.dp))
            Text(
                if (n == 1) "1 new line" else "$n new lines",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
                color = scheme.onPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** The empty filter result, fading in (250 ms). */
@Composable
internal fun BoxScope.LogcatEmptyMessage(visible: Boolean, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.Center).padding(20.dp),
        enter = if (motion) fadeIn(tween(250)) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(200)) else ExitTransition.None,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp)
            Text("No lines match this filter yet.", style = style, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text("New lines that match appear here as they arrive.", style = style, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
