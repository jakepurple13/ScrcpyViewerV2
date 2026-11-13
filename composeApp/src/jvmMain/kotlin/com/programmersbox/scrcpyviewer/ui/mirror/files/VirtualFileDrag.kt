package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TreeEntry
import com.programmersbox.scrcpyviewer.files.devicePath
import com.programmersbox.scrcpyviewer.files.hostFileName
import java.io.IOException
import java.io.InputStream
import java.util.Collections

/** Longest path a virtual file may have: FILEDESCRIPTORW's cFileName is 260 WCHARs with its terminator. */
const val VIRTUAL_PATH_MAX = 259

/**
 * One item of a virtual-file drag (Windows): [path] is relative to the drop folder, its parts joined with "/"
 * (the platform side turns them into its own separator). A folder has no content; a file is read through [open]
 * after the drop, on a thread of the drop target's.
 */
class VirtualFile(val path: String, val size: Long, val isDirectory: Boolean, val open: (() -> InputStream)?)

/** What a virtual-file drag offers, and what to do once the drop target is done reading ([readsEnded], once). */
class VirtualDrag(val files: List<VirtualFile>, val readsEnded: () -> Unit)

/** Hands virtual files to the computer's own drag and drop (Windows: FileGroupDescriptor + FileContents). */
interface VirtualDragStarter {
    /**
     * Runs the OS drag of [drag] and blocks until it ends (the platform's own loop, on the UI thread). True when it
     * was dropped somewhere. Reads may go on after it returns; the starter calls [VirtualDrag.readsEnded] when the
     * drop target says it is done, or at once when it reads nothing later.
     */
    fun start(drag: VirtualDrag): Boolean
}

/** A transfer shown in the tray for one dragged row (see FileTransferQueue.track). */
interface RowTransfer {
    fun progress(bytes: Long)
    fun finish(state: JobState)
}

/**
 * Builds virtual-file drags of the rows of [fromDir] for one device. Folder rows are listed with [tree] first. Each
 * row gets one tray job ([track], made when the drop target first reads from it) whose bytes are those read from its
 * files ([openStream]: a device file's bytes, e.g. `cat` over adb). Cancelling it from the tray closes its open
 * streams and fails later reads; reading stops early or fails, the job fails. [windows] picks Windows-safe names.
 */
class VirtualDragBuilder(
    private val tree: suspend (dir: String, names: List<String>) -> List<TreeEntry>,
    private val openStream: (devicePath: String) -> InputStream,
    private val track: (row: DeviceEntry, total: Long, onCancel: () -> Unit) -> RowTransfer?,
    private val windows: Boolean = true,
) {
    /** The drag of [rows] in [fromDir]; null when it cannot be offered as virtual files (a path too long). */
    suspend fun build(fromDir: String, rows: List<DeviceEntry>): VirtualDrag? {
        val folders = rows.filter { it.isDir }.map { it.name }
        val listed = if (folders.isEmpty()) emptyList() else tree(fromDir, folders)
        val rowStates = ArrayList<RowState>()
        val files = ArrayList<VirtualFile>()
        for (row in rows) {
            val entries = if (row.isDir) {
                listed.filter { it.path == row.name || it.path.startsWith(row.name + "/") }
                    .ifEmpty { listOf(TreeEntry(row.name, isDir = true, size = 0)) }
            } else {
                listOf(TreeEntry(row.name, isDir = false, size = row.size))
            }
            // Empty files have no bytes to read: the drop target may never read them, so they do not count.
            val state = RowState(row, entries.filterNot { it.isDir }.sumOf { it.size }, entries.count { !it.isDir && it.size > 0 })
            rowStates += state
            for (e in entries) {
                val path = e.path.split('/').joinToString("/") { hostFileName(it, windows) }
                if (path.length > VIRTUAL_PATH_MAX) {
                    logDragOut("virtual drag refused: path too long (${path.length}): $path")
                    return null
                }
                files += if (e.isDir) {
                    VirtualFile(path, 0, isDirectory = true, open = null)
                } else {
                    VirtualFile(path, e.size, isDirectory = false) { state.open(devicePath(fromDir, e.path), e.size) }
                }
            }
        }
        return VirtualDrag(files) { rowStates.forEach(RowState::readsEnded) }
    }

    /** One dragged row: its tray job, its open streams, and how much of it was read. */
    /** [fileCount]: the row's files with bytes; the row is done once each was read to its end. */
    private inner class RowState(val row: DeviceEntry, val total: Long, val fileCount: Int) {
        private val lock = Any()
        private var job: RowTransfer? = null
        private var started = false
        private var ended = false
        private var cancelled = false
        private var bytes = 0L
        private var filesDone = 0
        private val open: MutableSet<InputStream> = Collections.newSetFromMap(java.util.IdentityHashMap())

        fun open(path: String, size: Long): InputStream {
            val firstRead = synchronized(lock) {
                if (cancelled) throw IOException("${row.name}: cancelled")
                !started.also { started = true }
            }
            if (firstRead) {
                val made = track(row, total) { cancel() }
                synchronized(lock) { job = made }
            }
            val raw = try {
                openStream(path)
            } catch (e: IOException) {
                fail("${row.name}: ${e.message ?: "could not read"}")
                throw e
            }
            val stream = CountingStream(raw, size)
            synchronized(lock) {
                if (cancelled) {
                    runCatching { raw.close() }
                    throw IOException("${row.name}: cancelled")
                }
                open += stream
            }
            return stream
        }

        private fun counted(n: Long) {
            val (j, now) = synchronized(lock) {
                bytes += n
                job to bytes
            }
            j?.progress(now)
        }

        private fun fileDone(stream: InputStream) {
            val finishNow = synchronized(lock) {
                open -= stream
                filesDone++
                filesDone == fileCount && !ended && !cancelled
            }
            if (finishNow) end(JobState.Done)
        }

        private fun closed(stream: InputStream) = synchronized(lock) { open -= stream }

        fun fail(reason: String) = end(JobState.Failed(reason))

        /** From the tray: stops every read of this row; the job is already marked cancelled. */
        fun cancel() {
            val streams = synchronized(lock) {
                cancelled = true
                ended = true
                open.toList().also { open.clear() }
            }
            streams.forEach { runCatching { it.close() } }
        }

        /**
         * The drop target is done: a row it started ends done when every file with bytes was read, else cancelled
         * (the user skipped some, or stopped the copy).
         */
        fun readsEnded() {
            val state = synchronized(lock) {
                if (!started || ended) return
                if (filesDone >= fileCount) JobState.Done else JobState.Cancelled
            }
            end(state)
        }

        private fun end(state: JobState) {
            val j = synchronized(lock) {
                if (ended) return
                ended = true
                job
            }
            j?.finish(state)
        }

        /**
         * A device file's bytes, counted into the row. Reaching the end with other than [expected] bytes is an error
         * (a `cat` that failed part way, or printed an error instead of the file).
         */
        private inner class CountingStream(private val raw: InputStream, private val expected: Long) : InputStream() {
            private var read = 0L
            private var finished = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (finished) return -1
                val n = try {
                    raw.read(b, off, len)
                } catch (e: IOException) {
                    if (!synchronized(lock) { cancelled }) fail("${row.name}: ${e.message ?: "read failed"}")
                    throw e
                }
                if (n < 0) {
                    finished = true
                    if (read != expected) {
                        val reason = "${row.name}: got $read of $expected bytes"
                        fail(reason)
                        throw IOException(reason)
                    }
                    if (expected > 0) fileDone(this) else closed(this)
                    return -1
                }
                read += n
                if (read > expected) {
                    val reason = "${row.name}: more than $expected bytes"
                    fail(reason)
                    throw IOException(reason)
                }
                counted(n.toLong())
                // An empty file, or the last byte: done without waiting for one more read.
                if (read == expected && !finished) {
                    finished = true
                    fileDone(this)
                }
                return n
            }

            override fun close() {
                closed(this)
                raw.close()
            }
        }
    }
}
