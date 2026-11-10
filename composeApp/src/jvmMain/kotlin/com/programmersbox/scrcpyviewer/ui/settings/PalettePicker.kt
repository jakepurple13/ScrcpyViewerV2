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
import com.programmersbox.scrcpyviewer.ui.theme.OutlinedButton
import androidx.compose.material3.Slider
import com.programmersbox.scrcpyviewer.ui.theme.Switch
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
import com.programmersbox.scrcpyviewer.ui.theme.CustomNeon
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

/**
 * Palette thumbnails (regular, then a Neon row), then the Custom or Custom neon editor when one is selected.
 * Regular thumbnails follow the Neon switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteControl(b: SettingsBindings) {
    val theme = b.state.settings.appTheme
    val dark = theme.mode.isDark()
    var draft by remember(theme.custom) { mutableStateOf(theme.custom) }
    var neonDraft by remember(theme.customNeon) { mutableStateOf(theme.customNeon) }
    val origin = LocalThemeRevealOrigin.current
    @Composable
    fun Tiles(palettes: List<ThemePalette>) = FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        palettes.forEach { palette ->
            // Neon tiles always preview neon, since picking one turns it on; the rest follow the switch.
            val tileTheme = AppTheme(
                palette = palette, pureBlack = theme.pureBlack, custom = draft,
                neon = theme.neon || palette.neon, customNeon = neonDraft,
            )
            val scheme = remember(tileTheme, dark) { colorSchemeFor(tileTheme, dark) }
            val source = rememberRevealSource()
            PaletteThumbnail(
                palette = palette,
                scheme = scheme,
                glow = tileTheme.glow,
                selected = theme.palette == palette,
                onClick = {
                    source.mark(origin)
                    b.actions.onPalette(palette)
                },
                modifier = source.modifier,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Tiles(ThemePalette.entries.filterNot { it.neon })
        Text(
            "Neon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Tiles(ThemePalette.entries.filter { it.neon })
        AnimatedVisibility(
            visible = theme.palette == ThemePalette.CustomNeon,
            enter = expandVertically(tween(240, easing = Emphasized), expandFrom = Alignment.Top) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(200, easing = Emphasized), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
        ) {
            CustomNeonEditor(
                draft = neonDraft,
                dark = dark,
                pureBlack = theme.pureBlack,
                onDraft = { neonDraft = it },
                onCommit = { b.actions.onCustomNeon(neonDraft) },
                onReset = {
                    neonDraft = CustomNeon()
                    b.actions.onCustomNeon(neonDraft)
                },
            )
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
    glow: Float,
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
        ) { drawMiniWindow(scheme, glow) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (palette == ThemePalette.Custom || palette == ThemePalette.CustomNeon) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(rainbowSweep))
            }
            Text(
                palette.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniWindow(s: ColorScheme, glow: Float) {
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
    val btn = Offset(w * 0.75f - btnW / 2, bar + (h - bar) / 2 - btnH / 2)
    // Neon: a few widening halos behind the button stand in for the glow.
    if (glow > 0f) {
        for (i in 3 downTo 1) {
            val g = i * 2.dp.toPx() * glow
            drawRoundRect(
                s.primary.copy(alpha = 0.18f * glow), Offset(btn.x - g, btn.y - g), Size(btnW + g * 2, btnH + g * 2),
                CornerRadius(btnH / 2 + g),
            )
        }
    }
    drawRoundRect(s.primary, btn, Size(btnW, btnH), CornerRadius(btnH / 2))
}

/** The bordered panel both editors sit in. */
private val editorPanel: Modifier
    @Composable get() = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        .padding(16.dp)

/** Hands out the next row's stagger: each row fades and slides up 8dp, 40ms after the one above. */
private class Stagger(private val scope: AnimatedVisibilityScope) {
    private var row = 0

    @Composable
    fun Modifier.next(): Modifier {
        val delay = 120 + 40 * row++
        val shift = with(LocalDensity.current) { 8.dp.roundToPx() }
        return with(scope) {
            animateEnterExit(
                enter = fadeIn(tween(220, delay, Emphasized)) + slideInVertically(tween(220, delay, Emphasized)) { shift },
                exit = ExitTransition.None,
            )
        }
    }
}

@Composable
private fun RoleSwatches(scheme: ColorScheme, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

/** Glow color, Accent color and Glow strength for Custom neon. Rows stagger in like the Custom editor. */
@Composable
private fun AnimatedVisibilityScope.CustomNeonEditor(
    draft: CustomNeon,
    dark: Boolean,
    pureBlack: Boolean,
    onDraft: (CustomNeon) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val scheme = remember(draft, dark, pureBlack) {
        colorSchemeFor(AppTheme(palette = ThemePalette.CustomNeon, pureBlack = pureBlack, customNeon = draft), dark)
    }
    val neonRainbow = remember { listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { oklch(0.75f, 0.2f, it) } }
    with(Stagger(this)) {
        Column(editorPanel, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().next(), verticalAlignment = Alignment.CenterVertically) {
                Text("Custom neon", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onReset) { Text("Reset") }
            }
            GradientSlider(
                label = "Glow color",
                value = draft.glowHue, range = 0f..359f,
                valueText = "${draft.glowHue.roundToInt()}°",
                track = Brush.horizontalGradient(neonRainbow),
                knob = oklch(0.75f, 0.2f, draft.glowHue),
                onChange = { onDraft(draft.copy(glowHue = it)) }, onCommit = onCommit,
                modifier = Modifier.next(),
            )
            GradientSlider(
                label = "Accent color",
                value = draft.accentHue, range = 0f..359f,
                valueText = "${draft.accentHue.roundToInt()}°",
                track = Brush.horizontalGradient(neonRainbow),
                knob = oklch(0.75f, 0.2f, draft.accentHue),
                onChange = { onDraft(draft.copy(accentHue = it)) }, onCommit = onCommit,
                modifier = Modifier.next(),
            )
            GradientSlider(
                label = "Glow strength",
                value = draft.glow, range = 0f..1f,
                valueText = "${(draft.glow * 100).roundToInt()}%",
                track = Brush.horizontalGradient(listOf(oklch(0.3f, 0.05f, draft.glowHue), oklch(0.8f, 0.22f, draft.glowHue))),
                knob = oklch(0.8f, 0.22f, draft.glowHue),
                onChange = { onDraft(draft.copy(glow = it)) }, onCommit = onCommit,
                modifier = Modifier.next(),
            )
            RoleSwatches(scheme, Modifier.next())
            Text(
                "Glow color lights up buttons, switches and the mirror frame. Accent color is for chips and badges. " +
                    "Turn on Pure black for a black background.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.next(),
            )
        }
    }
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
    with(Stagger(this)) { Column(editorPanel, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().next(), verticalAlignment = Alignment.CenterVertically) {
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
            modifier = Modifier.next(),
        )
        GradientSlider(
            label = "Vividness",
            value = draft.chroma, range = 0f..CustomPalette.MAX_CHROMA,
            valueText = "${(draft.chroma / CustomPalette.MAX_CHROMA * 100).roundToInt()}%",
            track = Brush.horizontalGradient(listOf(oklch(0.65f, 0f, seed.hue), oklch(0.65f, CustomPalette.MAX_CHROMA, seed.hue))),
            knob = oklch(0.65f, seed.chroma, seed.hue),
            onChange = { onDraft(draft.copy(chroma = it)) }, onCommit = onCommit,
            modifier = Modifier.next(),
        )
        GradientSlider(
            label = "Accent shift",
            value = draft.accentShift, range = 0f..180f,
            valueText = "+${draft.accentShift.roundToInt()}°",
            track = Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f).map { oklch(0.65f, 0.14f, (seed.hue + it) % 360f) }),
            knob = oklch(0.65f, 0.14f, seed.tertiaryHue),
            onChange = { onDraft(draft.copy(accentShift = it)) }, onCommit = onCommit,
            modifier = Modifier.next(),
        )
        RoleSwatches(scheme, Modifier.next())
        Text(
            "Color sets the main hue. Vividness goes from grey to full color. Accent shift moves the secondary color around the wheel.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.next(),
        )
    } }
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

/** Works with every palette; picking a neon palette turns it on, and it can be turned off again. */
@Composable
internal fun neonToggle(b: SettingsBindings): Modifier {
    val source = rememberRevealSource()
    val origin = LocalThemeRevealOrigin.current
    return source.modifier.toggleable(
        value = b.state.settings.neon, role = Role.Switch,
        onValueChange = {
            source.mark(origin)
            b.actions.onNeon(it)
        },
    )
}

@Composable
internal fun NeonSwitch(b: SettingsBindings) {
    Switch(checked = b.state.settings.neon, onCheckedChange = null)
}
