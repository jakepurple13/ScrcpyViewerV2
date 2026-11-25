package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.theme.FilledTonalButton

/** The Data tab can open [app]: a debug build, or a phone that is rooted (or has `su` to ask). */
fun canOpenData(app: DeviceApp, root: RootState): Boolean =
    app.debuggable || root == RootState.Rooted || root == RootState.AdbRoot || root == RootState.Available

/** The options sheet's "Data access" value for [app]. */
fun dataAccessText(app: DeviceApp, root: RootState): String = when {
    app.debuggable -> "Debuggable · run-as"
    root == RootState.Rooted -> "Through root (su)"
    root == RootState.AdbRoot -> "Through adb root"
    root == RootState.Available -> "Release build · ask for root"
    else -> "Release build · locked"
}

/** The line under the Databases button; null for a debug build, which needs no explanation. */
fun dataAccessReason(app: DeviceApp, root: RootState): String? = when {
    app.debuggable -> null
    root == RootState.Rooted || root == RootState.AdbRoot -> "Release build. Opens through root."
    root == RootState.Available -> "Release build. Opens through root; your phone may ask first."
    else -> "Release build. Android only shares app data for debug builds or on a rooted phone."
}

private fun dataAccessIcon(app: DeviceApp, root: RootState): ImageVector = when {
    app.debuggable -> Icons.Filled.BugReport
    canOpenData(app, root) -> Icons.Filled.AdminPanelSettings
    else -> Icons.Filled.Lock
}

/** "DEBUG" on a debug build's card, in [SystemBadge]'s shape with the tertiary container colors. */
@Composable
internal fun DebugBadge(modifier: Modifier = Modifier, short: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(colors.tertiaryContainer).padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = "Debug build" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Filled.BugReport, contentDescription = null, Modifier.size(10.dp), tint = colors.onTertiaryContainer)
        Text(if (short) "DEBUG" else "Debug", fontSize = if (short) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiaryContainer)
    }
}

/**
 * The options sheet's "Data access" box: how the Data tab reaches [app]'s files. The value scrolls as a marquee when
 * it doesn't fit, and its icon, words and color cross-fade when root changes.
 */
@Composable
internal fun DataAccessBox(app: DeviceApp, root: RootState, motion: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val open = canOpenData(app, root)
    val tint by animateColorAsState(
        when {
            app.debuggable -> colors.primary
            open -> colors.tertiary
            else -> colors.onSurfaceVariant
        },
        tween(if (motion) 250 else 0),
    )
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("Data access", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        AnimatedContent(
            dataAccessText(app, root) to dataAccessIcon(app, root),
            transitionSpec = { if (motion) fadeIn(tween(220)) togetherWith fadeOut(tween(120)) else fadeIn(snap()) togetherWith fadeOut(snap()) },
        ) { (text, icon) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, Modifier.size(15.dp), tint = tint)
                Spacer(Modifier.width(6.dp))
                Text(
                    text, Modifier.basicMarquee(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = tint, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/**
 * Show in Logcat and Databases, side by side (stacked when [compact]). Databases is disabled for an app the Data tab
 * can't open, with the reason under the buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogcatAndDatabases(
    app: DeviceApp,
    root: RootState,
    compact: Boolean,
    onLogcat: () -> Unit,
    onDatabases: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val open = canOpenData(app, root)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (compact) 1 else 2,
        ) {
            FilledTonalButton(onClick = onLogcat, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show in Logcat", maxLines = 1)
            }
            FilledTonalButton(
                onClick = onDatabases,
                enabled = open,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                Icon(if (open) Icons.Filled.Storage else Icons.Filled.Lock, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Databases", maxLines = 1)
            }
        }
        dataAccessReason(app, root)?.let { reason ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(if (open) Icons.Filled.AdminPanelSettings else Icons.Filled.Info, contentDescription = null, Modifier.size(15.dp),
                    tint = if (open) colors.tertiary else colors.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = if (open) colors.tertiary else colors.onSurfaceVariant)
            }
        }
    }
}
