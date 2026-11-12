package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.geometry.Offset
import com.programmersbox.scrcpyviewer.files.DragCache
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records OS drag starts; [result] is what the platform said, [dropped] what reaches onEnd (null: never called). */
internal class FakeStarter(var result: Boolean = true, var dropped: Boolean? = true) : OutboundDragStarter {
    val starts = mutableListOf<Pair<List<File>, Offset>>()

    /** Runs inside the "native loop", before it returns. */
    var during: () -> Unit = {}

    override fun start(files: List<File>, at: Offset, onEnd: (dropped: Boolean) -> Unit): Boolean {
        starts += files to at
        during()
        if (result) dropped?.let(onEnd)
        return result
    }
}

class DragOutTest {
    private val root: Path = Files.createTempDirectory("drag-out").resolve("scrcpy-vwr")
    private val cache = DragCache(root)
    private val starter = FakeStarter()
    private val pulls = mutableListOf<Pair<List<String>, Path>>()
    private val cancelled = mutableListOf<Long>()
    private val dismissed = mutableListOf<Long>()
    private var finished = 0
    private var nextId = 10L

    private fun controller(
        scope: CoroutineScope,
        rule: DragOutStartRule = DragOutStartRule.WhenReady,
        cleanupScope: CoroutineScope = scope,
        currentJobs: (() -> List<TransferJob>)? = null,
    ) = DragOutController(
        "192.168.1.5:5555", cache,
        pull = { names, dir -> pulls += names to dir; names.map { nextId++ } },
        cancelJob = { cancelled += it },
        dismissJob = { dismissed += it },
        starter = starter,
        scope = scope,
        cleanupScope = cleanupScope,
        currentJobs = currentJobs,
        io = EmptyCoroutineContext,
        rule = rule,
    ).also { it.onFinished = { finished++ } }

    private fun dragDir(): Path = pulls.last().second

    private fun job(id: Long, name: String, state: JobState, bytes: Long = 0, total: Long? = 100, wrote: Boolean = true): TransferJob {
        val dir = dragDir()
        val host = dir.resolve(name)
        // The queue makes the folder when the pull runs.
        if (state == JobState.Done && wrote) Files.writeString(Files.createDirectories(dir).resolve(name), "data")
        return TransferJob(
            id, TransferRequest(dir, TransferKind.Pull, "/sdcard/Download", name, size = total, dragOut = true),
            state, bytes, total, null, hostResult = if (state == JobState.Done) host else null,
        )
    }

    private fun TestScope.ready(c: DragOutController, vararg names: String) {
        c.onJobs(names.mapIndexed { i, n -> job(10L + i, n, JobState.Done, 100) })
        runCurrent()
    }

    @Test
    fun `begin pulls each name into a fresh drag dir`() = runTest {
        val c = controller(this)
        assertTrue(c.begin(listOf("a.jpg", "Camera"), Offset(5f, 6f)))
        assertEquals(listOf(listOf("a.jpg", "Camera")), pulls.map { it.first })
        assertFalse(Files.exists(dragDir()), "no file I/O in the pointer handler: the pull makes the folder")
        assertEquals(root.resolve("192.168.1.5_5555").resolve("drag"), dragDir().parent)
        assertEquals(DragOutPhase.Pulling, c.phase)
        assertNull(c.readyFiles)
        c.cancel()
    }

    @Test
    fun `nothing to pull starts nothing and leaves no folder`() = runTest {
        val c = DragOutController(
            "S", cache, pull = { names, dir -> pulls += names to dir; emptyList() }, cancelJob = {}, dismissJob = {},
            starter = starter, scope = this, io = EmptyCoroutineContext,
        )
        assertFalse(c.begin(listOf("a.jpg"), Offset.Zero))
        runCurrent()
        assertFalse(Files.exists(dragDir()))
        assertEquals(DragOutPhase.Idle, c.phase)
    }

    @Test
    fun `progress is the pulled share of the known sizes, null while one is unknown`() = runTest {
        val c = controller(this)
        c.begin(listOf("a", "b"), Offset.Zero)
        c.onJobs(listOf(job(10, "a", JobState.Running, 50, 100), job(11, "b", JobState.Queued, 0, 300)))
        assertEquals(0.125f, c.progress)
        c.onJobs(listOf(job(10, "a", JobState.Running, 50, 100), job(11, "b", JobState.Queued, 0, null)))
        assertNull(c.progress)
        c.cancel()
    }

    @Test
    fun `released before the files are ready cancels the pulls and deletes the folder`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg", "b.jpg"), Offset.Zero)
        c.onJobs(listOf(job(10, "a.jpg", JobState.Done), job(11, "b.jpg", JobState.Running, 10)))
        c.released()
        assertEquals(listOf(11L), cancelled, "the running pull; the finished one has nothing to cancel")
        assertEquals(DragOutPhase.Idle, c.phase)
        // The folder goes once no pull can still write into it.
        c.onJobs(listOf(job(10, "a.jpg", JobState.Done), job(11, "b.jpg", JobState.Running, 10)))
        runCurrent()
        assertTrue(Files.exists(dragDir()), "adb may still be writing")
        c.onJobs(listOf(job(10, "a.jpg", JobState.Done), job(11, "b.jpg", JobState.Cancelled)))
        runCurrent()
        assertFalse(Files.exists(dragDir()))
        assertEquals(listOf(10L, 11L), dismissed.sorted())
        assertTrue(starter.starts.isEmpty())
        // A late "ready" for the cancelled drag starts nothing.
        c.onJobs(listOf(job(10, "a.jpg", JobState.Done, wrote = false), job(11, "b.jpg", JobState.Done, wrote = false)))
        runCurrent()
        assertTrue(starter.starts.isEmpty())
    }

    @Test
    fun `ready while held starts the OS drag at once, wherever the pointer is`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg", "b.jpg"), Offset(1f, 1f))
        c.pointerAt(Offset(30f, 40f), outsideWindow = false)
        val dir = dragDir()
        var phaseDuring: DragOutPhase? = null
        starter.during = { phaseDuring = c.phase }
        ready(c, "a.jpg", "b.jpg")
        assertEquals(listOf(listOf(dir.resolve("a.jpg").toFile(), dir.resolve("b.jpg").toFile())), starter.starts.map { it.first })
        assertEquals(Offset(30f, 40f), starter.starts.single().second)
        assertEquals(DragOutPhase.OsDrag, phaseDuring)
        // After the synchronous drag returns, everything is reset without waiting for a pointer-up.
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(1, finished)
        assertTrue(cancelled.isEmpty())
        // The drop target gets time to copy before the folder goes.
        advanceTimeBy(DRAG_DIR_KEEP_MILLIS - 1_000)
        runCurrent()
        assertTrue(Files.exists(dir))
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(Files.exists(dir))
        assertEquals(listOf(10L, 11L), dismissed.sorted())
    }

    @Test
    fun `a release after ready but before the drag ran starts nothing`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        c.onJobs(listOf(job(10, "a.jpg", JobState.Done)))
        assertEquals(DragOutPhase.Ready, c.phase)
        assertEquals(1, c.readyFiles?.size)
        c.released()
        runCurrent()
        assertTrue(starter.starts.isEmpty())
        assertFalse(Files.exists(dragDir()))
        assertEquals(DragOutPhase.Idle, c.phase)
    }

    @Test
    fun `with the outside rule, ready waits for the pointer to leave the window`() = runTest {
        val c = controller(this, DragOutStartRule.WhenReadyAndOutside)
        c.begin(listOf("a.jpg"), Offset.Zero)
        ready(c, "a.jpg")
        assertTrue(starter.starts.isEmpty())
        assertEquals(DragOutPhase.Ready, c.phase)
        c.pointerAt(Offset(-5f, 10f), outsideWindow = true)
        runCurrent()
        assertEquals(Offset(-5f, 10f), starter.starts.single().second)
        assertEquals(DragOutPhase.Idle, c.phase)
    }

    @Test
    fun `coming back inside cancels the pull, and a new drag-out can begin`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        val first = dragDir()
        c.cancel()
        assertEquals(listOf(10L), cancelled)
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(0, finished, "the gesture goes on as a move")
        c.onJobs(emptyList())
        runCurrent()
        assertFalse(Files.exists(first))
        assertTrue(c.begin(listOf("a.jpg"), Offset.Zero))
        assertTrue(dragDir() != first)
    }

    @Test
    fun `a failed pull ends the drag and leaves nothing`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg", "b.jpg"), Offset.Zero)
        c.onJobs(listOf(job(10, "a.jpg", JobState.Failed("no such file")), job(11, "b.jpg", JobState.Queued)))
        assertEquals(listOf(11L), cancelled)
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(1, finished)
        c.onJobs(listOf(job(10, "a.jpg", JobState.Failed("no such file"))))
        runCurrent()
        assertFalse(Files.exists(dragDir()))
        assertTrue(starter.starts.isEmpty())
    }

    @Test
    fun `a pull the queue dropped ends the drag`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        c.onJobs(listOf(job(10, "a.jpg", JobState.Queued)))
        c.onJobs(emptyList())
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(1, finished)
    }

    @Test
    fun `a drag the platform refused deletes the folder at once`() = runTest {
        starter.result = false
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        ready(c, "a.jpg")
        assertEquals(1, starter.starts.size)
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(1, finished)
        assertFalse(Files.exists(dragDir()))
    }

    @Test
    fun `a drag whose completion never comes is still cleaned up`() = runTest {
        starter.dropped = null
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        ready(c, "a.jpg")
        assertEquals(DragOutPhase.Idle, c.phase)
        advanceTimeBy(DRAG_DIR_KEEP_MILLIS + 1_000)
        runCurrent()
        assertFalse(Files.exists(dragDir()))
    }

    @Test
    fun `the delete after a drop outlives the tab's scope`() = runTest {
        val tab = CoroutineScope(coroutineContext + Job())
        val c = controller(tab, cleanupScope = this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        ready(c, "a.jpg")
        assertEquals(1, starter.starts.size)
        tab.cancel() // the sheet closed
        advanceTimeBy(DRAG_DIR_KEEP_MILLIS + 1_000)
        runCurrent()
        assertFalse(Files.exists(dragDir()))
    }

    @Test
    fun `a pull the jobs snapshot has not shown yet still counts as running`() = runTest {
        var now = emptyList<TransferJob>()
        val c = controller(this, currentJobs = { now })
        c.begin(listOf("a.jpg"), Offset.Zero)
        val dir = Files.createDirectories(dragDir()) // adb has started writing
        now = listOf(job(10, "a.jpg", JobState.Running, 10))
        c.released()
        assertEquals(listOf(10L), cancelled)
        runCurrent()
        assertTrue(Files.exists(dir), "the cancelled adb may still be writing")
        now = listOf(job(10, "a.jpg", JobState.Cancelled))
        c.onJobs(now)
        runCurrent()
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `release and cancel after the OS drag started do nothing`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.jpg"), Offset.Zero)
        starter.during = {
            c.released()
            c.cancel()
        }
        ready(c, "a.jpg")
        assertTrue(cancelled.isEmpty())
        assertTrue(Files.exists(dragDir()), "kept for the drop target")
        assertEquals(1, finished)
    }
}
