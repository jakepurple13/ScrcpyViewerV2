package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.common.LabelTooltip
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring

/** The chip's words for [state]; null hides the chip (not checked yet). */
fun rootLabel(state: RootState): String? = when (state) {
    RootState.Unknown -> null
    RootState.None -> "Not rooted"
    RootState.Available -> "Root available"
    RootState.Asking -> "Asking for root…"
    RootState.Rooted -> "Rooted"
    RootState.AdbRoot -> "adb root"
    RootState.Denied -> "Root denied"
}

/** The chip's tooltip for [state]. */
fun rootTooltip(state: RootState): String = when (state) {
    RootState.Unknown, RootState.None -> "Not rooted. Databases open for debuggable apps through run-as."
    RootState.Available -> "su is installed. Click to ask for root; your phone may show a prompt."
    RootState.Asking -> "Waiting for you to allow root on the phone."
    RootState.Rooted -> "Root granted. Every app's databases open through su."
    RootState.AdbRoot -> "adbd runs as root. Every app's databases open directly."
    RootState.Denied -> "Root was refused or timed out. Click to ask again."
}

private fun RootState.icon(): ImageVector = when (this) {
    RootState.Rooted, RootState.AdbRoot -> Icons.Filled.AdminPanelSettings
    RootState.Denied -> Icons.Filled.RemoveModerator
    else -> Icons.Outlined.Shield
}

/**
 * Whether the device is rooted, beside the sheet's title. Root states fill with the tertiary container; the others are
 * outlined. Clicking asks for root when that's possible ([RootState.Available] or [RootState.Denied]). The words swap
 * with a short vertical slide and the colors fade.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RootChip(state: RootState, motion: Boolean, modifier: Modifier = Modifier, onAsk: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val filled = state == RootState.Rooted || state == RootState.AdbRoot
    val container by animateColorAsState(if (filled) colors.tertiaryContainer else Color.Transparent, tween(if (motion) 250 else 0))
    val content by animateColorAsState(
        if (filled) colors.onTertiaryContainer else colors.onSurfaceVariant, tween(if (motion) 250 else 0),
    )
    val outline by animateColorAsState(if (filled) Color.Transparent else colors.outlineVariant, tween(if (motion) 250 else 0))
    val askable = state == RootState.Available || state == RootState.Denied
    AnimatedVisibility(
        visible = rootLabel(state) != null,
        modifier = modifier,
        enter = if (motion) scaleIn(initialScale = 0.8f) + fadeIn() else fadeIn(snap()),
        exit = if (motion) scaleOut(targetScale = 0.8f) + fadeOut() else fadeOut(snap()),
    ) {
        TooltipArea(tooltip = { LabelTooltip(rootTooltip(state), shape = RoundedCornerShape(4.dp)) }) {
            Row(
                Modifier.height(24.dp).clip(ChipShape).background(container).border(1.dp, outline, ChipShape)
                    .then(if (askable) Modifier.clickable(role = Role.Button, onClick = onAsk) else Modifier)
                    .padding(start = 6.dp, end = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        if (motion) {
                            (slideInVertically { it / 2 } + fadeIn(tween(180))).togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                                .using(SizeTransform(clip = false) { _, _ -> layoutSpring() })
                        } else {
                            fadeIn(snap()).togetherWith(fadeOut(snap()))
                        }
                    },
                ) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(s.icon(), contentDescription = null, Modifier.size(14.dp), tint = content)
                        Text(rootLabel(s).orEmpty(), style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val ChipShape = RoundedCornerShape(12.dp)
