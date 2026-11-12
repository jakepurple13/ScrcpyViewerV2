package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.sun.jna.Pointer
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.common.ProvideAppClipboard
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.LocalStyleTokens
import com.programmersbox.scrcpyviewer.ui.theme.LocalThemeRevealOrigin
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.theme.rememberThemeRevealState
import com.programmersbox.scrcpyviewer.ui.theme.styleBackdrop
import com.programmersbox.scrcpyviewer.ui.theme.themeReveal
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlin.math.roundToInt

/** What a title-bar slot can use: the window's [chrome], and [interactive] for clickable parts of the bar. */
interface AppTitleBarScope {
    val chrome: WindowChrome

    /**
     * Marks this element as clickable inside the title bar. A no-op: Nucleus title-bar children get their own
     * pointer input, and the bar drags the window only from presses no child consumed. [key] is kept for call sites.
     */
    fun Modifier.interactive(key: String): Modifier
}

private object CustomTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.Custom
    override fun Modifier.interactive(key: String): Modifier = this
}

/**
 * A top-level window with the app's own title bar: a Nucleus [MaterialDecoratedWindow] whose [MaterialTitleBar] is
 * painted from the Material scheme in the theme's style. The [titleBar] slot is laid out inside the bar
 * (start-aligned after the window controls on macOS / Windows, centred on Linux). The slot is as wide as its
 * content, up to the room between the window controls, so a `fillMaxWidth` child spans exactly that.
 * [titleBarHeight] is the bar's height. A change of [theme] is revealed in a growing circle over [content] (see
 * [rememberThemeRevealState]); the title bar's colors tween.
 *
 * Glass with desktop blur needs a see-through window, which Tao only makes when the window is created: a window opened
 * in another style keeps an opaque background until it is opened again (and one opened in Glass stays see-through,
 * painted opaque by its content in the other styles).
 */
@Composable
fun NucleusApplicationScope.AppWindow(
    onCloseRequest: () -> Unit,
    title: String,
    theme: AppTheme,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    alwaysOnTop: Boolean = false,
    icon: Painter? = AppIcon.painter,
    titleBarHeight: Dp = 38.dp,
    /** Sees each key event of this window before its focused content; true consumes it. */
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBar: @Composable AppTitleBarScope.() -> Unit,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    val reveal = rememberThemeRevealState(theme)
    val shown = reveal.shown
    val dark = shown.mode == ThemeMode.Dark
    // Glass on macOS: the window is see-through and AppKit blurs the desktop behind it (see setMacVibrancy). Only a
    // window created see-through can show it (creation-time on Tao).
    val wantsBlur = shown.style == ThemeStyle.Glass && shown.desktopBlur && Os.isMac
    val transparent = remember { wantsBlur }
    val desktopBlur = wantsBlur && transparent
    ScrcpyViewerTheme(shown) {
        val bar = animatedBarColors(barColors(MaterialTheme.colorScheme, LocalStyleTokens.current, desktopBlur))
        MaterialDecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            titleBarStyle = nucleusTitleBarStyle(bar, titleBarHeight),
            transparent = transparent,
        ) {
            // Tao windows run their own Compose scene: the theme, the reveal origin and the clipboard are provided
            // again here (Nucleus bridges the outer locals, but the theme must follow `shown` inside the window too).
            CompositionLocalProvider(LocalThemeRevealOrigin provides reveal.origin) {
                ProvideAppClipboard {
                    ScrcpyViewerTheme(shown) {
                        // Drawn with the bar's own colors, see-through for Glass with blur (see nucleusTitleBarStyle).
                        MaterialTitleBar(
                            if (desktopBlur) ClearWindowRoot else Modifier,
                            style = titleBarStyle(bar, titleBarHeight),
                        ) {
                            BoundedSlot {
                                CompositionLocalProvider(LocalContentColor provides bar.content) {
                                    CustomTitleBarScope.titleBar()
                                }
                            }
                        }
                        nucleusWindow.unsafe.taoWindow?.nsWindowHandle?.takeIf { it != 0L && Os.isMac }?.let { ns ->
                            MacVibrancyEffect(Pointer(ns), desktopBlur, dark)
                        }
                        Box(
                            Modifier
                                // First, so it draws on the window's own canvas, not in a layer of the reveal's.
                                .then(if (desktopBlur) ClearWindowRoot else Modifier)
                                .fillMaxSize()
                                .themeReveal(reveal)
                                .styleBackdrop(transparent = desktopBlur),
                        ) { content() }
                    }
                }
            }
        }
    }
}

/**
 * Clears what is under the content to fully transparent, so nothing Nucleus paints under the content (the title bar's
 * opaque background) hides the Glass window's blurred desktop.
 */
private val ClearWindowRoot: Modifier = Modifier.drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }

/** Restores [window] when minimized, then raises and focuses it. */
fun bringToFront(window: NucleusWindow) {
    window.setMinimized(false)
    window.toFront()
    window.requestFocus()
}

/** [colors] tweened over the reveal's length, so the title bar follows the window's new palette. */
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
 * The title-bar slot, at most as wide as the room between the window controls. Nucleus measures the bar's children
 * against its full width and only then adds the macOS traffic-light / Windows button insets, so a full-width child
 * would run past the right edge (under the buttons on Windows). The insets are read back from where the slot is
 * placed (start) and a zero-width end marker (end), and the slot is narrowed by them from the next layout on. On
 * Linux the slot is centred and not narrowed.
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

/**
 * The window's style, handed to Nucleus: [titleBarStyle] with opaque backgrounds, which Nucleus uses for the window's
 * own background. The Glass bar with blur is see-through, so it is laid over black (dark) or white (light) here; the
 * bar itself is drawn with [titleBarStyle], and the window stays see-through through [ClearWindowRoot] and
 * [MacVibrancyEffect].
 */
internal fun nucleusTitleBarStyle(bar: BarColors, height: Dp): TitleBarStyle {
    val style = titleBarStyle(bar, height)
    val under = if (bar.content.luminance() > 0.5f) Color.Black else Color.White
    fun Color.opaque() = if (alpha >= 1f) this else compositeOver(under)
    return style.copy(
        colors = style.colors.copy(
            background = style.colors.background.opaque(),
            inactiveBackground = style.colors.inactiveBackground.opaque(),
            border = style.colors.border.opaque(),
            fullscreenControlButtonsBackground = style.colors.fullscreenControlButtonsBackground.opaque(),
        ),
    )
}

/** The bar's style in its own colors, for drawing it. */
private fun titleBarStyle(bar: BarColors, height: Dp): TitleBarStyle {
    val hover = bar.content.copy(alpha = 0.08f)
    val pressed = bar.content.copy(alpha = 0.12f)
    return TitleBarStyle(
        colors = TitleBarColors(
            background = bar.background,
            inactiveBackground = bar.inactiveBackground,
            content = bar.content,
            border = bar.background,
            fullscreenControlButtonsBackground = bar.background,
            // Windows caption buttons (min / max): a neutral hover; close stays the system red.
            iconButtonHoveredBackground = hover,
            iconButtonPressedBackground = pressed,
        ),
        metrics = TitleBarMetrics(height = height, titlePaneButtonSize = DpSize(32.dp, 32.dp)),
    )
}
