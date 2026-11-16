package com.programmersbox.scrcpyviewer.tools

import com.programmersbox.scrcpyviewer.Os
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Locates the bundled scrcpy-server and adb.
 *
 * Packaged apps read from [resourcesDir] (`compose.application.resources.dir`). Dev runs fall back to
 * `composeApp/bundled/<common|host folder>` via the `scrcpyviewer.bundledDir` system property, then to
 * `bundled` / `composeApp/bundled` found from [workingDir] (IDE launches set no property).
 */
class BundledTools(
    resourcesDir: Path? = System.getProperty("compose.application.resources.dir")?.let { Paths.get(it) },
    private val dataDir: Path,
    workingDir: Path = Paths.get(System.getProperty("user.dir")),
) {
    val serverVersion: String = "5.0"

    private val searchDirs: List<Path> = buildList {
        resourcesDir?.let { add(it) }
        System.getProperty("scrcpyviewer.bundledDir")?.let { Paths.get(it) }?.let {
            add(it.resolve("common"))
            add(it.resolve(hostDirName()))
        }
        // IDE launches set neither property; fall back to composeApp/bundled near the working directory.
        for (bundled in devBundledDirs(workingDir)) {
            add(bundled.resolve("common"))
            add(bundled.resolve(hostDirName()))
        }
    }

    val serverJar: Path
        get() = find("scrcpy-server")

    /** [serverJar], or null when it is missing. */
    val serverJarOrNull: Path?
        get() = findOrNull("scrcpy-server")

    /** The bundled adb this instance resolved last and its copy; reading and hashing adb on every call is slow. */
    private class ResolvedAdb(val source: Path, val copy: Path)

    @Volatile
    private var resolvedAdb: ResolvedAdb? = null
    private val adbLock = Any()

    /**
     * Copies adb (and the Windows DLLs) into `dataDir/tools/adb-<sha256 prefix>/` once and returns the copy. The
     * result is remembered, so later calls (one per adb command) only look the source up again; a different source
     * path, or a deleted copy, resolves it anew. Callers are serialized so only one copies at a time.
     */
    fun adbExecutable(): Path {
        val source = find(if (isWindows()) "adb.exe" else "adb")
        resolvedAdb?.let { if (it.source == source && Files.isRegularFile(it.copy)) return it.copy }
        synchronized(adbLock) {
            resolvedAdb?.let { if (it.source == source && Files.isRegularFile(it.copy)) return it.copy }
            val copy = copyAdb(source)
            resolvedAdb = ResolvedAdb(source, copy)
            return copy
        }
    }

    private fun copyAdb(source: Path): Path {
        val windows = isWindows()
        val prefix = sha256(source).take(12)
        val targetDir = dataDir.resolve("tools").resolve("adb-$prefix")
        Files.createDirectories(targetDir)
        val names = if (windows) listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll") else listOf("adb")
        for (name in names) {
            val src = if (name == source.fileName.toString()) source else findOrNull(name) ?: continue
            copyIfMissing(src, targetDir.resolve(name), executable = !windows)
        }
        val adb = targetDir.resolve(source.fileName.toString())
        if (!windows) adb.toFile().setExecutable(true)
        return adb
    }

    /**
     * Copies through a temp file in the same folder that is then moved into place, so neither another thread nor a
     * second app instance can run a half-written adb.
     */
    private fun copyIfMissing(src: Path, dest: Path, executable: Boolean) {
        if (Files.exists(dest) && Files.size(dest) == Files.size(src)) return
        val tmp = Files.createTempFile(dest.parent, dest.fileName.toString(), ".tmp")
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
            if (executable) tmp.toFile().setExecutable(true)
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun findOrNull(name: String): Path? =
        searchDirs.map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) }

    private fun find(name: String): Path =
        findOrNull(name) ?: error(
            "$name is missing. Run ./gradlew :composeApp:downloadBundledTools, then restart. Searched: ${searchDirs.joinToString().ifEmpty { "(no directories)" }}",
        )

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private companion object {
        /** `bundled/` and `composeApp/bundled/` under [start] and up to three parents. */
        fun devBundledDirs(start: Path): List<Path> {
            val dirs = mutableListOf<Path>()
            dirs.add(start.resolve("bundled"))
            var dir: Path? = start.toAbsolutePath()
            repeat(4) {
                dir?.let { dirs.add(it.resolve("composeApp").resolve("bundled")) }
                dir = dir?.parent
            }
            return dirs.distinct()
        }

        fun isWindows() = Os.isWindows

        fun hostDirName(): String {
            val arch = System.getProperty("os.arch").lowercase()
            val arm = arch == "aarch64" || arch == "arm64"
            return when {
                Os.isMac -> if (arm) "macos-arm64" else "macos-x64"
                Os.isWindows -> "windows-x64"
                else -> "linux-x64"
            }
        }
    }
}
