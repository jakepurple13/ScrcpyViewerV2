package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.Os
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

private const val CACHE_DIR_NAME = "ScrcpyViewer"
private const val CACHE_OVERRIDE_PROPERTY = "scrcpyviewer.cacheDir"

/** `rwx------`: the cache root is this user's alone. */
private val PRIVATE_DIR = PosixFilePermissions.fromString("rwx------")

/**
 * The per-user folder that holds files pulled from a device for the computer to use: per serial
 * ([serialPathSegment]), `drag/` (rows dragged out of the Files tab), `open/` (Open on computer) and `install/` (device
 * APKs being installed). Never the shared temp folder: on Linux `/tmp` is shared, and another user could read the
 * pulled files or swap a pulled APK before `adb install`.
 *
 * macOS `~/Library/Caches/ScrcpyViewer`; Windows `%LOCALAPPDATA%\ScrcpyViewer\Cache`; elsewhere
 * `$XDG_CACHE_HOME/ScrcpyViewer` (an absolute one), else `~/.cache/ScrcpyViewer`. The `scrcpyviewer.cacheDir` system
 * property overrides it. Not created here: see [ensurePrivateDir].
 */
fun transferCacheRoot(
    isMac: Boolean = Os.isMac,
    isWindows: Boolean = Os.isWindows,
    env: (String) -> String? = System::getenv,
    home: Path = Paths.get(System.getProperty("user.home")),
    override: String? = System.getProperty(CACHE_OVERRIDE_PROPERTY),
): Path {
    override?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    fun envPath(name: String) = env(name)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
    return when {
        isMac -> home.resolve("Library").resolve("Caches").resolve(CACHE_DIR_NAME)
        isWindows -> (envPath("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")).resolve(CACHE_DIR_NAME).resolve("Cache")
        else -> (envPath("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve(CACHE_DIR_NAME)
    }
}

/** [path] is [dir] or inside it, as given or (when both exist) through their real paths. */
fun isInsideHostDir(path: Path, dir: Path): Boolean {
    fun norm(p: Path) = p.toAbsolutePath().normalize()
    if (norm(path).startsWith(norm(dir))) return true
    return runCatching { path.toRealPath().startsWith(dir.toRealPath()) }.getOrDefault(false)
}

/** Whether this file system has POSIX owners and permissions (not Windows). */
internal fun hasPosixPermissions(): Boolean = "posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()

/** [path] (not followed if a link) belongs to the user running the app. */
internal fun ownedByCurrentUser(path: Path): Boolean = runCatching {
    val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
    val me = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    owner == me
}.getOrDefault(false)

/**
 * Makes [root] a folder only this user can use and returns it: created `rwx------` when missing (its parents too), and
 * an existing one narrowed to that. Throws [IOException], using nothing, when [root] is a symbolic link, is not a
 * folder, or (on POSIX) belongs to another user: someone else made it, so what is pulled there could be read or
 * swapped. Plain file I/O: call it off the UI thread.
 */
fun ensurePrivateDir(
    root: Path,
    posix: Boolean = hasPosixPermissions(),
    isOwner: (Path) -> Boolean = ::ownedByCurrentUser,
): Path {
    if (Files.isSymbolicLink(root)) throw IOException("The cache folder $root is a link; not using it")
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        root.parent?.let { Files.createDirectories(it) }
        try {
            if (posix) Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE_DIR)) else Files.createDirectory(root)
        } catch (e: FileAlreadyExistsException) {
            // Made meanwhile (another window, or someone else): checked below like any existing one.
        }
        if (Files.isSymbolicLink(root)) throw IOException("The cache folder $root is a link; not using it")
    }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("The cache folder $root is not a folder")
    if (posix) {
        if (!isOwner(root)) throw IOException("The cache folder $root belongs to another user; not using it")
        if (Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS) != PRIVATE_DIR) {
            Files.setPosixFilePermissions(root, PRIVATE_DIR)
        }
    }
    return root
}
