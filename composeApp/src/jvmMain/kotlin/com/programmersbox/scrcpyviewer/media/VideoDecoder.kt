package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import com.programmersbox.scrcpyviewer.scrcpy.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes the scrcpy video stream with FFmpeg (JavaCPP) and publishes every frame as a new [VideoFrame] (an
 * immutable BGRA Skia bitmap).
 *
 * Config packets are never decoded on their own (v5.0 `decoder.c` ignores them). For H.264/H.265 the config packet
 * is kept and prepended to the next media packet, like `app/src/packet_merger.c`; other codecs carry it in-band.
 *
 * Each frame is converted with `sws_scale` straight into the pixels of a freshly allocated Skia [Bitmap], which is
 * then made immutable and published through [frame] and [onFrame]. Nothing writes to a bitmap after it is
 * published.
 *
 * Frame lifetime: the GC does not see Skia's native memory (one frame is up to ~16 MB), so the previous frame is
 * released as soon as a new one is published. Release takes the frame lock that [VideoFrame.use] holds, so a
 * consumer either sees the pixels inside `use` or gets null; it never touches a released bitmap. Only the current
 * frame is alive; it stays valid after [close] so it can be shown under the Disconnected scrim. The conversion
 * context is recreated when the frame size or pixel format changes ([onSession] on rotation, or a frame of another
 * size), and [frameSize] follows.
 *
 * The decoded rate is recomputed once a second from the frames of the last second (0 on a still screen with no
 * frames, and 0 after [close]) and published through [fps] and [onFps]. Decoding calls come from the session's
 * video reader thread; [close] may come from another thread and is serialized with decoding.
 */
class VideoDecoder(
    private val codecId: Int,
    private val onFrame: (VideoFrame) -> Unit = {},
    private val onFps: (Double) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) : VideoSink {
    private val _frameSize = MutableStateFlow<IntSize?>(null)
    /** Size of the session (from the headers) or of the last decoded frame, whichever changed last. */
    val frameSize: StateFlow<IntSize?> = _frameSize.asStateFlow()

    private val _frame = MutableStateFlow<VideoFrame?>(null)
    val frame: StateFlow<VideoFrame?> = _frame.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    @Volatile var decodedFrames: Long = 0
        private set

    private val lock = Any()
    /** Taken by [VideoFrame.use] and by the release of a replaced frame (never while holding it, take [lock]). */
    private val frameLock = Any()
    @Volatile private var closed = false
    private val mergeConfig = codecId == StreamProtocol.CODEC_H264 || codecId == StreamProtocol.CODEC_H265
    private var pendingConfig: ByteArray? = null

    // Native state, allocated by [open] (all or nothing) and freed by [close].
    private val ctx: AVCodecContext
    private val packet: AVPacket
    private val avFrame: AVFrame
    private val dstData: PointerPointer<BytePointer>
    private val dstLinesize: IntPointer

    // Conversion context, recreated when the size or format changes.
    private var sws: SwsContext? = null
    private var swsWidth = 0
    private var swsHeight = 0
    private var swsFormat = -1

    private var current: VideoFrame? = null

    private val windowFrames = AtomicInteger()
    @Volatile private var windowStart = clock()
    private val fpsTask: ScheduledFuture<*>
    private var loggedSendError = false
    private var loggedScaleError = false

    init {
        val avId = when (codecId) {
            StreamProtocol.CODEC_H264 -> avcodec.AV_CODEC_ID_H264
            StreamProtocol.CODEC_H265 -> avcodec.AV_CODEC_ID_HEVC
            StreamProtocol.CODEC_AV1 -> avcodec.AV_CODEC_ID_AV1
            StreamProtocol.CODEC_VP8 -> avcodec.AV_CODEC_ID_VP8
            StreamProtocol.CODEC_VP9 -> avcodec.AV_CODEC_ID_VP9
            else -> throw IOException("Unsupported video codec 0x%08x".format(codecId))
        }
        val codec = avcodec.avcodec_find_decoder(avId) ?: throw IOException("No FFmpeg decoder for ${codecName(codecId)}")
        val c = avcodec.avcodec_alloc_context3(codec) ?: throw IOException("Could not allocate the decoder context")
        c.flags(c.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        val r = avcodec.avcodec_open2(c, codec, null as PointerPointer<*>?)
        if (r < 0) {
            avcodec.avcodec_free_context(c)
            throw IOException("Could not open the ${codecName(codecId)} decoder ($r)")
        }
        val p = avcodec.av_packet_alloc()
        val f = avutil.av_frame_alloc()
        if (p == null || f == null) {
            p?.let { avcodec.av_packet_free(it) }
            f?.let { avutil.av_frame_free(it) }
            avcodec.avcodec_free_context(c)
            throw IOException("Out of memory for the decoder")
        }
        ctx = c
        packet = p
        avFrame = f
        dstData = PointerPointer(4)
        dstLinesize = IntPointer(4L)
        fpsTask = fpsTimer.scheduleAtFixedRate(::tickFps, 1, 1, TimeUnit.SECONDS)
    }

    override fun onMeta(meta: VideoCodecMeta) {
        _frameSize.value = IntSize(meta.width, meta.height)
    }

    override fun onSession(width: Int, height: Int): Unit = synchronized(lock) {
        if (closed) return
        _frameSize.value = IntSize(width, height)
        // The next frame has the new size; recreate the converter now.
        ensureConverter(width, height, swsFormat.takeIf { it >= 0 } ?: avutil.AV_PIX_FMT_YUV420P)
    }

    override fun onPacket(p: MediaPacket): Unit = synchronized(lock) {
        if (closed) return
        if (p.isConfig) {
            if (mergeConfig) pendingConfig = p.data
            return
        }
        val config = pendingConfig
        pendingConfig = null
        decode(if (config != null) config + p.data else p.data, p)
    }

    private fun decode(data: ByteArray, p: MediaPacket) {
        if (avcodec.av_new_packet(packet, data.size) < 0) throw IOException("Out of memory for a video packet")
        try {
            packet.data().put(data, 0, data.size)
            packet.pts(p.ptsUs)
            packet.dts(p.ptsUs)
            if (p.isKeyFrame) packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)
            val sent = avcodec.avcodec_send_packet(ctx, packet)
            if (sent < 0) {
                // A corrupt packet is not fatal: the next key frame recovers.
                if (!loggedSendError) log.log(System.Logger.Level.WARNING, "Could not send a video packet ($sent)")
                loggedSendError = true
                return
            }
        } finally {
            avcodec.av_packet_unref(packet)
        }
        while (avcodec.avcodec_receive_frame(ctx, avFrame) >= 0) {
            try {
                publish(avFrame)
            } finally {
                avutil.av_frame_unref(avFrame)
            }
        }
    }

    private fun publish(f: AVFrame) {
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return
        if (w != swsWidth || h != swsHeight || f.format() != swsFormat) {
            ensureConverter(w, h, f.format())
            _frameSize.value = IntSize(w, h)
        }
        val converter = sws ?: return
        // A new bitmap per frame, never a released one reused: drawing a frame makes Skia images that share its
        // pixels and outlive VideoFrame.use (a recorded picture replays them later), and skiko gives no reliable way
        // to tell when they are gone, so a reused bitmap could show a later or half-written frame.
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) {
            bitmap.close()
            throw IOException("Could not allocate a ${w}x$h frame")
        }
        val pixmap = checkNotNull(bitmap.peekPixels()) { "frame bitmap has no pixels" }
        try {
            // sws_scale writes straight into the bitmap's pixel memory: one copy per frame.
            dstData.put(0L, NativeAddress(pixmap.addr, pixmap.rowBytes.toLong() * h))
            dstLinesize.put(0L, pixmap.rowBytes) // 0L: put(Int, Int) would resolve to the vararg put(int...)
            val rows = swscale.sws_scale(converter, f.data(), f.linesize(), 0, h, dstData, dstLinesize)
            if (rows <= 0) {
                if (!loggedScaleError) log.log(System.Logger.Level.WARNING, "Could not convert a ${w}x$h frame ($rows)")
                loggedScaleError = true
                bitmap.close() // never published
                return
            }
        } finally {
            pixmap.close()
        }
        bitmap.notifyPixelsChanged()
        bitmap.setImmutable()
        val image = VideoFrame(bitmap, frameLock)
        val previous = current
        current = image
        decodedFrames++
        windowFrames.incrementAndGet()
        _frame.value = image
        onFrame(image)
        previous?.release() // consumers holding it get null from use()
    }

    /** Under [lock], so a tick never publishes after [close] published 0. */
    private fun tickFps(): Unit = synchronized(lock) {
        if (closed) return
        val now = clock()
        val frames = windowFrames.getAndSet(0)
        val elapsed = now - windowStart
        windowStart = now
        if (elapsed <= 0) return
        val value = frames * 1e9 / elapsed
        _fps.value = value
        runCatching { onFps(value) }
    }

    private fun ensureConverter(w: Int, h: Int, format: Int) {
        if (w <= 0 || h <= 0) return
        if (w == swsWidth && h == swsHeight && format == swsFormat && sws != null) return
        sws = swscale.sws_getCachedContext(
            sws, w, h, format, w, h, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: throw IOException("Could not create the color converter for ${w}x$h")
        swsWidth = w
        swsHeight = h
        swsFormat = format
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        fpsTask.cancel(false)
        _fps.value = 0.0
        runCatching { onFps(0.0) }
        avcodec.avcodec_free_context(ctx)
        avcodec.av_packet_free(packet)
        avutil.av_frame_free(avFrame)
        sws?.let { swscale.sws_freeContext(it) }
        sws = null
        dstData.close()
        dstLinesize.close()
        // The current frame stays valid: the Disconnected scrim draws it.
    }

    /** A non-owning view of native memory owned by someone else (here, a Skia bitmap's pixels). */
    private class NativeAddress(addr: Long, size: Long) : BytePointer() {
        init {
            address = addr
            limit = size
            capacity = size
        }
    }

    companion object {
        private val log = System.getLogger("scrcpyviewer.VideoDecoder")

        /** One daemon thread recomputes the fps of every decoder once a second. */
        private val fpsTimer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "video-fps").apply { isDaemon = true }
        }

        fun codecName(codecId: Int): String = when (codecId) {
            StreamProtocol.CODEC_H264 -> "H.264"
            StreamProtocol.CODEC_H265 -> "H.265"
            StreamProtocol.CODEC_AV1 -> "AV1"
            StreamProtocol.CODEC_VP8 -> "VP8"
            StreamProtocol.CODEC_VP9 -> "VP9"
            else -> "0x%08x".format(codecId)
        }
    }
}
