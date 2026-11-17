package com.programmersbox.scrcpyviewer.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A JVM crash log (`hs_err_pid<pid>.log`) left by an earlier run of this app. Only [signal] and [frames] are sent;
 * [file] is kept so the log can be deleted.
 */
data class NativeCrash(val file: Path, val signal: String, val frames: List<String>)

/**
 * Finds the crash logs the JVM wrote when an earlier run of this app died in native code. The JVM writes them to its
 * working directory, else to `java.io.tmpdir` (the app cannot set `-XX:ErrorFile`, see the telemetry spec). A log
 * counts as ours when it mentions `com.programmersbox.scrcpyviewer` and was not written by [currentPid].
 */
class NativeCrashScanner(private val dirs: List<Path>, private val currentPid: Long) {
    fun scan(): List<NativeCrash> = dirs.map { it.toAbsolutePath().normalize() }.distinct().flatMap(::scanDir)

    /** Deletes [c]'s log. Never throws. */
    fun delete(c: NativeCrash) {
        runCatching { Files.deleteIfExists(c.file) }.onFailure { System.err.println("Telemetry: delete crash log failed: $it") }
    }

    /** Reports each crash found (sent only when crash reports are on), then deletes its log either way. */
    fun reportAndDelete(telemetry: Telemetry) {
        for (c in scan()) {
            runCatching { telemetry.captureNativeCrash(c) }
            delete(c)
        }
    }

    private fun scanDir(dir: Path): List<NativeCrash> = runCatching {
        if (!Files.isDirectory(dir)) return@runCatching emptyList()
        Files.newDirectoryStream(dir, "hs_err_pid*.log").use { files ->
            files.mapNotNull { file ->
                val pid = PID.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()
                if (pid == null || pid == currentPid) return@mapNotNull null
                runCatching { parse(file) }.getOrNull()
            }
        }
    }.getOrElse {
        System.err.println("Telemetry: scan for crash logs failed: $it")
        emptyList()
    }

    private fun parse(file: Path): NativeCrash? {
        // Decoding replaces malformed bytes, so a log in another charset still parses.
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        if (OWN_PACKAGE !in text) return null
        val lines = text.lines()
        val signal = lines.firstNotNullOfOrNull { SIGNAL.find(it)?.groupValues?.get(1) } ?: UNKNOWN_SIGNAL
        val frames = (section(lines, "Native frames:") ?: section(lines, "Java frames:").orEmpty())
            .mapNotNull(::functionName)
            .take(MAX_FRAMES)
        return NativeCrash(file, signal, frames)
    }

    /** The frame lines under [header], up to the next blank line; null when the log has no such section. */
    private fun section(lines: List<String>, header: String): List<String>? {
        val start = lines.indexOfFirst { it.startsWith(header) }
        if (start < 0) return null
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }
    }

    companion object {
        const val MAX_FRAMES = 20
        private const val OWN_PACKAGE = "com.programmersbox.scrcpyviewer"
        private const val UNKNOWN_SIGNAL = "UNKNOWN"
        private val PID = Regex("""hs_err_pid(\d+)\.log""")

        /** `#  SIGSEGV (0xb) at pc=…`, or `#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=…` on Windows. */
        private val SIGNAL = Regex("""^#\s+([A-Z][A-Z0-9_]*) \(0x[0-9a-fA-F]+\) at pc=""")
        private val FRAME = Regex("""^([CVvjJA])\s+(.*)$""")
        private val NATIVE = Regex("""^\[(.+?)]\s*(.*)$""")
        private val HEX_OFFSET = Regex("""\+0x[0-9a-fA-F]+$""")
        private val BCI = Regex("""\+\d+$""")

        /** The scanner the app runs at launch: the JVM's default crash log locations. */
        fun forThisProcess(): NativeCrashScanner = NativeCrashScanner(
            dirs = listOfNotNull(Paths.get("").toAbsolutePath(), System.getProperty("java.io.tmpdir")?.let { Paths.get(it) }),
            currentPid = ProcessHandle.current().pid(),
        )

        /**
         * The function of one frame line, never a path: `C  [lib.so+0x1]  sym+0x2` gives `sym`, or `lib.so` (the
         * file name only) when there is no symbol; Java frames give `pkg.Class.method(sig)`.
         */
        internal fun functionName(line: String): String? {
            val (kind, rest) = FRAME.matchEntire(line.trimEnd())?.destructured ?: return null
            val name = if (kind == "C" || kind == "V") {
                val (library, symbol) = NATIVE.matchEntire(rest)?.destructured ?: return null
                symbol.replace(HEX_OFFSET, "").trim().ifEmpty {
                    library.replace(HEX_OFFSET, "").substringAfterLast('/').substringAfterLast('\\')
                }
            } else {
                val tokens = rest.trim().split(Regex("""\s+"""))
                (tokens.firstOrNull { '(' in it } ?: tokens.first()).replace(BCI, "")
            }
            return name.ifEmpty { null }
        }
    }
}
