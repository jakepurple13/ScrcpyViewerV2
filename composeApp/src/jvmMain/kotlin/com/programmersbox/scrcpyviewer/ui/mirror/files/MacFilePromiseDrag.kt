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

/**
 * The promised items of file-promise drags, by the key in their provider's userInfo. Finder asks for an item only
 * after a drop, on the promise queue, and may ask after the drag ended (the source hears the end at the drop; the
 * writes run after it, one by one if the queue runs them so). So an item stays until it is written, unless its drag
 * ended with nothing dropped: then no write can come, and it goes at the end. Each item's write holds the tab's
 * browser and adb session, so one left behind would keep them for the app's lifetime. Pure Kotlin; any thread.
 */
internal class PromiseRegistry<T> {
    private class Entry<T>(val drag: Long, val item: T)

    private val items = HashMap<String, Entry<T>>()
    private var nextKey = 0L
    private var nextDrag = 0L

    /** Registers one drag's [promised] items: the drag's id and each item's key, in order. */
    @Synchronized
    fun register(promised: List<T>): Pair<Long, List<String>> {
        val drag = ++nextDrag
        val keys = promised.map { item -> "promise-${++nextKey}".also { items[it] = Entry(drag, item) } }
        return drag to keys
    }

    @Synchronized
    operator fun get(key: String): T? = items[key]?.item

    /** [key]'s write ended (done or not): Finder will not ask again. */
    @Synchronized
    fun written(key: String) {
        items.remove(key)
    }

    /** [drag] ended; without a drop ([dropped] false) its items go, as none will be asked for. */
    @Synchronized
    fun ended(drag: Long, dropped: Boolean) {
        if (!dropped) items.values.removeIf { it.drag == drag }
    }

    @Synchronized
    fun size(): Int = items.size
}

private object MacFilePromises {
    private const val NS_DRAG_OPERATION_COPY = 1L
    private const val NS_DRAG_OPERATION_NONE = 0L
    private const val NS_USER_CANCELLED_ERROR = 3072L
    private const val QOS_USER_INITIATED = 25L

    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend = objc.getFunction("objc_msgSend")
    private val foundation = NativeLibrary.getInstance("Foundation")

    /** Promised items by the key in their provider's userInfo: until written, or their drag ended without a drop. */
    private val items = PromiseRegistry<PromisedItem>()

    /** The running drag's end handler and id in [items] (one drag at a time; main thread). */
    private var onEnd: ((Boolean) -> Unit)? = null
    private var drag = 0L

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
        val dropped = operation != NS_DRAG_OPERATION_NONE
        // Dropped nowhere or cancelled: Finder asks for nothing, so the items (and the tab they hold) go now.
        items.ended(drag, dropped)
        end?.invoke(dropped)
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
        val (id, keys) = items.register(promised)
        promised.forEachIndexed { i, item ->
            val key = keys[i]
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
        }
        onEnd = end
        drag = id
        val session = ObjC.msgPtr(view, "beginDraggingSessionWithItems:event:source:", list, event, delegate)
        if (session == null) {
            onEnd = null
            items.ended(id, dropped = false)
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
            items.written(key)
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
