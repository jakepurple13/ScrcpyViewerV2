package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.layoutSpring
import com.programmersbox.scrcpyviewer.ui.common.rememberSelectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlight
import com.programmersbox.scrcpyviewer.ui.common.selectionHighlightTarget
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeState
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.icon
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.menuLabel
import com.programmersbox.scrcpyviewer.ui.theme.FilterChip
import com.programmersbox.scrcpyviewer.ui.theme.Switch
import com.programmersbox.scrcpyviewer.ui.theme.TextButton
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.currentStyle
import com.programmersbox.scrcpyviewer.ui.theme.listRow
import com.programmersbox.scrcpyviewer.ui.tray.TrayAction

// The Send input panel and the Arrange section of the popup: both fold open under their trigger.

private fun List<PopupDevice>.titleOf(serial: String) = firstOrNull { it.serial == serial }?.title ?: serial

/** The Send input panel under the stage. Folds open while [sendOpen] and the stage can send; keeps its last content while it folds shut. */
@Composable
internal fun SendInputPanel(state: TrayPopupUiState, sendOpen: Boolean, onEvent: (PopupEvent) -> Unit) {
    val reduce = LocalReduceMotion.current
    val panel = state.sendPanel
    // While the panel folds shut its state is already Hidden, so the last real one stays to be drawn.
    val last = remember { arrayOfNulls<SendPanel>(1) }
    if (panel !is SendPanel.Hidden) last[0] = panel
    val shown = last[0]
    val stageTitle = state.devices.titleOf(state.stage ?: "")
    AnimatedVisibility(
        visible = sendOpen && panel !is SendPanel.Hidden,
        enter = if (reduce) fadeIn(tween(150)) else expandVertically(layoutSpring()) + fadeIn(tween(150)),
        exit = if (reduce) fadeOut(tween(100)) else shrinkVertically(layoutSpring()) + fadeOut(tween(100)),
    ) {
        // The text crossfades when the stage (or the kind of panel) changes; chips read the live state.
        val key = when (shown) {
            is SendPanel.Choose -> "choose" to state.stage
            is SendPanel.Following -> "following" to state.stage
            else -> "none" to null
        }
        Crossfade(key, label = "send panel", modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            when (shown) {
                is SendPanel.Choose -> ChoosePanel(stageTitle, shown, state.devices, onEvent)
                is SendPanel.Following -> FollowingPanel(stageTitle, shown, state.devices, onEvent)
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoosePanel(name: String, panel: SendPanel.Choose, devices: List<PopupDevice>, onEvent: (PopupEvent) -> Unit) {
    val reduce = LocalReduceMotion.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Send $name's taps, keys and text to:", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (target in panel.targets) {
                FilterChip(
                    selected = target.serial in panel.selected,
                    onClick = { onEvent(PopupEvent.ToggleFollower(target.serial)) },
                    label = { Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        // The note folds and fades in and out with the other leader; it keeps the last leader's name while it leaves.
        val other = panel.otherLeader
        val note = remember { arrayOfNulls<String>(1) }
        if (other != null) note[0] = other
        AnimatedVisibility(
            visible = other != null,
            enter = if (reduce) fadeIn(tween(150)) else expandVertically(layoutSpring()) + fadeIn(tween(150)),
            exit = if (reduce) fadeOut(tween(100)) else shrinkVertically(layoutSpring()) + fadeOut(tween(100)),
        ) {
            Text(
                "Picking a device here stops ${devices.titleOf(note[0] ?: "")} sending input.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FollowingPanel(name: String, panel: SendPanel.Following, devices: List<PopupDevice>, onEvent: (PopupEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "$name follows ${devices.titleOf(panel.leader)}, so its input comes from there.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton({ onEvent(PopupEvent.StopFollowing) }) { Text("Stop following", maxLines = 1) }
    }
}

/** The Arrange section under the strip: shows with [TrayPopupUiState.arrange] and at least two mirrors. */
@Composable
internal fun ArrangeSection(state: TrayPopupUiState, onEvent: (PopupEvent) -> Unit) {
    val reduce = LocalReduceMotion.current
    val arrange = state.arrange
    val show = arrange != null && state.devices.count { it.status == PopupStatus.Mirroring } >= 2
    // Keeps the last state to draw while it folds shut.
    val last = remember { arrayOfNulls<ArrangeState>(1) }
    if (arrange != null) last[0] = arrange
    val shown = last[0]
    AnimatedVisibility(
        visible = show,
        enter = if (reduce) fadeIn(tween(150)) else expandVertically(layoutSpring()) + fadeIn(tween(150)),
        exit = if (reduce) fadeOut(tween(100)) else shrinkVertically(layoutSpring()) + fadeOut(tween(100)),
    ) {
        if (shown == null) return@AnimatedVisibility
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Arrange mirror windows", style = MaterialTheme.typography.labelLarge)
            LayoutRow(shown.layout, shown.movable) { onEvent(PopupEvent.Act(TrayAction.Arrange(it), null)) }
            val enabled = shown.keepTiledEnabled
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .toggleable(shown.keepTiled, enabled, Role.Switch) { onEvent(PopupEvent.Act(TrayAction.KeepTiled(it), null)) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Keep tiled", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Switch(shown.keepTiled, null, enabled = enabled)
            }
        }
    }
}

/** Five icon + label buttons; the selected background slides from one to the next (Material) or fades (other styles). */
@Composable
private fun LayoutRow(current: ArrangeLayout, movable: Boolean, onPick: (ArrangeLayout) -> Unit) {
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(12.dp)
    val material = currentStyle == ThemeStyle.Material
    val highlight = rememberSelectionHighlight()
    Row(
        Modifier.fillMaxWidth().then(
            if (material) Modifier.selectionHighlight(highlight, MaterialTheme.colorScheme.secondaryContainer, shape) else Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (layout in ArrangeLayout.entries) {
            val selected = layout == current
            Box(Modifier.weight(1f).selectionHighlightTarget(highlight, selected && material)) {
                if (!material) {
                    val fade by animateFloatAsState(if (selected) 1f else 0f, tween(if (reduce) 100 else 150), label = "layout selected")
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = fade }.listRow(true, shape))
                }
                Column(
                    Modifier.fillMaxWidth().clip(shape)
                        .clickable(enabled = movable, role = Role.Button) { onPick(layout) }
                        .semantics { this.selected = selected }
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (movable) 1f else 0.38f)
                    Icon(layout.icon, null, Modifier.size(20.dp), tint = tint)
                    Text(
                        layout.menuLabel, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
                        color = tint, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
