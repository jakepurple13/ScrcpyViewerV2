package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path

/** Leftover drag folders younger than this may belong to another running instance: the start-up sweep keeps them. */
const val DRAG_SWEEP_MIN_AGE_MILLIS = 60 * 60 * 1000L

/**
 * Where rows dragged out of the Files tab are pulled before the OS drag hands them to the computer:
 * `<root>/<serial>/drag/<random>/` (the serial as one path segment, see [serialPathSegment]). Each drag gets its own
 * folder, so the pulled names never clash. The window deletes its serial's drag folders when it closes
 * ([deleteSession]); the app removes leftovers of a crash when it starts ([sweep]).
 *
 * The temp folder can be shared with other users (`/tmp` on Linux), so nothing here deletes through a symbolic link:
 * a root that is a link is left alone, and a folder is only deleted when its real path is exactly
 * `<real root>/<serial>/drag[/<random>]`. Plain file I/O: call it off the UI thread.
 */
class DragCache(private val root: Path = Path(System.getProperty("java.io.tmpdir"), "scrcpy-vwr")) {
    /** The folder for a new drag of [serial]'s files. Not created here: the pull creates it, off the UI thread. */
    fun newDragDir(serial: String): Path = dragRoot(serial).resolve(UUID.randomUUID().toString().take(12))

    /** Removes [dir], one drag's folder (`<root>/<serial>/drag/<random>`), and everything in it; nothing else. */
    fun delete(dir: Path) {
        val parent = dir.parent ?: return
        if (parent.fileName?.toString() != "drag") return
        val seg = parent.parent ?: return
        if (seg.parent?.toAbsolutePath()?.normalize() != root.toAbsolutePath().normalize()) return
        deleteChecked(dir, seg.fileName.toString(), dir.fileName.toString())
    }

    /** Removes every drag folder of [serial]: the window closed. */
    fun deleteSession(serial: String) = deleteChecked(dragRoot(serial), serialPathSegment(serial), null)

    /**
     * Removes the drag folders of every serial whose last change is older than [minAgeMillis]: leftovers of a run that
     * crashed or was killed. Younger ones may be another instance's drag in progress.
     */
    fun sweep(minAgeMillis: Long = DRAG_SWEEP_MIN_AGE_MILLIS, now: Long = System.currentTimeMillis()) {
        if (!realDir(root)) return
        runCatching {
            Files.list(root).use { it.toList() }.filter(::realDir).forEach { seg ->
                val drag = seg.resolve("drag")
                if (!realDir(drag)) return@forEach
                Files.list(drag).use { it.toList() }.forEach { dir ->
                    val modified = runCatching { Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toMillis() }
                        .getOrDefault(now)
                    if (now - modified >= minAgeMillis) deleteChecked(dir, seg.fileName.toString(), dir.fileName.toString())
                }
            }
        }
    }

    private fun dragRoot(serial: String): Path = root.resolve(serialPathSegment(serial)).resolve("drag")

    private fun realDir(path: Path) = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    /**
     * Deletes `<root>/<seg>/drag` ([name] null) or `<root>/<seg>/drag/<name>` recursively, only when the root is a real
     * folder and the target's real path is exactly that path under the root's real path (no link anywhere on the way).
     * A link inside the target is removed itself; what it points to is not touched.
     */
    private fun deleteChecked(target: Path, seg: String, name: String?) {
        if (!realDir(root)) return
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            val realRoot = root.toRealPath()
            val expected = realRoot.resolve(seg).resolve("drag").let { if (name != null) it.resolve(name) else it }
            if (target.toRealPath() != expected || Files.isSymbolicLink(target)) return
            Files.walk(target).use { s -> s.sorted(Comparator.reverseOrder()).toList() }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
