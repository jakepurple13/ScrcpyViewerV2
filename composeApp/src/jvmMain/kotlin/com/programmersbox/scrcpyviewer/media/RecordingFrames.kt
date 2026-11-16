package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** A recording's length and its picture's size (after FFmpeg's auto-rotation, as the GIF will have it). */
data class RecordingInfo(val durationMs: Long, val width: Int, val height: Int)

/** A finished recording, probed: its [info] and its first frame as a full-size [firstFrame] (the caller owns it). */
class RecordingProbe(val info: RecordingInfo, val firstFrame: ImageBitmap)

/** The length from FFmpeg's input summary, `Duration: 00:00:18.03,`, in milliseconds; null when it says `N/A` or none. */
fun parseDurationMs(stderr: String): Long? {
    val m = DURATION.find(stderr) ?: return null
    val (h, min, s) = m.destructured
    val fraction = m.groupValues[4]
    val ms = if (fraction.isEmpty()) 0L else (fraction + "00").take(3).toLong()
    return ((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms
}

private val DURATION = Regex("""Duration: (\d+):(\d{2}):(\d{2})(?:\.(\d+))?""")

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * The PNG files one after another in [bytes] (FFmpeg's `image2pipe` output), each a copy. Walks the chunks to each
 * `IEND`; a truncated last image (the run was stopped) is left out.
 */
fun splitPngs(bytes: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var start = 0
    while (start + PNG_SIGNATURE.size <= bytes.size) {
        for (i in PNG_SIGNATURE.indices) if (bytes[start + i] != PNG_SIGNATURE[i]) return out
        var at = start + PNG_SIGNATURE.size
        var end = -1
        while (at + 8 <= bytes.size) {
            val length = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            if (length < 0) return out
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            val next = at + 12L + length
            if (next > bytes.size) return out
            at = next.toInt()
            if (type == "IEND") {
                end = at
                break
            }
        }
        if (end < 0) return out
        out += bytes.copyOfRange(start, end)
        start = end
    }
    return out
}

/** A PNG as a bitmap the caller owns (close it with `asSkiaBitmap().close()`). Plain CPU work: call it off the UI thread. */
fun decodePng(png: ByteArray): ImageBitmap {
    val image = Image.makeFromEncoded(png)
    try {
        return Bitmap.makeFromImage(image).asComposeImageBitmap()
    } finally {
        image.close()
    }
}

/** The argv that writes the first video frame of [input] as one PNG to stdout, with the input summary on stderr. */
fun firstFrameArgv(ffmpeg: Path, input: Path): List<String> = listOf(
    ffmpeg.toString(), "-hide_banner", "-nostdin", "-nostats",
    "-i", input.toString(),
    "-map", "0:v:0", "-frames:v", "1",
    "-f", "image2pipe", "-c:v", "png", "pipe:1",
)

/**
 * The argv that writes [count] frames spread evenly over [startMs]..[endMs] of [input], [width] px wide, as PNGs one
 * after another to stdout. The rate is `count / duration` as whole milliseconds (never locale formatted).
 */
fun framesArgv(ffmpeg: Path, input: Path, startMs: Long, endMs: Long, count: Int, width: Int): List<String> {
    val durationMs = (endMs - startMs).coerceAtLeast(1)
    return listOf(
        ffmpeg.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-nostats",
        "-ss", seconds(startMs), "-t", seconds(durationMs), "-i", input.toString(),
        "-map", "0:v:0", "-an",
        "-vf", "fps=${count * 1000L}/$durationMs,scale=$width:-2",
        "-frames:v", count.toString(),
        "-f", "image2pipe", "-c:v", "png", "pipe:1",
    )
}

/**
 * Pictures from a recording with the bundled FFmpeg, run as a separate process like [GifExporter]: its first frame and
 * length when it is saved ([probe]), and a few small frames for the Export GIF window's film strip and preview
 * ([frames]). Never blocks the caller's thread; cancelling the caller kills FFmpeg.
 */
class RecordingFrames(
    private val ffmpegPath: () -> Path = ::bundledFfmpeg,
    private val starter: GifProcessStarter = SystemStarter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where its FFmpeg processes are registered, for the cleanup on quit. */
    private val runs: FfmpegRuns = FfmpegRuns.shared,
) {
    /**
     * [input]'s length, its picture's size and its first frame. Throws [GifExportException] with a reason when the
     * file is gone, has no video, or FFmpeg can't run or read it.
     */
    suspend fun probe(input: Path): RecordingProbe = withContext(io) {
        if (!Files.isRegularFile(input)) throw RecordingGoneException(input)
        val (stdout, stderr) = run(firstFrameArgv(ffmpeg(), input.toAbsolutePath()))
        val png = splitPngs(stdout).firstOrNull() ?: throw GifExportException("No video in the recording")
        val duration = parseDurationMs(stderr) ?: throw GifExportException("FFmpeg couldn't tell the recording's length")
        val bitmap = decodePng(png)
        RecordingProbe(RecordingInfo(duration, bitmap.width, bitmap.height), bitmap)
    }

    /**
     * Up to [count] PNG frames spread over [startMs]..[endMs] of [input], [width] px wide (empty for [count] 0).
     * Throws [GifExportException] when FFmpeg can't run or read it.
     */
    suspend fun frames(input: Path, startMs: Long, endMs: Long, count: Int, width: Int): List<ByteArray> {
        if (count <= 0 || endMs <= startMs) return emptyList()
        return withContext(io) {
            if (!Files.isRegularFile(input)) throw RecordingGoneException(input)
            splitPngs(run(framesArgv(ffmpeg(), input.toAbsolutePath(), startMs, endMs, count, width)).first)
        }
    }

    private fun ffmpeg(): Path = try {
        ffmpegPath()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw GifExportException(ffmpegUnavailableReason(e), e)
    }

    /** One run: all of stdout, and stderr as text. A failed run throws with its last stderr line. */
    private suspend fun run(argv: List<String>): Pair<ByteArray, String> = coroutineScope {
        val process = try {
            starter.start(argv)
        } catch (e: IOException) {
            throw GifExportException("FFmpeg couldn't start: ${e.message ?: e.javaClass.simpleName}", e)
        }
        runs.add(process)
        try {
            runCatching { process.outputStream.close() }
            val stdout = async(io) { readQuietly { process.inputStream.readAllBytes() } }
            val stderr = async(io) { readQuietly { process.errorStream.readAllBytes() } }
            val code = runInterruptible(io) { process.waitFor() }
            val err = stderr.await().decodeToString()
            if (code != 0) throw GifExportException(lastLine(err) ?: "FFmpeg stopped with exit code $code")
            stdout.await() to err
        } finally {
            if (process.isAlive) killProcessTree(process)
            runs.remove(process)
        }
    }

    private inline fun readQuietly(read: () -> ByteArray): ByteArray = try {
        read()
    } catch (_: IOException) {
        ByteArray(0) // killed: its pipe closed
    }

    private fun lastLine(stderr: String): String? =
        stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.replace(CONTEXT_PREFIX, "")?.trim()?.ifEmpty { null }

    private companion object {
        val CONTEXT_PREFIX = Regex("""^\[[^\]]* @ (0x)?[0-9a-fA-F]+]\s*""")
    }
}
