package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.state.appTheme
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.CustomPalette
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.colorSchemeFor
import com.programmersbox.scrcpyviewer.ui.theme.isDark
import com.programmersbox.scrcpyviewer.ui.theme.oklch
import kotlin.math.roundToInt

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Root-space centre of the element, so a click can start the theme reveal there. */
@Composable
internal fun rememberRevealSource(): RevealSource = remember { RevealSource() }

internal class RevealSource {
    private var center = Offset.Unspecified
    val modifier: Modifier = Modifier.onGloballyPositioned { center = it.boundsInRoot().center }

    /** Call just before changing the theme. */
    fun mark(origin: ThemeRevealOrigin?) {
        if (origin != null && center != Offset.Unspecified) origin.next = center
    }
}

/** Palette thumbnails, then the Custom editor when Custom is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteControl(b: SettingsBindings) {
    val theme = b.state.settings.appTheme
    val dark = theme.mode.isDark()
    var draft by remember(theme.custom) { mutableStateOf(theme.custom) }
    val origin = LocalThemeRevealOrigin.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemePalette.entries.forEach { palette ->
                val scheme = remember(palette, dark, theme.pureBlack, if (palette == ThemePalette.Custom) draft else null) {
                    colorSchemeFor(AppTheme(palette = palette, pureBlack = theme.pureBlack, custom = draft), dark)
                }
                val source = rememberRevealSource()
                PaletteThumbnail(
                    palette = palette,
                    scheme = scheme,
                    selected = theme.palette == palette,
                    onClick = {
                        source.mark(origin)
                        b.actions.onPalette(palette)
                    },
                    modifier = source.modifier,
                )
            }
        }
        AnimatedVisibility(
            visible = theme.palette == ThemePalette.Custom,
            enter = expandVertically(tween(240, easing = Emphasized), expandFrom = Alignment.Top) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(200, easing = Emphasized), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
        ) {
            CustomPaletteEditor(
                draft = draft,
                dark = dark,
                pureBlack = theme.pureBlack,
                onDraft = { draft = it },
                onCommit = { b.actions.onCustomPalette(draft) },
                onReset = {
                    draft = CustomPalette()
                    b.actions.onCustomPalette(draft)
                },
            )
        }
    }
}

/** A tiny Scrcpy Vwr window drawn in [scheme], with the palette's name under it. */
@Composable
private fun PaletteThumbnail(
    palette: ThemePalette,
    scheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val border = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, border, shape),
        ) { drawMiniWindow(scheme) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (palette == ThemePalette.Custom) Box(Modifier.size(9.dp).clip(CircleShape).background(rainbowSweep))
            Text(
                palette.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniWindow(s: ColorScheme) {
    val w = size.width
    val h = size.height
    val bar = h * 0.13f
    drawRect(s.surface)
    drawRect(s.surfaceContainer, size = Size(w, bar))
    // List pane: selected row, then two plain rows.
    val pad = w * 0.05f
    val rowH = h * 0.09f
    val listW = w / 2 - pad * 2
    listOf(s.secondaryContainer, s.surfaceContainerHighest, s.surfaceContainerHighest).forEachIndexed { i, c ->
        drawRoundRect(c, Offset(pad, bar + pad + i * (rowH + pad * 0.8f)), Size(listW, rowH), CornerRadius(rowH / 2))
    }
    // Detail pane: container with a primary button.
    drawRect(s.primaryContainer, Offset(w / 2, bar), Size(w / 2, h - bar))
    val btnW = w * 0.24f
    val btnH = h * 0.12f
    drawRoundRect(s.primary, Offset(w * 0.75f - btnW / 2, bar + (h - bar) / 2 - btnH / 2), Size(btnW, btnH), CornerRadius(btnH / 2))
}

private val rainbow = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { oklch(0.65f, 0.15f, it) }
private val rainbowSweep = Brush.sweepGradient(rainbow)

/** Three sliders and a preview of the Custom palette's roles. Rows stagger in when the editor opens. */
@Composable
private fun AnimatedVisibilityScope.CustomPaletteEditor(
    draft: CustomPalette,
    dark: Boolean,
    pureBlack: Boolean,
    onDraft: (CustomPalette) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val scheme = remember(draft, dark, pureBlack) {
        colorSchemeFor(AppTheme(palette = ThemePalette.Custom, pureBlack = pureBlack, custom = draft), dark)
    }
    val seed = draft.seed
    var row = 0
    @Composable
    fun Modifier.stagger(): Modifier {
        val delay = 120 + 40 * row++
        val shift = with(LocalDensity.current) { 8.dp.roundToPx() }
        return animateEnterExit(
            enter = fadeIn(tween(220, delay, Emphasized)) + slideInVertically(tween(220, delay, Emphasized)) { shift },
            exit = ExitTransition.None,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().stagger(), verticalAlignment = Alignment.CenterVertically) {
            Text("Custom palette", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onReset) { Text("Reset") }
        }
        GradientSlider(
            label = "Color",
            value = draft.hue, range = 0f..359f,
            valueText = "${draft.hue.roundToInt()}°",
            track = Brush.horizontalGradient(rainbow),
            knob = oklch(0.65f, 0.15f, seed.hue),
            onChange = { onDraft(draft.copy(hue = it)) }, onCommit = onCommit,
            modifier = Modifier.stagger(),
        )
        GradientSlider(
            label = "Vividness",
            value = draft.chroma, range = 0f..CustomPalette.MAX_CHROMA,
            valueText = "${(draft.chroma / CustomPalette.MAX_CHROMA * 100).roundToInt()}%",
            track = Brush.horizontalGradient(listOf(oklch(0.65f, 0f, seed.hue), oklch(0.65f, CustomPalette.MAX_CHROMA, seed.hue))),
            knob = oklch(0.65f, seed.chroma, seed.hue),
            onChange = { onDraft(draft.copy(chroma = it)) }, onCommit = onCommit,
            modifier = Modifier.stagger(),
        )
        GradientSlider(
            label = "Accent shift",
            value = draft.accentShift, range = 0f..180f,
            valueText = "+${draft.accentShift.roundToInt()}°",
            track = Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f).map { oklch(0.65f, 0.14f, (seed.hue + it) % 360f) }),
            knob = oklch(0.65f, 0.14f, seed.tertiaryHue),
            onChange = { onDraft(draft.copy(accentShift = it)) }, onCommit = onCommit,
            modifier = Modifier.stagger(),
        )
        Row(Modifier.fillMaxWidth().stagger(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Primary" to scheme.primary,
                "Container" to scheme.primaryContainer,
                "Secondary" to scheme.secondaryContainer,
                "Accent" to scheme.tertiaryContainer,
                "Surface" to scheme.surface,
            ).forEach { (name, color) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    )
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "Color sets the main hue. Vividness goes from grey to full color. Accent shift moves the secondary color around the wheel.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.stagger(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    track: Brush,
    knob: Color,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(Color.White).padding(3.dp)
                        .clip(CircleShape).background(knob),
                )
            },
            track = {
                Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(track))
            },
        )
        Text(
            valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
        )
    }
}

/** Pure black only does something in dark mode. */
@Composable
private fun pureBlackEnabled(b: SettingsBindings): Boolean = b.state.settings.themeMode.isDark()

/** The whole row toggles; the click point starts the reveal. */
@Composable
internal fun pureBlackToggle(b: SettingsBindings): Modifier {
    val source = rememberRevealSource()
    val origin = LocalThemeRevealOrigin.current
    return source.modifier.toggleable(
        value = b.state.settings.pureBlack, enabled = pureBlackEnabled(b), role = Role.Switch,
        onValueChange = {
            source.mark(origin)
            b.actions.onPureBlack(it)
        },
    )
}

@Composable
internal fun PureBlackSwitch(b: SettingsBindings) {
    Switch(checked = b.state.settings.pureBlack, onCheckedChange = null, enabled = pureBlackEnabled(b))
}

@Composable
internal fun PureBlackNotes(b: SettingsBindings) {
    if (!pureBlackEnabled(b)) {
        Text(
            "Only applies in dark mode.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
