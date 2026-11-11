package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.LocalStyleTokens
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.rememberThemeRevealState
import com.programmersbox.scrcpyviewer.ui.theme.themeReveal
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.defaults
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.IconButtonMetrics
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.styling.DecoratedWindowColors
import org.jetbrains.jewel.window.styling.DecoratedWindowStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.jetbrains.jewel.window.utils.clientRegion
import kotlin.math.roundToInt

/** What a title-bar slot can use: the window's [chrome], and [interactive] for clickable parts of the bar. */
interface AppTitleBarScope {
    val chrome: WindowChrome

    /**
     * Marks this element as clickable inside the custom title bar; the rest of the bar drags the window.
     * [key] must be unique within the window. No-op with the system title bar.
     */
    fun Modifier.interactive(key: String): Modifier
}

private object CustomTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.Custom
    override fun Modifier.interactive(key: String): Modifier = clientRegion(key)
}

private object SystemTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.System
    override fun Modifier.interactive(key: String): Modifier = this
}

/**
 * A top-level window whose title bar is the app's own ([WindowChrome.Custom], Jewel decorated window
 * painted from the Material scheme) or the OS one ([WindowChrome.System], plain [Window]). The
 * [titleBar] slot is laid out inside the custom bar (start-aligned after the window controls on
 * macOS / Windows, centred on Linux), or above [content] in System mode — render nothing there if
 * the window wants no extra strip. The slot is as wide as its content, up to the room between the
 * window controls, so a `fillMaxWidth` child spans exactly that. [titleBarHeight] is the custom bar's
 * height. Only pass [WindowChrome.Custom] on JetBrains Runtime (see [windowChrome]). A change of [theme] is
 * revealed in a growing circle over [content] (see [rememberThemeRevealState]); the title bar's colors tween.
 */
@Composable
fun AppWindow(
    onCloseRequest: () -> Unit,
    title: String,
    theme: AppTheme,
    chrome: WindowChrome,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    alwaysOnTop: Boolean = false,
    icon: Painter? = AppIcon.painter,
    titleBarHeight: Dp = 38.dp,
    titleBar: @Composable AppTitleBarScope.() -> Unit,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val reveal = rememberThemeRevealState(theme)
    val shown = reveal.shown
    val dark = shown.mode == ThemeMode.Dark
    // Glass on macOS: the window turns see-through and AppKit blurs the desktop behind it (see setMacVibrancy).
    val desktopBlur = shown.style == ThemeStyle.Glass && shown.desktopBlur && Os.isMac
    CompositionLocalProvider(LocalThemeRevealOrigin provides reveal.origin) {
        when (chrome) {
            WindowChrome.System -> Window(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                alwaysOnTop = alwaysOnTop,
            ) {
                MacVibrancyEffect(window, desktopBlur, dark)
                ScrcpyViewerTheme(shown) {
                    WindowBackgroundEffect(window, MaterialTheme.colorScheme.surface, enabled = !desktopBlur)
                    Column(Modifier.fillMaxSize().themeReveal(reveal).styleBackdrop(transparent = desktopBlur)) {
                        CompositionLocalProvider(LocalContentColor provides barColors(MaterialTheme.colorScheme, LocalStyleTokens.current).content) {
                            SystemTitleBarScope.titleBar()
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
            WindowChrome.Custom -> ScrcpyViewerTheme(shown) {
                val bar = animatedBarColors(barColors(MaterialTheme.colorScheme, LocalStyleTokens.current, desktopBlur))
                IntUiTheme(
                    theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
                    styling = ComponentStyling.default().decoratedWindow(
                        windowStyle = decoratedWindowStyle(bar, dark),
                        titleBarStyle = titleBarStyle(bar, dark, titleBarHeight),
                    ),
                ) {
                    // Jewel's theme replaces LocalIndication; give the window Material's locals back.
                    ScrcpyViewerTheme(shown) {
                        DecoratedWindow(
                            onCloseRequest = onCloseRequest,
                            state = state,
                            visible = visible,
                            title = title,
                            icon = icon,
                            alwaysOnTop = alwaysOnTop,
                        ) {
                            TitleBar { _ ->
                                BoundedSlot {
                                    CompositionLocalProvider(LocalContentColor provides bar.content) {
                                        CustomTitleBarScope.titleBar()
                                    }
                                }
                            }
                            val window = this
                            MacVibrancyEffect(window.window, desktopBlur, dark)
                            WindowBackgroundEffect(window.window, MaterialTheme.colorScheme.surface, enabled = !desktopBlur)
                            Box(Modifier.fillMaxSize().themeReveal(reveal).styleBackdrop(transparent = desktopBlur)) { window.content() }
                        }
                    }
                }
            }
        }
    }
}

/** [colors] tweened over the reveal's length, so the custom title bar follows the window's new palette. */
@Composable
private fun animatedBarColors(colors: BarColors): BarColors {
    val spec = tween<Color>(500, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    return BarColors(
        background = animateColorAsState(colors.background, spec).value,
        inactiveBackground = animateColorAsState(colors.inactiveBackground, spec).value,
        content = animateColorAsState(colors.content, spec).value,
        border = animateColorAsState(colors.border, spec).value,
    )
}

/**
 * The title-bar slot, at most as wide as the room between the window controls. Jewel measures the bar's
 * children against its full width and only then adds the macOS traffic-light / Windows button insets, so a
 * full-width child would run past the right edge (under the buttons on Windows). Jewel keeps those insets
 * private: they are read back from where it places the slot (start) and a zero-width end marker (end), and
 * the slot is narrowed by them from the next layout on. On Linux there are no insets: the pane buttons are
 * children measured before the slot, so its constraints already leave them out.
 */
@Composable
private fun TitleBarScope.BoundedSlot(content: @Composable () -> Unit) {
    var startInset by remember { mutableIntStateOf(0) }
    var endInset by remember { mutableIntStateOf(0) }
    Row(
        Modifier
            .align(if (Os.isLinux) Alignment.CenterHorizontally else Alignment.Start)
            .then(if (Os.isLinux) Modifier else Modifier.onPlaced { startInset = it.positionInParent().x.roundToInt() })
            .layout { measurable, constraints ->
                val maxWidth = (constraints.maxWidth - startInset - endInset).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    if (!Os.isLinux) {
        Box(
            Modifier.align(Alignment.End).onPlaced {
                val barWidth = it.parentLayoutCoordinates?.size?.width ?: return@onPlaced
                endInset = barWidth - it.positionInParent().x.roundToInt()
            },
        )
    }
}

/** Linux frame border (the only place Jewel draws one; macOS / Windows keep the native frame). */
private fun decoratedWindowStyle(bar: BarColors, dark: Boolean): DecoratedWindowStyle {
    val colors = DecoratedWindowColors(border = bar.border, borderInactive = bar.border)
    return if (dark) DecoratedWindowStyle.dark(colors) else DecoratedWindowStyle.light(colors)
}

@Composable
private fun titleBarStyle(bar: BarColors, dark: Boolean, height: Dp): TitleBarStyle {
    val hover = bar.content.copy(alpha = 0.08f)
    val pressed = bar.content.copy(alpha = 0.12f)
    val colors = TitleBarColors(
        background = bar.background,
        inactiveBackground = bar.inactiveBackground,
        content = bar.content,
        // Jewel draws a 1dp line under the bar; same colour as the bar so it reads as one surface with the rail.
        border = bar.background,
        fullscreenControlButtonsBackground = bar.background,
        // Linux pane buttons (GNOME style): neutral hover, close included.
        titlePaneButtonHoveredBackground = hover,
        titlePaneButtonPressedBackground = pressed,
        titlePaneCloseButtonHoveredBackground = hover,
        titlePaneCloseButtonPressedBackground = pressed,
        iconButtonHoveredBackground = hover,
        iconButtonPressedBackground = pressed,
        dropdownHoveredBackground = hover,
        dropdownPressedBackground = pressed,
    )
    val metrics = TitleBarMetrics.defaults(height = height, titlePaneButtonSize = DpSize(32.dp, 32.dp))
    val base = if (dark) TitleBarStyle.dark(colors, metrics) else TitleBarStyle.lightWithLightHeader(colors, metrics)
    // Pane buttons are only drawn by Jewel on Linux (macOS / Windows use the native ones): make them round.
    return TitleBarStyle(
        colors = base.colors,
        metrics = base.metrics,
        icons = base.icons,
        dropdownStyle = base.dropdownStyle,
        iconButtonStyle = base.iconButtonStyle,
        paneButtonStyle = base.paneButtonStyle.round(),
        paneCloseButtonStyle = base.paneCloseButtonStyle.round(),
    )
}

private fun IconButtonStyle.round(): IconButtonStyle =
    IconButtonStyle(colors, with(metrics) { IconButtonMetrics(CornerSize(50), borderWidth, padding, minSize) })

