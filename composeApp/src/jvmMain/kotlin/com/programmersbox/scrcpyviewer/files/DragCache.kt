package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Leftover drag folders younger than this may belong to another running instance: the start-up sweep keeps them. */
const val DRAG_SWEEP_MIN_AGE_MILLIS = 60 * 60 * 1000L

private const val DRAG = "drag"

/** The per-serial cache folders the start-up sweep clears: drags, opened files, device APKs being installed. */
private val SWEPT = listOf(DRAG, "open", "install")

/**
 * Where rows dragged out of the Files tab are pulled before the OS drag hands them to the computer:
 * `<root>/<serial>/drag/<random>/` (the serial as one path segment, see [serialPathSegment]). Each drag gets its own
 * folder, so the pulled names never clash. The window deletes its serial's drag folders when it closes
 * ([deleteSession]); the app removes leftovers of a crash when it starts ([sweep]).
 *
 * The root is the per-user [transferCacheRoot], shared with the transfer queue's `open/` and `install/` folders, which
 * [sweep] also clears of old leftovers. Nothing here deletes through a symbolic link: a root that is a link (or, on
 * POSIX, another user's) is left alone, and an entry is only deleted when its real path is exactly
 * `<real root>/<serial>/<drag|open|install>[/<name>]`. Plain file I/O: call it off the UI thread.
 */
class DragCache(
    private val root: Path = transferCacheRoot(),
    private val isOwner: (Path) -> Boolean = { !hasPosixPermissions() || ownedByCurrentUser(it) },
) {
    /** The folder for a new drag of [serial]'s files. Not created here: the pull creates it, off the UI thread. */
    fun newDragDir(serial: String): Path = dragRoot(serial).resolve(UUID.randomUUID().toString().take(12))

    /** Removes [dir], one drag's folder (`<root>/<serial>/drag/<random>`), and everything in it; nothing else. */
    fun delete(dir: Path) {
        val parent = dir.parent ?: return
        if (parent.fileName?.toString() != DRAG) return
        val seg = parent.parent ?: return
        if (seg.parent?.toAbsolutePath()?.normalize() != root.toAbsolutePath().normalize()) return
        deleteChecked(dir, seg.fileName.toString(), DRAG, dir.fileName.toString())
    }

    /** Removes every drag folder of [serial]: the window closed. */
    fun deleteSession(serial: String) = deleteChecked(dragRoot(serial), serialPathSegment(serial), DRAG, null)

    /**
     * Removes, for every serial, the drag folders and the files opened or installed from the device (`open/`,
     * `install/`) whose last change is older than [minAgeMillis]: leftovers of a run that crashed, was killed, or (for
     * opened files) ended. Younger ones may be another instance's transfer in progress, or a file still open in an app.
     * Nothing when the root is a link or another user's.
     */
    fun sweep(minAgeMillis: Long = DRAG_SWEEP_MIN_AGE_MILLIS, now: Long = System.currentTimeMillis()) {
        if (!usableRoot()) return
        runCatching {
            Files.list(root).use { it.toList() }.filter(::realDir).forEach { seg ->
                for (kind in SWEPT) {
                    val folder = seg.resolve(kind)
                    if (!realDir(folder)) continue
                    Files.list(folder).use { it.toList() }.forEach { entry ->
                        val modified = runCatching { Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis() }
                            .getOrDefault(now)
                        if (now - modified >= minAgeMillis) {
                            deleteChecked(entry, seg.fileName.toString(), kind, entry.fileName.toString())
                        }
                    }
                }
            }
        }
    }

    private fun dragRoot(serial: String): Path = root.resolve(serialPathSegment(serial)).resolve(DRAG)

    private fun realDir(path: Path) = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    private fun usableRoot() = realDir(root) && isOwner(root)

    /**
     * Deletes `<root>/<seg>/<kind>` ([name] null) or `<root>/<seg>/<kind>/<name>` recursively, only when the root is a
     * real folder of this user and the target's real path is exactly that path under the root's real path (no link
     * anywhere on the way). A link inside the target is removed itself; what it points to is not touched.
     */
    private fun deleteChecked(target: Path, seg: String, kind: String, name: String?) {
        if (!usableRoot()) return
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            val realRoot = root.toRealPath()
            val expected = realRoot.resolve(seg).resolve(kind).let { if (name != null) it.resolve(name) else it }
            if (target.toRealPath() != expected || Files.isSymbolicLink(target)) return
            Files.walk(target).use { s -> s.sorted(Comparator.reverseOrder()).toList() }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
