package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.files.DEVICE_ROOT
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.devicePath
import com.programmersbox.scrcpyviewer.files.isInside
import com.programmersbox.scrcpyviewer.files.normalizeDir
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.common.OPEN_MILLIS
import com.programmersbox.scrcpyviewer.ui.mirror.acceptAsCopy
import com.programmersbox.scrcpyviewer.ui.mirror.copyDropAction
import com.programmersbox.scrcpyviewer.ui.mirror.dropPaths
import com.programmersbox.scrcpyviewer.ui.mirror.rootPosition
import com.programmersbox.scrcpyviewer.ui.mirror.sourceActions
import com.programmersbox.scrcpyviewer.ui.theme.styledCorner

/** Where a drop on the Files tab lands. */
sealed interface FilesDropTarget {
    val dir: String

    /** A folder row or a breadcrumb. */
    data class Folder(override val dir: String) : FilesDropTarget

    /** The open folder itself: empty space or a file row. */
    data class Current(override val dir: String) : FilesDropTarget
}

/** Holding a drag over a folder row this long opens it. */
const val SPRING_OPEN_MILLIS = 800

/**
 * What a drop over the row [rowName] (null: empty space) of the folder [current] does. [internal] drags (moves inside
 * the tab) only land on a folder row that is not itself one of the [dragged] names; computer drags land on a folder
 * row, or else on [current]. Null when nothing takes the drop, and always when the target is [blocked].
 */
fun dropTargetFor(
    rowName: String?,
    rowIsDir: Boolean,
    current: String,
    dragged: Set<String>,
    blocked: Boolean,
    internal: Boolean,
): FilesDropTarget? = when {
    blocked -> null
    rowName != null && rowIsDir -> if (internal && rowName in dragged) null else FilesDropTarget.Folder(devicePath(current, rowName))
    internal -> null
    else -> FilesDropTarget.Current(current)
}

private val BLOCKED_ROOTS = listOf("$DEVICE_ROOT/Android/data", "$DEVICE_ROOT/Android/obb")

/** Folders Android hides from adb (Android 11 and later): drops there are refused up front. */
fun isKnownBlocked(dir: String): Boolean {
    val d = normalizeDir(dir).lowercase()
    return BLOCKED_ROOTS.any { isInside(d, it.lowercase()) }
}

/** The pointer is over [target]; [row] / [crumb] say what to light up, [label] is the chip's text. */
data class DropHover(val target: FilesDropTarget, val row: String?, val crumb: String?, val label: String)

/**
 * The tab's drop state: computer drags over it (push) and row drags inside it (move). Rows and crumbs report where
 * they are (root coordinates) through [placeRow] / [placeCrumb]; the drag finds what it is over from those.
 * Snapshot state is written on the UI thread only.
 */
@Stable
class FilesDropState internal constructor(private val browser: FilesBrowser) {
    private class RowHit(val dir: String, val name: String, val isDir: Boolean, var bounds: Rect)
    private class CrumbHit(val label: String, var bounds: Rect)

    // Plain maps: written from layout callbacks, read when the pointer moves.
    private val rows = HashMap<String, RowHit>()
    private val crumbs = HashMap<String, CrumbHit>()
    internal var listBounds: Rect? = null

    /** What the pointer is over, or null when a drop there would do nothing. */
    var hover by mutableStateOf<DropHover?>(null)
        private set

    /** The rows being moved inside the tab (in list order), or null. */
    var moving by mutableStateOf<List<String>?>(null)
        private set

    /** The folder [moving] comes from. */
    var movingFrom by mutableStateOf<String?>(null)
        private set

    /** "1 file" / "3 items", for the drag ghost. */
    var movingLabel by mutableStateOf("")
        private set

    /** The pointer during a move, in root coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** The spring-open line under the hovered folder row, 0 to 1. */
    internal val spring = Animatable(0f)

    // A plain press on one of several selected rows selects just it on release, unless it starts a drag.
    private var deferredClick: String? = null

    internal fun placeRow(dir: String, name: String, isDir: Boolean, bounds: Rect) {
        val key = devicePath(dir, name)
        rows[key]?.let { it.bounds = bounds } ?: rows.put(key, RowHit(dir, name, isDir, bounds))
    }

    internal fun removeRow(dir: String, name: String) {
        rows.remove(devicePath(dir, name))
    }

    internal fun placeCrumb(dir: String, label: String, bounds: Rect) {
        crumbs[dir]?.let { it.bounds = bounds } ?: crumbs.put(dir, CrumbHit(label, bounds))
    }

    internal fun removeCrumb(dir: String) {
        crumbs.remove(dir)
    }

    internal fun deferClick(name: String?) {
        deferredClick = name
    }

    internal fun takeDeferredClick(): String? = deferredClick.also { deferredClick = null }

    /** The row of the open folder at [root], if any. */
    internal fun rowAt(root: Offset): String? {
        if (listBounds?.contains(root) != true) return null
        val dir = browser.path
        if (browser.listingPath != dir) return null
        return rows.values.firstOrNull { it.dir == dir && it.bounds.contains(root) }?.name
    }

    private fun hoverAt(root: Offset?, internal: Boolean): DropHover? {
        if (!browser.connected) return null
        val verb = if (internal) "Move" else "Push"
        // A drag event whose position cannot be read (see rootPosition) still pushes, into the open folder.
        if (root == null) {
            if (internal) return null
            val current = browser.path
            val target = dropTargetFor(null, false, current, emptySet(), currentBlocked(), internal = false) ?: return null
            return DropHover(target, null, null, "Push to ${current.trimEnd('/')}/")
        }
        crumbs.entries.firstOrNull { it.value.bounds.contains(root) }?.let { (dir, hit) ->
            if (internal && dir.equals(movingFrom, ignoreCase = true)) return null
            if (isKnownBlocked(dir) || dir == browser.path && currentBlocked()) return null
            return DropHover(FilesDropTarget.Folder(dir), null, dir, "$verb into ${hit.label}")
        }
        if (listBounds?.contains(root) != true) return null
        val current = browser.path
        val row = rowAt(root)?.let { name -> rows[devicePath(current, name)] }
        val blocked = if (row?.isDir == true) isKnownBlocked(devicePath(current, row.name)) else currentBlocked()
        val dragged = if (internal && movingFrom == current) moving.orEmpty().toSet() else emptySet()
        val target = dropTargetFor(row?.name, row?.isDir == true, current, dragged, blocked, internal) ?: return null
        val label = when (target) {
            is FilesDropTarget.Folder -> "$verb into ${row?.name}"
            is FilesDropTarget.Current -> "Push to ${current.trimEnd('/')}/"
        }
        return DropHover(target, row?.name.takeIf { target is FilesDropTarget.Folder }, null, label)
    }

    private fun currentBlocked(): Boolean =
        isKnownBlocked(browser.path) || browser.listingPath == browser.path && browser.listing == Listing.Blocked

    // Computer drags

    /** A computer drag moved to [root] (null: unknown). */
    internal fun externalAt(root: Offset?): DropHover? = hoverAt(root, internal = false).also { hover = it }

    /** The computer drag left the tab or ended. */
    internal fun externalEnded() {
        if (moving == null) hover = null
    }

    // Moves inside the tab

    internal fun startMove(names: List<String>, label: String, root: Offset) {
        moving = names
        movingFrom = browser.path
        movingLabel = label
        moveTo(root)
    }

    internal fun moveTo(root: Offset) {
        if (moving == null) return
        pointer = root
        hover = hoverAt(root, internal = true)
    }

    /** Ends the move: into the hovered folder, if any. */
    internal fun dropMove() {
        val names = moving ?: return
        val from = movingFrom ?: return
        val target = hover?.target
        cancelMove()
        if (target is FilesDropTarget.Folder) browser.move(names, target.dir, from)
    }

    /** Drops the move without moving anything (Esc, or the gesture went away). */
    internal fun cancelMove() {
        moving = null
        movingFrom = null
        hover = null
    }

    /** After a spring-open: the rows under the pointer are the opened folder's, found on the next move. */
    internal fun sprung() {
        hover = null
    }
}

/** "1 file", "1 item" (a folder), "3 items". */
internal fun dragLabel(count: Int, singleIsDir: Boolean): String = when {
    count == 1 && !singleIsDir -> "1 file"
    count == 1 -> "1 item"
    else -> "$count items"
}

/**
 * The tab's drop target for computer drags: files only; a drop pushes (a copy, see [copyDropAction]) into what the
 * pointer is over. Kept for the tab's life, so the drag survives a spring-open.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class FilesPushDropTarget(
    private val browser: FilesBrowser,
    private val state: FilesDropState,
    private val read: () -> (DragAndDropEvent) -> DragData?,
) : DragAndDropTarget {
    fun accepts(event: DragAndDropEvent): Boolean = hasFiles(event)

    override fun onEntered(event: DragAndDropEvent) {
        state.externalAt(event.rootPosition())
    }

    override fun onMoved(event: DragAndDropEvent) {
        state.externalAt(event.rootPosition())
    }

    override fun onExited(event: DragAndDropEvent) = state.externalEnded()

    override fun onEnded(event: DragAndDropEvent) = state.externalEnded()

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val target = state.externalAt(event.rootPosition())?.target
        state.externalEnded()
        target ?: return false
        copyDropAction(event.sourceActions(), hasFiles(event), browser.connected) ?: return false
        event.acceptAsCopy()
        val paths = dropPaths(event, read()) ?: return false
        return browser.push(paths, target.dir)
    }

    private fun hasFiles(event: DragAndDropEvent) = runCatching { read()(event) is DragData.FilesList }.getOrDefault(false)
}

/**
 * Row drags inside the tab: a press on a row that moves past the touch slop starts a move of the selection (or of
 * that row alone when it is not selected); release drops it on the hovered folder. On the tab itself, not the row, so
 * the drag outlives the rows of a folder it springs out of. [toRoot] maps the tab's positions to root coordinates.
 */
internal fun Modifier.filesMoveGesture(browser: FilesBrowser, state: FilesDropState, toRoot: (Offset) -> Offset): Modifier =
    pointerInput(browser, state) {
        awaitEachGesture {
            // The row has already taken the press (Main pass, children first); only a primary press counts.
            val down = awaitFirstDown(requireUnconsumed = false)
            val deferred = state.takeDeferredClick()
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val pressed = state.rowAt(toRoot(down.position))?.takeIf { it != browser.renaming }
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (dragging) state.dropMove() else deferred?.let(browser::click)
                        dragging = false
                        break
                    }
                    if (!dragging && pressed != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        val selected = browser.selection.names
                        val names = if (pressed in selected) browser.visible.map { it.name }.filter { it in selected } else listOf(pressed)
                        val single = browser.visible.firstOrNull { it.name == names.singleOrNull() }
                        state.startMove(names, dragLabel(names.size, single?.isDir == true), toRoot(change.position))
                        dragging = true
                    }
                    if (dragging) {
                        // Esc cancelled it: the rest of the gesture is nobody's.
                        if (state.moving == null) {
                            dragging = false
                            break
                        }
                        change.consume()
                        state.moveTo(toRoot(change.position))
                    }
                }
            } finally {
                if (dragging) state.cancelMove()
            }
        }
    }

/** "Push into Camera" / "Push to /sdcard/DCIM/Camera/" / "Move into Camera": rises 8 dp and fades in. */
@Composable
internal fun DropChip(label: String?, motion: Boolean, modifier: Modifier = Modifier) {
    // The last label, kept while the chip fades out. A plain holder.
    val shown = remember { arrayOfNulls<String>(1) }
    if (label != null) shown[0] = label
    val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        label != null,
        modifier,
        enter = if (motion) fadeIn(tween(OPEN_MILLIS)) + slideInVertically(tween(OPEN_MILLIS, easing = EmphasizedDecelerate)) { rise } else EnterTransition.None,
        exit = if (motion) fadeOut(tween(150)) + slideOutVertically(tween(150)) { rise } else ExitTransition.None,
    ) {
        val colors = MaterialTheme.colorScheme
        Surface(shape = styledCorner(16.dp), color = colors.inverseSurface, tonalElevation = 3.dp, shadowElevation = 3.dp) {
            Text(
                shown[0].orEmpty(), Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp, color = colors.inverseOnSurface, maxLines = 1,
            )
        }
    }
}

/** The pill that follows the pointer during a move: download icon and "1 file" / "3 items". */
@Composable
internal fun DragGhost(label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = styledCorner(14.dp), color = colors.primaryContainer, tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
            Text(label, fontSize = 12.sp, color = colors.onPrimaryContainer, maxLines = 1)
        }
    }
}
