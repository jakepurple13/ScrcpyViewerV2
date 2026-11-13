package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.theme.Button
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** An open folder with nothing in it (or only hidden entries while they are hidden). */
@Composable
internal fun EmptyFolderState(label: String, enabled: Boolean, onPushFiles: () -> Unit) {
    StateMessage(
        Icons.Filled.FolderOpen,
        "$label is empty",
        "Drag files here from your computer, or push them from a file picker.",
    ) {
        Button(onClick = onPushFiles, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Filled.FileUpload, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Push files")
        }
    }
}

/** The filter hides every row. */
@Composable
internal fun NoMatchState(filter: String) {
    StateMessage(Icons.Filled.Search, "Nothing matches “$filter”", "The filter only searches this folder.")
}

/** Android refuses the listing ([com.programmersbox.scrcpyviewer.files.Listing.Blocked]). */
@Composable
internal fun BlockedState(path: String, canGoUp: Boolean, onUp: () -> Unit) {
    StateMessage(
        Icons.Filled.Lock,
        "Android blocks this folder",
        "Apps keep private files in $path. adb can’t list it without root.",
    ) {
        FilledTonalButton(onClick = onUp, enabled = canGoUp, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Go up")
        }
    }
}

/** The listing failed for another reason: the device's own error line, and Retry. */
@Composable
internal fun ListingFailedState(reason: String, enabled: Boolean, onRetry: () -> Unit) {
    StateMessage(Icons.Filled.ErrorOutline, title = null, body = reason, monospace = true) {
        FilledTonalButton(onClick = onRetry, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Retry")
        }
    }
}

/** The Files tab without adb (none found, or it failed to start): nothing can be listed. */
@Composable
internal fun AdbUnavailableState() {
    StateMessage(
        Icons.Filled.LinkOff,
        "adb isn’t available",
        "The Files tab browses the device over adb. Check the adb path in Settings, then reopen this window.",
    )
}

/** Over the dimmed list while the session is down. */
@Composable
internal fun DisconnectedBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(styledCorner(10.dp))
            .background(colors.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.LinkOff, contentDescription = null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Text("Device disconnected", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StateMessage(
    icon: ImageVector,
    title: String?,
    body: String,
    monospace: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(40.dp), tint = colors.outline)
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Text(
            body,
            Modifier.widthIn(max = 260.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = if (monospace) 11.5.sp else 12.5.sp,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}
