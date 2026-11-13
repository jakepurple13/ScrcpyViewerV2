package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.logcat.queryTerms
import com.programmersbox.scrcpyviewer.logcat.withTerm
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.systemReducesMotion
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle

/** True on the Play button while its pulse rings draw (tests read it). */
internal val LogcatPulseKey = SemanticsPropertyKey<Boolean>("LogcatPulse")

private val ButtonShape = RoundedCornerShape(8.dp)
private const val PULSE_MILLIS = 900

/**
 * The sheet's Logcat tab for [controller], in a sheet [sheetWidth] wide: the filter and Play/Pause, the chips, the
 * list and the status bar. [motion] is false under the OS's reduce motion.
 *
 * Keys, with focus anywhere in the tab ([logcatKeyAction]; ⌘ on macOS per [isMac], Ctrl elsewhere): Space plays or
 * pauses (once per press; not in the filter field or on a control that takes Space itself), ⌘F focuses the filter with its text selected, ⌘K clears the list. The sheet
 * holds focus, so none of them reach the device; Logcat's own window sends nothing to the device at all.
 */
@Composable
fun LogcatTab(
    controller: LogcatController,
    sheetWidth: Dp,
    motion: Boolean = remember { !systemReducesMotion() },
    isMac: Boolean = Os.isMac,
    /** In Logcat's own window: the pop-out button moves it back to the sheet instead. */
    inWindow: Boolean = false,
    /** Makes the tab itself focusable through this, inside its key handlers (the own window focuses it on open). */
    focusRequester: FocusRequester? = null,
) {
    val filterFocus = remember { LogcatFilterFocus() }
    // True from a Space press that toggled until its release: the key's auto-repeat presses don't toggle again.
    val spaceHeld = remember { BooleanArray(1) }
    Column(
        Modifier.fillMaxSize()
            .onFocusChanged { if (!it.hasFocus) spaceHeld[0] = false }
            // ⌘F and ⌘K before anything in the tab, the filter field included.
            .onPreviewKeyEvent { event ->
                when (logcatKeyAction(event, isMac, inField = filterFocus.focused)) {
                    LogcatKey.FocusFilter -> filterFocus.focusAndSelectAll()
                    LogcatKey.Clear -> controller.clear()
                    LogcatKey.TogglePlay, null -> return@onPreviewKeyEvent false
                }
                true
            }
            // Space after the focused control: a chip, toggle or button that takes Space keeps it (the Play
            // button's own click toggles); the list and the tab's plain areas get play and pause.
            .onKeyEvent { event ->
                if (logcatKeyAction(event, isMac, inField = filterFocus.focused) != LogcatKey.TogglePlay) {
                    return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    if (!spaceHeld[0]) controller.toggle()
                    spaceHeld[0] = true
                } else {
                    spaceHeld[0] = false
                }
                true
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                LogcatFilterField(controller, motion, Modifier.weight(1f), filterFocus)
                Spacer(Modifier.width(6.dp))
                PlayPauseButton(controller, motion)
                Spacer(Modifier.width(2.dp))
                PopOutButton(controller, inWindow, motion)
            }
            ChipsRow(controller, motion)
        }
        LogcatList(controller, sheetWidth, motion, Modifier.weight(1f).fillMaxWidth())
        LogcatStatusBar(controller, motion)
    }
}

/**
 * Play / Pause / Resume, 104 dp wide: filled primary while stopped, tonal while running (color fades 250 ms), the
 * icon morphing play ⇄ pause (rotate, scale, fade, 280 ms). Each [LogcatController.nudge] bump pulses two rings.
 */
@Composable
private fun PlayPauseButton(controller: LogcatController, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val run = controller.run
    val running = run == LogcatRun.Running
    val colorSpec = if (motion) tween<androidx.compose.ui.graphics.Color>(250) else snap()
    val container by animateColorAsState(if (running) scheme.surfaceContainerHighest else scheme.primary, colorSpec)
    val content by animateColorAsState(if (running) scheme.onSurface else scheme.onPrimary, colorSpec)
    // Soft draws every filled button on the surface with the container color as its text: keep that readable.
    val softAccent by animateColorAsState(if (running) scheme.onSurfaceVariant else scheme.primary, colorSpec)
    val colors = if (currentStyle == ThemeStyle.Soft) {
        ButtonDefaults.buttonColors(containerColor = softAccent)
    } else {
        ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    }
    val label = when (run) {
        LogcatRun.NotStarted -> "Play"
        LogcatRun.Running -> "Pause"
        LogcatRun.Paused, LogcatRun.StoppedByLeaving -> "Resume"
    }
    val description = when (run) {
        LogcatRun.NotStarted -> "Start logcat"
        LogcatRun.Running -> "Pause logcat"
        LogcatRun.Paused, LogcatRun.StoppedByLeaving -> "Resume logcat"
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Button(
            onClick = controller::toggle,
            modifier = Modifier.width(104.dp).height(36.dp).playPulse(controller, motion)
                .semantics { contentDescription = description },
            shape = ButtonShape,
            colors = colors,
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            PlayPauseIcon(running, motion)
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                label,
                transitionSpec = {
                    if (motion) fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(120)))
                    else EnterTransition.None.togetherWith(ExitTransition.None)
                },
            ) { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

/** Play and pause stacked: the leaving one turns 90° and shrinks to 0.4 as it fades, the arriving one turns in. */
@Composable
private fun PlayPauseIcon(running: Boolean, motion: Boolean) {
    val p by animateFloatAsState(
        if (running) 1f else 0f,
        if (motion) tween(280, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(),
    )
    Box(Modifier.size(18.dp)) {
        Icon(
            Icons.Filled.PlayArrow, contentDescription = null,
            Modifier.size(18.dp).graphicsLayer {
                rotationZ = 90f * p
                scaleX = 1f - 0.6f * p
                scaleY = 1f - 0.6f * p
                alpha = 1f - p
            },
        )
        Icon(
            Icons.Filled.Pause, contentDescription = null,
            Modifier.size(18.dp).graphicsLayer {
                rotationZ = -90f * (1f - p)
                scaleX = 0.4f + 0.6f * p
                scaleY = 0.4f + 0.6f * p
                alpha = p
            },
        )
    }
}

/**
 * "Open in new window" in the sheet, "Move back to the sheet" in the own window ([inWindow]). Right after a move the
 * icon swaps in from the other place's (open ⇄ dock, scale and fade, 280 ms).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PopOutButton(controller: LogcatController, inWindow: Boolean, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val target = if (inWindow) 1f else 0f
    // 0 shows open-in-new, 1 the dock icon.
    val p = remember {
        // Taken by the first button composed after the move: the one in the panel's new place.
        val swap = controller.moved && motion
        controller.moved = false
        Animatable(if (swap) 1f - target else target)
    }
    LaunchedEffect(target) { p.animateTo(target, if (motion) tween(280, easing = EmphasizedDecelerate) else snap()) }
    val label = if (inWindow) "Move back to the sheet" else "Open in new window"
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = scheme.inverseSurface) {
                Text(
                    label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.inverseOnSurface,
                )
            }
        },
    ) {
        Box(
            Modifier.size(36.dp).clip(ButtonShape)
                .clickable(role = Role.Button) { if (inWindow) controller.dock() else controller.popOut() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                Modifier.size(18.dp).graphicsLayer {
                    val s = 1f - 0.6f * p.value
                    scaleX = s
                    scaleY = s
                    alpha = 1f - p.value
                },
                tint = scheme.onSurfaceVariant,
            )
            Icon(
                Icons.AutoMirrored.Filled.Input, contentDescription = null,
                Modifier.size(18.dp).graphicsLayer {
                    val s = 0.4f + 0.6f * p.value
                    scaleX = s
                    scaleY = s
                    alpha = p.value
                },
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Two 900 ms rings spreading 10 dp from the button and fading, once per nudge bump; none without [motion]. */
@Composable
private fun Modifier.playPulse(controller: LogcatController, motion: Boolean): Modifier {
    val ring = remember { Animatable(0f) }
    var pulsing by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val nudge = controller.nudge
    LaunchedEffect(nudge) {
        if (nudge <= controller.nudgeShown) return@LaunchedEffect
        controller.nudgeShown = nudge
        if (!motion) return@LaunchedEffect
        pulsing = true
        try {
            repeat(2) {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(PULSE_MILLIS, easing = EmphasizedDecelerate))
            }
        } finally {
            pulsing = false
        }
    }
    return semantics { this[LogcatPulseKey] = pulsing }.drawBehind {
        if (!pulsing) return@drawBehind
        val p = ring.value
        val spread = 10.dp.toPx() * p
        val r = 8.dp.toPx() + spread
        drawRoundRect(
            color.copy(alpha = 0.6f * (1f - p)),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + 2 * spread, size.height + 2 * spread),
            cornerRadius = CornerRadius(r, r),
        )
    }
}

/** Picked app, Warnings and up, Crashes: each shows checked while its term is in the query, and toggles it. */
@Composable
private fun ChipsRow(controller: LogcatController, motion: Boolean) {
    val terms = queryTerms(controller.query)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, term) in listOf("Picked app" to "package:mine", "Warnings and up" to "level:warn", "Crashes" to "is:crash")) {
            val on = term in terms
            QueryChip(label, on, motion) { controller.query = withTerm(controller.query, term, !on) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { MineLabel(controller.mine, motion) }
    }
}

@Composable
private fun QueryChip(label: String, on: Boolean, motion: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spec = if (motion) tween<androidx.compose.ui.graphics.Color>(200) else snap()
    val container by animateColorAsState(if (on) scheme.primaryContainer else scheme.primaryContainer.copy(alpha = 0f), spec)
    val text by animateColorAsState(if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant, spec)
    val edge by animateColorAsState(if (on) scheme.outlineVariant.copy(alpha = 0f) else scheme.outlineVariant, spec)
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            // The check grows from 0 to 16 dp as the chip turns on.
            AnimatedVisibility(
                visible = on,
                enter = if (motion) expandHorizontally(tween(250, easing = EmphasizedDecelerate)) + scaleIn(tween(250), initialScale = 0.4f) + fadeIn(tween(160)) else EnterTransition.None,
                exit = if (motion) shrinkHorizontally(tween(200, easing = EmphasizedAccelerate)) + scaleOut(tween(200), targetScale = 0.4f) + fadeOut(tween(120)) else ExitTransition.None,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container, selectedContainerColor = container,
            labelColor = text, selectedLabelColor = text,
            iconColor = text, selectedLeadingIconColor = text,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = edge, selectedBorderColor = edge),
    )
}

/** "mine = <package>" for the app picked in Apps, or how to pick one. */
@Composable
private fun MineLabel(mine: String?, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(
        mine,
        transitionSpec = {
            if (motion) fadeIn(tween(220, delayMillis = 60)).togetherWith(fadeOut(tween(120)))
            else EnterTransition.None.togetherWith(ExitTransition.None)
        },
    ) { pkg ->
        val style = MaterialTheme.typography.bodySmall
        if (pkg == null) {
            Text("Pick an app in Apps", style = style, color = scheme.onSurfaceVariant, maxLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(LogcatColors.level(LogLevel.Info).first, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("mine =", style = style, color = scheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Text(
                    pkg,
                    style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
