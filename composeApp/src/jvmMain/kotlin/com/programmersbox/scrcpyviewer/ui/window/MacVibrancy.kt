package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.ui.awt.ComposeWindow
import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.jetbrains.skiko.SkiaLayer
import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.Container
import java.util.Collections

/** NSVisualEffectMaterial values (AppKit). */
enum class MacVibrancyMaterial(val value: Long) {
    Titlebar(3), Menu(5), Popover(6), Sidebar(7), HeaderView(10), Sheet(11), WindowBackground(12),
    HudWindow(13), FullScreenUI(15), ContentBackground(18), UnderWindowBackground(21), UnderPageBackground(22),
}

/**
 * Puts a macOS NSVisualEffectView (blending mode behind-window) under this window's content, so whatever Compose
 * leaves transparent shows the blurred desktop. Returns false when unsupported (not macOS, native call failed);
 * never throws. Call after the window is displayable (e.g. from a LaunchedEffect inside the window content).
 *
 * Works on decorated windows (system title bar and the JBR custom title bar) without Compose's `transparent = true`:
 * the Skia layer is switched to transparent through skiko's public [SkiaLayer.transparency], and the NSWindow, its
 * Java2D content layer and the effect view are configured natively on the AppKit thread.
 *
 * [dark] picks the darkAqua or aqua appearance for the effect view; call again when the theme mode changes.
 */
fun ComposeWindow.setMacVibrancy(
    enabled: Boolean,
    dark: Boolean,
    material: MacVibrancyMaterial = MacVibrancyMaterial.UnderWindowBackground,
): Boolean {
    if (!Os.isMac) return false
    return try {
        val skia = findSkiaLayer(this) ?: return false
        val handle = windowHandle
        if (handle == 0L) return false
        skia.transparency = enabled
        skia.background = if (enabled) Color(0, 0, 0, 0) else null
        rootPane.isOpaque = !enabled
        (contentPane as? javax.swing.JComponent)?.isOpaque = !enabled
        // AWT sizes are in points, like AppKit's; the effect view follows later resizes by itself.
        val w = width.toDouble()
        val h = height.toDouble()
        ObjC.onMain { MacVibrancyNative.apply(Pointer(handle), enabled, dark, material.value, w, h) }
        skia.needRender()
        true
    } catch (t: Throwable) {
        System.err.println("setMacVibrancy failed: $t")
        false
    }
}

internal fun findSkiaLayer(container: Container): SkiaLayer? {
    for (c in container.components) {
        if (c is SkiaLayer) return c
        if (c is Container) findSkiaLayer(c)?.let { return it }
    }
    return null
}

private object MacVibrancyNative {
    private const val NS_WINDOW_BELOW = -1L
    private const val NS_VIEW_WIDTH_SIZABLE_HEIGHT_SIZABLE = 2L or 16L
    private const val BLENDING_BEHIND_WINDOW = 0L
    private const val STATE_FOLLOWS_WINDOW = 0L
    private const val IDENTIFIER = "scrcpyviewer.vibrancy"

    /** Runs on the AppKit main thread. [handle] is skiko's window handle (an NSView or NSWindow). */
    fun apply(handle: Pointer, enabled: Boolean, dark: Boolean, material: Long, width: Double, height: Double) {
        val nsWindow = when {
            ObjC.isKindOf(handle, "NSWindow") -> handle
            ObjC.isKindOf(handle, "NSView") -> ObjC.msgPtr(handle, "window")
            else -> null
        } ?: return
        val contentView = ObjC.msgPtr(nsWindow, "contentView") ?: return
        val frameView = ObjC.msgPtr(contentView, "superview") ?: return

        var effect = findEffectView(frameView)
        if (!enabled) {
            effect?.let { ObjC.msgVoid(it, "removeFromSuperview") }
            ObjC.msgVoid(nsWindow, "setOpaque:", true)
            ObjC.msgVoid(nsWindow, "setBackgroundColor:", ObjC.msgPtr(ObjC.cls("NSColor"), "windowBackgroundColor")!!)
            ObjC.msgVoid(nsWindow, "setAppearance:", null) // back to following the system
            ObjC.msgPtr(contentView, "layer")?.let { ObjC.msgVoid(it, "setOpaque:", true) }
            return
        }

        if (effect == null) {
            // Built from the window size rather than read back with [frameView]'s bounds: returning a struct needs
            // objc_msgSend_stret on Intel Macs, which plain objc_msgSend would get wrong.
            val bounds = ObjC.CGRect().apply { this.width = width; this.height = height }
            effect = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSVisualEffectView"), "alloc")!!, "initWithFrame:", bounds)!!
            ObjC.msgVoid(effect, "setAutoresizingMask:", NS_VIEW_WIDTH_SIZABLE_HEIGHT_SIZABLE)
            ObjC.msgVoid(effect, "setBlendingMode:", BLENDING_BEHIND_WINDOW)
            ObjC.msgVoid(effect, "setState:", STATE_FOLLOWS_WINDOW)
            ObjC.msgVoid(effect, "setIdentifier:", ObjC.nsString(IDENTIFIER))
            ObjC.msgVoid(frameView, "addSubview:positioned:relativeTo:", effect, NS_WINDOW_BELOW, contentView)
            ObjC.msgVoid(effect, "release") // retained by its superview
        }
        ObjC.msgVoid(effect, "setMaterial:", material)
        val appearanceName = ObjC.nsString(if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
        ObjC.msgPtr(ObjC.cls("NSAppearance"), "appearanceNamed:", appearanceName)
            // On the window, not just the effect view, so the native title text and traffic lights match.
            ?.let { ObjC.msgVoid(nsWindow, "setAppearance:", it) }

        ObjC.msgVoid(nsWindow, "setOpaque:", false)
        ObjC.msgVoid(nsWindow, "setBackgroundColor:", ObjC.msgPtr(ObjC.cls("NSColor"), "clearColor")!!)
        // The AWT content view's layer holds the Java2D window surface; make it non-opaque so it does not cover us.
        ObjC.msgPtr(contentView, "layer")?.let { ObjC.msgVoid(it, "setOpaque:", false) }
    }

    private fun findEffectView(frameView: Pointer): Pointer? {
        val subviews = ObjC.msgPtr(frameView, "subviews") ?: return null
        val id = ObjC.nsString(IDENTIFIER)
        for (i in 0 until ObjC.msgLong(subviews, "count")) {
            val view = ObjC.msgPtr(subviews, "objectAtIndex:", i) ?: continue
            val viewId = ObjC.msgPtr(view, "identifier") ?: continue
            if (ObjC.msgBool(viewId, "isEqualToString:", id)) return view
        }
        return null
    }
}

/** Minimal Objective-C runtime bridge over JNA. */
internal object ObjC {
    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val getClass: Function = objc.getFunction("objc_getClass")
    private val selRegister: Function = objc.getFunction("sel_registerName")
    private val system = NativeLibrary.getInstance("System")
    private val dispatchAsync: Function = system.getFunction("dispatch_async_f")
    private val mainQueue: Pointer = system.getGlobalVariableAddress("_dispatch_main_q")
    private val pending = Collections.synchronizedSet(HashSet<Callback>())

    @Structure.FieldOrder("x", "y", "width", "height")
    class CGRect : Structure(), Structure.ByValue {
        @JvmField var x = 0.0
        @JvmField var y = 0.0
        @JvmField var width = 0.0
        @JvmField var height = 0.0
    }

    fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name)) ?: error("No class $name")
    fun sel(name: String): Pointer = selRegister.invokePointer(arrayOf(name))

    fun msgPtr(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    fun msgVoid(receiver: Pointer, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }

    fun msgLong(receiver: Pointer, selector: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))

    fun msgBool(receiver: Pointer, selector: String, vararg args: Any?): Boolean =
        msgSend.invokeInt(arrayOf(receiver, sel(selector), *args)) and 0xFF != 0

    private val msgSendStret: Function? by lazy { runCatching { objc.getFunction("objc_msgSend_stret") }.getOrNull() }

    /** A CGRect-returning getter as [x, y, width, height]. Intel returns structs this big through objc_msgSend_stret. */
    fun msgRect(receiver: Pointer, selector: String): DoubleArray {
        val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
        val r = if (arm) {
            msgSend.invoke(CGRect::class.java, arrayOf(receiver, sel(selector))) as CGRect
        } else {
            val buffer = com.sun.jna.Memory(32)
            (msgSendStret ?: error("no objc_msgSend_stret")).invokeVoid(arrayOf(buffer, receiver, sel(selector)))
            CGRect().apply { x = buffer.getDouble(0); y = buffer.getDouble(8); width = buffer.getDouble(16); height = buffer.getDouble(24) }
        }
        return doubleArrayOf(r.x, r.y, r.width, r.height)
    }

    fun nsString(value: String): Pointer = msgPtr(cls("NSString"), "stringWithUTF8String:", value)!!

    fun isKindOf(obj: Pointer, className: String): Boolean =
        msgBool(obj, "isKindOfClass:", cls(className))

    /** Runs [block] asynchronously on the AppKit main thread; exceptions are logged. */
    fun onMain(block: () -> Unit) {
        lateinit var callback: DispatchFunction
        callback = DispatchFunction {
            try {
                block()
            } catch (t: Throwable) {
                System.err.println("MacVibrancy main-thread call failed: $t")
            } finally {
                pending.remove(callback)
            }
        }
        pending.add(callback) // keep it reachable until AppKit runs it
        dispatchAsync.invokeVoid(arrayOf(mainQueue, null, callback))
    }
}

/**
 * Keeps [window]'s desktop blur in step with [enabled] and [dark]. Never touches a window that has not had the blur,
 * so the other styles keep AWT's untouched, opaque window.
 *
 * [titleBarBackground] is the color Nucleus' AWT window paints every AWT component (and the frame, which makes AppKit
 * opaque again) with, from a posted task, each time it changes. While the blur is on, it is applied again after that
 * pass so the window stays see-through.
 */
@androidx.compose.runtime.Composable
fun MacVibrancyEffect(window: ComposeWindow, enabled: Boolean, dark: Boolean, titleBarBackground: Any? = null) {
    val applied = androidx.compose.runtime.remember(window) { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(window, enabled, dark) {
        if (enabled || applied[0]) {
            window.setMacVibrancy(enabled, dark)
            applied[0] = enabled
        }
    }
    androidx.compose.runtime.LaunchedEffect(window, enabled, dark, titleBarBackground) {
        if (enabled) java.awt.EventQueue.invokeLater { window.setMacVibrancy(true, dark) }
    }
}

/**
 * Paints the window's own background in [color] (opaque), so a resize shows the theme's color in the strip Skia has
 * not drawn yet instead of the default light gray or white: the AWT window, its panes and Skia layer, and on macOS
 * the NSWindow. Never throws.
 */
fun ComposeWindow.setWindowBackground(color: Color) {
    try {
        background = color
        rootPane.background = color
        contentPane.background = color
        findSkiaLayer(this)?.background = color
        if (Os.isMac) {
            val handle = windowHandle
            if (handle != 0L) {
                val r = color.red / 255.0
                val g = color.green / 255.0
                val b = color.blue / 255.0
                ObjC.onMain {
                    val h = Pointer(handle)
                    val nsWindow = when {
                        ObjC.isKindOf(h, "NSWindow") -> h
                        ObjC.isKindOf(h, "NSView") -> ObjC.msgPtr(h, "window")
                        else -> null
                    } ?: return@onMain
                    val ns = ObjC.msgPtr(ObjC.cls("NSColor"), "colorWithSRGBRed:green:blue:alpha:", r, g, b, 1.0)
                        ?: return@onMain
                    ObjC.msgVoid(nsWindow, "setBackgroundColor:", ns)
                }
            }
        }
    } catch (t: Throwable) {
        System.err.println("setWindowBackground failed: $t")
    }
}

/** Keeps the window's background at [color] while [enabled] (off for the see-through Glass window). */
@androidx.compose.runtime.Composable
fun WindowBackgroundEffect(window: ComposeWindow, color: androidx.compose.ui.graphics.Color, enabled: Boolean) {
    val argb = color.toArgb()
    // After MacVibrancyEffect in composition order, so turning the blur off ends with this color, not the system's.
    androidx.compose.runtime.LaunchedEffect(window, argb, enabled) {
        if (enabled) {
            window.setWindowBackground(Color(argb, false))
            // Nucleus' window paints its AWT components in the title bar's background from a posted task when it
            // opens; posting this too makes the theme's color land last.
            java.awt.EventQueue.invokeLater { window.setWindowBackground(Color(argb, false)) }
        }
    }
}

/**
 * [handle] as an NSWindow: itself, or the window of the view it is. Runs on the AppKit main thread; null when it is
 * neither.
 */
private fun resolveNsWindow(handle: Pointer): Pointer? = when {
    ObjC.isKindOf(handle, "NSWindow") -> handle
    ObjC.isKindOf(handle, "NSView") -> ObjC.msgPtr(handle, "window")
    else -> null
}

/**
 * Animates [nsWindow] (an NSWindow, or a view in it) to [to] (window units, top-left origin) natively on macOS, over
 * [millis] along the cubic-bezier [curve] (x1, y1, x2, y2). AppKit moves the frame itself, so it stays smooth while
 * the content catches up. The main screen's height, for the flip to AppKit's bottom-left origin, comes from
 * [geometry]. Returns false (nothing started) when not on macOS or the main screen is unknown; never throws.
 */
fun animateMacFrame(nsWindow: Pointer, to: java.awt.Rectangle, millis: Int, curve: FloatArray, geometry: ScreenGeometry): Boolean {
    if (!Os.isMac) return false
    return try {
        // AppKit's origin is the bottom-left of the main screen (the one at 0,0 in window units); ours is its top-left.
        val main = geometry.screens().map { it.bounds }.firstOrNull { it.x == 0 && it.y == 0 } ?: return false
        val frame = ObjC.CGRect().apply {
            x = to.x.toDouble()
            y = (main.height - to.y - to.height).toDouble()
            width = to.width.toDouble()
            height = to.height.toDouble()
        }
        ObjC.onMain {
            val window = resolveNsWindow(nsWindow) ?: return@onMain
            val contexts = ObjC.cls("NSAnimationContext")
            ObjC.msgVoid(contexts, "beginGrouping")
            val context = ObjC.msgPtr(contexts, "currentContext")!!
            ObjC.msgVoid(context, "setDuration:", millis / 1000.0)
            ObjC.msgPtr(
                ObjC.cls("CAMediaTimingFunction"), "functionWithControlPoints::::",
                curve[0], curve[1], curve[2], curve[3],
            )?.let { ObjC.msgVoid(context, "setTimingFunction:", it) }
            ObjC.msgVoid(ObjC.msgPtr(window, "animator")!!, "setFrame:display:", frame, true)
            ObjC.msgVoid(contexts, "endGrouping")
        }
        true
    } catch (t: Throwable) {
        System.err.println("animateMacFrame failed: $t")
        false
    }
}

/**
 * Sets [nsWindow]'s native opacity on macOS (NSWindow alphaValue, 0..1), which, unlike AWT's opacity, works with a
 * title bar. [nsWindow] is an NSWindow or a view in it. Returns false (nothing done) when not on macOS; never throws.
 */
fun setMacAlpha(nsWindow: Pointer, alpha: Double): Boolean {
    if (!Os.isMac) return false
    return try {
        ObjC.onMain {
            val window = resolveNsWindow(nsWindow) ?: return@onMain
            ObjC.msgVoid(window, "setAlphaValue:", alpha)
        }
        true
    } catch (t: Throwable) {
        System.err.println("setMacAlpha failed: $t")
        false
    }
}

/** NSWindowAnimationBehaviorNone. */
private const val NS_WINDOW_ANIMATION_NONE = 2L

/**
 * Turns off the zoom macOS plays as [nsWindow] (an NSWindow or a view in it) appears, for windows that animate their
 * own opening. Call while the window is displayable but not yet shown. No-op elsewhere; never throws.
 */
fun disableMacAppearAnimation(nsWindow: Pointer) {
    if (!Os.isMac) return
    try {
        ObjC.onMain {
            val window = resolveNsWindow(nsWindow) ?: return@onMain
            ObjC.msgVoid(window, "setAnimationBehavior:", NS_WINDOW_ANIMATION_NONE)
        }
    } catch (t: Throwable) {
        System.err.println("disableMacAppearAnimation failed: $t")
    }
}
