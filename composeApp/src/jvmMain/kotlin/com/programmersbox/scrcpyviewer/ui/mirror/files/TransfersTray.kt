package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.formatSize
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import java.nio.file.Path

/** A finished row stays this long before it collapses out. */
internal const val DONE_ROW_MILLIS = 2_500L

private val ROW_RISE = 8.dp
private val TRAY_ROWS_MAX_HEIGHT = 168.dp
private val DONE_GREEN = Color(0xFF34A853)

/**
 * The transfers docked under the Files list: a header line ("Transfers · 2 running", "Transfers · done", or the idle
 * hint) with a collapse chevron, then a row per job in [jobs]. A finished row turns green and calls [onDismiss] after
 * [doneMillis] (frame time, so it follows the window's clock); failures stay until dismissed. The chevron rotates and
 * the rows' height animates with the open timing; [motion] false drops every animation.
 */
@Composable
internal fun TransfersTray(
    jobs: List<TransferJob>,
    pushTarget: String,
    downloads: Path,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = true,
    doneMillis: Long = DONE_ROW_MILLIS,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    val running = jobs.count { it.active }
    val chevron by animateFloatAsState(if (expanded) 0f else -90f, if (motion) tween(OPEN_MILLIS, easing = EmphasizedDecelerate) else snap())
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth()
                    .then(if (jobs.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) { expanded = !expanded })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        jobs.isEmpty() -> "Nothing transferring. Pulls, pushes and installs show here."
                        running > 0 -> "Transfers · $running running"
                        else -> "Transfers · done"
                    },
                    Modifier.weight(1f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (jobs.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (jobs.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ExpandMore, if (expanded) "Collapse transfers" else "Expand transfers",
                        Modifier.size(20.dp).graphicsLayer { rotationZ = chevron },
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                expanded && jobs.isNotEmpty(),
                enter = if (motion) expandVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) + fadeIn(tween(OPEN_MILLIS)) else EnterTransition.None,
                exit = if (motion) shrinkVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) + fadeOut(tween(OPEN_MILLIS)) else ExitTransition.None,
            ) {
                Column(Modifier.heightIn(max = TRAY_ROWS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                    for (job in jobs) {
                        key(job.id) { TransferRow(job, pushTarget, downloads, motion, doneMillis, onCancel, onDismiss) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    job: TransferJob,
    pushTarget: String,
    downloads: Path,
    motion: Boolean,
    doneMillis: Long,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rise = with(LocalDensity.current) { ROW_RISE.roundToPx() }
    // Rises in on its first frame; leaves (height to 0 with the close timing) once its time is up.
    val visible = remember { MutableTransitionState(!motion) }.apply { targetState = true }
    var leaving by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val finished = job.state == JobState.Done || job.state == JobState.Cancelled
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        awaitFrames(doneMillis)
        leaving = true
        if (motion) awaitFrames(CLOSE_MILLIS.toLong())
        currentDismiss(job.id)
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = if (motion) slideInVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { rise } + fadeIn(tween(OPEN_MILLIS)) else EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        AnimatedVisibility(
            !leaving,
            enter = EnterTransition.None,
            exit = if (motion) shrinkVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) + fadeOut(tween(CLOSE_MILLIS)) else ExitTransition.None,
        ) {
            val name = job.request.name
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(directionIcon(job), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, Modifier.weight(1f, fill = false), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "· " + destinationLabel(job, pushTarget, downloads),
                            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    val done = job.state == JobState.Done
                    val total = job.total
                    val barColor = if (done) DONE_GREEN else colors.primary
                    when {
                        done -> LinearProgressIndicator({ 1f }, Modifier.fillMaxWidth(), color = barColor, trackColor = colors.surfaceVariant)
                        job.state is JobState.Failed || job.state == JobState.Cancelled ->
                            LinearProgressIndicator({ if (total != null && total > 0) (job.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth(), color = colors.outline, trackColor = colors.surfaceVariant)
                        total == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = barColor, trackColor = colors.surfaceVariant)
                        else -> LinearProgressIndicator(
                            { if (total > 0) (job.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f },
                            Modifier.fillMaxWidth(), color = barColor, trackColor = colors.surfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (done) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = DONE_GREEN)
                        val status = statusText(job)
                        if (status.isNotEmpty()) {
                            Text(
                                status, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = when {
                                    done -> DONE_GREEN
                                    job.state is JobState.Failed -> colors.error
                                    else -> colors.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                if (job.state is JobState.Failed) {
                    IconButton({ onDismiss(job.id) }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Dismiss $name", Modifier.size(16.dp))
                    }
                } else if (job.active) {
                    IconButton({ onCancel(job.id) }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Cancel $name", Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Waits [millis] of frame time. */
private suspend fun awaitFrames(millis: Long) {
    var start = -1L
    while (true) {
        val now = withFrameNanos { it }
        if (start < 0) start = now
        if ((now - start) / 1_000_000 >= millis) return
    }
}

private fun directionIcon(job: TransferJob): ImageVector = when {
    job.request.opensAfter -> Icons.AutoMirrored.Filled.OpenInNew
    job.request.kind == TransferKind.Push -> Icons.Filled.FileUpload
    job.request.kind == TransferKind.Pull -> Icons.Filled.FileDownload
    else -> Icons.Filled.Android
}

/** "To Downloads", "To Internal storage", "Opening", "Installing". */
internal fun destinationLabel(job: TransferJob, pushTarget: String, downloads: Path): String {
    val r = job.request
    return when {
        r.opensAfter -> "Opening"
        r.kind == TransferKind.Push -> "To " + folderLabel(r.deviceDir ?: pushTarget)
        r.kind == TransferKind.Pull -> "To " + if (r.path == downloads) "Downloads" else (r.path.fileName?.toString() ?: r.path.toString())
        else -> "Installing"
    }
}

/** "48.2 MB of 184.5 MB · 34.8 MB/s"; the speed only when known, the total only when known. */
internal fun statusText(job: TransferJob): String = when (val s = job.state) {
    JobState.Queued -> "Queued"
    JobState.Done -> "done"
    JobState.Cancelled -> "Cancelled"
    is JobState.Failed -> s.reason
    JobState.Running -> buildList {
        val total = job.total
        if (total != null) add("${formatSize(job.bytes)} of ${formatSize(total)}") else if (job.bytes > 0) add(formatSize(job.bytes))
        job.bytesPerSecond?.let { add("${formatSize(it)}/s") }
    }.joinToString(" · ")
}
