package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Pointer
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * NSOpenPanel / NSSavePanel through the JNA Objective-C bridge, run modal on the AppKit main thread: in place when
 * the caller is already on it (Tao's event loop), else dispatched there while the caller waits.
 */
internal object MacFileDialogs : NativeFileDialogs {
    private const val NS_MODAL_RESPONSE_OK = 1L

    override fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean, initial: Path?): List<Path> =
        onMainAndWait {
            val panel = ObjC.msgPtr(ObjC.cls("NSOpenPanel"), "openPanel")!!
            configure(panel, title)
            ObjC.msgVoid(panel, "setCanChooseFiles:", !directories)
            ObjC.msgVoid(panel, "setCanChooseDirectories:", directories)
            ObjC.msgVoid(panel, "setAllowsMultipleSelection:", multiple)
            val types = extensionsFilter(extensions)
            if (!directories && types.isNotEmpty()) ObjC.msgVoid(panel, "setAllowedFileTypes:", nsArray(types))
            initial?.let { dir ->
                val url = ObjC.msgPtr(ObjC.cls("NSURL"), "fileURLWithPath:isDirectory:", ObjC.nsString(dir.toString()), true)
                url?.let { ObjC.msgVoid(panel, "setDirectoryURL:", it) }
            }
            if (ObjC.msgLong(panel, "runModal") != NS_MODAL_RESPONSE_OK) return@onMainAndWait emptyList()
            val urls = ObjC.msgPtr(panel, "URLs") ?: return@onMainAndWait emptyList()
            (0 until ObjC.msgLong(urls, "count")).mapNotNull { i ->
                ObjC.msgPtr(urls, "objectAtIndex:", i)?.let(::urlPath)
            }
        }

    override fun save(title: String, suggestedName: String, extension: String): Path? = onMainAndWait {
        val panel = ObjC.msgPtr(ObjC.cls("NSSavePanel"), "savePanel")!!
        configure(panel, title)
        ObjC.msgVoid(panel, "setNameFieldStringValue:", ObjC.nsString(suggestedFileName(suggestedName, extension)))
        val types = extensionsFilter(listOf(extension))
        if (types.isNotEmpty()) ObjC.msgVoid(panel, "setAllowedFileTypes:", nsArray(types))
        if (ObjC.msgLong(panel, "runModal") != NS_MODAL_RESPONSE_OK) return@onMainAndWait null
        ObjC.msgPtr(panel, "URL")?.let(::urlPath)
    }

    private fun configure(panel: Pointer, title: String) {
        // Since macOS 11 the panel has no title bar text, so the title is shown as the message too.
        ObjC.msgVoid(panel, "setTitle:", ObjC.nsString(title))
        ObjC.msgVoid(panel, "setMessage:", ObjC.nsString(title))
        ObjC.msgVoid(panel, "setCanCreateDirectories:", true)
    }

    private fun nsArray(values: List<String>): Pointer {
        val array = ObjC.msgPtr(ObjC.cls("NSMutableArray"), "array")!!
        for (v in values) ObjC.msgVoid(array, "addObject:", ObjC.nsString(v))
        return array
    }

    private fun urlPath(url: Pointer): Path? =
        ObjC.msgPtr(url, "path")?.let { ObjC.msgPtr(it, "UTF8String") }?.getString(0, "UTF-8")?.let { Path.of(it) }

    private fun isMainThread(): Boolean = ObjC.msgBool(ObjC.cls("NSThread"), "isMainThread")

    /** Runs [block] on the AppKit main thread inside an autorelease pool and returns its result. */
    private fun <T> onMainAndWait(block: () -> T): T {
        val pooled = {
            val pool = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSAutoreleasePool"), "alloc")!!, "init")!!
            try {
                block()
            } finally {
                ObjC.msgVoid(pool, "drain")
            }
        }
        if (isMainThread()) return pooled()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        ObjC.onMain {
            try {
                result = runCatching(pooled)
            } finally {
                done.countDown()
            }
        }
        done.await()
        return result!!.getOrThrow()
    }
}
