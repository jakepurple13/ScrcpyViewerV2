package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import com.programmersbox.scrcpyviewer.files.DEVICE_ROOT
import com.programmersbox.scrcpyviewer.files.isInside
import com.programmersbox.scrcpyviewer.files.normalizeDir
import com.programmersbox.scrcpyviewer.files.wayInto
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import kotlinx.coroutines.delay
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
internal fun EmptyFolderState(label: String, enabled: Boolean, onPushFiles: () -> Unit, readOnly: Boolean = false) {
    if (readOnly) {
        StateMessage(Icons.Filled.FolderOpen, "$label is empty", "Nothing adb can see is in this folder.")
        return
    }
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

/** Why adb can't list [path], in the No access state. */
internal fun blockedReason(path: String): String {
    val way = wayInto(path)
    return when {
        way != null && way.second == DEVICE_ROOT -> "Only the system can list this folder. Its 0 folder is your Internal storage."
        way != null -> "Only the system can list ${normalizeDir(path)}. Its ${way.second.removePrefix(normalizeDir(path) + "/")} folder is open to adb."
        isInside(normalizeDir(path), "$DEVICE_ROOT/Android") -> "Apps keep private files here. adb can’t list it without root."
        else -> "Only the system or the app that owns this folder can read it. adb runs as the shell user, so it can’t list it."
    }
}

/** How long each line of the No access state waits after the one before it as it rises in. */
private const val BLOCKED_STAGGER_MILLIS = 40

/**
 * Android refuses the listing ([com.programmersbox.scrcpyviewer.files.Listing.Blocked]): the folder's name, why,
 * the device's own [message], and the ways out: a readable folder inside it when one is known ([wayInto]), Go up and
 * Copy path. Its lines rise in one after another.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlockedState(
    path: String,
    message: String,
    enabled: Boolean,
    canGoUp: Boolean,
    motion: Boolean,
    onUp: () -> Unit,
    onOpen: (String) -> Unit,
    onCopyPath: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val way = wayInto(path)
    var copied by remember(path) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Rise(0, motion) { Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(40.dp), tint = colors.outline) }
        Rise(1, motion) {
            Text("No access to ${folderLabel(path)}", style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Rise(2, motion) {
            Text(
                blockedReason(path), Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodySmall,
                fontSize = 12.5.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Rise(3, motion) {
            Text(
                message,
                Modifier.widthIn(max = 300.dp).clip(styledCorner(6.dp)).background(colors.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        Rise(4, motion) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (way != null) {
                    Button(onClick = { onOpen(way.second) }, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(way.first)
                    }
                }
                FilledTonalButton(onClick = onUp, enabled = enabled && canGoUp, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Go up")
                }
                FilledTonalButton(
                    onClick = {
                        onCopyPath()
                        copied = true
                    },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    AnimatedContent(
                        copied,
                        transitionSpec = { if (motion) fadeIn(tween(150)) togetherWith fadeOut(tween(150)) else EnterTransition.None togetherWith ExitTransition.None },
                    ) { Text(if (it) "Copied" else "Copy path") }
                }
            }
        }
    }
}

/** How long Copy path reads "Copied". */
private const val COPIED_MILLIS = 1_200L

/** Line [index] of a state message, rising and fading in [BLOCKED_STAGGER_MILLIS] after the line before it. */
@Composable
private fun Rise(index: Int, motion: Boolean, content: @Composable () -> Unit) {
    val shown = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        shown.animateTo(1f, tween(OPEN_MILLIS, delayMillis = NAV_IN_DELAY_MILLIS + index * BLOCKED_STAGGER_MILLIS, easing = EmphasizedDecelerate))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * RISE.toPx()
        },
    ) { content() }
}

/** How far a line of the No access state rises as it comes in. */
private val RISE = 6.dp

/**
 * Under the toolbar while the open folder is a read-only system folder (`test -w` fails): files can be pulled, but
 * New folder, Push and drops are off.
 */
@Composable
internal fun ReadOnlyStrip(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp).clip(styledCorner(10.dp))
            .background(colors.tertiaryContainer).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(16.dp), tint = colors.onTertiaryContainer)
        Text(
            "Read-only folder. You can pull files, but adb can’t add or change anything here.",
            style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = colors.onTertiaryContainer,
        )
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
