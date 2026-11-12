package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.files.FileTransferQueue
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.pushTarget
import com.programmersbox.scrcpyviewer.files.requestsFor
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.NativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.onPickerThread
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.Files
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Native file pickers for Install APK… and Push file…, behind an interface so tests never open a window. */
interface TransferFileDialogs {
    /** APKs to install (multi-select); empty when cancelled. */
    suspend fun chooseApks(): List<Path>

    /** Files to push (multi-select); empty when cancelled. */
    suspend fun chooseFiles(pushTarget: String): List<Path>
}

/** The platform's own open dialog through [NativeFileDialogs]; never touches the AWT toolkit. */
class NativeTransferFileDialogs(private val dialogs: NativeFileDialogs = nativeFileDialogs()) : TransferFileDialogs {
    override suspend fun chooseApks(): List<Path> = onPickerThread { dialogs.open("Install APK", listOf("apk"), multiple = true) }

    override suspend fun chooseFiles(pushTarget: String): List<Path> =
        onPickerThread { dialogs.open("Push to $pushTarget", emptyList(), multiple = true) }
}

/**
 * The window's install / push state: the queue (null without adb), the drag in progress and the progress label.
 * Snapshot state is written on the UI thread only (Compose drag-and-drop callbacks, effects).
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
        return q.enqueuePaths(paths).isNotEmpty()
    }

    /** Mirror of the queue's jobs (newest last), for the transfer tray. */
    var jobs by mutableStateOf<List<TransferJob>>(emptyList())
        internal set

    /** Jobs queued or running. */
    val runningCount: Int get() = jobs.count { it.active }
}

/**
 * Runs [pick] (Install APK... / Push file...) and queues what it returns on [transfers] as they are once the picker
 * closes, not at the click: a Reconnect while the picker is open replaces the transfers and cancels the old queue,
 * which would drop the files without a word.
 */
internal suspend fun pickAndStart(transfers: () -> FileTransfers, pick: suspend (pushTarget: String) -> List<Path>): Boolean {
    val paths = pick(transfers().pushTarget)
    return transfers().start(paths)
}

/**
 * The install / push queue of [session] (v5.0 `file_pusher.c`): one per session, cancelled (the running adb killed,
 * the rest dropped) when the window closes or a Reconnect replaces the session. Results go to [notify].
 */
@Composable
fun rememberFileTransfers(serial: String, session: ScrcpySession, adb: AdbClient?, notify: (String) -> Unit): FileTransfers {
    val currentNotify by rememberUpdatedState(notify)
    val scope = remember(session) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val transfers = remember(session, adb) {
        val target = pushTarget(session.values)
        val queue = adb?.let {
            FileTransferQueue(
                serial, it, target,
                canTransfer = { session.status.value.isConnected() },
                scanFile = { path -> session.send(ControlMessage.ScanFile(path)) },
                scope = scope,
            )
        }
        FileTransfers(queue, target) { currentNotify(it) }
    }
    LaunchedEffect(transfers) { session.status.collect { transfers.connected = it.isConnected() } }
    transfers.queue?.let { queue ->
        LaunchedEffect(queue) { queue.progress.collect { transfers.progressLabel = it?.label } }
        LaunchedEffect(queue) { queue.jobs.collect { transfers.jobs = it } }
        LaunchedEffect(queue) { queue.results.collect { currentNotify(it.message) } }
    }
    return transfers
}

/** The session is neither Disconnected nor Failed. */
internal fun SessionStatus.isConnected() = this != SessionStatus.Disconnected && this != SessionStatus.Failed

/**
 * The drop action for a drag offering [sourceActions] (`DnDConstants` bits): always [DnDConstants.ACTION_COPY], so
 * a source never deletes its files after a drop (Explorer would, after a MOVE); null (reject) when the drag has no
 * files, the source cannot copy, or transfers are not [enabled].
 */
fun copyDropAction(sourceActions: Int, hasFiles: Boolean, enabled: Boolean): Int? =
    if (hasFiles && enabled && sourceActions and DnDConstants.ACTION_COPY != 0) DnDConstants.ACTION_COPY else null

/** The drag's data, or null when it cannot be read (yet, or ever: no AWT event behind it). */
@OptIn(ExperimentalComposeUiApi::class)
private fun readDragData(event: DragAndDropEvent): DragData? = runCatching { event.dragData() }.getOrNull()

/**
 * The files of a drag or drop [event] (file URIs from [read]), or null when it carries no files or they cannot be
 * read (yet).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun dropPaths(event: DragAndDropEvent, read: (DragAndDropEvent) -> DragData? = ::readDragData): List<Path>? =
    runCatching {
        (read(event) as? DragData.FilesList)?.readFiles()?.map { Paths.get(URI(it)) }
    }.getOrNull()

/**
 * The `DnDConstants` actions the drag's source offers: the AWT event's (the Tao backend's synthetic events offer
 * COPY_OR_MOVE), else COPY when Compose reports a Copy.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.sourceActions(): Int = when (val native = nativeEvent) {
    is DropTargetDragEvent -> native.sourceActions
    is DropTargetDropEvent -> native.sourceActions
    else -> if (action == DragAndDropTransferAction.Copy) DnDConstants.ACTION_COPY else DnDConstants.ACTION_NONE
}

/**
 * Compose's AWT drop handling accepts the user's drop action (MOVE by default in Windows Explorer, which then deletes
 * the original): accepting the AWT drop again as COPY, before it completes, makes the drop a copy. No-op under Tao
 * (its drops are copies and its synthetic events ignore this).
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.acceptAsCopy() {
    (nativeEvent as? DropTargetDropEvent)?.let { runCatching { it.acceptDrop(DnDConstants.ACTION_COPY) } }
}

/**
 * The window's file drop: file lists start a drag (the overlay shows [FileTransfers.hint]), and a drop feeds
 * [transfers] when [copyDropAction] allows it, as a COPY. Equal per [transfers] and [read], so recomposing mid-drag
 * keeps the drop node and its session. Runs on the UI thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal data class CopyOnlyFileDropTarget(
    private val transfers: FileTransfers,
    private val read: (DragAndDropEvent) -> DragData? = ::readDragData,
) : DragAndDropTarget {
    /** `shouldStartDragAndDrop`: only drags that carry files. */
    fun accepts(event: DragAndDropEvent): Boolean = hasFiles(event)

    override fun onStarted(event: DragAndDropEvent) = startDrag(event)

    override fun onEntered(event: DragAndDropEvent) = startDrag(event)

    override fun onExited(event: DragAndDropEvent) = endDrag()

    override fun onEnded(event: DragAndDropEvent) = endDrag()

    override fun onDrop(event: DragAndDropEvent): Boolean {
        endDrag()
        copyDropAction(event.sourceActions(), hasFiles(event), transfers.enabled) ?: return false
        event.acceptAsCopy()
        val paths = dropPaths(event, read)
        return paths != null && transfers.start(paths)
    }

    private fun hasFiles(event: DragAndDropEvent) = runCatching { read(event) is DragData.FilesList }.getOrDefault(false)

    private fun startDrag(event: DragAndDropEvent) {
        transfers.dragging = true
        transfers.dragPaths = dropPaths(event, read)
    }

    private fun endDrag() {
        transfers.dragging = false
        transfers.dragPaths = null
    }
}

/**
 * Makes this (the mirror window's root) a COPY-only drop target for files through Compose drag-and-drop, which the
 * AWT and Tao backends both feed. Drops install APKs and push other files through [transfers].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.fileDropTarget(transfers: FileTransfers): Modifier {
    val target = CopyOnlyFileDropTarget(transfers)
    return dragAndDropTarget(shouldStartDragAndDrop = target::accepts, target = target)
}

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

/**
 * Shown over the stage while files are dragged over the window. The border is a dashed outline that marches while a
 * drop is allowed; the icon bobs. When it is not allowed the colors crossfade to the error colors, the march and bob
 * stop, and the card shakes once. The card eases in the first time it shows (the caller fades the whole overlay).
 */
@Composable
fun DropOverlay(hint: DropHint, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val colorSpec = tween<Color>(250)
    val accent by animateColorAsState(if (hint.allowed) colors.primary else colors.error, colorSpec)
    val iconContainer by animateColorAsState(if (hint.allowed) colors.primaryContainer else colors.errorContainer, colorSpec)
    val iconTint by animateColorAsState(if (hint.allowed) colors.onPrimaryContainer else colors.onErrorContainer, colorSpec)
    val phase = rememberMarchingPhase(hint.allowed, with(density) { (DropDash + DropGap).toPx() })
    val bob = rememberBob(hint.allowed)
    val shake = rememberRefusalShake(hint.allowed)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) }
    Box(
        modifier.fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.55f))
            .padding(12.dp)
            .marchingDashBorder(2.dp, 20.dp, color = { accent }, phase = { phase.value }),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.graphicsLayer {
                val t = entrance.value
                val s = 0.92f + 0.08f * t
                scaleX = s
                scaleY = s
                alpha = t
                translationY = (1f - t) * 8.dp.toPx()
                translationX = shake.value.dp.toPx()
            },
            shape = styledCorner(16.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp,
        ) {
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
                    Modifier.size(56.dp)
                        .graphicsLayer { translationY = bob.value.dp.toPx() }
                        .drawBehind { drawRoundRect(iconContainer, cornerRadius = CornerRadius(16.dp.toPx())) },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(icon, animationSpec = tween(200)) { shown ->
                        Icon(shown, contentDescription = null, Modifier.size(32.dp), tint = iconTint)
                    }
                }
                Text(hint.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
