package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.PointerPointer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How a recording ended. */
sealed interface RecordResult {
    /** The target file; null when the requested path itself was invalid. */
    val file: Path?

    data class Saved(override val file: Path) : RecordResult
    data class Failed(override val file: Path?, val message: String) : RecordResult
}

/**
 * Records the scrcpy streams to [file] by stream copy through avformat, ported from v5.0 `app/src/recorder.c`.
 *
 * [format] is one of [FORMATS] (see [formatFor]); [video] is null when there is no video stream and [audioCodecId]
 * null when there is no audio stream. [orientation] (0, 90, 180 or 270, clockwise) is written as display matrix side
 * data. The combinations v5.0 `cli.c` rejects throw [IllegalArgumentException] from the constructor.
 *
 * [onVideo] and [onAudio] only queue the packet (they are called on the session's reader threads); a dedicated
 * low-priority thread opens the file, waits until each enabled stream has had its config packet (VP8/VP9 video and
 * raw audio have none), uses those as extradata, writes the header, then writes the packets. Like v5.0:
 * - pts are relative to the first packet (the smaller of the first video and audio pts) and rescaled from
 *   microseconds to the stream time base; non-monotonic pts are bumped by one tick;
 * - a video packet is written once the next one arrived, so its duration is known; the last one gets 100 ms;
 * - later config packets are not written; for H.264/H.265 they are prepended to the next media packet
 *   (`packet_merger.c`), as is the first one;
 * - a session change (mid-stream resize) is not handled: the stream keeps its initial size.
 *
 * Unlike v5.0, which always starts with the session, a recorder may start mid-stream: video packets before the first
 * key frame are dropped, and audio packets too, so both streams start together.
 *
 * [onEnded] is called on the recorder thread once the file is closed. A file this recorder opened but whose header
 * was never written is deleted (never a file or directory it could not open). [stop] asks the thread to finish (queued packets are still written); [close] also waits for it.
 */
class Recorder(
    private val file: Path,
    private val format: String,
    private val video: VideoCodecMeta?,
    private val audioCodecId: Int?,
    private val orientation: Int,
    private val onEnded: (RecordResult) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    // Guarded by lock.
    private val videoQueue = ArrayDeque<MediaPacket>()
    private val audioQueue = ArrayDeque<MediaPacket>()
    private var stopped = false
    private var videoKeySeen = false

    private val videoAvId = video?.let { videoAvCodecId(it.codecId) }
    private val audioAvId = audioCodecId?.let(::audioAvCodecId)
    // A config packet is provided for all supported formats except VPx and raw audio.
    private val videoExpectsConfig = videoAvId != null && videoAvId != avcodec.AV_CODEC_ID_VP8 && videoAvId != avcodec.AV_CODEC_ID_VP9
    private val audioExpectsConfig = audioAvId != null && audioAvId != avcodec.AV_CODEC_ID_PCM_S16LE
    private val mergeConfig = video?.codecId == StreamProtocol.CODEC_H264 || video?.codecId == StreamProtocol.CODEC_H265

    /** Set by the recorder thread once the header is written (tests). */
    @Volatile internal var headerWritten = false
        private set

    /** Set once avio_open created/truncated [file]: only then may a header-less file be deleted. */
    private var opened = false

    @Volatile var result: RecordResult? = null
        private set

    private val thread: Thread

    init {
        validate(format, video, audioCodecId, orientation)
        thread = Thread(::run, "recorder").apply {
            priority = Thread.MIN_PRIORITY // recording is a background task
            // Non-daemon, so the JVM does not stop it when the last other thread ends; System.exit() (and
            // exitProcess) still kills it mid-file. The app's shutdown joins the recorders before exiting.
            isDaemon = false
            start()
        }
    }

    fun onVideo(p: MediaPacket): Unit = lock.withLock {
        if (stopped || video == null) return
        if (!p.isConfig && !videoKeySeen) {
            if (!p.isKeyFrame) return // started mid-GOP: not decodable
            videoKeySeen = true
        }
        videoQueue.addLast(p)
        cond.signal()
    }

    fun onAudio(p: MediaPacket): Unit = lock.withLock {
        if (stopped || audioCodecId == null) return
        if (!p.isConfig && video != null && !videoKeySeen) return // start with the video
        audioQueue.addLast(p)
        cond.signal()
    }

    /** Finishes the recording in the background (queued packets are written, then the trailer). */
    fun stop(): Unit = lock.withLock {
        stopped = true
        cond.signal()
    }

    /** [stop], then waits until the file is closed and [onEnded] has run. */
    fun close() {
        stop()
        if (Thread.currentThread() !== thread) thread.join()
    }

    /** Waits up to [millis] for the recorder thread; true when it ended. */
    fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }

    private fun run() {
        var error: String? = null
        var ctx: AVFormatContext? = null
        var pb: AVIOContext? = null
        try {
            ctx = AVFormatContext(null)
            pb = AVIOContext(null)
            error = openOutput(ctx, pb) ?: processPackets(ctx)
        } catch (t: Throwable) {
            log.log(System.Logger.Level.ERROR, "Recording to $file failed", t)
            error = t.message ?: t.toString()
        } finally {
            lock.withLock {
                stopped = true // prevent the producers from pushing any new packet
                videoQueue.clear()
                audioQueue.clear()
            }
            if (pb != null && !pb.isNull) avformat.avio_closep(pb)
            if (ctx != null && !ctx.isNull) avformat.avformat_free_context(ctx)
        }
        if (opened && !headerWritten) runCatching { Files.deleteIfExists(file) }
        val r = if (error == null) RecordResult.Saved(file) else RecordResult.Failed(file, error)
        if (error == null) log.log(System.Logger.Level.INFO, "Recording complete to $format file: $file")
        else log.log(System.Logger.Level.WARNING, "Recording failed to $file: $error")
        result = r
        runCatching { onEnded(r) }.onFailure { log.log(System.Logger.Level.ERROR, "onEnded failed", it) }
    }

    private var videoStream: AVStream? = null
    private var audioStream: AVStream? = null
    private var videoLastPts = avutil.AV_NOPTS_VALUE
    private var audioLastPts = avutil.AV_NOPTS_VALUE

    /** Returns an error message, or null once the file is open and the streams are declared. */
    private fun openOutput(ctx: AVFormatContext, pb: AVIOContext): String? {
        val muxer = muxerName(format)
        if (avformat.avformat_alloc_output_context2(ctx, null, muxer, null as String?) < 0 || ctx.isNull) {
            return "Could not find muxer $muxer"
        }
        val r = avformat.avio_open(pb, "file:$file", avformat.AVIO_FLAG_WRITE)
        if (r < 0) return "Failed to open output file: $file (${errorString(r)})"
        opened = true
        ctx.pb(pb)
        val metadata = AVDictionary(null)
        avutil.av_dict_set(metadata, "comment", "Recorded by Scrcpy Vwr (scrcpy 5.0)", 0)
        ctx.metadata(metadata)

        if (video != null) {
            val st = avformat.avformat_new_stream(ctx, null) ?: throw IOException("Could not create the video stream")
            val par = st.codecpar()
            par.codec_type(avutil.AVMEDIA_TYPE_VIDEO)
            par.codec_id(videoAvId!!)
            par.width(video.width)
            par.height(video.height)
            par.format(avutil.AV_PIX_FMT_YUV420P)
            if (orientation != 0) {
                setOrientation(par, orientation)
                log.log(System.Logger.Level.INFO, "Record orientation set to $orientation")
            }
            videoStream = st
        }
        if (audioCodecId != null) {
            val st = avformat.avformat_new_stream(ctx, null) ?: throw IOException("Could not create the audio stream")
            val par = st.codecpar()
            par.codec_type(avutil.AVMEDIA_TYPE_AUDIO)
            par.codec_id(audioAvId!!)
            // Hardcoded audio properties (demuxer.c).
            avutil.av_channel_layout_default(par.ch_layout(), 2)
            par.sample_rate(48_000)
            if (audioCodecId == StreamProtocol.CODEC_FLAC) par.format(avutil.AV_SAMPLE_FMT_S16)
            audioStream = st
        }
        log.log(System.Logger.Level.INFO, "Recording started to $muxer file: $file")
        return null
    }

    /** Waits for the config packets (`sc_recorder_process_header`); returns an error message or null. */
    private fun processHeader(ctx: AVFormatContext): String? {
        val (videoConfig, audioConfig) = lock.withLock {
            while (!stopped && mustWaitForConfigPackets()) cond.await()
            if (stopped && video != null && videoQueue.isEmpty()) {
                // If the recorder is stopped, don't process anything if there are not at least video packets.
                return "Recording stopped before headers were processed"
            }
            val v = if (videoExpectsConfig && videoQueue.isNotEmpty()) videoQueue.removeFirst() else null
            val a = if (audioExpectsConfig && audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
            v to a
        }
        if (videoConfig != null) {
            if (!videoConfig.isConfig) return "The first video packet is not a config packet"
            setExtradata(videoStream!!.codecpar(), videoConfig.data)
            if (mergeConfig) pendingConfig = videoConfig.data
        }
        if (audioConfig != null) {
            if (!audioConfig.isConfig) return "The first audio packet is not a config packet"
            setExtradata(audioStream!!.codecpar(), audioConfig.data)
        }
        val r = avformat.avformat_write_header(ctx, null as PointerPointer<*>?)
        if (r < 0) return "Failed to write header to $file (${errorString(r)})"
        headerWritten = true
        return null
    }

    /** Caller holds [lock]. */
    private fun mustWaitForConfigPackets(): Boolean =
        (videoExpectsConfig && videoQueue.isEmpty()) || (audioExpectsConfig && audioQueue.isEmpty())

    /** The config packet to prepend to the next H.26x media packet (`packet_merger.c`). */
    private var pendingConfig: ByteArray? = null

    private class PendingVideo(val pts: Long, val data: ByteArray, val key: Boolean)

    /** `sc_recorder_process_packets`; returns an error message or null. */
    private fun processPackets(ctx: AVFormatContext): String? {
        processHeader(ctx)?.let { return it }
        val packet = avcodec.av_packet_alloc() ?: throw IOException("Out of memory for a packet")
        try {
            var ptsOrigin: Long? = null
            var videoPkt: MediaPacket? = null
            var audioPkt: MediaPacket? = null
            // A video packet is written once the next one arrived, so that its duration is known.
            var previous: PendingVideo? = null
            while (true) {
                val stoppedNow = lock.withLock {
                    while (!stopped) {
                        if (video != null && videoPkt == null && videoQueue.isNotEmpty()) break
                        if (audioCodecId != null && audioPkt == null && audioQueue.isNotEmpty()) break
                        cond.await()
                    }
                    // If stopped, the remaining packets are still processed to finish the recording.
                    if (videoPkt == null) videoPkt = videoQueue.removeFirstOrNull()
                    if (audioPkt == null) audioPkt = audioQueue.removeFirstOrNull()
                    stopped
                }
                if (stoppedNow && videoPkt == null && audioPkt == null) break

                // Further config packets are not written; for H.26x the next media packet carries it.
                videoPkt?.let { if (it.isConfig) { if (mergeConfig) pendingConfig = it.data; videoPkt = null } }
                audioPkt?.let { if (it.isConfig) audioPkt = null }

                if (ptsOrigin == null) {
                    val v = videoPkt
                    val a = audioPkt
                    ptsOrigin = when {
                        audioCodecId == null && v != null -> v.ptsUs
                        video == null && a != null -> a.ptsUs
                        video != null && audioCodecId != null && v != null && a != null -> minOf(v.ptsUs, a.ptsUs)
                        // Stopped without audio: record the video packets; fail if there is no video.
                        stoppedNow -> v?.ptsUs ?: return "Recording stopped before any media packet"
                        else -> continue // both video and audio packets are needed to initialize the origin
                    }
                }
                val origin: Long = ptsOrigin

                videoPkt?.let { v ->
                    val pts = v.ptsUs - origin
                    val config = pendingConfig
                    val data = if (mergeConfig && config != null) config + v.data else v.data
                    pendingConfig = null
                    previous?.let { prev ->
                        if (!writeVideo(ctx, packet, prev, duration = pts - prev.pts)) return "Could not record video packet"
                    }
                    previous = PendingVideo(pts, data, v.isKeyFrame)
                    videoPkt = null
                }
                audioPkt?.let { a ->
                    val stream = audioStream!!
                    val pts = a.ptsUs - origin
                    if (!write(ctx, packet, stream, isVideo = false, pts, 0, a.data, a.isKeyFrame)) return "Could not record audio packet"
                    audioPkt = null
                }
            }
            previous?.let {
                // An arbitrary duration for the last packet. Failing to write it is not serious: no frame depends on it.
                if (!writeVideo(ctx, packet, it, duration = 100_000)) log.log(System.Logger.Level.WARNING, "Could not record last packet")
            }
            val r = avformat.av_write_trailer(ctx)
            if (r < 0) return "Failed to write trailer to $file (${errorString(r)})"
            return null
        } finally {
            avcodec.av_packet_free(packet)
        }
    }

    private fun writeVideo(ctx: AVFormatContext, packet: AVPacket, v: PendingVideo, duration: Long): Boolean =
        write(ctx, packet, videoStream!!, isVideo = true, v.pts, duration, v.data, v.key)

    /** `sc_recorder_write_stream`: rescales to the stream time base, keeps pts increasing, writes interleaved. */
    private fun write(
        ctx: AVFormatContext, packet: AVPacket, stream: AVStream, isVideo: Boolean,
        ptsUs: Long, durationUs: Long, data: ByteArray, key: Boolean,
    ): Boolean {
        if (avcodec.av_new_packet(packet, data.size) < 0) return false
        try {
            packet.data().put(data, 0, data.size)
            packet.pts(ptsUs)
            packet.dts(ptsUs)
            packet.duration(durationUs)
            packet.stream_index(stream.index())
            if (key) packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)
            avcodec.av_packet_rescale_ts(packet, US_TIME_BASE, stream.time_base())
            val last = if (isVideo) videoLastPts else audioLastPts
            val pts = if (last != avutil.AV_NOPTS_VALUE && packet.pts() <= last) {
                (last + 1).also { packet.pts(it); packet.dts(it) }
            } else {
                packet.pts()
            }
            if (isVideo) videoLastPts = pts else audioLastPts = pts
            return avformat.av_interleaved_write_frame(ctx, packet) >= 0
        } finally {
            avcodec.av_packet_unref(packet)
        }
    }

    private fun setExtradata(par: AVCodecParameters, data: ByteArray) {
        // Owned (and freed) by the codec parameters, so allocated with av_malloc, padded as FFmpeg expects.
        val buf = avutil.av_mallocz((data.size + avcodec.AV_INPUT_BUFFER_PADDING_SIZE).toLong())
            ?: throw IOException("Out of memory for the extradata")
        BytePointer(buf).capacity(data.size.toLong()).put(data, 0, data.size)
        par.extradata(BytePointer(buf))
        par.extradata_size(data.size)
    }

    private fun setOrientation(par: AVCodecParameters, degrees: Int) {
        // One display matrix entry in coded_side_data, freed with the codec parameters.
        val sd = AVPacketSideData(avutil.av_mallocz(Loader.sizeof(AVPacketSideData::class.java).toLong()) ?: throw IOException("Out of memory"))
        val matrix = avutil.av_mallocz(9L * 4) ?: throw IOException("Out of memory")
        avutil.av_display_rotation_set(IntPointer(matrix).capacity(9), degrees.toDouble())
        sd.data(BytePointer(matrix))
        sd.size(9L * 4)
        sd.type(avcodec.AV_PKT_DATA_DISPLAYMATRIX)
        par.coded_side_data(sd)
        par.nb_coded_side_data(1)
    }

    companion object {
        private val log = System.getLogger("scrcpyviewer.Recorder")
        private val US_TIME_BASE = avutil.av_make_q(1, 1_000_000)

        /** The `--record-format` values, as v5.0 `cli.c`. */
        val FORMATS = listOf("mp4", "mkv", "m4a", "mka", "opus", "aac", "flac", "wav")
        private val AUDIO_ONLY = setOf("m4a", "mka", "opus", "aac", "flac", "wav")

        /** [recordFormat] (the `record-format` option) when set, else the file extension (`guess_record_format`). */
        fun formatFor(file: Path, recordFormat: String?): String? {
            if (recordFormat != null) return recordFormat.takeIf { it in FORMATS }
            val name = file.fileName?.toString() ?: return null
            if ('.' !in name) return null
            return name.substringAfterLast('.').takeIf { it in FORMATS }
        }

        /**
         * Format of a recording started from the mirror window's Record button: `record-format` when set, else mp4,
         * or mkv when the session's codecs do not fit in MP4 (raw audio, VP8/VP9 video).
         */
        fun toolbarFormat(values: OptionValues): String {
            val e = values.entries
            e["record-format"]?.takeIf { it in FORMATS }?.let { return it }
            val videoCodec = e["video-codec"]
            val rawAudio = e["audio-codec"] == "raw" && "no-audio" !in e
            return if (rawAudio || videoCodec == "vp8" || videoCodec == "vp9") "mkv" else "mp4"
        }

        fun muxerName(format: String): String = when (format) {
            "mp4", "m4a", "aac" -> "mp4"
            "mkv", "mka" -> "matroska"
            "opus" -> "opus"
            "flac" -> "flac"
            "wav" -> "wav"
            else -> throw IllegalArgumentException("Unsupported record format: $format")
        }

        /** The checks of v5.0 `cli.c` (against the actual streams instead of the requested codecs). */
        fun validate(format: String, video: VideoCodecMeta?, audioCodecId: Int?, orientation: Int) {
            require(format in FORMATS) { "Unsupported record format: $format (expected mp4, mkv, m4a, mka, opus, aac, flac or wav)" }
            require(video != null || audioCodecId != null) { "Video and audio disabled, nothing to record" }
            require(orientation in listOf(0, 90, 180, 270)) { "Record orientation only supports rotation, not flipping: $orientation" }
            require(video == null || format !in AUDIO_ONLY) { "Audio container does not support video stream" }
            require(format != "opus" || audioCodecId == StreamProtocol.CODEC_OPUS) {
                "Recording to OPUS file requires an OPUS audio stream (try with --audio-codec=opus)"
            }
            require(format != "aac" || audioCodecId == StreamProtocol.CODEC_AAC) {
                "Recording to AAC file requires an AAC audio stream (try with --audio-codec=aac)"
            }
            require(format != "flac" || audioCodecId == StreamProtocol.CODEC_FLAC) {
                "Recording to FLAC file requires a FLAC audio stream (try with --audio-codec=flac)"
            }
            require(format != "wav" || audioCodecId == StreamProtocol.CODEC_RAW) {
                "Recording to WAV file requires a RAW audio stream (try with --audio-codec=raw)"
            }
            require(!((format == "mp4" || format == "m4a") && audioCodecId == StreamProtocol.CODEC_RAW)) {
                "Recording to MP4 container does not support RAW audio"
            }
            require(!(format == "mp4" && video?.codecId == StreamProtocol.CODEC_VP8)) {
                "Recording to MP4 container does not support VP8 video"
            }
            video?.let { videoAvCodecId(it.codecId) }
            audioCodecId?.let(::audioAvCodecId)
        }

        private fun videoAvCodecId(codecId: Int): Int = when (codecId) {
            StreamProtocol.CODEC_H264 -> avcodec.AV_CODEC_ID_H264
            StreamProtocol.CODEC_H265 -> avcodec.AV_CODEC_ID_HEVC
            StreamProtocol.CODEC_AV1 -> avcodec.AV_CODEC_ID_AV1
            StreamProtocol.CODEC_VP8 -> avcodec.AV_CODEC_ID_VP8
            StreamProtocol.CODEC_VP9 -> avcodec.AV_CODEC_ID_VP9
            else -> throw IllegalArgumentException("Unsupported video codec 0x%08x".format(codecId))
        }

        private fun audioAvCodecId(codecId: Int): Int = when (codecId) {
            StreamProtocol.CODEC_OPUS -> avcodec.AV_CODEC_ID_OPUS
            StreamProtocol.CODEC_AAC -> avcodec.AV_CODEC_ID_AAC
            StreamProtocol.CODEC_FLAC -> avcodec.AV_CODEC_ID_FLAC
            StreamProtocol.CODEC_RAW -> avcodec.AV_CODEC_ID_PCM_S16LE
            else -> throw IllegalArgumentException("Unsupported audio codec 0x%08x".format(codecId))
        }

        private fun errorString(code: Int): String {
            val buf = BytePointer(256L)
            return try {
                avutil.av_strerror(code, buf, 256)
                buf.string
            } finally {
                buf.close()
            }
        }
    }
}
