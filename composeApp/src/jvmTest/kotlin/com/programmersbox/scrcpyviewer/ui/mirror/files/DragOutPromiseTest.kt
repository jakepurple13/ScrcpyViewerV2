package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.geometry.Offset
import com.programmersbox.scrcpyviewer.files.DragCache
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records promise drags; [result] is what the platform said. [end] plays the drag ending. */
internal class FakePromiseStarter(var result: Boolean = true) : PromiseDragStarter {
    val starts = mutableListOf<List<PromisedItem>>()
    private var onEnd: ((Boolean) -> Unit)? = null

    override fun start(items: List<PromisedItem>, onEnd: (dropped: Boolean) -> Unit): Boolean {
        starts += items
        if (result) this.onEnd = onEnd
        return result
    }

    fun end(dropped: Boolean) = onEnd!!.invoke(dropped)
}

class DragOutPromiseTest {
    private val cache = DragCache(Files.createTempDirectory("drag-out-promise").resolve("scrcpy-vwr"))
    private val starter = FakeStarter()
    private val promises = FakePromiseStarter()
    private val jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    private val pulls = mutableListOf<List<String>>()
    // Written on the promise's thread.
    private val enqueued = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Path>>())
    private val heard = mutableListOf<Boolean>()
    private val progress = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
    private var finished = 0
    private var nextId = 40L
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = background.cancel()

    private fun controller(scope: CoroutineScope, entries: List<Pair<String, Boolean>> = listOf("a.mp4" to false, "Camera" to true)) =
        DragOutController(
            "S", cache,
            pull = { names, _ -> pulls += names; names.map { nextId++ } },
            cancelJob = {}, dismissJob = {},
            starter = starter, scope = scope, io = EmptyCoroutineContext,
            onOsDrag = { heard += it },
            promises = promises,
            promisedEntries = { names ->
                entries.filter { it.first in names }.map { (name, dir) ->
                    PromisedEntry(name, dir, if (dir) null else 100L) { target -> enqueued += name to target; nextId++ }
                }
            },
            jobUpdates = jobs,
            ui = EmptyCoroutineContext,
        ).also { it.onFinished = { finished++ } }

    private fun job(id: Long, target: Path, state: JobState, bytes: Long = 0, total: Long? = null) = TransferJob(
        id, TransferRequest(target.parent, TransferKind.Pull, "/sdcard/Download", target.fileName.toString(), hostTarget = target),
        state, bytes, total, null,
    )

    /** Runs [item]'s write on another thread, as AppKit's promise queue does. */
    private fun writeAsync(item: PromisedItem, target: Path): CompletableFuture<PromiseOutcome> =
        CompletableFuture.supplyAsync { item.write(target) { b, total -> progress += b to total } }

    private fun awaitEnqueued(n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (synchronized(enqueued) { enqueued.size } < n) {
            check(System.nanoTime() < until) { "nothing enqueued" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `with promises the OS drag starts at once and nothing is pulled yet`() = runTest {
        val c = controller(this)
        assertTrue(c.begin(listOf("a.mp4", "Camera"), Offset(3f, 4f)))
        assertEquals(listOf(listOf("a.mp4" to false, "Camera" to true)), promises.starts.map { s -> s.map { it.name to it.isDirectory } })
        assertEquals(listOf(100L, null), promises.starts.single().map { it.size }, "the size goes to the drop target up front")
        assertTrue(pulls.isEmpty(), "no pull before the drop")
        assertTrue(enqueued.isEmpty())
        assertTrue(starter.starts.isEmpty(), "Tao's file drag is not used")
        assertEquals(DragOutPhase.OsDrag, c.phase)
        assertEquals(listOf(true), heard)
    }

    @Test
    fun `the drag ending resets the controller and tells the tab`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.mp4"), Offset.Zero)
        promises.end(dropped = true)
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(listOf(true, false), heard)
        assertEquals(1, finished)
    }

    @Test
    fun `a release or cancel during the promise drag changes nothing`() = runTest {
        val c = controller(this)
        c.begin(listOf("a.mp4"), Offset.Zero)
        c.released()
        c.cancel()
        assertEquals(DragOutPhase.OsDrag, c.phase)
        assertEquals(0, finished)
    }

    @Test
    fun `a refused promise drag falls back to pulling first`() = runTest {
        promises.result = false
        val c = controller(this)
        assertTrue(c.begin(listOf("a.mp4"), Offset.Zero))
        assertEquals(listOf(listOf("a.mp4")), pulls)
        assertEquals(DragOutPhase.Pulling, c.phase)
        assertEquals(listOf(true, false), heard, "the window's drop guard is lifted again")
        c.cancel()
    }

    @Test
    fun `nothing to promise falls back to pulling first`() = runTest {
        val c = controller(this, entries = emptyList())
        assertTrue(c.begin(listOf("a.mp4"), Offset.Zero))
        assertTrue(promises.starts.isEmpty())
        assertEquals(listOf(listOf("a.mp4")), pulls)
        c.cancel()
    }

    @Test
    fun `a write pulls to the drop target's path and waits for the pull`() = runTest {
        val c = controller(background)
        c.begin(listOf("a.mp4"), Offset.Zero)
        val target = Paths.get("/Users/me/Desktop/a 2.mp4")
        val result = writeAsync(promises.starts.single().single(), target)
        awaitEnqueued(1)
        assertEquals("a.mp4" to target, enqueued.single())
        val id = nextId - 1
        jobs.value = listOf(job(id, target, JobState.Running, bytes = 30, total = 100))
        Thread.sleep(50)
        assertTrue(!result.isDone, "still pulling")
        jobs.value = listOf(job(id, target, JobState.Done, bytes = 100, total = 100))
        assertEquals(PromiseOutcome.Done, result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf(30L to 100L, 100L to 100L), progress.toList(), "the drop target's progress follows the job")
    }

    @Test
    fun `a failed, cancelled or vanished pull ends the write with that`() = runTest {
        val c = controller(background)
        c.begin(listOf("a.mp4", "Camera"), Offset.Zero)
        val (file, folder) = promises.starts.single()

        val failed = writeAsync(file, Paths.get("/d/a.mp4"))
        awaitEnqueued(1)
        jobs.value = listOf(job(nextId - 1, Paths.get("/d/a.mp4"), JobState.Failed("device gone")))
        assertEquals(PromiseOutcome.Failed("device gone"), failed.get(5, TimeUnit.SECONDS))

        val cancelled = writeAsync(folder, Paths.get("/d/Camera"))
        awaitEnqueued(2)
        jobs.value = listOf(job(nextId - 1, Paths.get("/d/Camera"), JobState.Cancelled))
        assertEquals(PromiseOutcome.Cancelled, cancelled.get(5, TimeUnit.SECONDS))

        val vanished = writeAsync(file, Paths.get("/d/a 2.mp4"))
        awaitEnqueued(3)
        jobs.value = listOf(job(nextId - 1, Paths.get("/d/a 2.mp4"), JobState.Queued))
        Thread.sleep(50)
        jobs.value = emptyList()
        // The queue drops a job cancelled while queued: a cancel, quiet in Finder.
        assertEquals(PromiseOutcome.Cancelled, vanished.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a write whose pull could not be queued fails at once`() = runTest {
        val c = DragOutController(
            "S", cache, pull = { _, _ -> emptyList() }, cancelJob = {}, dismissJob = {},
            starter = starter, scope = background, io = EmptyCoroutineContext,
            promises = promises,
            promisedEntries = { names -> names.map { PromisedEntry(it, false, 1L) { null } } },
            jobUpdates = jobs, ui = EmptyCoroutineContext,
        )
        c.begin(listOf("a.mp4"), Offset.Zero)
        val outcome = writeAsync(promises.starts.single().single(), Paths.get("/d/a.mp4")).get(5, TimeUnit.SECONDS)
        assertTrue(outcome is PromiseOutcome.Failed)
    }
}
