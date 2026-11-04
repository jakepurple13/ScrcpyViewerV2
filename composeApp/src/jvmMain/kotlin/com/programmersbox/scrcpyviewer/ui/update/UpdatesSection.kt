package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    now: Instant = Instant.now(),
) {
    val uriHandler = LocalUriHandler.current
    val openReleasePage = { uriHandler.openSafely(ui.releasePageUrl) }
    val state = ui.state
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
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
                    if (state is UpdateState.Downloading) {
                        Text(state.progressText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DownloadProgressBar(state, Modifier.weight(1f))
                            TextButton(onClick = onCancel) { Text("Cancel") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ui.canSelfUpdate) {
                                Button(onClick = onUpdate, enabled = !busy) { Text("Update now") }
                            }
                            TextButton(onClick = openReleasePage) { Text("View release notes") }
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
