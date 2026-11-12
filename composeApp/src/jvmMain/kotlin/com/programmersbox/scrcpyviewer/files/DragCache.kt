package com.programmersbox.scrcpyviewer.files

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path

/**
 * Where rows dragged out of the Files tab are pulled before the OS drag hands them to the computer:
 * `<root>/<serial>/drag/<random>/` (the serial as one path segment, see [serialPathSegment]). Each drag gets its own
 * folder, so the pulled names never clash. The window deletes its serial's drag folders when it closes
 * ([deleteSession]); the app removes leftovers of a crash when it starts ([sweep]). Plain file I/O: call it off the
 * UI thread where it can matter.
 */
class DragCache(private val root: Path = Path(System.getProperty("java.io.tmpdir"), "scrcpy-vwr")) {
    /** A new, empty folder for one drag of [serial]'s files. */
    fun newDragDir(serial: String): Path =
        Files.createDirectories(dragRoot(serial).resolve(UUID.randomUUID().toString().take(12)))

    /** Removes [dir] (one drag's folder) and everything in it; nothing outside this cache. */
    fun delete(dir: Path) {
        if (!dir.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) return
        deleteRecursively(dir)
    }

    /** Removes every drag folder of [serial]: the window closed. */
    fun deleteSession(serial: String) = deleteRecursively(dragRoot(serial))

    /** Removes every serial's drag folders: leftovers of a run that crashed or was killed. */
    fun sweep() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.list(root).use { serials -> serials.toList() }.forEach { deleteRecursively(it.resolve("drag")) }
        }
    }

    private fun dragRoot(serial: String): Path = root.resolve(serialPathSegment(serial)).resolve("drag")

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).toList() }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
