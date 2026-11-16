package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.programmersbox.scrcpyviewer.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.ui.common.openWebUrl
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.update.UpdateState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val publishedFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

/** The Settings "Updates" block, without its own header. Stateless; [now] is a parameter so screenshots are stable. */
@Composable
fun UpdatesContent(
    ui: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit = {},
    now: Instant = Instant.now(),
    openUrl: (String) -> Boolean = ::openWebUrl,
) {
    val openReleasePage = { openUrl(ui.releasePageUrl); Unit }
    val state = ui.state
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading ||
        state is UpdateState.Ready || state is UpdateState.Installing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Current version ${ui.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        ui.updatedFrom?.let {
            Text(
                "Updated from $it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.available?.let { release ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${release.versionLabel} available", style = MaterialTheme.typography.titleSmall)
                    publishedDate(release.publishedAt)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Keyed by phase, so the download numbers tick in place and only a phase change slides.
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it.cardPhase },
                        transitionSpec = {
                            val inSpec = tween<Float>(OPEN_MILLIS, delayMillis = CLOSE_MILLIS / 2, easing = EmphasizedDecelerate)
                            val inSlide = tween<IntOffset>(OPEN_MILLIS, delayMillis = CLOSE_MILLIS / 2, easing = EmphasizedDecelerate)
                            val outSpec = tween<Float>(CLOSE_MILLIS / 2, easing = EmphasizedAccelerate)
                            (fadeIn(inSpec) + slideInVertically(inSlide) { it / 2 }) togetherWith fadeOut(outSpec) using
                                SizeTransform(clip = false)
                        },
                        label = "updateCard",
                    ) { phase ->
                        when (phase) {
                            is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(phase.progressText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DownloadProgressBar(phase, Modifier.weight(1f))
                                    TextButton(onClick = onCancel) { Text("Cancel") }
                                }
                            }
                            is UpdateState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PoppingCheck(ReadyCheckSize)
                                    Text("Downloaded. Restart Scrcpy Vwr to finish updating.", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = onRestart) { Text("Restart to update") }
                                    TextButton(onClick = openReleasePage) { Text("View release notes") }
                                }
                            }
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (ui.canSelfUpdate) {
                                    Button(onClick = onUpdate, enabled = !busy) { Text("Update now") }
                                }
                                TextButton(onClick = openReleasePage) { Text("View release notes") }
                            }
                        }
                    }
                }
            }
        }
        if (ui.macNotInApplications) {
            Text(
                "Move Scrcpy Vwr to Applications to enable updates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCheck, enabled = !busy) {
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Check for updates")
            }
            // Always offered, so a user can get the installer even when self-update is unavailable or a check fails.
            OutlinedButton(onClick = openReleasePage) { Text("Download from GitHub") }
        }
        ui.lastChecked?.let {
            Text(
                "Last checked ${lastCheckedText(it, now)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state is UpdateState.UpToDate) Text("You're on the latest version.", style = MaterialTheme.typography.bodyMedium)
        val failure = (state as? UpdateState.Failed)?.message ?: ui.previousFailure
        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

private val ReadyCheckSize = 20.dp

/** Which body the release card shows; a change between these animates. */
private val UpdateState.cardPhase: Int
    get() = when (this) {
        is UpdateState.Downloading -> 1
        is UpdateState.Ready -> 2
        else -> 0
    }

/** "Download updates automatically": the whole row toggles; off limits when this copy cannot update itself. */
internal fun autoDownloadToggle(enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) =
    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)

@Composable
internal fun AutoDownloadSwitch(enabled: Boolean, checked: Boolean) {
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
}

private fun publishedDate(publishedAt: String?): String? =
    publishedAt?.let { runCatching { publishedFormat.format(Instant.parse(it)) }.getOrNull() }

internal fun lastCheckedText(lastChecked: Instant, now: Instant): String {
    val elapsed = Duration.between(lastChecked, now)
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} min ago"
        else -> "${elapsed.toHours()} h ago"
    }
}
