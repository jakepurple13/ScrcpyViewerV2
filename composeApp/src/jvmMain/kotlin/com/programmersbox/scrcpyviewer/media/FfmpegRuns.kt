package com.programmersbox.scrcpyviewer.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The FFmpeg runs going on ([GifExporter], [RecordingFrames]): their processes and the temp files they write (the
 * partial GIF and the palette, never a finished GIF). A normal quit waits for them to clean up ([awaitIdle]); what is
 * still here when the app quits anyway, or the JVM is stopped (SIGTERM), is cleaned up by [forceCleanup]: the
 * processes killed (their descendants first), then the files deleted. Thread safe.
 */
class FfmpegRuns {
    private val processes = LinkedHashSet<Process>()
    private val files = LinkedHashSet<Path>()
    private val _active = MutableStateFlow(0)
    private val hookInstalled = AtomicBoolean(false)

    /** How many processes and temp files are registered; 0 when nothing is left to clean up. */
    val active: StateFlow<Int> = _active.asStateFlow()

    fun add(process: Process) { update { processes.add(process) } }

    fun remove(process: Process) { update { processes.remove(process) } }

    /** A temp file a run writes; registered only while it is temp (unregister before it becomes the user's file). */
    fun addFile(path: Path) { update { files.add(path) } }

    fun removeFile(path: Path) { update { files.remove(path) } }

    /** Suspends until every run has finished and unregistered its process and files. */
    suspend fun awaitIdle() {
        active.first { it == 0 }
    }

    /**
     * Kills every registered process (descendants first: once the parent dies they are re-parented and no longer its
     * descendants), waits briefly for each to go (so a file it holds can be deleted, also on Windows), then deletes
     * the registered temp files. Only registered files: a finished GIF never is.
     */
    fun forceCleanup() {
        val (procs, paths) = synchronized(this) {
            val snapshot = processes.toList() to files.toList()
            processes.clear()
            files.clear()
            _active.value = 0
            snapshot
        }
        for (p in procs) {
            runCatching { p.descendants().toList() }.getOrDefault(emptyList()).forEach { runCatching { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
        for (p in procs) runCatching { p.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        for (f in paths) runCatching { Files.deleteIfExists(f) }
    }

    /** Runs [forceCleanup] when the JVM shuts down (a normal exit or SIGTERM). Once per instance. */
    fun installShutdownHook() {
        if (hookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::forceCleanup, "ffmpeg-cleanup"))
        }
    }

    private inline fun update(change: () -> Boolean) {
        synchronized(this) {
            change()
            _active.value = processes.size + files.size
        }
    }

    companion object {
        private const val KILL_WAIT_MS = 1_000L

        /** The app's runs, cleaned up by the JVM shutdown hook installed with it. */
        val shared: FfmpegRuns by lazy { FfmpegRuns().also { it.installShutdownHook() } }
    }
}
