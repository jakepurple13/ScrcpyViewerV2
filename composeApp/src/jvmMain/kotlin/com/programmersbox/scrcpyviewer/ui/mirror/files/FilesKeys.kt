package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.FileKind
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.kindOf

/** What a key does in the Files tab (the spec's Keyboard table). */
enum class FilesKey { Up, Down, ExtendUp, ExtendDown, Enter, Open, Parent, Rename, Delete, SelectAll, NewFolder, Refresh, Escape }

/**
 * The Files tab's action for [event], or null when it is not one of the tab's keys. ⌘ on macOS ([isMac]) and Ctrl
 * elsewhere is the command modifier; the other platform's modifier never stands in for it.
 */
fun filesKeyAction(event: KeyEvent, isMac: Boolean): FilesKey? {
    if (event.type != KeyEventType.KeyDown) return null
    val cmd = if (isMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed
    val other = if (isMac) event.isCtrlPressed else event.isMetaPressed
    if (other) return null
    val shift = event.isShiftPressed
    return when (event.key) {
        Key.DirectionUp -> when {
            cmd -> FilesKey.Parent
            shift -> FilesKey.ExtendUp
            else -> FilesKey.Up
        }
        Key.DirectionDown -> when {
            cmd -> FilesKey.Open
            shift -> FilesKey.ExtendDown
            else -> FilesKey.Down
        }
        Key.Enter, Key.NumPadEnter -> FilesKey.Enter.takeIf { !cmd && !shift }
        Key.F2 -> FilesKey.Rename.takeIf { !cmd }
        Key.Backspace, Key.Delete -> FilesKey.Delete.takeIf { !cmd && !shift }
        Key.A -> FilesKey.SelectAll.takeIf { cmd && !shift }
        Key.N -> FilesKey.NewFolder.takeIf { cmd && shift }
        Key.R -> FilesKey.Refresh.takeIf { cmd && !shift }
        Key.Escape -> FilesKey.Escape
        else -> null
    }
}

/** How the menus show a shortcut on this platform: "⇧⌘N" on macOS, "Ctrl+Shift+N" elsewhere. */
internal fun shortcutLabel(key: String, isMac: Boolean, cmd: Boolean = true, shift: Boolean = false): String = when {
    isMac -> (if (shift) "⇧" else "") + (if (cmd) "⌘" else "") + key
    else -> (if (cmd) "Ctrl+" else "") + (if (shift) "Shift+" else "") + key
}

/**
 * Runs [key] (anything but [FilesKey.Escape], which goes through the tab's Esc order) on [browser]. True when the key
 * was the browser's to use.
 */
internal fun FilesBrowser.onKey(key: FilesKey): Boolean {
    if (!connected) return false
    val single = selection.names.singleOrNull()
    when (key) {
        FilesKey.Up -> moveSelection(-1, extend = false)
        FilesKey.Down -> moveSelection(1, extend = false)
        FilesKey.ExtendUp -> moveSelection(-1, extend = true)
        FilesKey.ExtendDown -> moveSelection(1, extend = true)
        // Enter opens a folder and renames a file, as in Finder; ⌘↓ opens either.
        FilesKey.Enter -> {
            val name = single ?: return false
            val entry = visible.firstOrNull { it.name == name } ?: return false
            if (entry.isDir) openEntry(name) else startRename(name)
        }
        FilesKey.Open -> openEntry(single ?: return false)
        FilesKey.Parent -> up()
        FilesKey.Rename -> startRename(single ?: return false)
        FilesKey.Delete -> askDelete()
        FilesKey.SelectAll -> selectAll()
        FilesKey.NewFolder -> newFolder()
        FilesKey.Refresh -> refresh()
        FilesKey.Escape -> return false
    }
    return true
}

/** Whether the open folder's listing is a plain list of entries (not blocked, failed or still loading). */
internal val FilesBrowser.listed: Boolean get() = listingPath == path && listing is Listing.Entries

/** True for an `.apk` file. */
internal fun isApk(entry: DeviceEntry): Boolean = kindOf(entry) == FileKind.Apk
