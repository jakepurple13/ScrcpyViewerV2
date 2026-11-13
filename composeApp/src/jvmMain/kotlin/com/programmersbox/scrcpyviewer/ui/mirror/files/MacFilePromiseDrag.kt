package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * File-promise drags on macOS (NSFilePromiseProvider), see docs/superpowers/notes/2026-11-13-file-promise-drag-spike.md.
 * The drag starts from [nsWindow]'s content view with the mouse event being handled; Finder asks for each item only
 * after the drop, on a background queue. Tao's own outbound drag is not involved.
 *
 * The ObjC delegate class is built once at run time over JNA; its callbacks and the delegate live as long as the app.
 */
class MacFilePromiseDragStarter(private val nsWindow: () -> Pointer?) : PromiseDragStarter {
    override fun start(items: List<PromisedItem>, onEnd: (dropped: Boolean) -> Unit): Boolean {
        val window = nsWindow() ?: run {
            logDragOut("promise start refused: no NSWindow")
            return false
        }
        return try {
            MacFilePromises.start(window, items, onEnd)
        } catch (t: Throwable) {
            logDragOutWarn("promise start failed", t)
            false
        }
    }
}

private object MacFilePromises {
    private const val NS_DRAG_OPERATION_COPY = 1L
    private const val NS_DRAG_OPERATION_NONE = 0L
    private const val NS_USER_CANCELLED_ERROR = 3072L
    private const val QOS_USER_INITIATED = 25L

    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend = objc.getFunction("objc_msgSend")
    private val foundation = NativeLibrary.getInstance("Foundation")

    /** Promised items by the key in their provider's userInfo; removed once written. */
    private val items = ConcurrentHashMap<String, PromisedItem>()
    private val nextKey = AtomicLong()

    /** The running drag's end handler (one drag at a time; main thread). */
    private var onEnd: ((Boolean) -> Unit)? = null

    @Structure.FieldOrder("x", "y")
    open class CGPoint : Structure() {
        @JvmField var x = 0.0
        @JvmField var y = 0.0
    }

    class CGPointByValue : CGPoint(), Structure.ByValue

    fun interface FileNameImp : Callback {
        fun invoke(self: Pointer, cmd: Pointer, provider: Pointer, type: Pointer): Pointer?
    }

    fun interface WriteImp : Callback {
        fun invoke(self: Pointer, cmd: Pointer, provider: Pointer, url: Pointer, completion: Pointer)
    }

    fun interface QueueImp : Callback {
        fun invoke(self: Pointer, cmd: Pointer, provider: Pointer): Pointer?
    }

    fun interface MaskImp : Callback {
        fun invoke(self: Pointer, cmd: Pointer, session: Pointer, context: Long): Long
    }

    fun interface EndedImp : Callback {
        fun invoke(self: Pointer, cmd: Pointer, session: Pointer, point: CGPointByValue, operation: Long)
    }

    // The IMPs, in fields: JNA frees a callback's native stub once its object is collected.
    private val fileNameImp = FileNameImp { _, _, provider, _ -> itemOf(provider)?.second?.let { ObjC.nsString(it.name) } }

    private val writeImp = WriteImp { _, _, provider, url, completion ->
        val error = try {
            write(provider, url)
        } catch (t: Throwable) {
            logDragOutWarn("promise write threw", t)
            nsError(t.message ?: "the pull failed")
        }
        // A block: isa, flags, reserved, then invoke at offset 16, called as invoke(block, NSError * or nil).
        Function.getFunction(completion.getPointer(16)).invokeVoid(arrayOf(completion, error))
    }

    private val queue: Pointer by lazy {
        ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSOperationQueue"), "alloc")!!, "init")!!.also {
            ObjC.msgVoid(it, "setQualityOfService:", QOS_USER_INITIATED)
            ObjC.msgVoid(it, "setName:", ObjC.nsString("scrcpyviewer.file-promises"))
        }
    }

    // The writes block until their pull ends: off the main thread, on a queue of our own.
    private val queueImp = QueueImp { _, _, _ -> queue }

    // Copy only, inside and outside the app: a drop must never move anything.
    private val maskImp = MaskImp { _, _, _, _ -> NS_DRAG_OPERATION_COPY }

    private val endedImp = EndedImp { _, _, _, _, operation ->
        val end = onEnd
        onEnd = null
        end?.invoke(operation != NS_DRAG_OPERATION_NONE)
    }

    private val delegate: Pointer by lazy {
        val add = objc.getFunction("class_addMethod")
        val cls = objc.getFunction("objc_allocateClassPair")
            .invokePointer(arrayOf(ObjC.cls("NSObject"), "ScrcpyViewerFilePromiseDelegate", 0L))
            ?: error("objc_allocateClassPair failed")
        fun method(sel: String, imp: Callback, types: String) =
            check(add.invokeInt(arrayOf(cls, ObjC.sel(sel), imp, types)) and 0xFF != 0) { "class_addMethod $sel" }
        method("filePromiseProvider:fileNameForType:", fileNameImp, "@@:@@")
        method("filePromiseProvider:writePromiseToURL:completionHandler:", writeImp, "v@:@@@?")
        method("operationQueueForFilePromiseProvider:", queueImp, "@@:@")
        method("draggingSession:sourceOperationMaskForDraggingContext:", maskImp, "Q@:@q")
        method("draggingSession:endedAtPoint:operation:", endedImp, "v@:@{CGPoint=dd}Q")
        val getProtocol = objc.getFunction("objc_getProtocol")
        val addProtocol = objc.getFunction("class_addProtocol")
        for (name in listOf("NSFilePromiseProviderDelegate", "NSDraggingSource")) {
            getProtocol.invokePointer(arrayOf(name))?.let { addProtocol.invokeInt(arrayOf(cls, it)) }
        }
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
        ObjC.msgPtr(ObjC.msgPtr(cls, "alloc")!!, "init")!!
    }

    /** Main thread, inside a mouse event. */
    fun start(nsWindow: Pointer, promised: List<PromisedItem>, end: (Boolean) -> Unit): Boolean {
        if (!ObjC.msgBool(ObjC.cls("NSThread"), "isMainThread")) {
            logDragOut("promise start refused: not on the main thread")
            return false
        }
        val app = ObjC.msgPtr(ObjC.cls("NSApplication"), "sharedApplication") ?: return false
        val event = ObjC.msgPtr(app, "currentEvent") ?: run {
            logDragOut("promise start refused: no current event")
            return false
        }
        val view = ObjC.msgPtr(nsWindow, "contentView") ?: return false
        val at = point(view, "convertPoint:fromView:", point(event, "locationInWindow"), null)
        val workspace = ObjC.msgPtr(ObjC.cls("NSWorkspace"), "sharedWorkspace")!!
        val list = ObjC.msgPtr(ObjC.cls("NSMutableArray"), "array")!!
        val keys = promised.mapIndexed { i, item ->
            val key = "promise-${nextKey.incrementAndGet()}"
            items[key] = item
            val type = if (item.isDirectory) "public.folder" else "public.data"
            val provider = ObjC.msgPtr(
                ObjC.msgPtr(ObjC.cls("NSFilePromiseProvider"), "alloc")!!, "initWithFileType:delegate:", ObjC.nsString(type), delegate,
            )!!
            ObjC.msgVoid(provider, "setUserInfo:", ObjC.nsString(key))
            val dragItem = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSDraggingItem"), "alloc")!!, "initWithPasteboardWriter:", provider)!!
            ObjC.msgVoid(provider, "release")
            val icon = if (item.isDirectory) {
                ObjC.msgPtr(ObjC.cls("NSImage"), "imageNamed:", ObjC.nsString("NSFolder")) // NSImageNameFolder
            } else {
                ObjC.msgPtr(workspace, "iconForFileType:", ObjC.nsString(item.name.substringAfterLast('.', "")))
            }
            // Fanned out a little under the pointer, as Finder stacks a multi-item drag.
            val frame = ObjC.CGRect().apply { x = at.x - 32 + i * 6; y = at.y - 32 - i * 6; width = 64.0; height = 64.0 }
            ObjC.msgVoid(dragItem, "setDraggingFrame:contents:", frame, icon)
            ObjC.msgVoid(list, "addObject:", dragItem)
            ObjC.msgVoid(dragItem, "release")
            key
        }
        onEnd = end
        val session = ObjC.msgPtr(view, "beginDraggingSessionWithItems:event:source:", list, event, delegate)
        if (session == null) {
            onEnd = null
            keys.forEach(items::remove)
            logDragOut("promise start refused: beginDraggingSession returned nil")
            return false
        }
        ObjC.msgVoid(session, "setAnimatesToStartingPositionsOnCancelOrFail:", true)
        logDragOut("promise drag started: ${promised.map { it.name }}")
        return true
    }

    /** Writes the item of [provider] to [url]; null when done, else the NSError to hand back. Promise queue. */
    private fun write(provider: Pointer, url: Pointer): Pointer? {
        val (key, item) = itemOf(provider) ?: return nsError("nothing to write")
        val path = ObjC.msgPtr(url, "path")?.let { ObjC.msgPtr(it, "UTF8String")?.getString(0, "UTF-8") }
            ?: return nsError("no destination")
        val target: Path = Paths.get(path)
        // Finder draws a file's progress bar only on a file that exists while it is published; adb makes the file
        // only once the pull runs (the queue may hold it first). The empty file is ours to remove if nothing replaced it.
        val placeholder = !item.isDirectory && runCatching { Files.createFile(target) }.isSuccess
        val progress = startProgress(url, item.size)
        var done = false
        try {
            return when (val outcome = item.write(target) { bytes, total -> report(progress, bytes, total) }) {
                PromiseOutcome.Done -> {
                    done = true
                    null
                }
                PromiseOutcome.Cancelled -> cancelledError()
                is PromiseOutcome.Failed -> nsError(outcome.reason)
            }
        } finally {
            // A pull that never ran (cancelled or failed while queued) left the empty file behind.
            if (placeholder && !done) runCatching { if (Files.size(target) == 0L) Files.deleteIfExists(target) }
            ObjC.msgVoid(progress, "unpublish")
            ObjC.msgVoid(progress, "release")
            items.remove(key)
        }
    }

    private fun itemOf(provider: Pointer): Pair<String, PromisedItem>? {
        val key = ObjC.msgPtr(provider, "userInfo")?.let { ObjC.msgPtr(it, "UTF8String")?.getString(0, "UTF-8") } ?: return null
        return items[key]?.let { key to it }
    }

    private fun point(receiver: Pointer, selector: String, vararg args: Any?): CGPoint =
        msgSend.invoke(CGPointByValue::class.java, arrayOf(receiver, ObjC.sel(selector), *args)) as CGPoint

    private fun constant(name: String): Pointer = foundation.getGlobalVariableAddress(name).getPointer(0)

    /**
     * An NSProgress on the destination: Finder draws it on the file (which must already exist, see [write]) or on
     * the folder. A folder has no total and shows indeterminate.
     */
    private fun startProgress(url: Pointer, total: Long?): Pointer {
        val progress = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSProgress"), "alloc")!!, "initWithParent:userInfo:", null, null)!!
        ObjC.msgVoid(progress, "setKind:", constant("NSProgressKindFile"))
        ObjC.msgVoid(progress, "setUserInfoObject:forKey:", url, constant("NSProgressFileURLKey"))
        ObjC.msgVoid(
            progress, "setUserInfoObject:forKey:",
            constant("NSProgressFileOperationKindCopying"), constant("NSProgressFileOperationKindKey"),
        )
        ObjC.msgVoid(progress, "setTotalUnitCount:", total?.takeIf { it > 0 } ?: -1L)
        ObjC.msgVoid(progress, "publish")
        return progress
    }

    private fun report(progress: Pointer, bytes: Long, total: Long?) {
        if (total != null && total > 0) ObjC.msgVoid(progress, "setTotalUnitCount:", total)
        ObjC.msgVoid(progress, "setCompletedUnitCount:", bytes)
    }

    private fun nsError(message: String): Pointer {
        val info = ObjC.msgPtr(
            ObjC.cls("NSDictionary"), "dictionaryWithObject:forKey:", ObjC.nsString(message), ObjC.nsString("NSLocalizedDescription"),
        )
        return ObjC.msgPtr(ObjC.cls("NSError"), "errorWithDomain:code:userInfo:", ObjC.nsString("scrcpyviewer.files"), 1L, info)!!
    }

    /** NSUserCancelledError: Finder removes its placeholder without an alert. */
    private fun cancelledError(): Pointer =
        ObjC.msgPtr(ObjC.cls("NSError"), "errorWithDomain:code:userInfo:", constant("NSCocoaErrorDomain"), NS_USER_CANCELLED_ERROR, null)!!
}
