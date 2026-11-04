package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        is UpdateState.Downloading -> ProgressDialog(state.release, state, onCancel)
        is UpdateState.Installing -> ProgressDialog(state.release, null, null)
        else -> Unit
    }
}

@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.width(400.dp),
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

/** [downloading] is null while installing: no numbers, an indeterminate bar and no buttons. */
@Composable
private fun ProgressDialog(release: Release, downloading: UpdateState.Downloading?, onCancel: (() -> Unit)?) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        DialogCard {
            Text("Updating to ${release.versionLabel}", style = MaterialTheme.typography.headlineSmall)
            if (downloading != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    downloading.progressText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                DownloadProgressBar(downloading, Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                "Scrcpy Vwr will close and reopen. Your computer may ask for your password. Open mirror windows will close.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (onCancel != null) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}
