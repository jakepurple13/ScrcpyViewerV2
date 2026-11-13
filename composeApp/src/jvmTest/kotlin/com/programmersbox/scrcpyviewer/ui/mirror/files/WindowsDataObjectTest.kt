package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.Os
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Drives the Windows data object through its real COM vtables, as Explorer would after a drop (no drag: CI has no
 * desktop to drop on). Runs on Windows only; elsewhere it is skipped.
 */
class WindowsDataObjectTest {
    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }

    /** Runs [block] on a fresh STA thread, like Tao's UI thread. */
    private fun <T> onSta(block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        try {
            return pool.submit(Callable {
                ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, 2)) // COINIT_APARTMENTTHREADED
                try {
                    block()
                } finally {
                    ole32.getFunction("CoUninitialize").invokeVoid(arrayOf())
                }
            }).get()
        } finally {
            pool.shutdown()
        }
    }

    private fun format(cf: Int, tymed: Int, lindex: Int = -1) = Memory(32).apply {
        clear()
        FormatEtc.write(this, 0, cf, tymed, lindex)
    }

    private fun queryInterface(obj: Pointer, iid: Guid): Pointer? {
        val out = Memory(8).apply { setPointer(0, null) }
        val hr = comCall(obj, 0, iid.memory(), out)
        return if (hr == 0) out.getPointer(0) else null
    }

    @Test
    fun `the data object serves the descriptor, streams contents and reports the async operation`() {
        assumeTrue(Os.isWindows)
        var readsEnded = 0
        val content = ByteArray(70_000) { (it % 251).toByte() }
        val drag = VirtualDrag(
            listOf(
                VirtualFile("F", 0, isDirectory = true, open = null),
                VirtualFile("F/a.bin", content.size.toLong(), isDirectory = false) { ByteArrayInputStream(content) },
            ),
        ) { readsEnded++ }

        onSta {
            val data = DataObject(drag)
            try {
                val p = data.pointer
                assertEquals(p, queryInterface(p, IID_IDATAOBJECT), "IDataObject")

                // QueryGetData, then GetData of the descriptor (index 3: GetData, 5: QueryGetData).
                assertEquals(0, comCall(p, 5, format(Win.cfFileDescriptor, 1)))
                val medium = Memory(24).apply { clear() }
                assertEquals(0, comCall(p, 3, format(Win.cfFileDescriptor, 1), medium))
                assertEquals(1, medium.getInt(0), "TYMED_HGLOBAL")
                assertContentEquals(fileGroupDescriptor(drag.files), Win.bytesOf(medium.getPointer(8)))
                Win.globalFree.invokePointer(arrayOf(medium.getPointer(8)))

                // A folder has no contents; a file is an IStream read through COM (ISequentialStream::Read, index 3).
                assertEquals(0x80040068.toInt(), comCall(p, 3, format(Win.cfFileContents, 4, lindex = 0), Memory(24).apply { clear() }))
                val stream = Memory(24).apply { clear() }
                assertEquals(0, comCall(p, 3, format(Win.cfFileContents, 4, lindex = 1), stream))
                assertEquals(4, stream.getInt(0), "TYMED_ISTREAM")
                val pstm = stream.getPointer(8)
                val buffer = Memory(content.size + 100L)
                val read = Memory(4)
                val got = java.io.ByteArrayOutputStream()
                while (true) {
                    read.setInt(0, 0)
                    val hr = comCall(pstm, 3, buffer, 16_384, read)
                    val n = read.getInt(0)
                    got.write(buffer.getByteArray(0, n))
                    if (hr != 0 || n == 0) break
                }
                assertContentEquals(content, got.toByteArray())
                comCall(pstm, 2) // Release

                // IDataObjectAsyncCapability: GetAsyncMode (4), StartOperation (5), EndOperation (7).
                val async = queryInterface(p, IID_IASYNC)!!
                val mode = Memory(4).apply { setInt(0, 0) }
                assertEquals(0, comCall(async, 4, mode))
                assertEquals(1, mode.getInt(0))
                assertEquals(0, comCall(async, 5, null))
                assertEquals(0, comCall(async, 7, 0, null, 1))
                assertEquals(1, readsEnded)
                assertEquals(true, data.operationStarted)
                comCall(async, 2)
                comCall(p, 2) // the QueryInterface reference
            } finally {
                data.release()
            }
        }
    }
}
