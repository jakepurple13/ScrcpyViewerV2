package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.geometry.Offset
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.files.FileTransferQueue
import com.programmersbox.scrcpyviewer.files.TransferKind
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalComposeUiApi::class)
class FileDropTest {
    /** A drag event with no native (AWT) event behind it, as a fake. */
    private fun fakeEvent(action: DragAndDropTransferAction? = DragAndDropTransferAction.Copy) =
        DragAndDropEvent(action, null, Offset.Zero)

    private fun filesOf(vararg paths: Path): DragData = object : DragData.FilesList {
        override fun readFiles() = paths.map { it.toUri().toString() }
    }

    private val noDirs: (Path) -> Boolean = { false }
    private fun hint(vararg names: String, connected: Boolean = true, target: String = "/sdcard/Download/") =
        dropHint(names.map { Paths.get("/x", it) }, target, connected, noDirs)

    @Test
    fun `labels for apks, files and both`() {
        assertEquals(DropHint("Drop to install APK", TransferKind.Install, true), hint("app.apk"))
        assertEquals("Drop to install 2 APKs", hint("a.apk", "B.APK").label)
        assertEquals(DropHint("Drop to push to /sdcard/Download/", TransferKind.Push, true), hint("photo.jpg"))
        assertEquals("Drop to push 2 files to /sdcard/DCIM/", hint("a.jpg", "b.txt", target = "/sdcard/DCIM/").label)
        assertEquals(DropHint("Drop to install 1 APK and push 2 files", null, true), hint("a.apk", "b.jpg", "c.txt"))
        assertEquals("Drop to install 2 APKs and push 1 file", hint("a.apk", "b.apk", "c.txt").label)
    }

    @Test
    fun `unknown files and disconnected device`() {
        assertEquals("Drop to install APKs or push files to /sdcard/Download/", dropHint(null, "/sdcard/Download/", true).label)
        val off = hint("a.apk", connected = false)
        assertEquals("Device not connected", off.label)
        assertFalse(off.allowed)
    }

    @Test
    fun `file list flavor is read from a transferable`() {
        val files = listOf(File("/x/a.apk"), File("/x/b.jpg"))
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = files
        }
        assertEquals(files.map { it.toPath() }, t.filePaths())
        assertNull(StringSelection("text").filePaths())
    }

    @Test
    fun `drops are COPY only, whatever the source offers`() {
        val copy = DnDConstants.ACTION_COPY
        assertEquals(copy, copyDropAction(DnDConstants.ACTION_COPY_OR_MOVE, hasFiles = true, enabled = true))
        assertEquals(copy, copyDropAction(DnDConstants.ACTION_COPY_OR_MOVE or DnDConstants.ACTION_LINK, hasFiles = true, enabled = true))
        assertEquals(copy, copyDropAction(DnDConstants.ACTION_COPY, hasFiles = true, enabled = true))
        // A source that can only move (or link) is rejected rather than told to move.
        assertNull(copyDropAction(DnDConstants.ACTION_MOVE, hasFiles = true, enabled = true))
        assertNull(copyDropAction(DnDConstants.ACTION_LINK, hasFiles = true, enabled = true))
        // No files, or no adb / device: rejected.
        assertNull(copyDropAction(DnDConstants.ACTION_COPY_OR_MOVE, hasFiles = false, enabled = true))
        assertNull(copyDropAction(DnDConstants.ACTION_COPY_OR_MOVE, hasFiles = true, enabled = false))
    }

    @Test
    fun `start reports whether anything was queued`() {
        val messages = mutableListOf<String>()
        val noAdb = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") { messages += it }
        assertFalse(noAdb.enabled)
        assertFalse(noAdb.start(listOf(Paths.get("/x/a.apk"))))
        assertEquals(listOf("adb is not available"), messages)
        assertFalse(noAdb.start(emptyList()))

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            var connected = false
            val queue = FileTransferQueue("SER", AdbClient({ Paths.get("adb") }), "/sdcard/Download/", { connected }, {}, scope)
            val transfers = FileTransfers(queue, "/sdcard/Download/") { messages += it }
            assertFalse(transfers.start(listOf(Paths.get("/x/a.apk"))), "refused while disconnected")
            transfers.connected = false
            assertFalse(transfers.enabled)
            transfers.dragging = true
            assertEquals("Device not connected", transfers.hint?.label)
            transfers.dragging = false
            assertNull(transfers.hint)
            connected = true
            transfers.connected = true
            assertTrue(transfers.enabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transfer actions send no control messages`() {
        assertEquals(emptyList(), MirrorAction.InstallApk.messages())
        assertEquals(emptyList(), MirrorAction.PushFile.messages())
    }

    @Test
    fun paths_fromDropEvent_extracted() {
        val a = Paths.get("/x/a.apk")
        val b = Paths.get("/x/b c.jpg") // URI-escaped by the platform
        assertEquals(listOf(a, b), dropPaths(fakeEvent()) { filesOf(a, b) })
        assertEquals(emptyList(), dropPaths(fakeEvent()) { filesOf() })
        val text = object : DragData.Text {
            override val bestMimeType = "text/plain"
            override fun readText() = "hello"
        }
        assertNull(dropPaths(fakeEvent()) { text }, "not files")
        assertNull(dropPaths(fakeEvent()) { error("cannot read yet") }, "unreadable")
        assertNull(dropPaths(fakeEvent()), "no AWT event behind it")
    }

    @Test
    fun noFiles_or_dirsOnly_disconnected_hintOnly_noTransfer() {
        val dir = createTempDirectory("drop")
        val messages = mutableListOf<String>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            var asked = 0 // the queue asks on every enqueue: a transfer was started
            val queue = FileTransferQueue("SER", AdbClient({ Paths.get("adb") }), "/sdcard/Download/", { asked++; true }, {}, scope)
            val transfers = FileTransfers(queue, "/sdcard/Download/") { messages += it }
            transfers.connected = false
            for (data in listOf(filesOf(), filesOf(dir), filesOf(dir, dir.resolve("..")))) {
                val target = CopyOnlyFileDropTarget(transfers) { data }
                val event = fakeEvent()
                assertTrue(target.accepts(event), "a file list is a drop target")
                target.onStarted(event)
                target.onEntered(event)
                assertEquals("Device not connected", transfers.hint?.label)
                assertFalse(transfers.hint!!.allowed)
                assertFalse(target.onDrop(event), "no transfer while disconnected")
                assertNull(transfers.hint, "the overlay goes once dropped")
                target.onEnded(event)
            }
            assertEquals(0, asked)
            assertEquals(emptyList(), messages)

            // Connected, zero files: still nothing to queue.
            transfers.connected = true
            val target = CopyOnlyFileDropTarget(transfers) { filesOf() }
            target.onEntered(fakeEvent())
            assertEquals("Drop to install APKs or push files to /sdcard/Download/", transfers.hint?.label)
            assertFalse(target.onDrop(fakeEvent()))
            assertEquals(0, asked)
        } finally {
            scope.cancel()
            dir.toFile().delete()
        }
    }

    @Test
    fun `drag without files is not a drop target, and exit clears the overlay`() {
        val transfers = FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}
        val text = object : DragData.Text {
            override val bestMimeType = "text/plain"
            override fun readText() = "hello"
        }
        assertFalse(CopyOnlyFileDropTarget(transfers) { text }.accepts(fakeEvent()))
        val target = CopyOnlyFileDropTarget(transfers) { filesOf(Paths.get("/x/a.apk")) }
        target.onEntered(fakeEvent())
        assertEquals(listOf(Paths.get("/x/a.apk")), transfers.dragPaths)
        target.onExited(fakeEvent())
        assertNull(transfers.hint)
        assertNull(transfers.dragPaths)
    }

    @Test
    fun `a move-only source is refused at the drop`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            var asked = 0
            val queue = FileTransferQueue("SER", AdbClient({ Paths.get("adb") }), "/sdcard/Download/", { asked++; false }, {}, scope)
            val transfers = FileTransfers(queue, "/sdcard/Download/") {}
            val target = CopyOnlyFileDropTarget(transfers) { filesOf(Paths.get("/x/a.apk")) }
            // No AWT event: the source's actions are unknown, and only a Copy action counts as copyable.
            assertFalse(target.onDrop(fakeEvent(DragAndDropTransferAction.Move)))
            assertEquals(0, asked)
            // Copy reaches the queue (which refuses: the fake device is gone).
            assertFalse(target.onDrop(fakeEvent(DragAndDropTransferAction.Copy)))
            assertEquals(1, asked)
        } finally {
            scope.cancel()
        }
    }
}
