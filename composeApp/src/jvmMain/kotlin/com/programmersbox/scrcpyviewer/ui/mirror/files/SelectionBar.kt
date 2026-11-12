package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.CLOSE_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedAccelerate
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** How far the selection bar rises as it slides in. */
private val BAR_RISE = 70.dp

/**
 * Floats over the bottom of the list while anything is selected (inverse surface): Clear, "2 selected", Pull (to the
 * computer's Downloads), Rename (one item only), Delete, and ⋮ ([onMore] with the button's bottom-left corner, in
 * window coordinates). Slides up [BAR_RISE] and fades in with the open timing; out with the close timing.
 */
@Composable
internal fun SelectionBar(
    browser: FilesBrowser,
    motion: Boolean,
    onPull: () -> Unit,
    onMore: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = browser.selection.names.size
    // The last count, so the bar keeps its label while it slides away. A plain holder.
    val kept = remember { IntArray(1) }
    if (count > 0) kept[0] = count
    val rise = with(LocalDensity.current) { BAR_RISE.roundToPx() }
    AnimatedVisibility(
        count > 0,
        modifier = modifier,
        enter = if (motion) {
            slideInVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { rise } +
                fadeIn(tween(OPEN_MILLIS, easing = EmphasizedDecelerate))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            slideOutVertically(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate)) { rise } +
                fadeOut(tween(CLOSE_MILLIS, easing = EmphasizedAccelerate))
        } else {
            ExitTransition.None
        },
    ) {
        Bar(browser, kept[0], onPull, onMore)
    }
}

@Composable
private fun Bar(browser: FilesBrowser, count: Int, onPull: () -> Unit, onMore: (Offset) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = browser.connected
    val more = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        shape = styledCorner(14.dp),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val iconColors = IconButtonDefaults.iconButtonColors(
                contentColor = colors.inverseOnSurface,
                disabledContentColor = colors.inverseOnSurface.copy(alpha = 0.38f),
            )
            IconButton(onClick = browser::clearSelection, Modifier.size(34.dp), colors = iconColors) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection", Modifier.size(18.dp))
            }
            Text("$count selected", Modifier.weight(1f).padding(start = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            TextButton(
                onClick = onPull,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Pull", fontSize = 13.sp)
            }
            IconButton(
                onClick = { browser.selection.names.singleOrNull()?.let(browser::startRename) },
                Modifier.size(34.dp), enabled = enabled && count == 1, colors = iconColors,
            ) {
                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename", Modifier.size(18.dp))
            }
            IconButton(onClick = browser::askDelete, Modifier.size(34.dp), enabled = enabled, colors = iconColors) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", Modifier.size(18.dp))
            }
            IconButton(
                onClick = { more[0]?.let { onMore(it.localToWindow(Offset(0f, it.size.height.toFloat()))) } },
                Modifier.size(34.dp).onGloballyPositioned { more[0] = it },
                enabled = enabled,
                colors = iconColors,
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More for selection", Modifier.size(18.dp))
            }
        }
    }
}
