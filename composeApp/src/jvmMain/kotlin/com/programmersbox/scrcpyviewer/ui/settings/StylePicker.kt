package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import com.programmersbox.scrcpyviewer.ui.theme.styledSegmentShape
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.theme.LocalStyleTokens
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.Switch
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import com.programmersbox.scrcpyviewer.ui.theme.stylePane
import com.programmersbox.scrcpyviewer.ui.theme.styleRaised
import com.programmersbox.scrcpyviewer.ui.theme.styleSunken

/**
 * One tile per [ThemeStyle], each a small pane drawn in that style with the current palette, then the chosen
 * style's own options: Retro's colors, and Glass's desktop blur on macOS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StyleControl(b: SettingsBindings) {
    val theme = b.state.settings.appTheme
    val origin = LocalThemeRevealOrigin.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeStyle.entries.forEach { style ->
                val source = rememberRevealSource()
                StyleTile(
                    style = style,
                    selected = theme.style == style,
                    onClick = {
                        source.mark(origin)
                        b.actions.onStyle(style)
                    },
                    preview = { ScrcpyViewerTheme(theme.copy(style = style)) { StylePreview() } },
                    modifier = source.modifier,
                )
            }
        }
        AnimatedVisibility(theme.style == ThemeStyle.Retro, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Retro colors", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow {
                    listOf(false to "Classic", true to "My palette").forEachIndexed { i, (value, label) ->
                        val source = rememberRevealSource()
                        SegmentedButton(
                            selected = theme.retroPalette == value,
                            onClick = {
                                source.mark(origin)
                                b.actions.onRetroPalette(value)
                            },
                            shape = styledSegmentShape(i, 2),
                            modifier = source.modifier,
                        ) { Text(label) }
                    }
                }
            }
        }
        AnimatedVisibility(theme.style == ThemeStyle.Glass, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassOpacitySlider(theme.glassOpacity, b.actions.onGlassOpacity)
                if (Os.isMac) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(theme.desktopBlur, role = Role.Switch, onValueChange = b.actions.onDesktopBlur),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Blur the desktop behind windows", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Off shows a color wallpaper drawn from your palette instead.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = theme.desktopBlur, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleTile(
    style: ThemeStyle,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(124.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val border = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp))
                .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(8.dp)),
        ) { preview() }
        Text(
            style.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A card with a pressed-in field and a raised button, drawn with the enclosing theme's style. */
@Composable
private fun StylePreview() {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalStyleTokens.current
    val retro = tokens.style == ThemeStyle.Retro
    val card = MaterialTheme.shapes.medium
    val small = MaterialTheme.shapes.small
    Box(
        Modifier.fillMaxSize().styleBackdrop().background(if (retro) scheme.surface else scheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxSize()
                .stylePane(card)
                .clip(card).background(scheme.surfaceContainerLow)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (retro) {
                Box(
                    Modifier.fillMaxWidth().height(8.dp)
                        .background(Brush.horizontalGradient(listOf(tokens.titleStart, tokens.titleEnd))),
                )
            }
            Box(
                Modifier.fillMaxWidth().height(12.dp).styleSunken(small)
                    .clip(small).background(if (retro) scheme.surfaceContainerLowest else scheme.surfaceContainerHighest),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pill = if (retro) RectangleShape else RoundedCornerShape(50)
                Box(
                    Modifier.width(40.dp).height(14.dp).styleRaised(pill)
                        .clip(pill)
                        .background(
                            when (tokens.style) {
                                ThemeStyle.Material, ThemeStyle.Glass -> scheme.primary
                                ThemeStyle.Soft, ThemeStyle.Retro -> scheme.surface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tokens.style == ThemeStyle.Soft || retro) {
                        Box(Modifier.size(width = 18.dp, height = 3.dp).background(if (retro) scheme.onSurface else scheme.primary))
                    }
                }
            }
        }
    }
}

/** Glass pane opacity, 0..100%. Saved when the slider is released, like the Custom palette sliders. */
@Composable
private fun GlassOpacitySlider(value: Float, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pane opacity", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Lower shows more of what's behind the window.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(draft * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Glass pane opacity" },
        )
    }
}
