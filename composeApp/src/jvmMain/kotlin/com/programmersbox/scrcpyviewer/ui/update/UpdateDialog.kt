package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.OvershootEasing
import com.programmersbox.scrcpyviewer.ui.common.dialogEntrance
import com.programmersbox.scrcpyviewer.ui.common.expandFadeIn
import com.programmersbox.scrcpyviewer.ui.common.shrinkFadeOut
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.programmersbox.scrcpyviewer.ui.theme.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState

private const val BYTES_PER_MB = 1_000_000L

internal val Release.versionLabel: String get() = version

internal fun UpdateState.Downloading.progressText() = "Downloading… ${read / BYTES_PER_MB} of ${total / BYTES_PER_MB} MB"

/** Determinate once the total size is known, indeterminate before that. */
@Composable
internal fun DownloadProgressBar(downloading: UpdateState.Downloading, modifier: Modifier = Modifier) {
    if (downloading.total > 0) {
        LinearProgressIndicator(progress = { downloading.read.toFloat() / downloading.total }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier)
    }
}

/**
 * The update popup and the download progress, by [UpdateUiState.state]; other states show nothing.
 * Stateless: the caller decides when to show it. It cannot be dismissed while downloading or installing.
 */
@Composable
fun UpdateDialog(
    ui: UpdateUiState,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    when (val state = ui.state) {
        is UpdateState.Available ->
            OfferDialog(state.release, ui.previousFailure?.let { "Last update failed: $it" }, ui, onLater, onUpdate)
        is UpdateState.Failed -> (state.release ?: ui.available)?.let {
            OfferDialog(it, state.message, ui, onLater, onUpdate)
        }
        // One call site for both, so the same dialog plays the download-finished animation instead of reopening.
        is UpdateState.Downloading, is UpdateState.Installing -> ProgressDialog(state, onCancel)
        else -> Unit
    }
}

@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.dialogEntrance().width(400.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(24.dp)) { content() }
    }
}

@Composable
private fun OfferDialog(
    release: Release,
    error: String?,
    ui: UpdateUiState,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onLater) {
        DialogCard {
            Text("Update available", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Version ${release.versionLabel} is ready. You have ${ui.currentVersion}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!release.body.isNullOrBlank()) {
                Text(
                    release.body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .heightIn(max = 110.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.medium)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                // The release page, for notes that could not be fetched or are cut off above. Without self-update the
                // main button already opens it.
                if (ui.canSelfUpdate) {
                    TextButton(onClick = { uriHandler.openSafely(ui.releasePageUrl) }) { Text("Release notes") }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onLater) { Text("Later") }
                Button(onClick = if (ui.canSelfUpdate) onUpdate else ({ uriHandler.openSafely(ui.releasePageUrl) })) {
                    Text(if (ui.canSelfUpdate) "Update now" else "Download from GitHub")
                }
            }
        }
    }
}

internal const val INSTALLING_TEXT = "Download complete. Installing…"

/**
 * Downloading shows the bar, the numbers and Cancel. Once the download is done ([UpdateState.Installing]) the bar
 * collapses, a check pops in and draws itself, and the status text changes; there is nothing left to cancel.
 */
@Composable
private fun ProgressDialog(state: UpdateState, onCancel: () -> Unit) {
    val downloading = state as? UpdateState.Downloading
    val release = downloading?.release ?: (state as UpdateState.Installing).release
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        DialogCard {
            Text("Updating to ${release.versionLabel}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            // Keyed by phase, so the numbers ticking up during the download update in place.
            AnimatedContent(
                targetState = state,
                contentKey = { it is UpdateState.Installing },
                transitionSpec = {
                    val inSpec = tween<Float>(OPEN_MILLIS, delayMillis = CLOSE_MILLIS / 2, easing = EmphasizedDecelerate)
                    val inSlide = tween<IntOffset>(OPEN_MILLIS, delayMillis = CLOSE_MILLIS / 2, easing = EmphasizedDecelerate)
                    val outSpec = tween<Float>(CLOSE_MILLIS / 2, easing = EmphasizedAccelerate)
                    val outSlide = tween<IntOffset>(CLOSE_MILLIS / 2, easing = EmphasizedAccelerate)
                    (fadeIn(inSpec) + slideInVertically(inSlide) { it / 2 }) togetherWith
                        (fadeOut(outSpec) + slideOutVertically(outSlide) { -it / 3 })
                },
                label = "updateStatus",
            ) { phase ->
                Text(
                    (phase as? UpdateState.Downloading)?.progressText() ?: INSTALLING_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            DownloadProgress(downloading)
            Text(
                "Scrcpy Vwr will close and reopen. Your computer may ask for your password. Open mirror windows will close.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AnimatedVisibility(downloading != null, enter = expandFadeIn(), exit = shrinkFadeOut()) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

private val CheckCircleSize = 40.dp
private val BarHeight = 4.dp
private const val BAR_COLLAPSE_MILLIS = OPEN_MILLIS
private const val CHECK_POP_MILLIS = 340
private const val CHECK_DRAW_DELAY_MILLIS = 200
private const val CHECK_DRAW_MILLIS = 360

/**
 * The download bar while [downloading], then the "ready" check once it is null (done): the bar collapses toward its
 * center and fades, the circle pops in (overshooting) and the check's stroke draws itself. Done on first appearance
 * shows the finished check without animating. Animated values are read only in layout, layer and draw.
 */
@Composable
private fun DownloadProgress(downloading: UpdateState.Downloading?) {
    val done = downloading == null
    val startDone = remember { done }
    val barGone = remember { Animatable(if (startDone) 1f else 0f) }
    val circle = remember { Animatable(if (startDone) 1f else 0f) }
    val check = remember { Animatable(if (startDone) 1f else 0f) }
    // The last numbers, so the collapsing bar keeps its fill instead of jumping.
    val lastDownloadHolder = remember { arrayOfNulls<UpdateState.Downloading>(1) }
    if (downloading != null) lastDownloadHolder[0] = downloading
    val lastDownload = lastDownloadHolder[0]
    LaunchedEffect(done) {
        if (!done || check.value == 1f) return@LaunchedEffect
        barGone.animateTo(1f, tween(BAR_COLLAPSE_MILLIS, easing = EmphasizedAccelerate))
        launch { circle.animateTo(1f, tween(CHECK_POP_MILLIS, easing = OvershootEasing)) }
        // The delay is part of the animation (frame time), not a coroutine delay.
        check.animateTo(1f, tween(CHECK_DRAW_MILLIS, delayMillis = CHECK_DRAW_DELAY_MILLIS, easing = EmphasizedDecelerate))
    }
    Box(
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            // Grows from the bar's height to the circle's as the circle pops (not past it on the overshoot).
            val grow = circle.value.coerceIn(0f, 1f)
            val height = (BarHeight.toPx() + (CheckCircleSize.toPx() - BarHeight.toPx()) * grow).roundToInt()
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, height) { placeable.place(0, 0) }
        },
        contentAlignment = Alignment.Center,
    ) {
        val bar = Modifier.fillMaxWidth().graphicsLayer {
            // Collapses toward its center.
            val gone = barGone.value
            scaleX = 1f - gone
            alpha = 1f - gone
        }
        when (lastDownload) {
            null -> if (!startDone) LinearProgressIndicator(bar)
            else -> DownloadProgressBar(lastDownload, bar)
        }
        val circleColor = MaterialTheme.colorScheme.primaryContainer
        val checkColor = MaterialTheme.colorScheme.onPrimaryContainer
        Canvas(
            Modifier.requiredSize(CheckCircleSize).graphicsLayer {
                val s = circle.value
                scaleX = s
                scaleY = s
                alpha = s.coerceIn(0f, 1f)
            },
        ) {
            if (circle.value == 0f) return@Canvas
            drawCircle(circleColor)
            val trim = check.value
            if (trim <= 0f) return@Canvas
            val unit = size.width / 40f
            val mark = Path().apply {
                moveTo(12f * unit, 20.5f * unit)
                lineTo(17.5f * unit, 26f * unit)
                lineTo(28.5f * unit, 14.5f * unit)
            }
            val measure = PathMeasure().apply { setPath(mark, false) }
            val drawn = Path()
            measure.getSegment(0f, measure.length * trim, drawn, true)
            drawPath(drawn, checkColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
