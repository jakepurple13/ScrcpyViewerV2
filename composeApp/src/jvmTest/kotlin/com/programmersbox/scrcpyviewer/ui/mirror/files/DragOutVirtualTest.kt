package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.geometry.Offset
import com.programmersbox.scrcpyviewer.files.DragCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records virtual drags; [dropped] is what the platform says, [during] runs inside its loop. */
internal class FakeVirtualStarter(var dropped: Boolean = true) : VirtualDragStarter {
    val starts = mutableListOf<VirtualDrag>()
    var during: () -> Unit = {}

    override fun start(drag: VirtualDrag): Boolean {
        starts += drag
        during()
        return dropped
    }
}

class DragOutVirtualTest {
    private val cache = DragCache(Files.createTempDirectory("drag-out-virtual").resolve("scrcpy-vwr"))
    private val starter = FakeStarter()
    private val virtual = FakeVirtualStarter()
    private val pulls = mutableListOf<List<String>>()
    private val built = mutableListOf<List<String>>()
    private val heard = mutableListOf<Boolean>()
    private var finished = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var result: (List<String>) -> VirtualDrag? = { names -> VirtualDrag(names.map { VirtualFile(it, 1, false) { error("no read") } }) {} }

    private fun controller(scope: CoroutineScope) = DragOutController(
        "S", cache,
        pull = { names, _ -> pulls += names; names.indices.map { 70L + it } },
        cancelJob = {}, dismissJob = {},
        starter = starter, scope = scope, io = EmptyCoroutineContext,
        onOsDrag = { heard += it },
        virtual = virtual,
        virtualDrag = { names ->
            built += names
            gate?.await()
            result(names)
        },
    ).also { it.onFinished = { finished++ } }

    @Test
    fun `the virtual drag runs once its files are known, and nothing is pulled`() = runTest {
        val c = controller(this)
        assertTrue(c.begin(listOf("a.mp4", "Camera"), Offset.Zero))
        runCurrent()
        assertEquals(listOf(listOf("a.mp4", "Camera")), built)
        assertEquals(listOf(listOf("a.mp4", "Camera")), virtual.starts.map { d -> d.files.map { it.path } })
        assertTrue(pulls.isEmpty())
        assertTrue(starter.starts.isEmpty())
        assertEquals(listOf(true, false), heard)
        assertEquals(DragOutPhase.Idle, c.phase)
        assertEquals(1, finished)
    }

    @Test
    fun `while the files are listed the drag shows working, and the window refuses drops only during the OS drag`() = runTest {
        gate = CompletableDeferred()
        val c = controller(this)
        var phaseDuring: DragOutPhase? = null
        virtual.during = { phaseDuring = c.phase }
        c.begin(listOf("Camera"), Offset.Zero)
        runCurrent()
        assertEquals(DragOutPhase.Pulling, c.phase)
        assertEquals(null, c.progress, "indeterminate while listing")
        assertTrue(heard.isEmpty())
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(DragOutPhase.OsDrag, phaseDuring)
    }

    @Test
    fun `a release while listing starts nothing`() = runTest {
        gate = CompletableDeferred()
        val c = controller(this)
        c.begin(listOf("Camera"), Offset.Zero)
        runCurrent()
        c.released()
        assertEquals(DragOutPhase.Idle, c.phase)
        gate!!.complete(Unit)
        runCurrent()
        assertTrue(virtual.starts.isEmpty(), "never after a release")
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `files that cannot be offered virtually are pulled first instead`() = runTest {
        result = { null }
        val c = controller(this)
        c.begin(listOf("deep"), Offset.Zero)
        runCurrent()
        assertTrue(virtual.starts.isEmpty())
        assertEquals(listOf(listOf("deep")), pulls)
        assertEquals(DragOutPhase.Pulling, c.phase)
        c.cancel()
    }

    @Test
    fun `a drag that was not dropped tells the files nothing more will be read`() = runTest {
        var ended = 0
        result = { names -> VirtualDrag(names.map { VirtualFile(it, 1, false) { error("no read") } }) { ended++ } }
        virtual.dropped = false
        val c = controller(this)
        c.begin(listOf("a.mp4"), Offset.Zero)
        runCurrent()
        assertEquals(1, ended)
        assertEquals(1, finished)
    }
}
