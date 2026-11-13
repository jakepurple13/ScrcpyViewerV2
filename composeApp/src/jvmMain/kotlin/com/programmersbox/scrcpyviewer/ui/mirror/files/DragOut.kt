@file:OptIn(ExperimentalComposeUiApi::class)

package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import com.programmersbox.scrcpyviewer.files.DragCache
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.TransferJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * How long a drag folder outlives its OS drag. Finder copies during the drop, before the drag returns; Explorer and
 * file managers on Linux may copy after it (a shell copy that runs on its own thread), so the files stay a while.
 */
const val DRAG_DIR_KEEP_MILLIS = 30_000L

private val dragLog = System.getLogger("scrcpyviewer.DragOut")

/** Diagnostic line for the drag-out flow (INFO, single line). */
internal fun logDragOut(message: String) = dragLog.log(System.Logger.Level.INFO, message)

internal fun logDragOutWarn(message: String, error: Throwable) = dragLog.log(System.Logger.Level.WARNING, message, error)

/** Hands files to the computer's own drag and drop. */
interface OutboundDragStarter {
    /**
     * Starts an OS drag of [files] at [at] (the tab's coordinates) and blocks until it ends: the platform runs its own
     * event loop until the drop. False when it did not start. [onEnd] hears whether something was dropped.
     */
    fun start(files: List<File>, at: Offset, onEnd: (dropped: Boolean) -> Unit): Boolean
}

/** How a promised write ended. */
sealed interface PromiseOutcome {
    data object Done : PromiseOutcome

    /** Stopped from the tray: the drop target drops its placeholder quietly. */
    data object Cancelled : PromiseOutcome

    data class Failed(val reason: String) : PromiseOutcome
}

/**
 * One file or folder of a promise drag: nothing exists until the drop target asks for it. [write] puts it at the
 * path the drop target picked (its folder, and a name it may have changed to avoid a clash) and blocks until done; it
 * runs on the platform's promise thread, never the UI thread, and reports the pull's bytes of its total (null:
 * unknown, as for a folder) as they come.
 */
class PromisedItem(
    val name: String,
    val isDirectory: Boolean,
    /** The file's size from the listing, for the drop target's progress from the start; null for a folder. */
    val size: Long?,
    val write: (target: Path, progress: (bytes: Long, total: Long?) -> Unit) -> PromiseOutcome,
)

/**
 * A device entry to promise, with how to queue its pull: [enqueue] queues a pull of it to an exact host path and
 * returns the job's id, or null when it could not be queued. Called on the UI thread.
 */
class PromisedEntry(val name: String, val isDirectory: Boolean, val size: Long?, val enqueue: (target: Path) -> Long?)

/**
 * Hands promised files to the computer's own drag and drop (macOS file promises). The drag starts at once; the drop
 * target asks for each item after the drop, through [PromisedItem.write].
 */
interface PromiseDragStarter {
    /**
     * Starts the OS drag of [items] from the pointer event being handled (UI thread, inside it: the platform takes
     * the event). Returns at once; false when it did not start. [onEnd] (UI thread) hears whether something was
     * dropped. The writes may outlive the drag.
     */
    fun start(items: List<PromisedItem>, onEnd: (dropped: Boolean) -> Unit): Boolean
}

/** When the OS drag starts once the pulled files are ready (the button always still held). */
enum class DragOutStartRule {
    /** At once, wherever the pointer is: the spec's fallback, and the default until the hand test says otherwise. */
    WhenReady,

    /** Only once the pointer is outside the window. */
    WhenReadyAndOutside,
}

enum class DragOutPhase {
    Idle,

    /** The rows are being pulled into the drag folder. */
    Pulling,

    /** Pulled; the OS drag is about to start (or, with [DragOutStartRule.WhenReadyAndOutside], waits for the pointer). */
    Ready,

    /** The OS drag runs; it ends on its own. */
    OsDrag,
}

/**
 * Rows dragged out of the Files tab, onto the computer. [begin] pulls them into a new drag folder ([DragCache]) through
 * [pull] (one transfer job per name; the pull creates the folder, off the UI thread). Once every pull is done and the button is still held, [starter] hands the files
 * to the OS drag (see [DragOutStartRule]); the folder is deleted [keepMillis] after the drag ends. [released] or
 * [cancel] before that cancels the pulls ([cancelJob]) and deletes the folder once no pull can still write into it.
 *
 * The OS drag never starts after a release: the platform reuses the last mouse-down without checking the button. It
 * is synchronous, so the gesture never sees its pointer-up; [onFinished] tells the tab to drop its drag state when the
 * drag-out ends on its own (the OS drag returned, a pull failed). [onJobs] feeds the queue's jobs in (a snapshot that
 * may lag); [currentJobs] reads the queue's jobs as they are now, to tell whether a pull can still write. Folders are
 * deleted on [cleanupScope], which outlives the tab (the window's). UI thread only.
 *
 * With [promises] (macOS), [begin] starts the OS drag at once instead, with nothing pulled: the drop target asks for
 * each of [promisedEntries] after the drop, and the pull goes straight to the path it picked (a job in the tray, which
 * [jobUpdates] follows until it ends). Without them, or when the promise drag does not start, the rows are pulled
 * first as above.
 */
@Stable
class DragOutController(
    private val serial: String,
    private val cache: DragCache,
    private val pull: (names: List<String>, dir: Path) -> List<Long>,
    private val cancelJob: (Long) -> Unit,
    private val dismissJob: (Long) -> Unit,
    private val starter: OutboundDragStarter,
    private val scope: CoroutineScope,
    private val cleanupScope: CoroutineScope = scope,
    private val currentJobs: (() -> List<TransferJob>)? = null,
    private val io: CoroutineContext = Dispatchers.IO,
    private val keepMillis: Long = DRAG_DIR_KEEP_MILLIS,
    private val rule: DragOutStartRule = DragOutStartRule.WhenReady,
    /** Hears the OS drag start (true) and end (false): the window's drop targets refuse it meanwhile. */
    private val onOsDrag: (Boolean) -> Unit = {},
    private val promises: PromiseDragStarter? = null,
    private val promisedEntries: (names: List<String>) -> List<PromisedEntry> = { emptyList() },
    private val jobUpdates: Flow<List<TransferJob>>? = null,
    /** Where promised pulls are queued from (the UI thread). */
    private val ui: CoroutineContext = Dispatchers.Main,
    private val virtual: VirtualDragStarter? = null,
    /** The virtual files of [names] (folders listed); null when they cannot be offered that way. */
    private val virtualDrag: suspend (names: List<String>) -> VirtualDrag? = { null },
) {
    /** One drag-out: its folder and pull jobs. */
    private class Drag(val dir: Path, val ids: Set<Long>) {
        /** The jobs that showed up in the queue's list; one that leaves it before it ended was dropped. */
        val seen = HashSet<Long>()
        val states = HashMap<Long, JobState>()
        var deleteScheduled = false
    }

    var phase by mutableStateOf(DragOutPhase.Idle)
        private set

    /** The pulls' share done, 0 to 1; null when a size is unknown (a folder). */
    var progress by mutableStateOf<Float?>(0f)
        private set

    /** The pulled files, once all are done. */
    var readyFiles by mutableStateOf<List<File>?>(null)
        private set

    /** The drag-out ended on its own: the OS drag returned, or a pull failed. */
    var onFinished: () -> Unit = {}

    val active: Boolean get() = phase != DragOutPhase.Idle

    private var current: Drag? = null
    private var pointer = Offset.Zero
    private var outsideWindow = false

    // Drags given up while their pulls may still write: their folders go once those pulls ended.
    private val abandoned = mutableListOf<Drag>()
    private var lastJobs: List<TransferJob> = emptyList()

    /** Pulls [names] into a new drag folder; false (nothing left behind) when nothing could be queued. */
    fun begin(names: List<String>, at: Offset): Boolean {
        if (active || names.isEmpty()) {
            logDragOut("begin refused: active=$active, phase=$phase, names=$names")
            return false
        }
        if (beginPromised(names)) return true
        if (beginVirtual(names, at)) return true
        return beginPulled(names, at)
    }

    /** The pull-first drag: [names] are pulled into a drag folder, then handed to the OS drag. */
    private fun beginPulled(names: List<String>, at: Offset): Boolean {
        val dir = cache.newDragDir(serial)
        logDragOut("begin: names=$names, at=$at, dragDir=$dir, rule=$rule")
        val ids = pull(names, dir)
        if (ids.isEmpty()) {
            logDragOut("begin refused: pull queued no jobs (names=$names, dragDir=$dir)")
            return false
        }
        logDragOut("pulls enqueued: jobIds=$ids, dragDir=$dir")
        current = Drag(dir, ids.toSet())
        pointer = at
        progress = 0f
        readyFiles = null
        moveToPhase(DragOutPhase.Pulling)
        onJobs(lastJobs)
        return true
    }

    /** Lists the folders of a virtual drag; null when none is being prepared. */
    private var virtualPrep: kotlinx.coroutines.Job? = null

    /**
     * The virtual-file drag of [names] (Windows): their files are listed (folders take a device call) while the button
     * stays held, shown as working; then the OS drag runs, blocking until the drop, and nothing is pulled until the
     * drop target reads. A release or cancel meanwhile starts nothing. Files that cannot be offered that way are
     * pulled first instead. False when there is no virtual starter.
     */
    private fun beginVirtual(names: List<String>, at: Offset): Boolean {
        val starter = virtual ?: return false
        logDragOut("begin (virtual): names=$names")
        pointer = at
        progress = null
        moveToPhase(DragOutPhase.Pulling)
        virtualPrep = scope.launch {
            val drag = try {
                virtualDrag(names)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logDragOutWarn("virtual drag: listing failed", e)
                null
            }
            virtualPrep = null
            // Released or cancelled while listing: never start after a release.
            if (phase != DragOutPhase.Pulling) return@launch
            if (drag == null) {
                logDragOut("virtual drag not possible: pulling first instead")
                moveToPhase(DragOutPhase.Idle)
                if (!beginPulled(names, pointer)) onFinished()
                return@launch
            }
            moveToPhase(DragOutPhase.OsDrag)
            onOsDrag(true)
            val dropped = try {
                starter.start(drag)
            } catch (e: Exception) {
                logDragOutWarn("virtual drag threw", e)
                false
            } finally {
                onOsDrag(false)
            }
            logDragOut("virtual drag ended: dropped=$dropped")
            // Nothing dropped: nothing will be read.
            if (!dropped) drag.readsEnded()
            reset()
            onFinished()
        }
        return true
    }

    /** Starts the promise drag of [names]; false (nothing changed) when there are no promises or it did not start. */
    private fun beginPromised(names: List<String>): Boolean {
        val starter = promises ?: return false
        val jobs = jobUpdates ?: return false
        val entries = promisedEntries(names)
        if (entries.isEmpty()) return false
        val items = entries.map { e -> PromisedItem(e.name, e.isDirectory, e.size) { target, progress -> writePromised(e, target, jobs, progress) } }
        logDragOut("begin (promise): names=${entries.map { it.name }}")
        moveToPhase(DragOutPhase.OsDrag)
        onOsDrag(true)
        val started = try {
            starter.start(items) { dropped ->
                logDragOut("promise drag ended: dropped=$dropped")
                onOsDrag(false)
                reset()
                onFinished()
            }
        } catch (e: Exception) {
            logDragOutWarn("promise drag threw", e)
            false
        }
        if (!started) {
            logDragOut("promise drag did not start: pulling first instead")
            onOsDrag(false)
            moveToPhase(DragOutPhase.Idle)
        }
        return started
    }

    /** Queues [entry]'s pull to [target] and waits for it (the platform's promise thread). */
    private fun writePromised(
        entry: PromisedEntry,
        target: Path,
        jobs: Flow<List<TransferJob>>,
        progress: (bytes: Long, total: Long?) -> Unit,
    ): PromiseOutcome = runBlocking {
        val id = withContext(ui) { entry.enqueue(target) }
            ?: return@runBlocking PromiseOutcome.Failed("could not pull ${entry.name}")
        logDragOut("promise write: ${entry.name} -> $target, jobId=$id")
        var seen = false
        val end = jobs.first { list ->
            val job = list.firstOrNull { it.id == id }
            if (job != null) {
                seen = true
                progress(job.bytes, job.total)
            }
            (job == null && seen) || job?.active == false
        }.firstOrNull { it.id == id }
        val outcome = when (val state = end?.state) {
            JobState.Done -> PromiseOutcome.Done
            JobState.Cancelled -> PromiseOutcome.Cancelled
            is JobState.Failed -> PromiseOutcome.Failed(state.reason)
            // Gone before it ended: the queue drops a job cancelled while queued (only ended jobs are dismissed).
            null -> PromiseOutcome.Cancelled
            else -> PromiseOutcome.Failed("the pull of ${entry.name} stopped unexpectedly")
        }
        logDragOut("promise write ended: ${entry.name}, $outcome")
        outcome
    }

    /** The pointer moved to [at] (the tab's coordinates). */
    fun pointerAt(at: Offset, outsideWindow: Boolean) {
        pointer = at
        this.outsideWindow = outsideWindow
        // With WhenReady the start was already launched when the files became ready.
        if (phase == DragOutPhase.Ready && rule == DragOutStartRule.WhenReadyAndOutside) startIfAllowed()
    }

    /** The button went up before the OS drag started: nothing reaches the computer. */
    fun released() = giveUp()

    /** The pointer came back in, Esc, or the device went away: the drag goes back to a move (or ends). */
    fun cancel() = giveUp()

    private fun giveUp() {
        virtualPrep?.let {
            logDragOut("giveUp: virtual drag still listing, cancelled")
            it.cancel()
            virtualPrep = null
            reset()
            return
        }
        val drag = current ?: return
        if (phase == DragOutPhase.OsDrag) {
            logDragOut("giveUp ignored: OS drag already running")
            return
        }
        logDragOut("giveUp: phase=$phase, jobIds=${drag.ids}, dragDir=${drag.dir}")
        current = null
        reset()
        cancelPulls(drag)
        abandoned += drag
        sweepAbandoned()
    }

    /** The transfer queue's jobs changed. */
    fun onJobs(jobs: List<TransferJob>) {
        lastJobs = jobs
        sweepAbandoned()
        val drag = current ?: return
        if (phase != DragOutPhase.Pulling) return
        val mine = jobs.filter { it.id in drag.ids }
        mine.forEach {
            if (drag.states[it.id] != it.state) {
                logDragOut("job ${it.id} state ${drag.states[it.id]} -> ${it.state} (bytes=${it.bytes}, total=${it.total})")
                drag.states[it.id] = it.state
            }
            drag.seen += it.id
        }
        progress = progressOf(mine)
        // A pull that failed, was cancelled elsewhere, or left the list (the queue dropped it): nothing to drag.
        val gone = drag.seen.any { id -> mine.none { it.id == id } }
        if (gone || mine.any { it.state is JobState.Failed || it.state == JobState.Cancelled }) {
            logDragOut("giveUp: pull failed/cancelled/dropped (gone=$gone, states=${mine.map { it.id to it.state }}), dragDir=${drag.dir}")
            current = null
            reset()
            cancelPulls(drag)
            abandoned += drag
            sweepAbandoned()
            onFinished()
            return
        }
        if (mine.size == drag.ids.size && mine.all { it.state == JobState.Done }) {
            readyFiles = mine.mapNotNull { it.hostResult?.toFile() }
            logDragOut("all pulls done: files=${readyFiles?.map { it.name + " exists=" + it.exists() + " size=" + it.length() }}")
            moveToPhase(DragOutPhase.Ready)
            startIfAllowed()
        }
    }

    private fun progressOf(jobs: List<TransferJob>): Float? {
        if (jobs.any { it.total == null }) return null
        val total = jobs.sumOf { it.total ?: 0L }
        if (total <= 0L) return if (jobs.all { it.state == JobState.Done }) 1f else 0f
        return (jobs.sumOf { it.bytes.coerceAtMost(it.total ?: 0L) }.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun startIfAllowed() {
        if (rule == DragOutStartRule.WhenReadyAndOutside && !outsideWindow) {
            logDragOut("startIfAllowed: waiting, pointer still inside the window (rule=$rule)")
            return
        }
        val drag = current ?: run {
            logDragOut("startIfAllowed: no current drag (phase=$phase)")
            return
        }
        val files = readyFiles ?: run {
            logDragOut("startIfAllowed: no ready files (phase=$phase)")
            return
        }
        logDragOut("startIfAllowed: launching OS drag, phase=$phase, pointer=$pointer, files=${files.map { it.name }}")
        // Out of the caller (a pointer handler or the jobs collector): the drag blocks until the drop.
        scope.launch {
            // Released (or cancelled) since: never start after a release.
            if (current !== drag || phase != DragOutPhase.Ready) {
                logDragOut("OS drag skipped: released or cancelled before launch (phase=$phase, stillCurrent=${current === drag})")
                return@launch
            }
            moveToPhase(DragOutPhase.OsDrag)
            onOsDrag(true)
            val started = try {
                starter.start(files, pointer) { dropped ->
                    logDragOut("OS drag ended: dropped=$dropped")
                    deleteLater(drag, keepMillis)
                }
            } catch (e: Exception) {
                logDragOutWarn("starter.start threw", e)
                false
            } finally {
                onOsDrag(false)
            }
            logDragOut("starter.start returned started=$started")
            if (current === drag) {
                current = null
                reset()
            }
            deleteLater(drag, if (started) keepMillis else 0)
            onFinished()
        }
    }

    private fun moveToPhase(next: DragOutPhase) {
        if (phase != next) logDragOut("phase $phase -> $next")
        phase = next
    }

    private fun reset() {
        moveToPhase(DragOutPhase.Idle)
        progress = 0f
        readyFiles = null
        outsideWindow = false
    }

    /** Cancels [drag]'s pulls that have not ended. */
    private fun cancelPulls(drag: Drag) {
        val jobs = jobsNow()
        drag.ids.filter { id -> jobs.none { it.id == id && !it.active } }.forEach(cancelJob)
    }

    private fun jobsNow(): List<TransferJob> = currentJobs?.invoke() ?: lastJobs

    /** Deletes abandoned folders once none of their pulls can still write. */
    private fun sweepAbandoned() {
        if (abandoned.isEmpty()) return
        val jobs = jobsNow()
        val it = abandoned.iterator()
        while (it.hasNext()) {
            val drag = it.next()
            if (jobs.any { j -> j.id in drag.ids && j.active }) continue
            it.remove()
            deleteLater(drag, 0)
        }
    }

    private fun deleteLater(drag: Drag, afterMillis: Long) {
        if (drag.deleteScheduled) return
        drag.deleteScheduled = true
        logDragOut("deleteLater scheduled: dir=${drag.dir}, afterMillis=$afterMillis")
        cleanupScope.launch(io) {
            if (afterMillis > 0) delay(afterMillis)
            cache.delete(drag.dir)
            drag.ids.forEach(dismissJob)
            logDragOut("deleteLater ran: dir=${drag.dir}")
        }
    }
}

/** A file list for the OS drag. java.awt.datatransfer is plain data: no AWT toolkit needed. */
private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
}

/**
 * The OS drag through Compose's drag-and-drop source (Tao's outbound drag, see
 * docs/superpowers/notes/2026-11-12-tao-drag-out-spike.md). Needs [dragOutSource] on the tab; [start]'s position is
 * only logged (the platform starts the drag where the mouse is). Copy only: a drop must never move the cached file away. Tao shows the platform's file icon, not a
 * custom drag image.
 */
class ComposeDragOutStarter : OutboundDragStarter {
    internal var node: DragAndDropSourceModifierNode? = null
    internal var pending: DragAndDropTransferData? = null
    internal var accepted: Boolean? = null

    override fun start(files: List<File>, at: Offset, onEnd: (dropped: Boolean) -> Unit): Boolean {
        val n = node ?: run {
            logDragOut("starter.start refused: no DragAndDropSourceModifierNode attached (dragOutSource missing)")
            return false
        }
        pending = DragAndDropTransferData(
            transferable = DragAndDropTransferable(FileListTransferable(files)),
            supportedActions = listOf(DragAndDropTransferAction.Copy),
            // The completion signal: the drop's action, null when nothing was dropped.
            onTransferCompleted = { action ->
                logDragOut("onTransferCompleted: action=$action")
                onEnd(action != null)
            },
        )
        accepted = null
        logDragOut("requestDragAndDropTransfer: at=$at, files=${files.map { it.name + " exists=" + it.exists() + " size=" + it.length() }}")
        try {
            // Offset.Unspecified, not [at]: Compose only calls a source whose own bounds contain the offset, and the
            // drag-out starts once the pointer has left the tab, so [at] is always outside them and the source was
            // skipped (nothing started, no error). Tao takes no position from it; the OS drag starts at the mouse.
            n.requestDragAndDropTransfer(Offset.Unspecified)
        } catch (e: Throwable) {
            logDragOutWarn("requestDragAndDropTransfer threw", e)
            throw e
        } finally {
            pending = null
        }
        logDragOut("requestDragAndDropTransfer returned: accepted=$accepted")
        return accepted == true
    }
}

private class DragOutNode(var starter: ComposeDragOutStarter) : DelegatingNode() {
    private val dnd = delegate(DragAndDropSourceModifierNode { _ ->
        val data = starter.pending ?: run {
            logDragOut("drag source callback: no pending transfer data")
            return@DragAndDropSourceModifierNode
        }
        // Tao ignores the decoration; the OS shows its own file icon.
        starter.accepted = try {
            startDragAndDropTransfer(data, Size(96f, 96f)) {}
        } catch (e: Throwable) {
            logDragOutWarn("startDragAndDropTransfer threw", e)
            throw e
        }
        logDragOut("startDragAndDropTransfer returned: ${starter.accepted}")
    })

    override fun onAttach() {
        starter.node = dnd
    }

    override fun onDetach() {
        if (starter.node === dnd) starter.node = null
    }
}

private class DragOutElement(val starter: ComposeDragOutStarter) : ModifierNodeElement<DragOutNode>() {
    override fun create() = DragOutNode(starter)
    override fun update(node: DragOutNode) {
        node.starter = starter
    }
    override fun equals(other: Any?) = other is DragOutElement && other.starter === starter
    override fun hashCode() = System.identityHashCode(starter)
}

/** Where [starter]'s OS drags start from; [OutboundDragStarter.start] takes this layout's coordinates. */
internal fun Modifier.dragOutSource(starter: OutboundDragStarter): Modifier =
    if (starter is ComposeDragOutStarter) this then DragOutElement(starter) else this
