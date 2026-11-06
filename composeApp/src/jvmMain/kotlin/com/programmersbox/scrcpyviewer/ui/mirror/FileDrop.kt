package com.programmersbox.scrcpyviewer.ui.mirror

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.files.FileTransferQueue
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.pushTarget
import com.programmersbox.scrcpyviewer.files.requestsFor
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import java.awt.Component
import java.awt.Container
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.RootPaneContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Native file pickers for Install APK… and Push file…, behind an interface so tests never open a window. */
interface TransferFileDialogs {
    /** APKs to install (multi-select); empty when cancelled. */
    fun chooseApks(parent: Frame?): List<Path>

    /** Files to push (multi-select); empty when cancelled. */
    fun chooseFiles(parent: Frame?, pushTarget: String): List<Path>
}

/** `java.awt.FileDialog` (the native dialog on macOS). The `.apk` filter is ignored by Windows' dialog. */
object AwtTransferFileDialogs : TransferFileDialogs {
    override fun chooseApks(parent: Frame?): List<Path> {
        val dialog = FileDialog(parent, "Install APK", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
        if (Os.isWindows) dialog.file = "*.apk" // the only filter the Windows dialog honours
        return show(dialog)
    }

    override fun chooseFiles(parent: Frame?, pushTarget: String): List<Path> {
        val dialog = FileDialog(parent, "Push to $pushTarget", FileDialog.LOAD)
        dialog.isMultipleMode = true
        return show(dialog)
    }

    private fun show(dialog: FileDialog): List<Path> {
        dialog.isVisible = true
        return dialog.files.orEmpty().map { it.toPath() }
    }
}

/** The files of a drag (`javaFileListFlavor`), or null when they cannot be read (yet). */
fun Transferable.filePaths(): List<Path>? = runCatching {
    if (!isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()?.map { it.toPath() }
}.getOrNull()

/**
 * The window's install / push state: the queue (null without adb), the drag in progress and the progress label.
 * Snapshot state is written on the EDT only (AWT drag-and-drop callbacks, effects).
 */
@Stable
class FileTransfers internal constructor(
    val queue: FileTransferQueue?,
    val pushTarget: String,
    private val notify: (String) -> Unit,
) {
    /** The session is not Disconnected or Failed. */
    var connected by mutableStateOf(true)
        internal set

    /** Files are being dragged over the window. */
    var dragging by mutableStateOf(false)
        internal set

    /** The dragged files, or null while the platform does not tell them before the drop. */
    var dragPaths by mutableStateOf<List<Path>?>(null)
        internal set

    /** "Installing app.apk…" / "Pushing 2 of 3: photo.jpg…" while the queue runs. */
    var progressLabel by mutableStateOf<String?>(null)
        internal set

    /** Install APK… / Push file… can be used and drops are accepted. */
    val enabled: Boolean get() = queue != null && connected

    /** The overlay to show, or null when nothing is dragged. */
    val hint: DropHint? get() = if (dragging) dropHint(dragPaths, pushTarget, enabled) else null

    /** Queues [paths]; false when nothing was queued (no adb, device disconnected, empty list). */
    fun start(paths: List<Path>): Boolean {
        if (paths.isEmpty()) return false
        val q = queue
        if (q == null) {
            notify("adb is not available")
            return false
        }
        return q.enqueuePaths(paths)
    }
}

/**
 * The install / push queue of [session] (v5.0 `file_pusher.c`): one per session, cancelled (the running adb killed,
 * the rest dropped) when the window closes or a Reconnect replaces the session. Results go to [notify].
 */
@Composable
fun rememberFileTransfers(
    serial: String,
    session: ScrcpySession,
    adb: AdbClient?,
    notify: (String) -> Unit,
    /** False while Claude Device Manager has not granted the device (queued, or taken back): nothing may touch it. */
    deviceReady: StateFlow<Boolean> = MutableStateFlow(true),
): FileTransfers {
    val currentNotify by rememberUpdatedState(notify)
    val scope = remember(session) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val transfers = remember(session, adb) {
        val target = pushTarget(session.values)
        val queue = adb?.let {
            FileTransferQueue(
                serial, it, target,
                canTransfer = { session.status.value.isConnected() && deviceReady.value },
                scanFile = { path -> session.send(ControlMessage.ScanFile(path)) },
                scope = scope,
            )
        }
        FileTransfers(queue, target) { currentNotify(it) }
    }
    LaunchedEffect(transfers, deviceReady) {
        combine(session.status, deviceReady) { status, ready -> status.isConnected() && ready }
            .collect { transfers.connected = it }
    }
    transfers.queue?.let { queue ->
        LaunchedEffect(queue) { queue.progress.collect { transfers.progressLabel = it?.label } }
        LaunchedEffect(queue) { queue.results.collect { currentNotify(it.message) } }
    }
    return transfers
}

private fun SessionStatus.isConnected() = this != SessionStatus.Disconnected && this != SessionStatus.Failed

/**
 * The drop action for a drag offering [sourceActions] (`DnDConstants` bits): always [DnDConstants.ACTION_COPY], so
 * a source never deletes its files after a drop (Explorer would, after a MOVE); null (reject) when the drag has no
 * files, the source cannot copy, or transfers are not [enabled].
 */
fun copyDropAction(sourceActions: Int, hasFiles: Boolean, enabled: Boolean): Int? =
    if (hasFiles && enabled && sourceActions and DnDConstants.ACTION_COPY != 0) DnDConstants.ACTION_COPY else null

/** AWT side of the file drop: accepts file lists as COPY only and feeds [transfers]. Runs on the EDT. */
internal class CopyOnlyFileDropListener(private val transfers: FileTransfers) : DropTargetListener {
    override fun dragEnter(dtde: DropTargetDragEvent) {
        if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            dtde.rejectDrag()
            return
        }
        transfers.dragging = true
        transfers.dragPaths = dtde.transferable.filePaths()
        answer(dtde)
    }

    override fun dragOver(dtde: DropTargetDragEvent) = answer(dtde)

    override fun dropActionChanged(dtde: DropTargetDragEvent) = answer(dtde)

    override fun dragExit(dte: DropTargetEvent) = endDrag()

    override fun drop(dtde: DropTargetDropEvent) {
        endDrag()
        val action = copyDropAction(dtde.sourceActions, dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor), transfers.enabled)
        if (action == null) {
            dtde.rejectDrop()
            return
        }
        dtde.acceptDrop(action)
        val paths = dtde.transferable.filePaths()
        dtde.dropComplete(paths != null && transfers.start(paths))
    }

    private fun answer(dtde: DropTargetDragEvent) {
        val action = copyDropAction(dtde.sourceActions, dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor), transfers.enabled)
        if (action == null) dtde.rejectDrag() else dtde.acceptDrag(action)
    }

    private fun endDrag() {
        transfers.dragging = false
        transfers.dragPaths = null
    }
}

/**
 * Makes [window] a COPY-only drop target for files. Compose's `dragAndDropTarget` accepts the user's drop action
 * (MOVE by default in Windows Explorer, which then deletes the original), so the AWT [DropTarget] Compose installs
 * on the window's container is swapped for one with [CopyOnlyFileDropListener] while this is in the composition,
 * and restored after.
 */
@Composable
fun FileDropTarget(window: Window, transfers: FileTransfers) {
    DisposableEffect(window, transfers) {
        val listener = CopyOnlyFileDropListener(transfers)
        val targets = window.descendants().filter { it.dropTarget != null }
            .ifEmpty { listOf((window as? RootPaneContainer)?.contentPane ?: window) }
        val previous = targets.associateWith { it.dropTarget }
        for (c in targets) c.dropTarget = DropTarget(c, DnDConstants.ACTION_COPY, listener, true)
        onDispose {
            for ((c, old) in previous) c.dropTarget = old
            transfers.dragging = false
            transfers.dragPaths = null
        }
    }
}

private fun Component.descendants(): List<Component> =
    if (this is Container) components.flatMap { listOf(it) + it.descendants() } else emptyList()

/** The last shown [DropHint], kept while the overlay fades out (plain field: no snapshot write in composition). */
class DropHintHolder {
    var hint: DropHint? = null
}

/** What a drop of [paths] does, for the overlay shown while dragging. */
data class DropHint(val label: String, val kind: TransferKind?, val allowed: Boolean)

private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

/**
 * "Drop to install APK" / "Drop to push to /sdcard/Download/" / "Drop to install 1 APK and push 2 files".
 * [paths] is null when the platform does not tell the files before the drop.
 */
fun dropHint(paths: List<Path>?, pushTarget: String, connected: Boolean, isDirectory: (Path) -> Boolean = Files::isDirectory): DropHint {
    if (!connected) return DropHint("Device not connected", null, allowed = false)
    if (paths.isNullOrEmpty()) return DropHint("Drop to install APKs or push files to $pushTarget", null, allowed = true)
    val requests = requestsFor(paths, isDirectory)
    val apks = requests.count { it.kind == TransferKind.Install }
    val files = requests.size - apks
    return when {
        files == 0 -> DropHint(if (apks == 1) "Drop to install APK" else "Drop to install $apks APKs", TransferKind.Install, true)
        apks == 0 -> DropHint(
            if (files == 1) "Drop to push to $pushTarget" else "Drop to push ${count(files, "file")} to $pushTarget",
            TransferKind.Push, true,
        )
        else -> DropHint("Drop to install ${count(apks, "APK")} and push ${count(files, "file")}", null, true)
    }
}

/** Shown over the stage while files are dragged over the window. */
@Composable
fun DropOverlay(hint: DropHint, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = if (hint.allowed) colors.primary else colors.error
    Box(
        modifier.fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.55f))
            .padding(12.dp)
            .border(2.dp, accent, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = 300.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val icon = when {
                    !hint.allowed -> Icons.Filled.Block
                    hint.kind == TransferKind.Install -> Icons.Filled.Android
                    else -> Icons.Filled.UploadFile
                }
                Box(
                    Modifier.size(56.dp).background(if (hint.allowed) colors.primaryContainer else colors.errorContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, Modifier.size(32.dp), tint = if (hint.allowed) colors.onPrimaryContainer else colors.onErrorContainer)
                }
                Text(hint.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
