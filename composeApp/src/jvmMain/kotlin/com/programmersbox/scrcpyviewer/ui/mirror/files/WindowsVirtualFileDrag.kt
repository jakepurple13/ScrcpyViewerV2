package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Bytes of one FILEDESCRIPTORW (shlobj_core.h). */
internal const val FILE_DESCRIPTOR_SIZE = 592

private const val FD_ATTRIBUTES = 0x4
private const val FD_FILESIZE = 0x40
private const val FD_PROGRESSUI = 0x4000
private const val FD_UNICODE = 0x80000000.toInt()
private const val FILE_ATTRIBUTE_DIRECTORY = 0x10
private const val FILE_ATTRIBUTE_NORMAL = 0x80

/**
 * A FILEGROUPDESCRIPTORW for [files]: the item count, then one FILEDESCRIPTORW each (attributes, size and the
 * relative path with Windows separators). Folders come before what is in them, as [VirtualDragBuilder] lists them.
 */
internal fun fileGroupDescriptor(files: List<VirtualFile>): ByteArray {
    val b = ByteBuffer.allocate(4 + files.size * FILE_DESCRIPTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, files.size)
    files.forEachIndexed { i, f ->
        val at = 4 + i * FILE_DESCRIPTOR_SIZE
        b.putInt(at, FD_UNICODE or FD_PROGRESSUI or FD_FILESIZE or FD_ATTRIBUTES)
        b.putInt(at + 36, if (f.isDirectory) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_NORMAL)
        b.putInt(at + 64, (f.size ushr 32).toInt())
        b.putInt(at + 68, f.size.toInt())
        f.path.replace('/', '\\').take(VIRTUAL_PATH_MAX).forEachIndexed { c, ch -> b.putChar(at + 72 + c * 2, ch) }
    }
    return b.array()
}

/**
 * Virtual-file drags on Windows: an OLE data object offering CFSTR_FILEDESCRIPTORW and CFSTR_FILECONTENTS, started
 * with SHDoDragDrop from [hwnd]'s thread (Tao's UI thread, which OleInitialize made an STA). It supports
 * IDataObjectAsyncCapability, so Explorer copies after the drop on its own thread and the drag returns at once.
 *
 * The streams Explorer reads are made on an MTA thread of ours and handed out as proxies: Explorer's reads then run on
 * COM's RPC threads, never queued to the UI thread, which a blocking device read would freeze.
 *
 * Not tried on Windows yet (written on macOS); see docs/superpowers/notes/2026-11-13-windows-virtual-files.md.
 */
class WindowsVirtualFileDragStarter(private val hwnd: () -> Pointer?) : VirtualDragStarter {
    override fun start(drag: VirtualDrag): Boolean {
        val window = hwnd() ?: run {
            logDragOut("virtual drag refused: no HWND")
            return false
        }
        // The button went up already (a release not yet handled): a drag now would drop at once where the pointer is.
        if (!Win.primaryButtonDown()) {
            logDragOut("virtual drag refused: the button is up")
            return false
        }
        val data = DataObject(drag)
        return try {
            val effect = Memory(4).apply { setInt(0, 0) }
            val hr = Win.shDoDragDrop.invokeInt(arrayOf(window, data.pointer, null, DROPEFFECT_COPY, effect))
            logDragOut("SHDoDragDrop: hr=0x${hr.toUInt().toString(16)}, effect=${effect.getInt(0)}, async=${data.inOperation || data.operationStarted}")
            val dropped = hr == DRAGDROP_S_DROP
            // A drop target that read during the drop (no async operation) is done already.
            if (dropped && !data.operationStarted) drag.readsEnded()
            dropped
        } finally {
            data.release()
        }
    }

    private companion object {
        const val DROPEFFECT_COPY = 1
        const val DRAGDROP_S_DROP = 0x00040100
    }
}

/** Win32 and COM entry points over JNA. */
internal object Win {
    private val ole32 = NativeLibrary.getInstance("ole32")
    private val shell32 = NativeLibrary.getInstance("shell32")
    private val kernel32 = NativeLibrary.getInstance("kernel32")
    private val user32 = NativeLibrary.getInstance("user32")

    val shDoDragDrop: Function = shell32.getFunction("SHDoDragDrop", Function.ALT_CONVENTION)
    val shCreateStdEnumFmtEtc: Function = shell32.getFunction("SHCreateStdEnumFmtEtc", Function.ALT_CONVENTION)
    val coInitializeEx: Function = ole32.getFunction("CoInitializeEx", Function.ALT_CONVENTION)
    val coMarshalInterThreadInterfaceInStream: Function =
        ole32.getFunction("CoMarshalInterThreadInterfaceInStream", Function.ALT_CONVENTION)
    val coGetInterfaceAndReleaseStream: Function = ole32.getFunction("CoGetInterfaceAndReleaseStream", Function.ALT_CONVENTION)
    val releaseStgMedium: Function = ole32.getFunction("ReleaseStgMedium", Function.ALT_CONVENTION)
    val coTaskMemAlloc: Function = ole32.getFunction("CoTaskMemAlloc", Function.ALT_CONVENTION)
    private val globalAlloc = kernel32.getFunction("GlobalAlloc", Function.ALT_CONVENTION)
    private val globalLock = kernel32.getFunction("GlobalLock", Function.ALT_CONVENTION)
    private val globalUnlock = kernel32.getFunction("GlobalUnlock", Function.ALT_CONVENTION)
    private val globalSize = kernel32.getFunction("GlobalSize", Function.ALT_CONVENTION)
    val globalFree: Function = kernel32.getFunction("GlobalFree", Function.ALT_CONVENTION)
    private val registerClipboardFormat = user32.getFunction("RegisterClipboardFormatW", Function.ALT_CONVENTION)
    private val getAsyncKeyState = user32.getFunction("GetAsyncKeyState", Function.ALT_CONVENTION)
    private val getSystemMetrics = user32.getFunction("GetSystemMetrics", Function.ALT_CONVENTION)

    val cfFileDescriptor = format("FileGroupDescriptorW")
    val cfFileContents = format("FileContents")
    val cfPreferredDropEffect = format("Preferred DropEffect")

    private fun format(name: String): Int = registerClipboardFormat.invokeInt(arrayOf(com.sun.jna.WString(name))) and 0xFFFF

    fun primaryButtonDown(): Boolean {
        val swapped = getSystemMetrics.invokeInt(arrayOf(23)) != 0 // SM_SWAPBUTTON
        val key = if (swapped) 0x02 else 0x01 // VK_RBUTTON : VK_LBUTTON
        return getAsyncKeyState.invokeInt(arrayOf(key)).toShort().toInt() and 0x8000 != 0
    }

    /** A movable HGLOBAL holding [bytes]. */
    fun hglobal(bytes: ByteArray): Pointer {
        val h = globalAlloc.invokePointer(arrayOf(0x0002, bytes.size.toLong().coerceAtLeast(1))) // GMEM_MOVEABLE
            ?: throw OutOfMemoryError("GlobalAlloc")
        val p = globalLock.invokePointer(arrayOf(h))!!
        p.write(0, bytes, 0, bytes.size)
        globalUnlock.invokeInt(arrayOf(h))
        return h
    }

    fun bytesOf(h: Pointer): ByteArray {
        val size = globalSize.invokeLong(arrayOf(h))
        val p = globalLock.invokePointer(arrayOf(h)) ?: return ByteArray(0)
        try {
            return p.getByteArray(0, size.toInt())
        } finally {
            globalUnlock.invokeInt(arrayOf(h))
        }
    }
}

private const val S_OK = 0
private const val S_FALSE = 1
private const val E_NOTIMPL = 0x80004001.toInt()
private const val E_NOINTERFACE = 0x80004002.toInt()
private const val E_POINTER = 0x80004003.toInt()
private const val E_UNEXPECTED = 0x8000FFFF.toInt()
private const val DV_E_FORMATETC = 0x80040064.toInt()
private const val DV_E_LINDEX = 0x80040068.toInt()
private const val DV_E_TYMED = 0x80040069.toInt()
private const val OLE_E_ADVISENOTSUPPORTED = 0x80040003.toInt()
private const val DATA_S_SAMEFORMATETC = 0x00040130
private const val STG_E_INVALIDFUNCTION = 0x80030001.toInt()
private const val STG_E_ACCESSDENIED = 0x80030005.toInt()
private const val STG_E_READFAULT = 0x8003001E.toInt()
private const val TYMED_HGLOBAL = 1
private const val TYMED_ISTREAM = 4
private const val DVASPECT_CONTENT = 1
private const val DATADIR_GET = 1

/** A GUID as its 16 bytes in memory (Data1-3 little-endian). */
internal class Guid(text: String) {
    val bytes: ByteArray = run {
        val h = text.replace("-", "")
        val raw = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        byteArrayOf(raw[3], raw[2], raw[1], raw[0], raw[5], raw[4], raw[7], raw[6]) + raw.copyOfRange(8, 16)
    }

    fun matches(p: Pointer?): Boolean = p != null && p.getByteArray(0, 16).contentEquals(bytes)

    fun memory(): Memory = Memory(16).apply { write(0, bytes, 0, 16) }
}

internal val IID_IUNKNOWN = Guid("00000000-0000-0000-C000-000000000046")
internal val IID_IDATAOBJECT = Guid("0000010e-0000-0000-C000-000000000046")
internal val IID_ISTREAM = Guid("0000000c-0000-0000-C000-000000000046")
internal val IID_ISEQUENTIALSTREAM = Guid("0c733a30-2a1c-11ce-ade5-00aa0044773d")
internal val IID_IASYNC = Guid("3D8B0590-F691-11d2-8EA9-006097DF5BD4") // IDataObjectAsyncCapability

// COM method shapes. StdCallCallback: the stdcall convention on 32-bit; x64 has one convention.
private fun interface QueryInterfaceFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, riid: Pointer?, ppv: Pointer?): Int
}

private fun interface RefFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer): Int
}

private fun interface PtrPtrFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, a: Pointer?, b: Pointer?): Int
}

private fun interface PtrFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, a: Pointer?): Int
}

private fun interface SetDataFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, format: Pointer?, medium: Pointer?, release: Int): Int
}

private fun interface IntPtrFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, a: Int, b: Pointer?): Int
}

private fun interface DAdviseFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, format: Pointer?, flags: Int, sink: Pointer?, connection: Pointer?): Int
}

private fun interface IntFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, a: Int): Int
}

private fun interface EndOperationFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, hr: Int, bindCtx: Pointer?, effects: Int): Int
}

private fun interface ReadFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, buffer: Pointer?, count: Int, read: Pointer?): Int
}

private fun interface SeekFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, move: Long, origin: Int, newPosition: Pointer?): Int
}

private fun interface LongFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, a: Long): Int
}

private fun interface CopyToFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, target: Pointer?, count: Long, read: Pointer?, written: Pointer?): Int
}

private fun interface RegionFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, offset: Long, count: Long, type: Int): Int
}

private fun interface StatFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, stat: Pointer?, flags: Int): Int
}

/**
 * Objects COM holds pointers to: they and their callbacks stay reachable until their count reaches zero (JNA frees
 * a collected callback's native stub, which COM would then call).
 */
private val alive: MutableSet<Any> = Collections.synchronizedSet(HashSet())

/** A COM object of ours: memory holding a vtable pointer, so `this` is [pointer]. */
private class ComInterface(methods: List<Callback>) {
    private val vtable = Memory(Native.POINTER_SIZE.toLong() * methods.size)
    private val keep = methods
    val pointer = Memory(Native.POINTER_SIZE.toLong())

    init {
        methods.forEachIndexed { i, m -> vtable.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(m)) }
        pointer.setPointer(0, vtable)
    }
}

/** Calls method [index] of the COM interface [obj]. */
internal fun comCall(obj: Pointer, index: Int, vararg args: Any?): Int {
    val vtable = obj.getPointer(0)
    val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    return fn.invokeInt(arrayOf(obj, *args))
}

private fun release(obj: Pointer) = comCall(obj, 2)

/** FORMATETC (x64): cfFormat at 0, ptd at 8, dwAspect at 16, lindex at 20, tymed at 24; 32 bytes. */
internal class FormatEtc(val format: Int, val aspect: Int, val lindex: Int, val tymed: Int) {
    companion object {
        fun read(p: Pointer) = FormatEtc(p.getShort(0).toInt() and 0xFFFF, p.getInt(16), p.getInt(20), p.getInt(24))

        fun write(p: Pointer, at: Long, format: Int, tymed: Int, lindex: Int = -1) {
            p.setShort(at, format.toShort())
            p.setPointer(at + 8, null)
            p.setInt(at + 16, DVASPECT_CONTENT)
            p.setInt(at + 20, lindex)
            p.setInt(at + 24, tymed)
        }
    }
}

/** STGMEDIUM (x64): tymed at 0, the handle or interface at 8, pUnkForRelease at 16. */
private fun writeMedium(p: Pointer, tymed: Int, handle: Pointer) {
    p.setInt(0, tymed)
    p.setPointer(8, handle)
    p.setPointer(16, null)
}

/**
 * The IDataObject of one drag, with IDataObjectAsyncCapability as a second interface of the same object. Formats
 * the shell sets (drag image, drop description) are kept as copies and handed back.
 */
internal class DataObject(private val drag: VirtualDrag) {
    private val refs = AtomicInteger(1)
    private val descriptor = fileGroupDescriptor(drag.files)
    private val stored = HashMap<Int, ByteArray>()

    @Volatile var operationStarted = false
        private set

    @Volatile var inOperation = false
        private set

    @Volatile private var async = true

    private val queryInterface = QueryInterfaceFn { _, riid, ppv ->
        when {
            ppv == null -> E_POINTER
            IID_IUNKNOWN.matches(riid) || IID_IDATAOBJECT.matches(riid) -> { ppv.setPointer(0, pointer); addRef(); S_OK }
            IID_IASYNC.matches(riid) -> { ppv.setPointer(0, asyncInterface.pointer); addRef(); S_OK }
            else -> { ppv.setPointer(0, null); E_NOINTERFACE }
        }
    }
    private val addRefFn = RefFn { addRef() }
    private val releaseFn = RefFn { release() }

    private val getData = PtrPtrFn { _, format, medium ->
        if (format == null || medium == null) return@PtrPtrFn E_POINTER
        try {
            getData(FormatEtc.read(format), medium)
        } catch (t: Throwable) {
            logDragOutWarn("IDataObject.GetData failed", t)
            E_UNEXPECTED
        }
    }
    private val getDataHere = PtrPtrFn { _, _, _ -> E_NOTIMPL }
    private val queryGetData = PtrFn { _, format ->
        if (format == null) E_POINTER else queryGetData(FormatEtc.read(format))
    }
    private val getCanonical = PtrPtrFn { _, _, out ->
        out?.setPointer(8, null)
        DATA_S_SAMEFORMATETC
    }
    private val setData = SetDataFn { _, format, medium, releaseIt ->
        if (format == null || medium == null) return@SetDataFn E_POINTER
        if (medium.getInt(0) != TYMED_HGLOBAL) return@SetDataFn E_NOTIMPL
        val f = FormatEtc.read(format)
        synchronized(stored) { stored[f.format] = Win.bytesOf(medium.getPointer(8)) }
        if (releaseIt != 0) Win.releaseStgMedium.invokeVoid(arrayOf(medium))
        S_OK
    }
    private val enumFormatEtc = IntPtrFn { _, direction, out ->
        if (out == null) return@IntPtrFn E_POINTER
        if (direction != DATADIR_GET) return@IntPtrFn E_NOTIMPL
        val formats = buildList {
            add(Win.cfFileDescriptor to TYMED_HGLOBAL)
            add(Win.cfFileContents to TYMED_ISTREAM)
            add(Win.cfPreferredDropEffect to TYMED_HGLOBAL)
            synchronized(stored) { stored.keys.forEach { add(it to TYMED_HGLOBAL) } }
        }
        val array = Memory(32L * formats.size)
        formats.forEachIndexed { i, (cf, tymed) -> FormatEtc.write(array, 32L * i, cf, tymed) }
        Win.shCreateStdEnumFmtEtc.invokeInt(arrayOf(formats.size, array, out))
    }
    private val dAdvise = DAdviseFn { _, _, _, _, _ -> OLE_E_ADVISENOTSUPPORTED }
    private val dUnadvise = IntFn { _, _ -> OLE_E_ADVISENOTSUPPORTED }
    private val enumDAdvise = PtrFn { _, _ -> OLE_E_ADVISENOTSUPPORTED }

    // IDataObjectAsyncCapability
    private val setAsyncMode = IntFn { _, mode -> async = mode != 0; S_OK }
    private val getAsyncMode = PtrFn { _, out -> out?.setInt(0, if (async) 1 else 0); S_OK }
    private val startOperation = PtrFn { _, _ ->
        operationStarted = true
        inOperation = true
        logDragOut("IDataObjectAsyncCapability.StartOperation")
        S_OK
    }
    private val inOperationFn = PtrFn { _, out -> out?.setInt(0, if (inOperation) 1 else 0); S_OK }
    private val endOperation = EndOperationFn { _, hr, _, effects ->
        inOperation = false
        logDragOut("IDataObjectAsyncCapability.EndOperation: hr=0x${hr.toUInt().toString(16)}, effects=$effects")
        drag.readsEnded()
        S_OK
    }

    private val main: ComInterface = ComInterface(
        listOf(
            queryInterface, addRefFn, releaseFn,
            getData, getDataHere, queryGetData, getCanonical, setData, enumFormatEtc, dAdvise, dUnadvise, enumDAdvise,
        ),
    )
    private val asyncInterface: ComInterface = ComInterface(
        listOf(queryInterface, addRefFn, releaseFn, setAsyncMode, getAsyncMode, startOperation, inOperationFn, endOperation),
    )

    val pointer: Pointer get() = main.pointer

    init {
        alive += this
    }

    private fun addRef(): Int = refs.incrementAndGet()

    fun release(): Int {
        val left = refs.decrementAndGet()
        if (left == 0) alive -= this
        return left
    }

    private fun queryGetData(f: FormatEtc): Int = when {
        f.format == Win.cfFileDescriptor || f.format == Win.cfPreferredDropEffect ->
            if (f.tymed and TYMED_HGLOBAL != 0) S_OK else DV_E_TYMED
        f.format == Win.cfFileContents -> if (f.tymed and TYMED_ISTREAM != 0) S_OK else DV_E_TYMED
        synchronized(stored) { f.format in stored } -> if (f.tymed and TYMED_HGLOBAL != 0) S_OK else DV_E_TYMED
        else -> DV_E_FORMATETC
    }

    private fun getData(f: FormatEtc, medium: Pointer): Int {
        queryGetData(f).let { if (it != S_OK) return it }
        when (f.format) {
            Win.cfFileDescriptor -> writeMedium(medium, TYMED_HGLOBAL, Win.hglobal(descriptor))
            Win.cfPreferredDropEffect -> writeMedium(medium, TYMED_HGLOBAL, Win.hglobal(byteArrayOf(1, 0, 0, 0)))
            Win.cfFileContents -> {
                val file = drag.files.getOrNull(f.lindex) ?: return DV_E_LINDEX
                val open = file.open ?: return DV_E_LINDEX
                logDragOut("FileContents requested: ${file.path}")
                writeMedium(medium, TYMED_ISTREAM, MtaStreams.streamFor(file.path.substringAfterLast('/'), file.size, open))
            }
            else -> writeMedium(medium, TYMED_HGLOBAL, Win.hglobal(synchronized(stored) { stored.getValue(f.format) }))
        }
        return S_OK
    }
}

/**
 * Makes the streams Explorer reads on a thread of ours in the multithreaded apartment, and hands the UI thread a
 * proxy to each: calls on it then reach the stream on COM's RPC threads, not through the UI thread's messages.
 */
private object MtaStreams {
    private val tasks = LinkedBlockingQueue<() -> Unit>()

    init {
        Thread({
            Win.coInitializeEx.invokeInt(arrayOf(null, 0)) // COINIT_MULTITHREADED
            while (true) runCatching { tasks.take()() }
        }, "virtual-file-streams").apply { isDaemon = true }.start()
    }

    /** An IStream proxy (for the calling thread) reading [open]'s bytes, created in the MTA. */
    fun streamFor(name: String, size: Long, open: () -> InputStream): Pointer {
        val result = java.util.concurrent.CompletableFuture<Pointer>()
        tasks += {
            try {
                val stream = ComStream(name, size, open)
                val holder = PointerByReference()
                val hr = Win.coMarshalInterThreadInterfaceInStream.invokeInt(arrayOf(IID_ISTREAM.memory(), stream.pointer, holder))
                stream.release() // the marshaled reference keeps it now
                check(hr == S_OK) { "CoMarshalInterThreadInterfaceInStream: 0x${hr.toUInt().toString(16)}" }
                result.complete(holder.value)
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        val holder = result.get()
        val proxy = PointerByReference()
        val hr = Win.coGetInterfaceAndReleaseStream.invokeInt(arrayOf(holder, IID_ISTREAM.memory(), proxy))
        check(hr == S_OK) { "CoGetInterfaceAndReleaseStream: 0x${hr.toUInt().toString(16)}" }
        return proxy.value
    }
}

/**
 * A read-only IStream over [open]'s bytes, opened at the first read (so a stream Explorer never reads costs nothing).
 * Called on COM's threads; the device read blocks there.
 */
private class ComStream(private val name: String, private val size: Long, private val open: () -> InputStream) {
    private val refs = AtomicInteger(1)
    private val lock = Any()
    private var input: InputStream? = null
    private var position = 0L
    private var failed = false

    private val queryInterface = QueryInterfaceFn { _, riid, ppv ->
        when {
            ppv == null -> E_POINTER
            IID_IUNKNOWN.matches(riid) || IID_ISTREAM.matches(riid) || IID_ISEQUENTIALSTREAM.matches(riid) -> {
                ppv.setPointer(0, pointer)
                refs.incrementAndGet()
                S_OK
            }
            else -> { ppv.setPointer(0, null); E_NOINTERFACE }
        }
    }
    private val addRef = RefFn { refs.incrementAndGet() }
    private val releaseFn = RefFn { release() }
    private val read = ReadFn { _, buffer, count, readOut ->
        if (buffer == null) return@ReadFn E_POINTER
        val n = readInto(buffer, count)
        readOut?.setInt(0, n.coerceAtLeast(0))
        when {
            n < 0 -> STG_E_READFAULT
            n < count -> S_FALSE
            else -> S_OK
        }
    }
    private val write = ReadFn { _, _, _, _ -> STG_E_ACCESSDENIED }
    private val seek = SeekFn { _, move, origin, newPosition ->
        // Only "where am I" and "rewind before reading": the device stream cannot go back.
        val now = synchronized(lock) { position }
        val ok = (origin == 1 && move == 0L) || (origin == 0 && move == now)
        if (!ok) return@SeekFn STG_E_INVALIDFUNCTION
        newPosition?.setLong(0, now)
        S_OK
    }
    private val setSize = LongFn { _, _ -> E_NOTIMPL }
    private val copyTo = CopyToFn { _, target, count, readOut, writtenOut ->
        if (target == null) return@CopyToFn E_POINTER
        val buffer = Memory(256 * 1024)
        val written = Memory(4)
        var total = 0L
        while (total < count) {
            val n = readInto(buffer, minOf(buffer.size(), count - total).toInt())
            if (n < 0) return@CopyToFn STG_E_READFAULT
            if (n == 0) break
            written.setInt(0, 0)
            val hr = comCall(target, 4, buffer, n, written) // ISequentialStream::Write
            if (hr < 0) return@CopyToFn hr
            total += n
        }
        readOut?.setLong(0, total)
        writtenOut?.setLong(0, total)
        S_OK
    }
    private val commit = IntFn { _, _ -> S_OK }
    private val revert = RefFn { E_NOTIMPL }
    private val lockRegion = RegionFn { _, _, _, _ -> STG_E_INVALIDFUNCTION }
    private val unlockRegion = RegionFn { _, _, _, _ -> STG_E_INVALIDFUNCTION }
    private val stat = StatFn { _, out, flags ->
        if (out == null) return@StatFn E_POINTER
        out.clear(80)
        if (flags and 1 == 0) { // not STATFLAG_NONAME
            val chars = (name + "\u0000").toCharArray()
            val p = Win.coTaskMemAlloc.invokePointer(arrayOf(chars.size.toLong() * 2))
            p?.write(0, chars, 0, chars.size)
            out.setPointer(0, p)
        }
        out.setInt(8, 2) // STGTY_STREAM
        out.setLong(16, size)
        out.setInt(48, 0) // STGM_READ
        S_OK
    }
    private val clone = PtrFn { _, out -> out?.setPointer(0, null); E_NOTIMPL }

    private val vtable: ComInterface = ComInterface(
        listOf(
            queryInterface, addRef, releaseFn,
            read, write,
            seek, setSize, copyTo, commit, revert, lockRegion, unlockRegion, stat, clone,
        ),
    )

    val pointer: Pointer get() = vtable.pointer

    init {
        alive += this
    }

    fun release(): Int {
        val left = refs.decrementAndGet()
        if (left == 0) {
            synchronized(lock) { runCatching { input?.close() } }
            alive -= this
        }
        return left
    }

    /** Up to [count] bytes into [buffer]; 0 at the end, -1 on a failure (logged once). */
    private fun readInto(buffer: Pointer, count: Int): Int = synchronized(lock) {
        if (failed) return -1
        try {
            val stream = input ?: open().also { input = it }
            val bytes = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = stream.read(bytes, filled, count - filled)
                if (n < 0) break
                filled += n
            }
            buffer.write(0, bytes, 0, filled)
            position += filled
            filled
        } catch (t: Throwable) {
            failed = true
            logDragOutWarn("virtual file read failed: $name", t)
            -1
        }
    }
}
