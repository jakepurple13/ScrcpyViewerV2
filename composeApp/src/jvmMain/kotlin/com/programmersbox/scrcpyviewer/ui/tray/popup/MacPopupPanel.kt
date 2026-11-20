package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.programmersbox.scrcpyviewer.ui.window.resolveNsWindow
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** AppKit bits the popup panel sets, kept apart so the bit math is testable off macOS. */
internal object MacPanelBits {
    /** NSWindowStyleMaskNonactivatingPanel. AppKit keeps it only on an NSPanel; a plain NSWindow drops it. */
    const val NON_ACTIVATING_PANEL = 1L shl 7

    /** NSWindowCollectionBehaviorCanJoinAllSpaces: the popup opens on whichever Space is current. */
    const val CAN_JOIN_ALL_SPACES = 1L shl 0

    /** NSWindowCollectionBehaviorFullScreenAuxiliary: it may open over another app's full-screen Space. */
    const val FULL_SCREEN_AUXILIARY = 1L shl 8

    /**
     * NSStatusWindowLevel (25): above the menu bar (24) and the always-on-top mirror windows (floating, 3), and below
     * pop-up menus (101), so a menu opened from inside the popup still shows over it.
     */
    const val STATUS_WINDOW_LEVEL = 25L

    fun panelStyleMask(mask: Long): Long = mask or NON_ACTIVATING_PANEL

    fun windowStyleMask(mask: Long): Long = mask and NON_ACTIVATING_PANEL.inv()

    const val COLLECTION_BEHAVIOR = CAN_JOIN_ALL_SPACES or FULL_SCREEN_AUXILIARY

    /** The runtime panel class made for windows of [original]. */
    fun panelClassName(original: String): String = "ScrcpyVwrPopupPanel_$original"
}

/**
 * Turns the popup's NSWindow into a non-activating key panel (the tauri-nspanel technique), so opening it from the
 * menu bar doesn't activate the app and bring the main or mirror windows in front of the app the user is in.
 *
 * Tao's window is a `TaoWindow`, its own NSWindow subclass (canBecomeKeyWindow, canBecomeMainWindow, sendEvent:
 * overrides). AppKit honours the non-activating style bit only on an NSPanel, so the window's class is swapped
 * (`object_setClass`) for a runtime NSPanel subclass that carries copies of every method `TaoWindow` (and any class
 * between it and NSWindow) adds, then overrides canBecomeKeyWindow (YES) and canBecomeMainWindow (NO). NSPanel adds no
 * instance variables to NSWindow, and the object keeps the memory `TaoWindow` allocated, so Tao's own ivars stay put.
 *
 * Swapping a window's class breaks AppKit's KVO bookkeeping at dealloc ("Cannot remove an observer … for the key path
 * _windowLayerContext"), so the window is retained while converted and [restoreWindowFromPanel] puts its class back
 * before letting it go.
 *
 * Main thread only (Compose runs on Tao's main thread on macOS). Returns false, changing nothing, when not on macOS,
 * not on the main thread, or anything fails; the popup then behaves as before (it brings itself forward).
 */
fun makeNonActivatingPanel(nsWindow: Pointer): Boolean {
    if (!Os.isMac) return false
    return try {
        MacPopupPanelNative.convert(nsWindow)
    } catch (t: Throwable) {
        System.err.println("makeNonActivatingPanel failed: $t")
        restoreWindowFromPanel(nsWindow) // undoes a class swap that got done before the failure
        false
    }
}

/** Shows a window [makeNonActivatingPanel] converted, and makes it key, without activating the app. Main thread. */
fun showNonActivatingPanel(nsWindow: Pointer) {
    try {
        MacPopupPanelNative.show(nsWindow)
    } catch (t: Throwable) {
        System.err.println("showNonActivatingPanel failed: $t")
    }
}

/** Gives a converted window its own class back and lets go of it; a window never converted is left alone. */
fun restoreWindowFromPanel(nsWindow: Pointer) {
    try {
        MacPopupPanelNative.restore(nsWindow)
    } catch (t: Throwable) {
        System.err.println("restoreWindowFromPanel failed: $t")
    }
}

/**
 * Runs [block] on the AppKit main thread and returns its result: at once when already there, else dispatched and
 * awaited for at most [timeoutMs] (null on timeout or failure).
 */
internal fun <T> onMacMainThread(timeoutMs: Long = 2_000, block: () -> T): T? {
    if (ObjC.msgBool(ObjC.cls("NSThread"), "isMainThread")) return block()
    val done = CountDownLatch(1)
    var result: T? = null
    ObjC.onMain {
        try {
            result = block()
        } finally {
            done.countDown()
        }
    }
    return if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
}

private object MacPopupPanelNative {
    private val objc = NativeLibrary.getInstance("objc")
    private val system = NativeLibrary.getInstance("System")
    private fun fn(name: String): Function = objc.getFunction(name)

    fun interface BoolImp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?): Byte
    }

    // Held for the life of the process: AppKit calls them for as long as the runtime class exists.
    private val yes = BoolImp { _, _ -> 1 }
    private val no = BoolImp { _, _ -> 0 }

    /** BOOL's type encoding: a C bool on Apple silicon, a signed char on Intel. */
    private val boolEncoding =
        if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "B@:" else "c@:"

    /** Converted windows (NSWindow address) to their own class, for [restore]. Main thread only. */
    private val converted = HashMap<Long, Pointer>()

    /** Runtime panel classes, by the original class's address. Main thread only. */
    private val panelClasses = HashMap<Long, Pointer>()

    fun convert(handle: Pointer): Boolean {
        if (!ObjC.msgBool(ObjC.cls("NSThread"), "isMainThread")) {
            System.err.println("makeNonActivatingPanel: not on the main thread")
            return false
        }
        val window = resolveNsWindow(handle) ?: return false
        val key = Pointer.nativeValue(window)
        if (key in converted) return true
        val original = fn("object_getClass").invokePointer(arrayOf(window)) ?: return false
        val originalName = className(original)
        // A KVO-observed window's class is a runtime NSKVONotifying_ one; swapping it would drop those observers.
        if (originalName.startsWith("NSKVONotifying_")) {
            System.err.println("makeNonActivatingPanel: window is KVO-observed ($originalName), left as is")
            return false
        }
        if (!ObjC.isKindOf(window, "NSPanel")) {
            val panelClass = panelClassFor(original, originalName) ?: return false
            ObjC.msgPtr(window, "retain") // see restore()
            fn("object_setClass").invokePointer(arrayOf(window, panelClass))
            converted[key] = original
        }
        applyPanelState(window)
        return true
    }

    fun show(handle: Pointer) {
        val window = resolveNsWindow(handle) ?: return
        // Tao rewrites styleMask when its own window state changes, which would drop the bit; put it back first.
        applyPanelState(window)
        ObjC.msgVoid(window, "orderFrontRegardless")
        ObjC.msgVoid(window, "makeKeyWindow")
    }

    fun restore(handle: Pointer) {
        val window = resolveNsWindow(handle) ?: handle
        val original = converted.remove(Pointer.nativeValue(window)) ?: return
        ObjC.msgVoid(window, "setStyleMask:", MacPanelBits.windowStyleMask(ObjC.msgLong(window, "styleMask")))
        fn("object_setClass").invokePointer(arrayOf(window, original))
        ObjC.msgVoid(window, "release")
    }

    private fun applyPanelState(window: Pointer) {
        val mask = ObjC.msgLong(window, "styleMask")
        val panelMask = MacPanelBits.panelStyleMask(mask)
        if (panelMask != mask) ObjC.msgVoid(window, "setStyleMask:", panelMask)
        // Private, so guarded: also tags the window server side, so a click in the popup never activates the app.
        if (ObjC.msgBool(window, "respondsToSelector:", ObjC.sel("_setPreventsActivation:"))) {
            ObjC.msgVoid(window, "_setPreventsActivation:", true)
        }
        ObjC.msgVoid(window, "setCollectionBehavior:", MacPanelBits.COLLECTION_BEHAVIOR)
        ObjC.msgVoid(window, "setHidesOnDeactivate:", false)
        // setFloatingPanel: sets the floating level itself, so the level goes after it.
        ObjC.msgVoid(window, "setFloatingPanel:", true)
        ObjC.msgVoid(window, "setLevel:", MacPanelBits.STATUS_WINDOW_LEVEL)
    }

    private fun panelClassFor(original: Pointer, originalName: String): Pointer? {
        panelClasses[Pointer.nativeValue(original)]?.let { return it }
        val name = MacPanelBits.panelClassName(originalName)
        val existing = fn("objc_getClass").invokePointer(arrayOf(name))
        val cls = existing ?: build(original, name) ?: return null
        panelClasses[Pointer.nativeValue(original)] = cls
        return cls
    }

    /**
     * An NSPanel subclass carrying the methods [original] adds over NSWindow; null when [original] isn't an NSWindow.
     */
    private fun build(original: Pointer, name: String): Pointer? {
        val nsWindow = ObjC.cls("NSWindow")
        // The classes between NSWindow and the window's own class, nearest first.
        val chain = ArrayList<Pointer>()
        var c: Pointer? = original
        while (c != null && Pointer.nativeValue(c) != Pointer.nativeValue(nsWindow)) {
            chain += c
            c = fn("class_getSuperclass").invokePointer(arrayOf(c))
        }
        if (c == null) {
            System.err.println("makeNonActivatingPanel: ${className(original)} is not an NSWindow")
            return null
        }
        val cls = fn("objc_allocateClassPair").invokePointer(arrayOf(ObjC.cls("NSPanel"), name, 0L)) ?: return null
        val addMethod = fn("class_addMethod")
        addMethod.invokeInt(arrayOf(cls, ObjC.sel("canBecomeKeyWindow"), yes, boolEncoding))
        addMethod.invokeInt(arrayOf(cls, ObjC.sel("canBecomeMainWindow"), no, boolEncoding))
        // Nearest class first: class_addMethod refuses a selector already added, so the nearest override wins.
        for (k in chain) copyMethods(k, cls)
        fn("objc_registerClassPair").invokeVoid(arrayOf(cls))
        return cls
    }

    private fun copyMethods(from: Pointer, to: Pointer) {
        val count = IntByReference()
        val list = fn("class_copyMethodList").invokePointer(arrayOf(from, count)) ?: return
        try {
            for (i in 0 until count.value) {
                val method = list.getPointer(i.toLong() * pointerSize) ?: continue
                val sel = fn("method_getName").invokePointer(arrayOf(method))
                val imp = fn("method_getImplementation").invokePointer(arrayOf(method))
                val types = fn("method_getTypeEncoding").invokePointer(arrayOf(method))
                fn("class_addMethod").invokeInt(arrayOf(to, sel, imp, types))
            }
            val protocolCount = IntByReference()
            fn("class_copyProtocolList").invokePointer(arrayOf(from, protocolCount))?.let { protocols ->
                try {
                    for (i in 0 until protocolCount.value) {
                        protocols.getPointer(i.toLong() * pointerSize)?.let {
                            fn("class_addProtocol").invokeInt(arrayOf(to, it))
                        }
                    }
                } finally {
                    system.getFunction("free").invokeVoid(arrayOf(protocols))
                }
            }
        } finally {
            system.getFunction("free").invokeVoid(arrayOf(list))
        }
    }

    private fun className(cls: Pointer): String =
        fn("class_getName").invokePointer(arrayOf(cls))?.getString(0, "UTF-8") ?: "?"

    private val pointerSize = Native.POINTER_SIZE.toLong()
}
