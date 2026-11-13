package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.state.launchQuit
import com.programmersbox.scrcpyviewer.ui.window.launch.LaunchQuitPreview

/**
 * One tile per [LaunchQuitAnimation], laid out like the Style picker: a small desktop that loops the animation while
 * hovered and plays it once when picked, the name with a radio dot, and a line on what it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchQuitAnimationControl(b: SettingsBindings) {
    val current = b.state.settings.launchQuit
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LaunchQuitAnimation.entries.forEach { anim ->
            LaunchQuitTile(anim, selected = anim == current, onClick = { b.actions.onLaunchQuitAnimation(anim) })
        }
    }
}

@Composable
private fun LaunchQuitTile(anim: LaunchQuitAnimation, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var picks by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(12.dp)
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, tween(200))
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(200))
    val fill by animateColorAsState(
        when {
            selected -> colors.secondaryContainer.copy(alpha = 0.4f)
            hovered -> colors.surfaceContainerHigh
            else -> colors.surface.copy(alpha = 0f)
        },
        tween(200),
    )
    Column(
        Modifier
            .width(200.dp)
            .clip(tileShape)
            .background(fill)
            .border(borderWidth, border, tileShape)
            .hoverable(interaction)
            .selectable(selected = selected, role = Role.RadioButton, interactionSource = interaction, indication = null) {
                picks++
                onClick()
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LaunchQuitPreview(
            anim, looping = hovered, playOnce = picks,
            modifier = Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                anim.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            RadioDot(selected)
        }
        Text(
            anim.description, Modifier.padding(horizontal = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, minLines = 2,
        )
    }
}

/** A radio button's ring with its dot growing in when selected. */
@Composable
private fun RadioDot(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val ring by animateColorAsState(if (selected) colors.primary else colors.outline, tween(200))
    val dot by animateFloatAsState(if (selected) 1f else 0f, tween(250))
    Box(Modifier.size(16.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).scale(dot).background(colors.primary, CircleShape))
    }
}
