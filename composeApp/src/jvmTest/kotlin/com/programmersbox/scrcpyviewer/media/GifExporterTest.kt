package com.programmersbox.scrcpyviewer.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalPathApi::class)
class GifExporterTest {
    private val tmp: Path = Files.createTempDirectory("gif-exporter-test")
    private val cache: Path = tmp.resolve("cache")
    private val recordings: Path = Files.createDirectories(tmp.resolve("rec ü #' dir"))

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    private fun recording(name: String = "Pixel 8-20261115-101500.mp4"): Path =
        recordings.resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }

    // ---- process ----

    @Test
    fun onLinuxFfmpegsFolderGoesOnTheLibraryPathAheadOfAnyExistingOne() {
        val dir = Paths.get("cache", "linux-x86_64").toAbsolutePath()
        val argv = listOf(dir.resolve("ffmpeg").toString(), "-version")
        val env = ffmpegProcess(argv, linux = true).environment()
        val path = env.getValue("LD_LIBRARY_PATH")
        assertTrue(path.startsWith(dir.toString()), path)
        assertEquals(System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }?.let { "$dir:$it" } ?: dir.toString(), path)
        assertEquals(System.getenv("LD_LIBRARY_PATH"), ffmpegProcess(argv, linux = false).environment()["LD_LIBRARY_PATH"])
    }

    @Test
    fun aSharedLibraryErrorFromFfmpegOnLinuxNamesTheLibrariesToInstall() {
        val e = IllegalStateException("ffmpeg: error while loading shared libraries: libva.so.2: cannot open shared object file")
        assertEquals(LINUX_FFMPEG_MESSAGE, ffmpegUnavailableReason(e, linux = true))
        assertTrue(ffmpegUnavailableReason(e, linux = false).startsWith("FFmpeg isn't available: "))
    }

    // ---- argv ----

    @Test
    fun argvForBothPasses() {
        val ffmpeg = Paths.get("ff", "ffmpeg")
        val input = Paths.get("in.mp4")
        val palette = Paths.get("pal.png")
        val output = Paths.get("out.part")
        val (p1, p2) = gifArgv(ffmpeg, input, palette, output, GifOptions(3_000, 12_000, 480, 15))
        val common = listOf(ffmpeg.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-nostats", "-y")
        assertEquals(
            common + listOf(
                "-ss", "3.000", "-t", "9.000", "-i", input.toString(),
                "-vf", "fps=15,scale=480:-2:flags=lanczos,palettegen=stats_mode=diff",
                "-update", "1", "-frames:v", "1",
                "-progress", "pipe:1",
                palette.toString(),
            ),
            p1,
        )
        assertEquals(
            common + listOf(
                "-ss", "3.000", "-t", "9.000", "-i", input.toString(),
                "-i", palette.toString(),
                "-lavfi", "fps=15,scale=480:-2:flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a",
                "-progress", "pipe:1",
                "-f", "gif",
                output.toString(),
            ),
            p2,
        )
    }

    @Test
    fun argvTrimAndRateFormatting() {
        val (p1, p2) = gifArgv(Paths.get("f"), Paths.get("i"), Paths.get("p"), Paths.get("o"), GifOptions(1_005, 1_505, 320, 10))
        for (argv in listOf(p1, p2)) {
            assertEquals("1.005", argv[argv.indexOf("-ss") + 1])
            assertEquals("0.500", argv[argv.indexOf("-t") + 1])
        }
        assertEquals("fps=10,scale=320:-2:flags=lanczos,palettegen=stats_mode=diff", p1[p1.indexOf("-vf") + 1])
        assertEquals("fps=10,scale=320:-2:flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a", p2[p2.indexOf("-lavfi") + 1])
    }

    @Test
    fun argvKeepsOddPathsAsSingleExactArguments() {
        val odd = recordings.resolve("my clip 'single' #1 ü 録画 % ;&.mp4")
        val palette = cache.resolve("pal ette #% ü.png")
        val output = recordings.resolve(".gif-export- x #.part")
        val ffmpeg = tmp.resolve("Program Files").resolve("ff mpeg")
        val (p1, p2) = gifArgv(ffmpeg, odd, palette, output, GifOptions(0, 2_000, 640, 15))
        assertEquals(ffmpeg.toString(), p1.first())
        assertEquals(ffmpeg.toString(), p2.first())
        assertEquals(odd.toString(), p1[p1.indexOf("-i") + 1])
        assertEquals(odd.toString(), p2[p2.indexOf("-i") + 1])
        assertEquals(palette.toString(), p1.last())
        assertEquals(palette.toString(), p2[p2.lastIndexOf("-i") + 1])
        assertEquals(output.toString(), p2.last())
        // Paths never leak into filter strings.
        assertFalse(p1[p1.indexOf("-vf") + 1].contains(odd.toString()))
    }

    // ---- naming ----

    @Test
    fun gifFileIsNextToTheRecordingAndNeverOverwrites() {
        val rec = recording("Pixel 8 #1 ü.mp4")
        val first = gifFile(rec)
        assertEquals(recordings.resolve("Pixel 8 #1 ü.gif").toAbsolutePath(), first)
        Files.write(first, byteArrayOf(0))
        assertEquals(recordings.resolve("Pixel 8 #1 ü-2.gif").toAbsolutePath(), gifFile(rec))
        Files.write(recordings.resolve("Pixel 8 #1 ü-2.gif"), byteArrayOf(0))
        assertEquals(recordings.resolve("Pixel 8 #1 ü-3.gif").toAbsolutePath(), gifFile(rec))
    }

    @Test
    fun gifFileForANameWithoutExtensionOrWithDots() {
        assertEquals("clip.gif", gifFile(recording("clip")).name)
        assertEquals("a.b.c.gif", gifFile(recording("a.b.c.mp4")).name)
    }

    // ---- estimate ----

    @Test
    fun estimateKeepsAspectAndScalesWithDurationRateAndWidth() {
        assertEquals(1040, gifHeight(480, 1080, 2340))
        // Checked against the bundled ffmpeg's scale=W:-2: nearest even height.
        assertEquals(694, gifHeight(320, 1080, 2340))
        assertEquals(1386, gifHeight(640, 1080, 2340))
        assertEquals(1066, gifHeight(480, 1080, 2400))
        assertEquals(712, gifHeight(320, 1080, 2400))
        assertEquals(360, gifHeight(640, 1920, 1080))
        val base = GifOptions(0, 9_000, 480, 15)
        val est = estimateBytes(base, 1080, 2340)
        // 9 s, 15 fps, 480 px screen clip: a few MB.
        assertTrue(est in 1_000_000L..8_000_000L, "estimate $est")
        assertEquals(est * 2, estimateBytes(base.copy(endMs = 18_000), 1080, 2340), "twice as long")
        assertTrue(estimateBytes(base.copy(fps = 10), 1080, 2340) < est)
        assertTrue(estimateBytes(base.copy(width = 640), 1080, 2340) > est)
    }

    // ---- progress ----

    @Test
    fun parsesProgressLines() {
        assertEquals(0.5f, parseProgress("out_time_us=1000000", 2_000_000))
        assertEquals(0.25f, parseProgress("out_time_ms=500000", 2_000_000))
        assertEquals(1f, parseProgress("out_time_us=9000000", 2_000_000), "clamped")
        assertEquals(0f, parseProgress("out_time_us=-5", 2_000_000), "clamped")
        assertEquals(1f, parseProgress("progress=end", 2_000_000))
        assertEquals(null, parseProgress("out_time_us=N/A", 2_000_000))
        assertEquals(null, parseProgress("out_time=00:00:01.000000", 2_000_000))
        assertEquals(null, parseProgress("progress=continue", 2_000_000))
        assertEquals(null, parseProgress("frame=12", 2_000_000))
        assertEquals(null, parseProgress("out_time_us=100", 0))
    }

    // ---- export with a fake ffmpeg ----

    private class FakeProcess(
        stdoutLines: List<String> = emptyList(),
        stderr: String = "",
        private val exit: Int = 0,
        private val hang: Boolean = false,
    ) : Process() {
        private val exited = CountDownLatch(if (hang) 1 else 0)
        private val stdoutPipe = PipedOutputStream()
        private val stdout = PipedInputStream(stdoutPipe, 64 * 1024)
        private val err = ByteArrayInputStream(stderr.toByteArray())
        @Volatile var destroyed = false

        init {
            stdoutPipe.write(stdoutLines.joinToString("") { "$it\n" }.toByteArray())
            if (!hang) stdoutPipe.close()
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int {
            exited.await()
            return exitValue()
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException()
            return if (destroyed) 137 else exit
        }
        override fun isAlive(): Boolean = exited.count > 0
        override fun destroy() {
            destroyForcibly()
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            runCatching { stdoutPipe.close() }
            exited.countDown()
            return this
        }
    }

    private class FakeStarter(private val make: (Int, List<String>) -> FakeProcess) : GifProcessStarter {
        val runs = CopyOnWriteArrayList<List<String>>()
        val processes = CopyOnWriteArrayList<FakeProcess>()
        override fun start(argv: List<String>): Process {
            runs += argv
            // Like FFmpeg, create the output (palette, then the partial GIF) as soon as it runs.
            Files.write(Paths.get(argv.last()), byteArrayOf(0x47, 0x49, 0x46))
            return make(runs.size, argv).also { processes += it }
        }
    }

    private fun fakeFfmpeg(): Path = tmp.resolve("ffmpeg").also { if (!Files.exists(it)) Files.write(it, byteArrayOf()) }

    private fun exporter(starter: GifProcessStarter) =
        GifExporter(ffmpegPath = ::fakeFfmpeg, cacheDir = { cache }, starter = starter)

    @Test
    fun exportRunsBothPassesAndMovesTheGifIntoPlace() = runBlocking {
        val rec = recording()
        val starter = FakeStarter { _, _ -> FakeProcess(listOf("out_time_us=1000000", "progress=end")) }
        val progress = CopyOnWriteArrayList<Pair<GifExporter.Phase, Float>>()
        val out = exporter(starter).export(rec, GifOptions(0, 2_000, 480, 15)) { p, f -> progress += p to f }.getOrThrow()
        assertEquals(recordings.resolve("Pixel 8-20261115-101500.gif").toAbsolutePath(), out)
        assertTrue(Files.isRegularFile(out))
        assertEquals(2, starter.runs.size)
        assertEquals(fakeFfmpeg().toString(), starter.runs[0].first())
        assertTrue(starter.runs[0].last().startsWith(cache.toString()), "palette in the cache dir")
        assertEquals(recordings.toAbsolutePath(), Paths.get(starter.runs[1].last()).parent, "partial next to the recording")
        assertTrue(GifExporter.Phase.Encode to 0.5f in progress)
        assertEquals(GifExporter.Phase.Encode to 1f, progress.last())
        assertEquals(listOf("Pixel 8-20261115-101500.gif", "Pixel 8-20261115-101500.mp4"), recordings.listDirectoryEntries().map { it.name }.sorted())
        assertTrue(cache.listDirectoryEntries().isEmpty(), "palette deleted")
    }

    @Test
    fun exportNeverOverwritesAnExistingGif() = runBlocking {
        val rec = recording()
        Files.write(recordings.resolve("Pixel 8-20261115-101500.gif"), byteArrayOf(9))
        val out = exporter(FakeStarter { _, _ -> FakeProcess() }).export(rec, GifOptions(0, 1_000)).getOrThrow()
        assertEquals("Pixel 8-20261115-101500-2.gif", out.name)
        assertEquals(listOf<Byte>(9), Files.readAllBytes(recordings.resolve("Pixel 8-20261115-101500.gif")).toList())
    }

    private fun cancelDuring(pass: Int) = runBlocking {
        val rec = recording()
        val running = CompletableDeferred<Unit>()
        val starter = FakeStarter { n, _ ->
            if (n == pass) running.complete(Unit)
            FakeProcess(listOf("out_time_us=500000"), hang = n == pass)
        }
        val job = async(Dispatchers.Default) { exporter(starter).export(rec, GifOptions(0, 2_000)) }
        withTimeout(10_000) { running.await() }
        withTimeout(10_000) { job.cancelAndJoin() }
        assertEquals(pass, starter.processes.size, "no later pass started")
        assertTrue(starter.processes.last().destroyed, "ffmpeg killed")
        assertFalse(starter.processes.last().isAlive)
        assertEquals(listOf("Pixel 8-20261115-101500.mp4"), recordings.listDirectoryEntries().map { it.name }, "no gif or partial left")
        assertTrue(cache.listDirectoryEntries().isEmpty(), "palette deleted")
    }

    @Test
    fun cancelDuringThePaletteKillsAndDeletes() = cancelDuring(1)

    @Test
    fun cancelDuringEncodingKillsAndDeletes() = cancelDuring(2)

    @Test
    fun failureReasonIsTheLastFfmpegErrorLine() = runBlocking {
        val rec = recording()
        val starter = FakeStarter { n, _ ->
            if (n == 1) FakeProcess()
            else FakeProcess(
                stderr = "[gif @ 0x7f8a1c004a00] some warning\n[in#0 @ 0x600003e0c000] Error opening input: Invalid data found when processing input\n\n",
                exit = 1,
            )
        }
        val result = exporter(starter).export(rec, GifOptions(0, 2_000))
        val e = assertIs<GifExportException>(result.exceptionOrNull())
        assertEquals("Error opening input: Invalid data found when processing input", e.message)
        assertEquals(listOf("Pixel 8-20261115-101500.mp4"), recordings.listDirectoryEntries().map { it.name })
        assertTrue(cache.listDirectoryEntries().isEmpty())
    }

    @Test
    fun failureWithNoStderrNamesTheExitCode() = runBlocking {
        val result = exporter(FakeStarter { _, _ -> FakeProcess(exit = 3) }).export(recording(), GifOptions(0, 2_000))
        assertEquals("FFmpeg stopped with exit code 3", result.exceptionOrNull()?.message)
    }

    @Test
    fun longerThanSixtySecondsIsRejectedBeforeStarting() = runBlocking {
        val starter = FakeStarter { _, _ -> FakeProcess() }
        val result = exporter(starter).export(recording(), GifOptions(1_000, 61_001))
        assertEquals("Trim to 60 s or less to export", result.exceptionOrNull()?.message)
        assertTrue(starter.runs.isEmpty())
        assertFalse(GifOptions(1_000, 61_001).withinLimit)
        assertTrue(GifOptions(1_000, 61_000).withinLimit)
    }

    @Test
    fun aMissingRecordingOrFfmpegIsAClearError() = runBlocking {
        val starter = FakeStarter { _, _ -> FakeProcess() }
        val gone = exporter(starter).export(recordings.resolve("gone.mp4"), GifOptions(0, 1_000))
        assertTrue(gone.exceptionOrNull()?.message.orEmpty().startsWith("The recording is no longer at"))
        val noFfmpeg = GifExporter(ffmpegPath = { error("no jar for this platform") }, cacheDir = { cache }, starter = starter)
            .export(recording(), GifOptions(0, 1_000))
        assertEquals("FFmpeg isn't available: no jar for this platform", noFfmpeg.exceptionOrNull()?.message)
        assertTrue(starter.runs.isEmpty())
    }

    @Test
    fun aRecordingFolderThatCantBeWrittenIsAClearError() = runBlocking {
        com.programmersbox.scrcpyviewer.assumePosix()
        val rec = recording()
        Files.setPosixFilePermissions(recordings, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val starter = FakeStarter { _, _ -> FakeProcess() }
            val result = exporter(starter).export(rec, GifOptions(0, 1_000))
            assertEquals("Can't write next to the recording (${rec.toAbsolutePath().parent})", result.exceptionOrNull()?.message)
            assertTrue(starter.runs.isEmpty())
            assertTrue(cache.listDirectoryEntries().isEmpty(), "palette deleted")
        } finally {
            Files.setPosixFilePermissions(recordings, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    // ---- the real bundled ffmpeg ----

    @Test
    fun realTwoPassRunWithTheBundledFfmpeg() = runBlocking {
        val ffmpeg = runCatching { bundledFfmpeg() }.getOrElse {
            skipOrFailOnCi("the bundled ffmpeg isn't available here ($it)")
            return@runBlocking
        }
        val clip = recordings.resolve("tiny clip 'q' #1 ü.mp4")
        val gen = runCatching {
            ffmpegProcess(listOf(
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x640:rate=30", "-t", "2",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", clip.toString(),
            )).redirectErrorStream(true).start().let { p ->
                val out = p.inputStream.readAllBytes().decodeToString()
                check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { out }
            }
        }
        if (gen.isFailure) {
            skipOrFailOnCi("the bundled ffmpeg can't run on this OS (${gen.exceptionOrNull()})")
            return@runBlocking
        }
        val progress = CopyOnWriteArrayList<Pair<GifExporter.Phase, Float>>()
        val out = GifExporter(ffmpegPath = { ffmpeg }, cacheDir = { cache })
            .export(clip, GifOptions(500, 1_500, 320, 10)) { p, f -> progress += p to f }
            .getOrThrow()
        assertEquals("tiny clip 'q' #1 ü.gif", out.name)
        val bytes = Files.readAllBytes(out)
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).decodeToString())
        fun le16(i: Int) = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
        assertEquals(320, le16(6))
        assertEquals(gifHeight(320, 320, 640), le16(8))
        assertTrue(GifExporter.Phase.Palette to 0f in progress)
        assertTrue(GifExporter.Phase.Encode to 0f in progress)
        assertEquals(GifExporter.Phase.Encode to 1f, progress.last())
        assertEquals(listOf("tiny clip 'q' #1 ü.gif", "tiny clip 'q' #1 ü.mp4"), recordings.listDirectoryEntries().map { it.name }.sorted())
        assertTrue(cache.listDirectoryEntries().isEmpty(), "palette deleted")
    }

    /**
     * The real run can't go ahead: on CI (`CI` set) that is a failure, as every CI OS should run the bundled binary;
     * locally the test is reported as skipped (a JUnit assumption), with the reason.
     */
    private fun skipOrFailOnCi(reason: String) {
        if (System.getenv("CI") != null) fail("realTwoPassRunWithTheBundledFfmpeg: $reason")
        println("SKIPPED realTwoPassRunWithTheBundledFfmpeg: $reason")
        assumeTrue(reason, false)
    }
}
