package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ViewHeadline
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.stateDescription
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate

private val IconShape = RoundedCornerShape(8.dp)

/**
 * Under the list: the live dot and state, clear, soft wrap and compact, "X of Y lines" (shown of all) and a bar of
 * the shown lines by level. While an export shows, its [exportStatusText] takes the counts' place: the new text rises
 * 10 dp from below as the old leaves 10 dp up (200 ms fade, 260 ms slide).
 *
 * When [compact] (under 420 dp) the level bar becomes a 3 dp strip along the bar's top edge, Wrap and Compact fold
 * into a ⋮ menu (Clear stays) and the counts read "shown/total". Folds open and close on the layout spring with
 * [glide]; without it they only fade (150 ms).
 */
@Composable
internal fun LogcatStatusBar(controller: LogcatController, motion: Boolean, compact: Boolean = false, glide: Boolean = motion) {
    val counts = controller.levelCounts
    val shown = counts.values.sum()
    Column(Modifier.fillMaxWidth()) {
        Fold(compact, vertical = true, glide) {
            LevelMix(counts, shown, motion, Modifier.fillMaxWidth(), height = 3.dp, rounded = false)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Gaps are spacers, not the row's arrangement, so each fold takes its gap with it.
            LiveIndicator(controller.run, motion)
            Spacer(Modifier.size(18.dp))
            StatusIcon(Icons.Filled.DeleteSweep, "Clear log", checked = null, motion) { controller.clear() }
            Fold(!compact, vertical = false, glide) {
                Row {
                    Spacer(Modifier.size(8.dp))
                    StatusIcon(Icons.AutoMirrored.Filled.WrapText, "Soft wrap", controller.wrap, motion) { controller.wrap = !controller.wrap }
                    Spacer(Modifier.size(8.dp))
                    StatusIcon(Icons.Filled.ViewHeadline, "Compact view", controller.compact, motion) { controller.compact = !controller.compact }
                }
            }
            Spacer(Modifier.size(8.dp))
            CountsText(controller, shown, compact, motion)
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Fold(!compact, vertical = false, glide) {
                    LevelMix(counts, shown, motion, Modifier.widthIn(min = 60.dp, max = 220.dp).fillMaxWidth())
                }
            }
            Fold(compact, vertical = false, glide) {
                Row {
                    Spacer(Modifier.size(8.dp))
                    StatusMenu(controller, motion)
                }
            }
        }
    }
}

/**
 * [content] while [shown]: it grows in and out along its width (or with [vertical], its height) on the layout spring
 * and fades (150 ms) with [glide]; otherwise it only fades.
 */
@Composable
private fun Fold(shown: Boolean, vertical: Boolean, glide: Boolean, content: @Composable () -> Unit) {
    val size = if (glide) layoutSpring<IntSize>() else snap()
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(150)) + if (vertical) expandVertically(size) else expandHorizontally(size),
        exit = fadeOut(tween(150)) + if (vertical) shrinkVertically(size) else shrinkHorizontally(size),
    ) { content() }
}

/** ⋮ with Wrap lines and Compact view, each checked while on; it opens under the button, end-aligned. */
@Composable
private fun StatusMenu(controller: LogcatController, motion: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        StatusIcon(Icons.Filled.MoreVert, "More status options", checked = null, motion) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuToggle("Wrap lines", controller.wrap) {
                open = false
                controller.wrap = !controller.wrap
            }
            MenuToggle("Compact view", controller.compact) {
                open = false
                controller.compact = !controller.compact
            }
        }
    }
}

@Composable
private fun MenuToggle(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            Box(Modifier.size(18.dp)) { if (checked) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
        },
        modifier = Modifier.semantics { stateDescription = if (checked) "On" else "Off" },
    )
}

/**
 * "[shown] of N lines", or the export's [exportStatusText] while one shows. Its own composable, so export progress
 * recomposes only this. Keyed on the export's stage: progress and new lines update the text in place. When [compact]
 * the counts read "[shown]/N".
 */
@Composable
private fun CountsText(controller: LogcatController, shown: Int, compact: Boolean, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    val shift = with(LocalDensity.current) { 10.dp.roundToPx() }
    AnimatedContent(
        controller.export,
        contentKey = { it::class },
        transitionSpec = {
            if (motion) {
                (slideInVertically(tween(260, easing = EmphasizedDecelerate)) { shift } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically(tween(260, easing = EmphasizedDecelerate)) { -shift } + fadeOut(tween(200)))
                    .using(SizeTransform(clip = false))
            } else {
                EnterTransition.None.togetherWith(ExitTransition.None)
            }
        },
    ) { export ->
        val status = exportStatusText(export)
        Text(
            if (status != null) {
                AnnotatedString(status)
            } else {
                buildAnnotatedString {
                    val num = SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)
                    withStyle(num) { append("%,d".format(shown)) }
                    append(if (compact) "/" else " of ")
                    withStyle(num) { append("%,d".format(controller.totalLines)) }
                    if (!compact) append(" lines")
                }
            },
            style = style.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A dot and "Not running", "Live" (the dot green and blinking) or "Paused". */
@Composable
private fun LiveIndicator(run: LogcatRun, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val live = run == LogcatRun.Running
    val dot by animateColorAsState(if (live) LogcatColors.level(LogLevel.Info).first else scheme.outline, if (motion) tween(300) else snap())
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogcatLiveDot(dot, 8.dp, blinking = live, motion = motion)
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

/**
 * A bar [height] tall (6 dp, ends [rounded]) of the shown lines by level, each part's width easing to its share
 * (500 ms).
 */
@Composable
private fun LevelMix(
    counts: Map<LogLevel, Int>,
    total: Int,
    motion: Boolean,
    modifier: Modifier,
    height: Dp = 6.dp,
    rounded: Boolean = true,
) {
    Row(
        modifier.height(height).then(if (rounded) Modifier.clip(RoundedCornerShape(height / 2)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
