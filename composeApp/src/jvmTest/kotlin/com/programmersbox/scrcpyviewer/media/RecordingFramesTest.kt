package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Reading a recording's first frame, length and film-strip / preview frames with the bundled FFmpeg. */
@OptIn(ExperimentalPathApi::class)
class RecordingFramesTest {
    private val tmp: Path = Files.createTempDirectory("recording-frames-test")
    private val dir: Path = Files.createDirectories(tmp.resolve("rec ü #' dir"))

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    private fun png(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(w, h); erase(0xFF336699.toInt()) }
        return Image.makeFromBitmap(bitmap).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
    }

    @Test
    fun `the length comes from FFmpeg's input summary`() {
        val summary = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'a.mp4':
              Duration: 00:01:18.03, start: 0.000000, bitrate: 2003 kb/s
        """.trimIndent()
        assertEquals(78_030, parseDurationMs(summary))
        assertEquals(3_723_500, parseDurationMs("Duration: 01:02:03.5,"))
        assertEquals(9_000, parseDurationMs("Duration: 00:00:09,"))
        assertEquals(null, parseDurationMs("Duration: N/A, bitrate: N/A"))
        assertEquals(null, parseDurationMs(""))
    }

    @Test
    fun `PNGs one after another are split at each end, a cut-off last one is left out`() {
        val a = png(3, 5)
        val b = png(7, 2)
        assertEquals(listOf(a.toList(), b.toList()), splitPngs(a + b).map { it.toList() })
        assertEquals(listOf(a.toList()), splitPngs(a + b.copyOf(b.size - 3)).map { it.toList() })
        assertTrue(splitPngs(ByteArray(0)).isEmpty())
        assertTrue(splitPngs("not a png".toByteArray()).isEmpty())
        val decoded = decodePng(b)
        assertEquals(7, decoded.width)
        assertEquals(2, decoded.height)
        decoded.asSkiaBitmap().close()
    }

    @Test
    fun `the argv keeps odd paths whole and builds the rate from whole milliseconds`() {
        val ffmpeg = dir.resolve("ff mpeg")
        val input = dir.resolve("clip 'q' #1 ü;&.mp4")
        val first = firstFrameArgv(ffmpeg, input)
        assertEquals(listOf(ffmpeg.toString(), "-hide_banner", "-nostdin", "-nostats", "-i", input.toString(), "-map", "0:v:0",
            "-frames:v", "1", "-f", "image2pipe", "-c:v", "png", "pipe:1"), first)
        val frames = framesArgv(ffmpeg, input, 3_000, 12_050, 12, 120)
        assertEquals(input.toString(), frames[frames.indexOf("-i") + 1])
        assertEquals("3.000", frames[frames.indexOf("-ss") + 1])
        assertEquals("9.050", frames[frames.indexOf("-t") + 1])
        assertEquals("fps=12000/9050,scale=120:-2", frames[frames.indexOf("-vf") + 1])
        assertEquals("12", frames[frames.indexOf("-frames:v") + 1])
        assertEquals("pipe:1", frames.last())
    }

    /** A finished process with [stdout] and [stderr], or one that runs until destroyed ([hang]). */
    private class FakeProcess(stdout: ByteArray = ByteArray(0), stderr: String = "", private val exit: Int = 0, private val hang: Boolean = false) : Process() {
        private val gone = CountDownLatch(if (hang) 1 else 0)
        var destroyed = false
        private val out = ByteArrayInputStream(stdout)
        private val err = ByteArrayInputStream(stderr.toByteArray())
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = if (hang) object : InputStream() {
            override fun read(): Int {
                gone.await()
                return -1
            }
        } else out
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int {
            gone.await()
            return exitValue()
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = gone.await(timeout, unit)
        override fun exitValue(): Int = if (destroyed) 137 else exit
        override fun destroy() {
            destroyed = true
            gone.countDown()
        }
        override fun destroyForcibly(): Process = also { destroy() }
        override fun isAlive(): Boolean = gone.count > 0
    }

    private fun recording(): Path = dir.resolve("Pixel 8-20261115.mp4").also { Files.write(it, byteArrayOf(1)) }

    @Test
    fun `probe gives the first frame and its size, and the length`() = runBlocking {
        val runs = mutableListOf<List<String>>()
        val frames = RecordingFrames(
            ffmpegPath = { dir.resolve("ffmpeg") },
            starter = { argv -> runs += argv; FakeProcess(png(54, 117), "  Duration: 00:00:18.03, start: 0.0\n") },
        )
        val rec = recording()
        val p = frames.probe(rec)
        assertEquals(RecordingInfo(18_030, 54, 117), p.info)
        assertEquals(54, p.firstFrame.width)
        assertEquals(rec.toAbsolutePath().toString(), runs.single()[runs.single().indexOf("-i") + 1])
        p.firstFrame.asSkiaBitmap().close()
    }

    @Test
    fun `probe says why it can not - gone, no video, FFmpeg missing or failing`() = runBlocking {
        val ok = GifProcessStarter { FakeProcess(ByteArray(0), "Duration: 00:00:01.00,\n") }
        val gone = assertFailsWith<RecordingGoneException> { RecordingFrames({ dir }, ok).probe(dir.resolve("gone.mp4")) }
        assertTrue(gone.message!!.startsWith("The recording is no longer at"))
        val noVideo = assertFailsWith<GifExportException> { RecordingFrames({ dir }, ok).probe(recording()) }
        assertEquals("No video in the recording", noVideo.message)
        val missing = assertFailsWith<GifExportException> { RecordingFrames({ error("no jar") }, ok).probe(recording()) }
        assertEquals("FFmpeg isn't available: no jar", missing.message)
        val failing = assertFailsWith<GifExportException> {
            RecordingFrames({ dir }, { FakeProcess(stderr = "[mov @ 0x1234] moov atom not found\n", exit = 1) }).probe(recording())
        }
        assertEquals("moov atom not found", failing.message)
    }

    @Test
    fun `frames splits FFmpeg's output, and cancelling kills it`() = runBlocking {
        val two = png(12, 26) + png(12, 26)
        val frames = RecordingFrames({ dir }, { FakeProcess(two) })
        assertEquals(2, frames.frames(recording(), 0, 1_000, 2, 12).size)
        assertTrue(frames.frames(recording(), 0, 1_000, 0, 12).isEmpty())
        val hanging = FakeProcess(hang = true)
        val slow = RecordingFrames({ dir }, { hanging })
        val job = async { slow.frames(recording(), 0, 1_000, 12, 120) }
        kotlinx.coroutines.delay(200) // it waits for FFmpeg
        assertTrue(job.isActive)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(hanging.destroyed)
        assertFalse(hanging.isAlive)
    }

    @Test
    fun `the real FFmpeg reads a tiny clip's first frame, length and frames`() = runBlocking {
        val ffmpeg = runCatching { bundledFfmpeg() }.getOrElse {
            skipOrFailOnCi("the bundled ffmpeg isn't available here ($it)")
            return@runBlocking
        }
        val clip = dir.resolve("tiny clip 'q' #1 ü.mp4")
        val gen = runCatching {
            ProcessBuilder(
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x640:rate=30", "-t", "2",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", clip.toString(),
            ).redirectErrorStream(true).start().let { p ->
                val out = p.inputStream.readAllBytes().decodeToString()
                check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { out }
            }
        }
        if (gen.isFailure) {
            skipOrFailOnCi("the bundled ffmpeg can't run on this OS (${gen.exceptionOrNull()})")
            return@runBlocking
        }
        val frames = RecordingFrames({ ffmpeg })
        val p = frames.probe(clip)
        assertEquals(320, p.info.width)
        assertEquals(640, p.info.height)
        assertEquals(2_000.0, p.info.durationMs.toDouble(), 100.0)
        p.firstFrame.asSkiaBitmap().close()
        val strip = frames.frames(clip, 0, p.info.durationMs, 12, 60)
        assertEquals(12, strip.size)
        val one = decodePng(strip.first())
        assertEquals(60, one.width)
        assertEquals(120, one.height)
        one.asSkiaBitmap().close()
    }

    private fun skipOrFailOnCi(reason: String) {
        if (System.getenv("CI") != null) fail("RecordingFramesTest real run: $reason")
        println("SKIPPED RecordingFramesTest real run: $reason")
        assumeTrue(reason, false)
    }
}
