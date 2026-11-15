package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.files.ensurePrivateDir
import com.programmersbox.scrcpyviewer.files.transferCacheRoot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.Loader
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/** Longest trimmed part that can be exported, in milliseconds. */
const val GIF_MAX_DURATION_MS = 60_000L

/** Shortest trimmed part (the trim handles stay this far apart), in milliseconds. */
const val GIF_MIN_DURATION_MS = 500L

/** The widths offered, in pixels; [GIF_DEFAULT_WIDTH] is picked first. */
val GIF_WIDTHS = listOf(320, 480, 640)
const val GIF_DEFAULT_WIDTH = 480

/** The frame rates offered; [GIF_DEFAULT_FPS] is picked first. */
val GIF_FPS = listOf(10, 15)
const val GIF_DEFAULT_FPS = 15

/**
 * Bytes per output pixel per frame for [estimateBytes]. Fitted on six real phone screen recordings exported at 480 px,
 * 15 fps with these exact passes: they measured 0.037 (a mostly still screen) to 0.23 (scrolling), geometric mean
 * 0.073. The estimate is shown as "About …".
 */
private const val GIF_BYTES_PER_PIXEL_FRAME = 0.08

/** What to export: the part of the recording from [startMs] to [endMs], [width] px wide, at [fps] frames a second. */
data class GifOptions(val startMs: Long, val endMs: Long, val width: Int = GIF_DEFAULT_WIDTH, val fps: Int = GIF_DEFAULT_FPS) {
    val durationMs: Long get() = endMs - startMs

    /** Within the 60 s limit (and not empty). */
    val withinLimit: Boolean get() = durationMs in 1..GIF_MAX_DURATION_MS
}

/**
 * The GIF's height for [width] when the recording is [srcW] × [srcH]: the aspect ratio kept, rounded to the nearest
 * even number like FFmpeg's `scale=W:-2` (1080 × 2340 at 320 px gives 694).
 */
fun gifHeight(width: Int, srcW: Int, srcH: Int): Int {
    if (srcW <= 0 || srcH <= 0) return width
    val halves = (width.toLong() * srcH + srcW) / (2L * srcW)
    return maxOf(2, (halves * 2).toInt())
}

/** The Linux system libraries the bundled FFmpeg's libavdevice links; a desktop has them, a minimal install may not. */
internal const val LINUX_FFMPEG_LIBRARIES = "libasound2, libpulse0, libxcb1, libxcb-shm0 and libdrm2"

/** What the user is told when they are missing on Linux. */
internal const val LINUX_FFMPEG_MESSAGE = "FFmpeg needs a few system libraries: install $LINUX_FFMPEG_LIBRARIES, then try again"

/**
 * Why the bundled FFmpeg can't be used, for the user. On Linux a native library that fails to load (anywhere in
 * [e]'s causes) is nearly always a missing system library, so the message names the ones to install.
 */
internal fun ffmpegUnavailableReason(e: Throwable, linux: Boolean = com.programmersbox.scrcpyviewer.Os.isLinux): String {
    val nativeLoad = generateSequence(e) { it.cause }.any {
        it is UnsatisfiedLinkError || it is NoClassDefFoundError || it.message?.contains("error while loading shared libraries") == true
    }
    return if (linux && nativeLoad) {
        LINUX_FFMPEG_MESSAGE
    } else {
        "FFmpeg isn't available: ${e.message ?: e.javaClass.simpleName}"
    }
}

/** The rough size of the GIF [o] makes from a [srcW] × [srcH] recording, in bytes. */
fun estimateBytes(o: GifOptions, srcW: Int, srcH: Int): Long {
    val seconds = o.durationMs.coerceAtLeast(0) / 1000.0
    val pixels = o.width.toDouble() * gifHeight(o.width, srcW, srcH)
    return (seconds * o.fps * pixels * GIF_BYTES_PER_PIXEL_FRAME).roundToLong()
}

/**
 * Where the GIF of [recording] goes: next to it, `<recording name>.gif`, else `<name>-2.gif`, `-3`, … Never a file
 * that exists. Does file I/O.
 */
fun gifFile(recording: Path): Path {
    val dir = recording.toAbsolutePath().parent
    val name = recording.fileName.toString()
    val base = name.substringBeforeLast('.', name).ifEmpty { name }
    var file = dir.resolve("$base.gif")
    var n = 2
    while (Files.exists(file)) file = dir.resolve("$base-${n++}.gif")
    return file
}

/** [ms] as FFmpeg seconds, `12.345`; never locale formatted. */
internal fun seconds(ms: Long): String {
    val v = ms.coerceAtLeast(0)
    return "${v / 1000}.${(v % 1000).toString().padStart(3, '0')}"
}

private fun scaleFilter(o: GifOptions) = "fps=${o.fps},scale=${o.width}:-2:flags=lanczos"

/**
 * The two FFmpeg runs as argv lists (never shell strings; the paths are single arguments and never go into a filter
 * string). First makes the palette into [palette], then encodes [output] with it. Both report progress on stdout
 * (`-progress pipe:1`) and print only errors on stderr. [output] may have any extension: the format is forced to GIF.
 */
fun gifArgv(ffmpeg: Path, input: Path, palette: Path, output: Path, o: GifOptions): Pair<List<String>, List<String>> {
    val common = listOf(ffmpeg.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-nostats", "-y")
    val trimmedInput = listOf("-ss", seconds(o.startMs), "-t", seconds(o.durationMs), "-i", input.toString())
    val paletteArgv = common + trimmedInput + listOf(
        "-vf", "${scaleFilter(o)},palettegen=stats_mode=diff",
        // One image, written as is: without -update the image2 muxer reads % in the name as a sequence pattern.
        "-update", "1", "-frames:v", "1",
        "-progress", "pipe:1",
        palette.toString(),
    )
    val encodeArgv = common + trimmedInput + listOf(
        "-i", palette.toString(),
        "-lavfi", "${scaleFilter(o)}[x];[x][1:v]paletteuse=dither=sierra2_4a",
        "-progress", "pipe:1",
        "-f", "gif",
        output.toString(),
    )
    return paletteArgv to encodeArgv
}

/**
 * The fraction done from one `-progress` line: `out_time_us=` (or `out_time_ms=`, which FFmpeg also gives in
 * microseconds) against [durationUs], clamped to 0..1; `progress=end` is 1. Null for any other line or `N/A`.
 */
fun parseProgress(line: String, durationUs: Long): Float? {
    val trimmed = line.trim()
    if (trimmed == "progress=end") return 1f
    val key = trimmed.substringBefore('=', "")
    if (key != "out_time_us" && key != "out_time_ms") return null
    val us = trimmed.substringAfter('=').toLongOrNull() ?: return null
    if (durationUs <= 0) return null
    return (us.toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat()
}

/** Why a GIF couldn't be made; [message] is the reason shown after "Couldn't make the GIF: ". */
open class GifExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The recording was moved or deleted. */
class RecordingGoneException(val file: Path) : GifExportException("The recording is no longer at $file")

/** Starts one FFmpeg run; tests pass a fake. */
fun interface GifProcessStarter {
    fun start(argv: List<String>): Process
}

/**
 * The real FFmpeg process. On Linux the executable's RUNPATH ($ORIGIN) covers only its own NEEDED libraries, not
 * theirs (libavutil needs libva.so.2, bundled next to it), so its folder goes on LD_LIBRARY_PATH.
 */
internal fun ffmpegProcess(argv: List<String>, linux: Boolean = com.programmersbox.scrcpyviewer.Os.isLinux): ProcessBuilder =
    ProcessBuilder(argv).apply {
        val dir = argv.firstOrNull()?.let { Paths.get(it).parent }?.toString()
        if (linux && dir != null) {
            val env = environment()
            env["LD_LIBRARY_PATH"] = listOfNotNull(dir, env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }).joinToString(":")
        }
    }

internal val SystemStarter = GifProcessStarter { argv -> ffmpegProcess(argv).start() }

/**
 * The `ffmpeg` executable inside the `org.bytedeco:ffmpeg` native jar, extracted by JavaCPP (its cache) on first use.
 * Throws when this platform's jar is missing or the file can't be extracted.
 */
fun bundledFfmpeg(): Path {
    val path = Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)
        ?: throw GifExportException("FFmpeg isn't available on this computer")
    val file = Paths.get(path)
    if (!Files.isExecutable(file)) file.toFile().setExecutable(true)
    return file
}

/**
 * The GIF temp folder in the app's cache (palettes), inside the cache root made private to this user
 * ([ensurePrivateDir]). Does file I/O: call it off the UI thread.
 */
fun gifCacheDir(): Path = ensurePrivateDir(transferCacheRoot()).resolve("gif")

/**
 * Turns a recording into a GIF with the bundled FFmpeg in two passes (palette, then encode), as separate processes.
 * The output is written to a temp name next to the recording and moved to [gifFile] only when FFmpeg succeeds, so a
 * cancel or failure never leaves a `.gif`.
 */
class GifExporter(
    private val ffmpegPath: () -> Path = ::bundledFfmpeg,
    private val cacheDir: () -> Path = ::gifCacheDir,
    private val starter: GifProcessStarter = SystemStarter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Phase { Palette, Encode }

    /**
     * Exports [o] of [input]. Suspends until done; never blocks the caller's thread. [onProgress] is called from a
     * background thread with the phase and its fraction done (0..1). Cancelling the calling coroutine stops FFmpeg
     * (and anything it started), deletes the partial GIF and the palette, then rethrows the cancellation.
     *
     * Failure is a [GifExportException] whose message is the reason, e.g. the last FFmpeg error line.
     */
    suspend fun export(input: Path, o: GifOptions, onProgress: (Phase, Float) -> Unit = { _, _ -> }): Result<Path> {
        if (o.durationMs > GIF_MAX_DURATION_MS) return failure("Trim to 60 s or less to export")
        if (o.durationMs <= 0) return failure("The trimmed part is empty")
        if (o.width <= 0 || o.fps <= 0) return failure("Pick a width and a frame rate")
        return withContext(io) {
            if (!Files.isRegularFile(input)) return@withContext Result.failure(RecordingGoneException(input))
            val ffmpeg = try {
                ffmpegPath()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext failure(ffmpegUnavailableReason(e), e)
            }
            if (!Files.isRegularFile(ffmpeg)) return@withContext failure("FFmpeg isn't available at $ffmpeg")

            var palette: Path? = null
            var partial: Path? = null
            var done = false
            try {
                val dir = input.toAbsolutePath().parent
                palette = Files.createTempFile(Files.createDirectories(cacheDir()), "palette-", ".png")
                // The partial GIF lives next to the recording, so the final move is an atomic rename in one folder.
                partial = try {
                    Files.createTempFile(dir, ".gif-export-", ".part")
                } catch (e: AccessDeniedException) {
                    throw GifExportException("Can't write next to the recording ($dir)", e)
                }
                val (paletteArgv, encodeArgv) = gifArgv(ffmpeg, input.toAbsolutePath(), palette, partial, o)
                val durationUs = o.durationMs * 1000
                onProgress(Phase.Palette, 0f)
                run(paletteArgv) { line -> parseProgress(line, durationUs)?.let { onProgress(Phase.Palette, it) } }
                onProgress(Phase.Palette, 1f)
                onProgress(Phase.Encode, 0f)
                run(encodeArgv) { line -> parseProgress(line, durationUs)?.let { onProgress(Phase.Encode, it) } }
                if (Files.size(partial) == 0L) throw GifExportException("FFmpeg wrote an empty file")
                val out = moveToFree(partial, input)
                done = true
                onProgress(Phase.Encode, 1f)
                Result.success(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GifExportException) {
                Result.failure(e)
            } catch (e: IOException) {
                failure(e.message ?: e.javaClass.simpleName, e)
            } finally {
                withContext(NonCancellable) {
                    // A delete that fails (a file still locked on Windows) must not hide the result or the cancel.
                    palette?.let { runCatching { Files.deleteIfExists(it) } }
                    if (!done) partial?.let { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }

    private fun failure(reason: String, cause: Throwable? = null): Result<Path> =
        Result.failure(GifExportException(reason, cause))

    /** Moves [partial] to the first free [gifFile] name; a name taken meanwhile is skipped, never replaced. */
    private fun moveToFree(partial: Path, input: Path): Path {
        repeat(100) {
            val target = gifFile(input)
            try {
                return Files.move(partial, target)
            } catch (_: FileAlreadyExistsException) {
            }
        }
        throw GifExportException("No free file name next to the recording")
    }

    /**
     * One FFmpeg run: stdout lines go to [onLine], stderr is kept as a short tail for the failure reason. Cancellation
     * kills the process tree and waits for it to exit (so the files can be deleted, also on Windows).
     */
    private suspend fun run(argv: List<String>, onLine: (String) -> Unit) = coroutineScope {
        val process = try {
            starter.start(argv)
        } catch (e: IOException) {
            throw GifExportException("FFmpeg couldn't start: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val tail = OutputTail(STDERR_TAIL_LINES)
        try {
            runCatching { process.outputStream.close() }
            val stderr = launch(io) { process.errorStream.forEachLineQuietly(tail::add) }
            val stdout = launch(io) { process.inputStream.forEachLineQuietly(onLine) }
            val code = runInterruptible(io) { process.waitFor() }
            // The pipes close when the process exits; read them to the end so the tail holds the last error.
            stderr.join()
            stdout.join()
            if (code != 0) throw GifExportException(tail.reason() ?: "FFmpeg stopped with exit code $code")
        } finally {
            if (process.isAlive) kill(process)
        }
    }

    private class OutputTail(private val max: Int) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            if (line.isBlank()) return
            lines.addLast(line.take(MAX_LINE_CHARS))
            while (lines.size > max) lines.removeFirst()
        }

        /** The last error line without FFmpeg's `[context @ 0x…]` prefix. */
        @Synchronized
        fun reason(): String? = lines.lastOrNull()?.replace(CONTEXT_PREFIX, "")?.trim()?.ifEmpty { null }
    }

    private companion object {
        const val STDERR_TAIL_LINES = 20
        const val MAX_LINE_CHARS = 500
        val CONTEXT_PREFIX = Regex("""^\[[^\]]* @ (0x)?[0-9a-fA-F]+]\s*""")

        fun InputStream.forEachLineQuietly(onLine: (String) -> Unit) {
            try {
                bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
            } catch (_: IOException) {
                // The process was killed and its pipe closed.
            }
        }

        fun kill(process: Process) = killProcessTree(process)
    }
}

/** Destroys [process]'s descendants, then the process, and waits briefly for it to go. */
internal fun killProcessTree(process: Process) {
    val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    children.forEach { it.destroyForcibly() }
    process.destroyForcibly()
    runCatching { process.waitFor(5, TimeUnit.SECONDS) }
}
