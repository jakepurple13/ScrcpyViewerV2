package com.programmersbox.scrcpyviewer.ui.mirror.shots.gif

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.scrcpyviewer.media.GIF_DEFAULT_FPS
import com.programmersbox.scrcpyviewer.media.GIF_DEFAULT_WIDTH
import com.programmersbox.scrcpyviewer.media.GIF_FPS
import com.programmersbox.scrcpyviewer.media.GIF_MIN_DURATION_MS
import com.programmersbox.scrcpyviewer.media.GIF_WIDTHS
import com.programmersbox.scrcpyviewer.media.GifExporter
import com.programmersbox.scrcpyviewer.media.GifOptions
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.media.decodePng
import com.programmersbox.scrcpyviewer.media.estimateBytes
import com.programmersbox.scrcpyviewer.media.gifHeight
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import com.programmersbox.scrcpyviewer.ui.mirror.WakeStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The size estimate pulses in the accent colour when it changes. */
const val GIF_PULSE_MILLIS = 450

/** The selected width or frame rate's pill glides to the pick. */
const val GIF_PILL_MILLIS = 350

/** The progress row and the done row unfold and fold. */
const val GIF_ROW_MILLIS = 400

/** The done row's check pops (0 to 1.12 to 1), then its tick draws. */
const val GIF_CHECK_POP_MILLIS = 340
const val GIF_CHECK_DRAW_MILLIS = 360
const val GIF_CHECK_DRAW_DELAY_MILLIS = 200

/** The Export button's label crossfades (Export, Cancel, Export again). */
const val GIF_LABEL_MILLIS = 200

/** Under reduced motion every change is a fade of this long. */
const val GIF_FADE_MILLIS = 150

/** The film strip's frames, spread evenly over the whole recording, and their width in pixels. */
const val GIF_STRIP_FRAMES = 12
const val GIF_STRIP_FRAME_WIDTH = 120

/**
 * The preview loops the trimmed part at the chosen frame rate, from frames FFmpeg decodes this wide (pixels), kept as
 * PNG bytes (a few MB) and decoded one at a time off the UI thread. At most this many frames: a longer part is
 * sampled evenly and each frame held longer, so the loop still runs in real time.
 */
const val GIF_PREVIEW_FRAME_WIDTH = 240
const val GIF_PREVIEW_MAX_FRAMES = 300

/** A trim, width or rate change waits this long (on the frame clock) before the preview's frames are decoded again. */
const val GIF_PREVIEW_SETTLE_MILLIS = 300

/** "Export cancelled. The partial GIF was deleted." */
const val GIF_CANCELLED_MESSAGE = "Export cancelled. The partial GIF was deleted."

/** The film strip's height and its trim handles' row. */
private val STRIP_HEIGHT = 46.dp
private val HANDLE_WIDTH = 12.dp
private val HANDLE_HEIGHT = 18.dp

/** Bytes as the window shows them: "820 KB", "4.3 MB" (1024-based, one decimal, never locale formatted). */
fun gifSize(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundToLong().coerceAtLeast(1)} KB"
    val tenths = (kb / 1024 * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} MB"
}

/** A trim position, "0:03.0" (minutes, seconds, tenths, rounded down). */
fun trimTime(ms: Long): String {
    val tenths = ms.coerceAtLeast(0) / 100
    val s = tenths / 10
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}.${tenths % 10}"
}

/** A length in seconds with tenths, "9.0 s". */
fun trimLength(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) / 100.0).roundToLong()
    return "${tenths / 10}.${tenths % 10} s"
}

/** "About 4.3 MB · 480 × 1040": the size estimate for [o] of a [video]. */
fun gifEstimateLine(o: GifOptions, video: RecordingInfo): String =
    "About ${gifSize(estimateBytes(o, video.width, video.height))} · ${o.width} × ${gifHeight(o.width, video.width, video.height)}"

/** A finished GIF: its [file], size in [bytes], picture size and length. */
class GifDone(val file: Path, val bytes: Long, val width: Int, val height: Int, val durationMs: Long) {
    /** "4.1 MB · 480 × 1040 · 9.0 s" */
    val line: String get() = "${gifSize(bytes)} · $width × $height · ${trimLength(durationMs)}"
}

/** Runs one export: [GifExporter.export] in the app. */
typealias GifExport = suspend (input: Path, o: GifOptions, onProgress: (GifExporter.Phase, Float) -> Unit) -> Result<Path>

/** Decodes frames of the recording for the film strip and the preview: [com.programmersbox.scrcpyviewer.media.RecordingFrames.frames]. */
fun interface GifFrameSource {
    suspend fun frames(file: Path, startMs: Long, endMs: Long, count: Int, width: Int): List<ByteArray>
}

/** Where the export is: [phase] and its [fraction] done, or null when none runs. */
data class GifProgress(val phase: GifExporter.Phase, val fraction: Float) {
    /** The whole export's share done: the palette is the first fifth, encoding the rest. */
    val overall: Float get() = if (phase == GifExporter.Phase.Palette) 0.2f * fraction else 0.2f + 0.8f * fraction

    val label: String get() = if (phase == GifExporter.Phase.Palette) "Making the palette…" else "Encoding…"
}

/**
 * One Export GIF window's choices and export: the trimmed part ([startMs]..[endMs], at least [GIF_MIN_DURATION_MS]
 * long), [width] and [fps], the export running ([progress]) and the last one done ([done]). UI thread only.
 */
@Stable
class GifExportState(val file: Path, val video: RecordingInfo) {
    var startMs by mutableLongStateOf(0L)
        private set
    var endMs by mutableLongStateOf(video.durationMs)
        private set
    var width by mutableIntStateOf(GIF_DEFAULT_WIDTH)
        private set
    var fps by mutableIntStateOf(GIF_DEFAULT_FPS)
        private set

    /** The export running: its phase and fraction; null when none runs. */
    var progress by mutableStateOf<GifProgress?>(null)
        private set

    /** The last GIF made in this window; cleared when the next export starts. */
    var done by mutableStateOf<GifDone?>(null)
        private set

    /** Bumped whenever the estimate changes: it pulses. */
    var estimateChanges by mutableIntStateOf(0)
        private set

    /** The estimate's accent pulse, 0 to 1 and back, read at draw time. */
    internal val pulse = Animatable(0f)

    /** The export running: its job, and whether [GifExport] has returned (its outcome stands, even if cancelled now). */
    private class Run {
        lateinit var job: Job
        var returned = false
    }

    private var run: Run? = null

    val options: GifOptions get() = GifOptions(startMs, endMs, width, fps)
    val gifHeight: Int get() = gifHeight(width, video.width, video.height)

    /** The trimmed part is 60 s or less: Export is enabled. */
    val canExport: Boolean get() = options.withinLimit
    val exporting: Boolean get() = progress != null

    private val minGap: Long get() = GIF_MIN_DURATION_MS.coerceAtMost(video.durationMs)

    /** Moves the start handle to [ms], kept [GIF_MIN_DURATION_MS] before the end. */
    fun trimStart(ms: Long) {
        val v = ms.coerceIn(0L, (endMs - minGap).coerceAtLeast(0L))
        if (v != startMs) {
            startMs = v
            estimateChanges++
        }
    }

    /** Moves the end handle to [ms], kept [GIF_MIN_DURATION_MS] after the start. */
    fun trimEnd(ms: Long) {
        val v = ms.coerceIn((startMs + minGap).coerceAtMost(video.durationMs), video.durationMs)
        if (v != endMs) {
            endMs = v
            estimateChanges++
        }
    }

    fun pickWidth(w: Int) {
        if (w != width) {
            width = w
            estimateChanges++
        }
    }

    fun pickFps(f: Int) {
        if (f != fps) {
            fps = f
            estimateChanges++
        }
    }

    /**
     * Starts exporting [options] with [export] on [scope] (the window's): the progress row follows it. Done: [done] is
     * set. Failed: [say] gets "Couldn't make the GIF: <reason>". Nothing when one runs or the part is too long.
     */
    fun export(scope: CoroutineScope, export: GifExport, say: (String) -> Unit, sizeOf: suspend (Path) -> Long = ::fileSize) {
        if (exporting || !canExport) return
        val o = options
        val height = gifHeight
        done = null
        progress = GifProgress(GifExporter.Phase.Palette, 0f)
        val run = Run()
        this.run = run
        run.job = scope.launch {
            // Progress comes from a background thread: handed over through a flow, applied here.
            val updates = MutableStateFlow(GifProgress(GifExporter.Phase.Palette, 0f))
            val follow = launch { updates.collect { if (progress != null) progress = it } }
            try {
                val result = export(file, o) { phase, f -> updates.value = GifProgress(phase, f) }
                run.returned = true
                follow.cancel()
                // The export is over: a Cancel arriving now must not drop the finished GIF (it is on disk).
                withContext(NonCancellable) {
                    result.fold(
                        onSuccess = { out -> done = GifDone(out, sizeOf(out), o.width, height, o.durationMs) },
                        onFailure = { e -> say("Couldn't make the GIF: ${e.message ?: e.javaClass.simpleName}") },
                    )
                }
            } finally {
                follow.cancel()
                progress = null
                if (this@GifExportState.run === run) this@GifExportState.run = null
            }
        }
    }

    /**
     * Stops the export running, if any, and waits until FFmpeg is gone and the partial GIF deleted. True when the
     * cancel stopped it (the caller says "Export cancelled. The partial GIF was deleted."); false when none ran or it
     * had already finished (its GIF stays and shows as done, or its failure was said).
     */
    suspend fun cancelExport(): Boolean {
        val running = run ?: return false
        running.job.cancelAndJoin()
        return !running.returned
    }

    private companion object {
        suspend fun fileSize(path: Path): Long = withContext(Dispatchers.IO) { runCatching { Files.size(path) }.getOrDefault(0L) }
    }
}

/** The preview's current frame: the one shown and the one before it (still on screen until the next frame is drawn). */
private class PreviewFrames {
    var shown by mutableStateOf<ImageBitmap?>(null)
        private set
    private var previous: ImageBitmap? = null

    fun show(bitmap: ImageBitmap) {
        previous?.let(::close)
        previous = shown
        shown = bitmap
    }

    fun dispose() {
        previous?.let(::close)
        shown?.let(::close)
        previous = null
        shown = null
    }
}

private fun close(bitmap: ImageBitmap) {
    val skia = bitmap.asSkiaBitmap()
    if (!skia.isClosed) skia.close()
}

/**
 * Decodes [pngs] with [decoder] on [decode]; the bitmaps are then the caller's to close. If the caller is cancelled
 * meanwhile, or one PNG fails, the ones already decoded are closed here: dropped open, their pixels stay in native
 * memory until a finalizer runs.
 */
internal suspend fun decodeAll(
    pngs: List<ByteArray>,
    decode: CoroutineContext,
    decoder: (ByteArray) -> ImageBitmap = ::decodePng,
): List<ImageBitmap> {
    // Filled on the decode thread; withContext returns (or throws on cancel) only once the block has finished.
    val out = ArrayList<ImageBitmap>(pngs.size)
    try {
        withContext(decode) { pngs.forEach { out += decoder(it) } }
        return out
    } catch (e: Throwable) {
        out.forEach(::close)
        throw e
    }
}

/** The trimmed part the preview plays: its PNG frames over [startMs]..[endMs]. */
private class PreviewClip(val startMs: Long, val endMs: Long, val pngs: List<ByteArray>)

/** The trim colour (the mockup's amber), and the done row's green and its background, for this theme. */
@Composable
private fun gifColors(): Triple<Color, Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        Triple(Color(0xFFF0C46A), Color(0xFF7FD3A0), Color(0xFF1D3A28))
    } else {
        Triple(Color(0xFFE0A100), Color(0xFF2E7D4F), Color(0xFFDCEFE2))
    }
}

/**
 * The Export GIF window's content: on the left a looping preview of the trimmed part at the chosen frame rate; on
 * the right the title, Trim (a film strip of [GIF_STRIP_FRAMES] frames with two handles, the parts outside dimmed, a
 * playhead, and "0:03.0   9.0 s   0:12.0"), Width, Frame rate, the size estimate, the progress row while exporting,
 * the done row, and Close / Export.
 *
 * Export runs [export] ([GifExportState.export]); while it runs the button reads Cancel, which stops it and says
 * "Export cancelled. The partial GIF was deleted." through [say] (also used for failures). Done: a green row with the
 * file and its size, Show in folder ([onReveal]) and Export again. A part longer than 60 s disables Export and the
 * range line says "Trim to 60 s or less to export" in the error colour. Close runs [onClose].
 *
 * Frames come from [frames]: the strip once, the preview again whenever the trim or rate settles; until they are in
 * the preview shows [firstFrame] (the thumbnail's, null: black). PNGs are decoded on [decode], never the UI thread.
 * The preview draws through [wake] as the markup editor's canvas does ([WakeStage], or a fade with [wakeFades]).
 * [active] false (the window is leaving): nothing acts. [motion] false: only [GIF_FADE_MILLIS] fades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GifExportContent(
    state: GifExportState,
    motion: Boolean,
    export: GifExport,
    frames: GifFrameSource,
    onClose: () -> Unit,
    onReveal: (Path) -> Unit,
    say: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstFrame: ImageBitmap? = null,
    wake: () -> Float = { 1f },
    wakeFades: Boolean = !motion,
    active: Boolean = true,
    decode: CoroutineContext = Dispatchers.Default,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val currentExport by rememberUpdatedState(export)
    val currentSay by rememberUpdatedState(say)
    val video = state.video

    // The film strip: decoded once, closed with the window.
    var strip by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    DisposableEffect(state) { onDispose { strip.forEach(::close) } }
    LaunchedEffect(state) {
        try {
            val pngs = frames.frames(state.file, 0, video.durationMs, GIF_STRIP_FRAMES, GIF_STRIP_FRAME_WIDTH)
            strip = decodeAll(pngs, decode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No strip (the recording went, FFmpeg can't read it): the black strip still trims.
        }
    }

    // The preview's frames for the trimmed part, decoded again once a change settles.
    var clip by remember { mutableStateOf<PreviewClip?>(null) }
    LaunchedEffect(state, state.startMs, state.endMs, state.fps) {
        if (clip != null) {
            val from = withFrameMillis { it }
            while (withFrameMillis { it } - from < GIF_PREVIEW_SETTLE_MILLIS) Unit
        }
        val start = state.startMs
        val end = state.endMs
        val count = ((end - start) * state.fps / 1000).toInt().coerceIn(1, GIF_PREVIEW_MAX_FRAMES)
        try {
            clip = PreviewClip(start, end, frames.frames(state.file, start, end, count, GIF_PREVIEW_FRAME_WIDTH))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            clip = null
        }
    }
    val preview = remember { PreviewFrames() }
    DisposableEffect(preview) { onDispose(preview::dispose) }
    // The playhead: where the frame on show is, as a share of the whole recording (null: none).
    var playhead by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(clip) {
        val c = clip?.takeIf { it.pngs.isNotEmpty() } ?: run {
            playhead = null
            return@LaunchedEffect
        }
        val n = c.pngs.size
        val frameMillis = ((c.endMs - c.startMs) / n).coerceAtLeast(1L)
        var i = 0
        while (true) {
            val bitmap = try {
                decodeAll(listOf(c.pngs[i]), decode).single()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) preview.show(bitmap)
            playhead = (c.startMs + i * frameMillis).toFloat() / video.durationMs.coerceAtLeast(1)
            if (n == 1) break
            delay(frameMillis)
            i = (i + 1) % n
        }
    }

    val (trimColor, okColor, okBackground) = gifColors()

    Row(modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The preview, at the recording's aspect ratio, as tall as the window allows.
        val aspect = video.width.toFloat() / video.height.coerceAtLeast(1)
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            val image = preview.shown?.takeIf { !it.asSkiaBitmap().isClosed }
                ?: firstFrame?.takeIf { !it.asSkiaBitmap().isClosed }
            val picture: @Composable () -> Unit = {
                if (image != null) {
                    Image(image, contentDescription = "Preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxSize().semantics { contentDescription = "Preview" })
                }
            }
            if (wakeFades) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = wake().coerceIn(0f, 1f) }) { picture() }
            } else {
                WakeStage(wake, standby = false) { picture() }
            }
            Text(
                "PREVIEW · LOOP",
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, lineHeight = 12.sp,
                ),
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Export GIF", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Trim")
                    TrimStrip(state, strip, { playhead }, trimColor, active)
                    RangeLine(state)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        SectionLabel("Width")
                        Segmented(GIF_WIDTHS, state.width, { "$it px" }, "width", motion, active, state::pickWidth)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        SectionLabel("Frame rate")
                        Segmented(GIF_FPS, state.fps, { "$it fps" }, "rate", motion, active, state::pickFps)
                    }
                }
                Estimate(state, motion)
                ProgressRow(state, motion)
                DoneRow(state.done.takeIf { !state.exporting }, motion, okColor, okBackground)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val done = state.done
                AnimatedVisibility(
                    done != null && !state.exporting,
                    enter = fadeIn(tween(if (motion) GIF_ROW_MILLIS else GIF_FADE_MILLIS)),
                    exit = fadeOut(tween(if (motion) GIF_LABEL_MILLIS else GIF_FADE_MILLIS)),
                ) {
                    TextButton(onClick = { if (active) done?.let { onReveal(it.file) } }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Show in folder")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (active) onClose() }) { Text("Close") }
                val label = when {
                    state.exporting -> "Cancel"
                    state.done != null -> "Export again"
                    else -> "Export"
                }
                Button(
                    onClick = {
                        if (!active) return@Button
                        if (state.exporting) {
                            scope.launch { if (state.cancelExport()) currentSay(GIF_CANCELLED_MESSAGE) }
                        } else {
                            state.export(scope, currentExport, currentSay)
                        }
                    },
                    enabled = state.exporting || state.canExport,
                    modifier = if (motion) Modifier.animateContentSize(tween(GIF_LABEL_MILLIS, easing = EmphasizedDecelerate)) else Modifier,
                ) {
                    Icon(Icons.Filled.Gif, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Crossfade(label, animationSpec = tween(if (motion) GIF_LABEL_MILLIS else GIF_FADE_MILLIS), label = "exportLabel") {
                        Text(it, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The film strip with the trimmed part framed and the rest dimmed, the playhead, and the two handles under it. A press
 * anywhere on it takes the nearer handle, and dragging moves it: the frame, the dims and the readout follow the pointer.
 */
@Composable
private fun TrimStrip(
    state: GifExportState,
    strip: List<ImageBitmap>,
    playhead: () -> Float?,
    trimColor: Color,
    active: Boolean,
) {
    val duration = state.video.durationMs.coerceAtLeast(1)
    val currentActive by rememberUpdatedState(active)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val handlePx = with(density) { HANDLE_WIDTH.toPx() }
        Column(Modifier.trimGestures(state, widthPx, duration) { currentActive }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(STRIP_HEIGHT)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
                    .drawWithContent {
                        drawContent()
                        val a = state.startMs.toFloat() / duration * size.width
                        val b = state.endMs.toFloat() / duration * size.width
                        val dim = Color.Black.copy(alpha = 0.6f)
                        drawRect(dim, Offset.Zero, Size(a, size.height))
                        drawRect(dim, Offset(b, 0f), Size(size.width - b, size.height))
                        val stroke = 3.dp.toPx()
                        drawRoundRect(
                            trimColor,
                            Offset(a + stroke / 2, stroke / 2),
                            Size((b - a - stroke).coerceAtLeast(0f), size.height - stroke),
                            CornerRadius(6.dp.toPx()),
                            style = Stroke(stroke),
                        )
                        playhead()?.let { p ->
                            val x = p * size.width
                            drawRect(Color.White, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                        }
                    },
            ) {
                if (strip.isNotEmpty()) Row(Modifier.matchParentSize()) {
                    strip.forEach { frame ->
                        if (!frame.asSkiaBitmap().isClosed) {
                            Image(frame, contentDescription = null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(HANDLE_HEIGHT)) {
                // While the window grows out of its button the strip can be narrower than a handle: never a negative range.
                val maxX = (widthPx - handlePx).coerceAtLeast(0f)
                Handle("Trim start", trimColor) { (state.startMs.toFloat() / duration * widthPx - handlePx / 2).coerceIn(0f, maxX) }
                Handle("Trim end", trimColor) { (state.endMs.toFloat() / duration * widthPx - handlePx / 2).coerceIn(0f, maxX) }
            }
        }
    }
}

/** One trim handle, [x] pixels from the strip's start (read at layout time, so dragging relayouts only it). */
@Composable
private fun Handle(label: String, color: Color, x: () -> Float) {
    Box(
        Modifier
            .offset { IntOffset(x().roundToInt(), 0) }
            .size(HANDLE_WIDTH, HANDLE_HEIGHT)
            .background(color, RoundedCornerShape(4.dp))
            .semantics { contentDescription = label },
    )
}

/**
 * Press and drag on the strip ([widthPx] wide, the whole recording's [duration]): the nearer handle follows the
 * pointer. A click moves the nearer handle there.
 */
private fun Modifier.trimGestures(state: GifExportState, widthPx: Float, duration: Long, active: () -> Boolean): Modifier {
    fun msAt(x: Float): Long = ((x / widthPx).coerceIn(0f, 1f) * duration).roundToLong()
    fun xOf(ms: Long): Float = ms.toFloat() / duration * widthPx
    fun startIsNearer(x: Float): Boolean {
        val a = xOf(state.startMs)
        val b = xOf(state.endMs)
        // Both at one point (pinned at an end): take the side the pointer is on.
        return if (a == b) x < a else abs(x - a) <= abs(x - b)
    }
    fun moveTo(start: Boolean, x: Float) {
        if (!active()) return
        if (start) state.trimStart(msAt(x)) else state.trimEnd(msAt(x))
    }
    return this
        .pointerInput(state, widthPx, duration) {
            var start = true
            detectDragGestures(
                onDragStart = { at ->
                    start = startIsNearer(at.x)
                    moveTo(start, at.x)
                },
            ) { change, _ ->
                change.consume()
                moveTo(start, change.position.x)
            }
        }
        .pointerInput(state, widthPx, duration) {
            detectTapGestures { at -> moveTo(startIsNearer(at.x), at.x) }
        }
}

/** "0:03.0   9.0 s   0:12.0", or with a part over 60 s "Trim to 60 s or less to export" in the error colour. */
@Composable
private fun RangeLine(state: GifExportState) {
    val colors = MaterialTheme.colorScheme
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(trimTime(state.startMs), style = mono, color = colors.onSurface)
        val tooLong = !state.canExport
        val middle = if (tooLong) "Trim to 60 s or less to export" else trimLength(state.endMs - state.startMs)
        val middleColor by animateColorAsState(if (tooLong) colors.error else colors.onSurface, tween(GIF_FADE_MILLIS), label = "rangeColor")
        Text(
            middle,
            Modifier.weight(1f, fill = false).padding(horizontal = 8.dp),
            style = if (tooLong) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium) else mono,
            color = middleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(trimTime(state.endMs), style = mono, color = colors.onSurface)
    }
}

/**
 * A segmented choice of [options]; the [selected] one sits on a pill. With [motion] the pill glides to a new pick over
 * [GIF_PILL_MILLIS]; without, the old pill fades out and the new one in ([GIF_FADE_MILLIS]).
 */
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tag: String,
    motion: Boolean,
    active: Boolean,
    onPick: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    // Each segment's x and width in the row, in pixels, once placed.
    val placed = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val pillX = remember { Animatable(0f) }
    val pillW = remember { Animatable(0f) }
    var pillShown by remember { mutableStateOf(false) }
    val index = options.indexOf(selected)
    val target = placed[index]
    LaunchedEffect(index, target, motion) {
        val t = target ?: return@LaunchedEffect
        if (!pillShown || !motion) {
            pillX.snapTo(t.first)
            pillW.snapTo(t.second)
            pillShown = true
            return@LaunchedEffect
        }
        val spec = tween<Float>(GIF_PILL_MILLIS, easing = EmphasizedDecelerate)
        launch { pillW.animateTo(t.second, spec) }
        pillX.animateTo(t.first, spec)
    }
    Box(Modifier.border(1.dp, colors.outlineVariant, shape).padding(3.dp)) {
        if (motion && pillShown) {
            Box(Modifier.matchParentSize()) {
                // Placed and sized at layout time from the animation: the glide relayouts only the pill.
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val w = pillW.value.roundToInt().coerceAtLeast(0)
                            val p = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) { p.place(pillX.value.roundToInt(), 0) }
                        }
                        .testTag("$tag pill")
                        .background(colors.secondaryContainer, RoundedCornerShape(14.dp)),
                )
            }
        }
        Row {
            options.forEachIndexed { i, option ->
                val on = option == selected
                val text by animateColorAsState(
                    if (on) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    tween(if (motion) 250 else GIF_FADE_MILLIS),
                    label = "segmentText",
                )
                // Reduced motion: each segment's own pill fades in and out.
                val fill by animateColorAsState(
                    if (!motion && on) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
                    tween(GIF_FADE_MILLIS),
                    label = "segmentFill",
                )
                Box(
                    Modifier
                        .onPlaced { placed[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .clip(RoundedCornerShape(14.dp))
                        .background(fill)
                        .selectable(on, role = Role.RadioButton) { if (active) onPick(option) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(label(option), color = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** "About 4.3 MB · 480 × 1040": the size pulses in the accent colour whenever the estimate changes. */
@Composable
private fun Estimate(state: GifExportState, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val changes = state.estimateChanges
    val first = remember { changes }
    LaunchedEffect(changes) {
        if (changes == first) return@LaunchedEffect
        val total = if (motion) GIF_PULSE_MILLIS else GIF_FADE_MILLIS
        state.pulse.snapTo(0f)
        state.pulse.animateTo(0f, keyframes {
            durationMillis = total
            1f at (total * 2 / 5) using EmphasizedDecelerate
        })
    }
    val o = state.options
    val video = state.video
    val size = gifSize(estimateBytes(o, video.width, video.height))
    val rest = " · ${o.width} × ${gifHeight(o.width, video.width, video.height)}"
    // A pulse recomposes only this line.
    val sizeColor = lerp(colors.onSurface, colors.primary, state.pulse.value)
    Text(
        buildAnnotatedString {
            append("About ")
            withStyle(SpanStyle(color = sizeColor, fontWeight = FontWeight.Medium)) { append(size) }
            append(rest)
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
        color = colors.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "About $size$rest" },
    )
}

/** While exporting: the bar, "Making the palette…" or "Encoding…", and the percentage. Unfolds and folds. */
@Composable
private fun ProgressRow(state: GifExportState, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val progress = state.progress
    // Keeps the last values while the row folds away.
    val last = remember { arrayOfNulls<GifProgress>(1) }
    progress?.let { last[0] = it }
    AnimatedVisibility(
        progress != null,
        modifier = Modifier.testTag("gif progress row"),
        enter = if (motion) {
            expandVertically(tween(GIF_ROW_MILLIS, easing = EmphasizedDecelerate)) + fadeIn(tween(GIF_ROW_MILLIS))
        } else {
            fadeIn(tween(GIF_FADE_MILLIS))
        },
        exit = if (motion) {
            shrinkVertically(tween(GIF_ROW_MILLIS, easing = EmphasizedDecelerate)) + fadeOut(tween(GIF_ROW_MILLIS))
        } else {
            fadeOut(tween(GIF_FADE_MILLIS))
        },
    ) {
        val p = progress ?: last[0] ?: return@AnimatedVisibility
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { p.overall },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("${(p.overall * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Done: a green row with a check that pops and draws, the file's name and "4.1 MB · 480 × 1040 · 9.0 s". */
@Composable
private fun DoneRow(done: GifDone?, motion: Boolean, ok: Color, okBackground: Color) {
    val last = remember { arrayOfNulls<GifDone>(1) }
    done?.let { last[0] = it }
    AnimatedVisibility(
        done != null,
        enter = if (motion) {
            expandVertically(tween(GIF_ROW_MILLIS, easing = EmphasizedDecelerate)) + fadeIn(tween(GIF_ROW_MILLIS))
        } else {
            fadeIn(tween(GIF_FADE_MILLIS))
        },
        exit = if (motion) {
            shrinkVertically(tween(GIF_ROW_MILLIS, easing = EmphasizedDecelerate)) + fadeOut(tween(GIF_ROW_MILLIS))
        } else {
            fadeOut(tween(GIF_FADE_MILLIS))
        },
    ) {
        val d = done ?: last[0] ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().background(okBackground, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DoneCheck(d, motion, ok, okBackground)
            Column(Modifier.weight(1f)) {
                Text(
                    d.file.fileName.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(d.line, style = MaterialTheme.typography.bodySmall, color = ok)
            }
        }
    }
}

/** The done check: with [motion] it pops (0 to 1.12 to 1) and its tick draws after; else it is simply there. */
@Composable
private fun DoneCheck(done: GifDone, motion: Boolean, ok: Color, tick: Color) {
    val pop = remember(done) { Animatable(if (motion) 0f else 1f) }
    val draw = remember(done) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(done) {
        if (!motion) return@LaunchedEffect
        launch {
            pop.animateTo(1f, keyframes {
                durationMillis = GIF_CHECK_POP_MILLIS
                0f at 0
                1.12f at (GIF_CHECK_POP_MILLIS * 7 / 10) using EmphasizedDecelerate
                1f at GIF_CHECK_POP_MILLIS
            })
        }
        draw.animateTo(1f, tween(GIF_CHECK_DRAW_MILLIS, delayMillis = GIF_CHECK_DRAW_DELAY_MILLIS, easing = LinearEasing))
    }
    Box(
        Modifier
            .size(28.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .semantics { contentDescription = "Done" }
            .drawBehind {
                drawCircle(ok)
                val path = DrawPath().apply {
                    moveTo(size.width * 0.29f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.67f)
                    lineTo(size.width * 0.72f, size.height * 0.37f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val part = DrawPath()
                measure.getSegment(0f, measure.length * draw.value.coerceIn(0f, 1f), part, true)
                drawPath(part, tick, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            },
    )
}
