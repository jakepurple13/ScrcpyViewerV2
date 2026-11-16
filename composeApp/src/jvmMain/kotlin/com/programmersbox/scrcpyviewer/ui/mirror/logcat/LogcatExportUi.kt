package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.logcat.ExportFormat
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.onPickerThread
import com.programmersbox.scrcpyviewer.ui.common.revealInFolder
import com.programmersbox.scrcpyviewer.ui.common.revealLabel
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import java.nio.file.Path

private val ExportShape = RoundedCornerShape(8.dp)
private val MenuWidth = 300.dp

/** Asks for the export's target with the native save dialog ("Export logs"), on the picker's thread. */
val defaultExportPick: suspend (String, String) -> Path? = { name, ext ->
    onPickerThread { nativeFileDialogs().save("Export logs", name, ext) }
}

/**
 * The status bar's text for [state]: `Exporting 4,120 of 12,340 lines…`, `Saved <file name>`, `Export canceled ·
 * partial file removed`; null while idle (the line counts show).
 */
internal fun exportStatusText(state: LogcatExport): String? = when (state) {
    LogcatExport.Idle -> null
    is LogcatExport.Running -> "Exporting %,d of %,d lines…".format(state.done, state.total)
    is LogcatExport.Done -> "Saved ${state.path.fileName}"
    LogcatExport.Canceled -> "Export canceled · partial file removed"
}

/**
 * Export, 36 dp: opens the format menu ([ExportMenu]), which exports through [pick]. Download, progress ring and
 * check are stacked: running, the download drops 8 dp, shrinks to 0.7 and fades (280 ms) as the ring grows in from
 * 0.4, turning once per 1,400 ms; done, the ring goes and the check pops 0.3 → 1.18 → 1 (420 ms); canceled, the
 * download comes back. While running the button cancels ("Cancel export"): on hover or focus the ring fades to 0.22
 * and a close icon turns in (180–260 ms). Tonal while the menu is open or an export shows (150 ms fade); dimmed to
 * 0.38 with nothing to export.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LogcatExportButton(controller: LogcatController, motion: Boolean, pick: suspend (String, String) -> Path?) {
    val scheme = MaterialTheme.colorScheme
    val state = controller.export
    val running = state is LogcatExport.Running
    val done = state is LogcatExport.Done
    val hasLines = controller.totalLines > 0
    var menu by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val tonal = menu || state !is LogcatExport.Idle
    val colorSpec = if (motion) tween<Color>(150) else snap()
    val container by animateColorAsState(if (tonal) scheme.secondaryContainer else scheme.secondaryContainer.copy(alpha = 0f), colorSpec)
    val tint by animateColorAsState(if (tonal) scheme.onSecondaryContainer else scheme.onSurfaceVariant, colorSpec)
    val dim by animateFloatAsState(if (hasLines || running) 1f else 0.38f, if (motion) tween(150) else snap())

    val swap = if (motion) tween<Float>(280, easing = EmphasizedDecelerate) else snap()
    val download by animateFloatAsState(if (running || done) 0f else 1f, swap)
    val ring by animateFloatAsState(if (running) 1f else 0f, swap)
    val hint by animateFloatAsState(
        if (running && (hovered || focused)) 1f else 0f,
        if (!motion) snap() else if (running && (hovered || focused)) tween(260, easing = EmphasizedDecelerate) else tween(180),
    )
    val check = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(done, motion) {
        when {
            !motion -> check.snapTo(if (done) 1f else 0f)
            done -> {
                check.snapTo(0.3f)
                check.animateTo(1f, keyframes {
                    durationMillis = 420
                    0.3f at 0 using EmphasizedDecelerate
                    1.18f at 240
                    1f at 420
                })
            }
            else -> check.animateTo(0f, tween(280, easing = EmphasizedDecelerate))
        }
    }
    // The ring keeps the last fraction while it goes; done fills it.
    val fraction = remember { floatArrayOf(0f) }
    when (state) {
        is LogcatExport.Running -> fraction[0] = if (state.total == 0) 0f else state.done.toFloat() / state.total
        is LogcatExport.Done -> fraction[0] = 1f
        else -> Unit
    }
    val progress by animateFloatAsState(fraction[0], if (motion) tween(80, easing = LinearEasing) else snap())
    val spin = remember { Animatable(0f) }
    LaunchedEffect(running, motion) {
        if (!running || !motion) return@LaunchedEffect
        spin.snapTo(spin.value % 360f)
        spin.animateTo(spin.value + 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart))
    }

    val description = if (running) "Cancel export" else "Export logs"
    val tooltip = when {
        running -> "Cancel export"
        !hasLines -> "No lines to export"
        else -> "Export logs"
    }
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = scheme.inverseSurface) {
                Text(
                    tooltip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.inverseOnSurface,
                )
            }
        },
    ) {
        Box(
            Modifier.size(36.dp).clip(ExportShape).background(container).hoverable(source)
                .clickable(source, LocalIndication.current, enabled = running || hasLines, role = Role.Button) {
                    when (controller.export) {
                        is LogcatExport.Running -> controller.cancelExport()
                        LogcatExport.Idle -> menu = true
                        else -> Unit // done or canceled: shown a moment, then idle again
                    }
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { alpha = dim }, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Download, contentDescription = null,
                    Modifier.size(18.dp).graphicsLayer {
                        translationY = 8.dp.toPx() * (1f - download)
                        val s = 0.7f + 0.3f * download
                        scaleX = s
                        scaleY = s
                        alpha = download
                    },
                    tint = tint,
                )
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(22.dp).graphicsLayer {
                        val s = (0.4f + 0.6f * ring) * (1f + 0.15f * hint)
                        scaleX = s
                        scaleY = s
                        alpha = ring * (1f - 0.78f * hint)
                        rotationZ = spin.value
                    },
                    color = scheme.primary,
                    strokeWidth = 2.5.dp,
                    trackColor = scheme.primary.copy(alpha = 0.18f),
                )
                Icon(
                    Icons.Filled.Close, contentDescription = null,
                    Modifier.size(16.dp).graphicsLayer {
                        val s = 0.5f + 0.5f * hint
                        scaleX = s
                        scaleY = s
                        rotationZ = -45f * (1f - hint)
                        alpha = hint
                    },
                    tint = tint,
                )
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    Modifier.size(18.dp).graphicsLayer {
                        scaleX = check.value
                        scaleY = check.value
                        alpha = check.value.coerceIn(0f, 1f)
                    },
                    tint = LogcatColors.level(LogLevel.Info).first,
                )
            }
            // End-aligned under the button: the menu's fixed width less the button's, to the left.
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                modifier = Modifier.width(MenuWidth),
                offset = DpOffset(36.dp - MenuWidth, 4.dp),
            ) {
                ExportMenu(controller.totalLines, controller.query, motion) { format ->
                    menu = false
                    controller.export(format, pick)
                }
            }
        }
    }
}

/**
 * The menu's content: `Export N lines · all in memory`, then Android Studio (with the filter it saves, in a chip) and
 * Plain text. Each part fades and rises 4 dp, 30 / 70 / 110 ms after opening.
 */
@Composable
private fun ExportMenu(lines: Int, filter: String, motion: Boolean, onPick: (ExportFormat) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    Staggered(30, motion) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Export %,d lines".format(lines), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text("all in memory", style = small, color = scheme.onSurfaceVariant)
        }
    }
    Staggered(70, motion) {
        DropdownMenuItem(
            text = {
                ExportItemText(ExportFormat.Studio.label, "Opens with Logcat › Import Logs from File", "Saves the filter; Studio applies it on import") {
                    val blank = filter.isBlank()
                    Text(
                        if (blank) "No filter" else filter,
                        Modifier.padding(top = 4.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = small.copy(fontFamily = if (blank) small.fontFamily else FontFamily.Monospace, fontSize = 11.5.sp),
                        color = if (blank) scheme.onSurfaceVariant else scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            onClick = { onPick(ExportFormat.Studio) },
        )
    }
    Staggered(110, motion) {
        DropdownMenuItem(
            text = { ExportItemText(ExportFormat.Text.label, "threadtime lines, same as adb logcat", "No filter saved") },
            onClick = { onPick(ExportFormat.Text) },
        )
    }
}

/** A menu item's title and two lines under it, then [extra]. */
@Composable
private fun ExportItemText(title: String, first: String, second: String, extra: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
        Text(first, style = small, color = scheme.onSurfaceVariant)
        Text(second, style = small, color = scheme.onSurfaceVariant)
        extra()
    }
}

/** [content] fading in and rising 4 dp, [delayMillis] after it composes (220 ms); at once without [motion]. */
@Composable
private fun Staggered(delayMillis: Int, motion: Boolean, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) p.animateTo(1f, tween(220, delayMillis = delayMillis, easing = EmphasizedDecelerate))
    }
    Box(Modifier.graphicsLayer {
        alpha = p.value
        translationY = 4.dp.toPx() * (1f - p.value)
    }) { content() }
}

/**
 * A 2 dp primary bar across the list's top edge, as wide as the export's progress (80 ms linear steps), fading out
 * (300 ms) once it isn't running.
 */
@Composable
internal fun LogcatExportBar(controller: LogcatController, motion: Boolean, modifier: Modifier) {
    val state = controller.export
    val running = state is LogcatExport.Running
    val fraction = remember { floatArrayOf(0f) }
    when (state) {
        is LogcatExport.Running -> fraction[0] = if (state.total == 0) 0f else state.done.toFloat() / state.total
        is LogcatExport.Done -> fraction[0] = 1f
        else -> Unit
    }
    val width by animateFloatAsState(fraction[0], if (motion) tween(80, easing = LinearEasing) else snap())
    val alpha by animateFloatAsState(if (running) 1f else 0f, if (motion) tween(300) else snap())
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier.height(2.dp).drawBehind {
            if (alpha > 0f) drawRect(color, size = Size(size.width * width, size.height), alpha = alpha)
        },
    )
}

/**
 * The finished export's toast, bottom center: `Saved N lines to <file>` with [revealLabel] (shows it with
 * [reveal]), or `Export canceled`. On inverseSurface; rises 24 dp and fades in (340 ms), drops out (220 ms).
 */
@Composable
internal fun LogcatExportToast(
    controller: LogcatController,
    motion: Boolean,
    modifier: Modifier,
    platform: OpenPlatform = OpenPlatform.current(),
    reveal: (Path) -> Unit = { revealInFolder(it) },
) {
    val scheme = MaterialTheme.colorScheme
    val state = controller.export
    val shown = state is LogcatExport.Done || state is LogcatExport.Canceled
    // What it shows, kept while it goes.
    val held = remember { arrayOfNulls<LogcatExport>(1) }
    if (shown) held[0] = state
    val rise = with(LocalDensity.current) { 24.dp.roundToPx() }
    AnimatedVisibility(
        shown,
        modifier,
        enter = if (motion) {
            slideInVertically(tween(340, easing = EmphasizedDecelerate)) { rise } + fadeIn(tween(340, easing = EmphasizedDecelerate))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            slideOutVertically(tween(220, easing = EmphasizedAccelerate)) { rise } + fadeOut(tween(220, easing = EmphasizedAccelerate))
        } else {
            ExitTransition.None
        },
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface, shadowElevation = 6.dp) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp).height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (val shownState = held[0]) {
                    is LogcatExport.Done -> {
                        Text(
                            "Saved %,d lines to %s".format(shownState.lines, shownState.path.fileName),
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { reveal(shownState.path) },
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.inversePrimary),
                        ) { Text(revealLabel(platform)) }
                    }
                    else -> {
                        Text("Export canceled", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
