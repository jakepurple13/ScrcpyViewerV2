package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.logcat.LogLevel

private val IconShape = RoundedCornerShape(8.dp)

/**
 * Under the list: the live dot and state, clear, soft wrap and compact, "X of Y lines" (shown of all) and a bar of
 * the shown lines by level.
 */
@Composable
internal fun LogcatStatusBar(controller: LogcatController, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveIndicator(controller.run, motion)
        Spacer(Modifier.size(2.dp))
        StatusIcon(Icons.Filled.DeleteSweep, "Clear log", checked = null, motion) { controller.clear() }
        StatusIcon(Icons.AutoMirrored.Filled.WrapText, "Soft wrap", controller.wrap, motion) { controller.wrap = !controller.wrap }
        StatusIcon(Icons.Filled.ViewHeadline, "Compact view", controller.compact, motion) { controller.compact = !controller.compact }
        val counts = controller.levelCounts
        val shown = counts.values.sum()
        Text(
            buildAnnotatedString {
                val num = SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)
                withStyle(num) { append("%,d".format(shown)) }
                append(" of ")
                withStyle(num) { append("%,d".format(controller.totalLines)) }
                append(" lines")
            },
            style = style.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            LevelMix(counts, shown, motion, Modifier.widthIn(min = 60.dp, max = 220.dp).fillMaxWidth())
        }
    }
}

/** A dot and "Not running", "Live" (the dot green and blinking) or "Paused". */
@Composable
private fun LiveIndicator(run: LogcatRun, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val live = run == LogcatRun.Running
    val dot by animateColorAsState(if (live) LogcatColors.level(LogLevel.Info).first else scheme.outline, if (motion) tween(300) else snap())
    // Held as State and read in the layer, so the blink redraws the dot without recomposing.
    val blink = if (live && motion) {
        rememberInfiniteTransition().animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    } else {
        null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = blink?.value ?: 1f }.background(dot, CircleShape))
        Spacer(Modifier.size(6.dp))
        val text = when (run) {
            LogcatRun.NotStarted -> "Not running"
            LogcatRun.Running -> "Live"
            LogcatRun.Paused, LogcatRun.StoppedByLeaving -> "Paused"
        }
        AnimatedContent(
            text,
            transitionSpec = {
                if (motion) fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(120)))
                else EnterTransition.None.togetherWith(ExitTransition.None)
            },
        ) { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1) }
    }
}

/** A 32 dp icon button; with [checked] a toggle whose container fades to primaryContainer while on. */
@Composable
private fun StatusIcon(icon: ImageVector, description: String, checked: Boolean?, motion: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val on = checked == true
    val spec = if (motion) tween<Color>(150) else snap()
    val bg by animateColorAsState(
        when {
            on -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHigh
            else -> scheme.surfaceContainerHigh.copy(alpha = 0f)
        },
        spec,
    )
    val fg by animateColorAsState(
        when {
            on -> scheme.onPrimaryContainer
            hovered -> scheme.onSurface
            else -> scheme.onSurfaceVariant
        },
        spec,
    )
    val action = if (checked == null) {
        Modifier.clickable(source, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.toggleable(checked, source, indication = null, role = Role.Checkbox) { onClick() }
    }
    Box(
        Modifier.size(32.dp).clip(IconShape).background(bg).hoverable(source).then(action)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = fg)
    }
}

/** A 6 dp bar of the shown lines by level, each part's width easing to its share (500 ms). */
@Composable
private fun LevelMix(counts: Map<LogLevel, Int>, total: Int, motion: Boolean, modifier: Modifier) {
    Row(
        modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = "Shown lines by level" },
    ) {
        var used = 0f
        for (level in LogLevel.entries) {
            val share = if (total == 0) 0f else (counts[level] ?: 0).toFloat() / total
            val w by animateFloatAsState(
                share,
                if (motion) tween(500, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
            )
            if (w > 0.0005f) {
                used += w
                Box(Modifier.weight(w).fillMaxHeight().background(LogcatColors.level(level).first))
            }
        }
        // Parts are weights: the rest keeps them at their share while they grow from an empty bar.
        if (used < 0.9995f) Spacer(Modifier.weight(1f - used))
    }
}
