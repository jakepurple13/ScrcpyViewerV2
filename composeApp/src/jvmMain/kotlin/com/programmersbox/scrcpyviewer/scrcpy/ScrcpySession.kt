package com.programmersbox.scrcpyviewer.scrcpy

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.media.RecordResult
import com.programmersbox.scrcpyviewer.media.Recorder
import com.programmersbox.scrcpyviewer.media.SessionRecording
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import javax.sound.sampled.LineUnavailableException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Receives the video stream: [onMeta] once, then packets, with [onSession] whenever the size changes mid-stream. */
interface VideoSink {
    fun onMeta(meta: VideoCodecMeta)
    fun onSession(width: Int, height: Int)
    fun onPacket(p: MediaPacket)
    fun close()
}

/** Receives the audio stream: [onCodec] once, then packets. */
interface AudioSink {
    fun onCodec(codecId: Int)
    fun onPacket(p: MediaPacket)
    fun close()
}

enum class SessionStatus { Connecting, Running, Disconnected, Failed }

/** The recording in progress: its file, and when it started ([System.nanoTime]). */
data class RecordingInfo(val file: Path, val startedNanos: Long)

/**
 * One mirroring session with one device. It starts connecting on construction.
 *
 * [decoderFactory] is called once the video codec meta is read and [audioFactory] once the audio codec id is read;
 * either may return null (no playback sink). The created sinks receive the session so they can publish
 * [frames]/[fps] through [publishFrame]/[publishFps]. More sinks (a recorder) can be attached with
 * [addVideoSink]/[addAudioSink]; a sink added late gets the meta/codec and the last config packet replayed. A sink
 * that throws is logged, closed and removed; the session keeps going, except for the video playback sink (the
 * decoder): a decoder that cannot be created or throws fails the session ([videoPlaybackFailed]), since no frame can
 * be shown any more.
 *
 * Any reader hitting EOF or an I/O error (or the control writer failing) sets [SessionStatus.Disconnected] and tears
 * the session down once: the sockets close and the readers stop, sinks are closed, recorders finish their files,
 * then [ServerConnection.cleanup] runs. A launch failure sets
 * [SessionStatus.Failed]. Nothing thrown inside escapes to [scope]: the session runs in its own [SupervisorJob]
 * (a child of [scope]'s job) with a [CoroutineExceptionHandler].
 *
 * Recording ([startRecording]) attaches a [Recorder] to both streams; `record` in [values] starts one when the
 * session starts, and `time-limit` stops each recording after that many seconds. How each one ends is sent to
 * [recordingResults]. Teardown waits for the recorder to write the trailer.
 */
class ScrcpySession(
    val serial: String,
    val values: OptionValues,
    private val launcher: ConnectionStarter,
    private val decoderFactory: (VideoCodecMeta, ScrcpySession) -> VideoSink?,
    private val audioFactory: (Int, ScrcpySession) -> AudioSink?,
    scope: CoroutineScope,
    /** Where a relative `record` path goes (`Settings.captureDir`); null keeps it relative to the working dir. */
    private val captureDir: () -> Path? = { null },
) {
    private val _status = MutableStateFlow(SessionStatus.Connecting)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _frameSize = MutableStateFlow<IntSize?>(null)
    /** Video size from the session headers (initial and on every mid-stream resize/rotation). */
    val frameSize: StateFlow<IntSize?> = _frameSize.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    /** Decoded frames per second, published by the video sink (Task 12). */
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private val _frames = MutableStateFlow<VideoFrame?>(null)
    /** Latest decoded frame, published by the video sink through [publishFrame]. Read pixels with [VideoFrame.use]. */
    val frames: StateFlow<VideoFrame?> = _frames.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    /** Device name from the device meta, once connected. */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _audioDisabledByDevice = MutableStateFlow(false)
    /** The device sent no audio (codec id 0 = disabled, 1 = error) and `require-audio` is off: video only. */
    val audioDisabledByDevice: StateFlow<Boolean> = _audioDisabledByDevice.asStateFlow()

    private val _audioPlaybackError = MutableStateFlow<String?>(null)
    /**
     * Why the audio stream is not played ("No audio output device"), when the playback sink could not be created
     * and `require-audio` is off (with it, the session fails instead). The stream is still read, for recording.
     */
    val audioPlaybackError: StateFlow<String?> = _audioPlaybackError.asStateFlow()

    private val _deviceMessages = MutableSharedFlow<DeviceMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Messages from the device on the control socket (clipboard, clipboard acks, UHID output). */
    val deviceMessages: SharedFlow<DeviceMessage> = _deviceMessages.asSharedFlow()

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(
        scope.coroutineContext + job + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            log.log(System.Logger.Level.ERROR, "Uncaught error in session $serial", t)
        },
    )
    /** Parent of the stream readers and the control writer; cancelled on teardown. */
    private val streamsJob = SupervisorJob(job)
    private val streamsScope = CoroutineScope(sessionScope.coroutineContext + streamsJob)

    private val controlEnabled = "no-control" !in values.entries
    private val controlQueue = Channel<ControlMessage>(CONTROL_QUEUE_CAPACITY)

    private val videoSinks = CopyOnWriteArrayList<VideoSink>()
    private val audioSinks = CopyOnWriteArrayList<AudioSink>()
    private val videoLock = Any()
    private val audioLock = Any()
    private var videoMeta: VideoCodecMeta? = null
    /** The sink [decoderFactory] made; its failure fails the session. */
    @Volatile private var playbackSink: VideoSink? = null
    /**
     * The video is shown (as `OptionValues.hasVideoPlayback`). Without it a decoder failure is only logged, as for
     * any sink: a recording-only session has nothing to show and keeps recording.
     */
    private val videoPlayback =
        "no-video" !in values.entries && "no-video-playback" !in values.entries && "no-playback" !in values.entries
    @Volatile private var videoCodec: Int? = null
    private var videoConfig: MediaPacket? = null
    private var videoPacketSeen = false
    private var audioCodec: Int? = null
    private var audioConfig: MediaPacket? = null

    private val recordLock = Any()
    /** The recording [recording] describes; guarded by [recordLock], which is never held while calling a sink. */
    private var activeRecording: SessionRecording? = null
    /** Started recordings whose recorder has not ended yet (teardown waits for them). */
    private val unfinishedRecordings = CopyOnWriteArrayList<SessionRecording>()
    @Volatile private var videoAbsent = false
    @Volatile private var audioAbsent = false
    private val _recording = MutableStateFlow<RecordingInfo?>(null)
    /** The recording in progress, or null. */
    val recording: StateFlow<RecordingInfo?> = _recording.asStateFlow()

    /** A started recording has not finished its file yet (it may be stopping in the background). */
    val hasUnfinishedRecording: Boolean get() = unfinishedRecordings.isNotEmpty()
    private val recordingResultChannel = Channel<RecordResult>(Channel.UNLIMITED)
    /**
     * How each recording ended (saved, or the error), for one collector (the [MirrorController] relays it app-wide).
     * Completes after teardown, once the recorders have finished.
     */
    val recordingResults: Flow<RecordResult> = recordingResultChannel.receiveAsFlow()

    private val tornDown = AtomicBoolean(false)
    private val teardownDone = CompletableDeferred<Unit>()
    @Volatile private var connection: ServerConnection? = null
    // Both jobs are LAZY and started at the end of init, so teardown (which may run on another thread as soon as
    // the launch fails) never sees an unassigned property.
    private val connectJob: Job = sessionScope.launch(start = CoroutineStart.LAZY) { connect() }

    /** Tears down when [scope] is cancelled before [close] (sockets, process and tunnel are never leaked). */
    private val cancelGuard: Job = sessionScope.launch(start = CoroutineStart.LAZY) {
        try {
            awaitCancellation()
        } finally {
            if (tornDown.compareAndSet(false, true)) {
                _status.update { if (it == SessionStatus.Failed) it else SessionStatus.Disconnected }
                teardown()
            }
        }
    }

    /** Queues [msg] for the control socket. Dropped when control is disabled, the queue is full, or after teardown. */
    fun send(msg: ControlMessage) {
        if (!controlEnabled) return
        if (controlQueue.trySend(msg).isFailure && !tornDown.get()) {
            log.log(System.Logger.Level.WARNING, "Control queue full, dropping $msg")
        }
    }

    /** For the video sink: publishes the latest decoded frame. */
    fun publishFrame(frame: VideoFrame?) {
        _frames.value = frame
    }

    /**
     * Releases the last frame's native pixels (up to ~16 MB that the GC does not see) and clears [frames]. The decoder
     * keeps that frame after the session ends, for the Disconnected scrim; call this once nothing shows the session
     * any more (its window closed, or a Reconnect replaced it), after [close].
     */
    fun releaseLastFrame() {
        _frames.getAndUpdate { null }?.release()
    }

    /**
     * For the video sink: decoding stopped for good (the decoder threw), so the window would stay frozen, or black,
     * while saying Running. Fails the session with [cause] instead; the window shows it on the Disconnected card.
     */
    fun videoPlaybackFailed(cause: Throwable) {
        if (!videoPlayback) return
        val codec = videoCodec?.let { " (${VideoDecoder.codecName(it)})" }.orEmpty()
        fail("Video decoding failed$codec: ${cause.message ?: cause}")
    }

    /** For the video sink: publishes the decoded frame rate. */
    fun publishFps(value: Double) {
        _fps.value = value
    }

    fun addVideoSink(sink: VideoSink) = synchronized(videoLock) {
        val meta = videoMeta
        if (meta != null) {
            val size = _frameSize.value
            val current = if (size != null) meta.copy(width = size.width, height = size.height) else meta
            if (deliver(sink, sink::close) { onMeta(current); videoConfig?.let(::onPacket) } != null) return@synchronized
        }
        videoSinks += sink
    }

    fun removeVideoSink(sink: VideoSink) {
        videoSinks -= sink
    }

    fun addAudioSink(sink: AudioSink) = synchronized(audioLock) {
        val codec = audioCodec
        if (codec != null) {
            if (deliver(sink, sink::close) { onCodec(codec); audioConfig?.let(::onPacket) } != null) return@synchronized
        }
        audioSinks += sink
    }

    fun removeAudioSink(sink: AudioSink) {
        audioSinks -= sink
    }

    /**
     * Starts recording both streams to [file] in [format] (default: the `record-format` option, else the file
     * extension), creating the parent directory. Does file I/O: call it off the UI thread. Throws when a recording is
     * already running, the session is closed, the format is unknown, or the directory cannot be written; later
     * failures (an unsupported stream for the container, a write error) arrive in [recordingResults].
     *
     * Started mid-stream, it asks the device for a new key frame (`ResetVideo`) when control is enabled.
     */
    fun startRecording(file: Path, format: String? = null) {
        val fmt = format ?: Recorder.formatFor(file, values.entries["record-format"])
            ?: throw IllegalArgumentException("No format specified for \"$file\" (try with --record-format=mkv)")
        val orientation = recordOrientation()
        file.toAbsolutePath().parent?.let { dir ->
            Files.createDirectories(dir)
            if (!Files.isWritable(dir)) throw IOException("Cannot write to $dir")
        }
        val rec = synchronized(recordLock) {
            check(!tornDown.get()) { "The session is closed" }
            check(activeRecording == null) { "Already recording to ${activeRecording?.file}" }
            SessionRecording(file, fmt, orientation, onEnded = ::recordingEnded).also {
                activeRecording = it
                unfinishedRecordings += it
                _recording.value = RecordingInfo(file, System.nanoTime())
            }
        }
        // Before the sinks: a recording that fails at once cancels it in recordingEnded.
        values.entries["time-limit"]?.toLongOrNull()?.takeIf { it > 0 }?.let { seconds ->
            rec.timeLimitJob = sessionScope.launch {
                delay(seconds * 1000)
                stopRecording(rec)
            }
        }
        // Any codec (VP8/VP9 have no config packet): a video packet was already streamed.
        val midStream = synchronized(videoLock) { videoPacketSeen }
        // Outside recordLock: the sinks take their own locks.
        addVideoSink(rec.videoSink)
        addAudioSink(rec.audioSink)
        if (videoAbsent) rec.videoAbsent()
        if (audioAbsent) rec.audioAbsent()
        if (midStream && !rec.finished) send(ControlMessage.ResetVideo)
    }

    /** Stops the recording in progress, if any; the recorder finishes the file in the background. */
    fun stopRecording() {
        synchronized(recordLock) { activeRecording }?.let(::stopRecording)
    }

    private fun stopRecording(rec: SessionRecording) {
        synchronized(recordLock) {
            if (activeRecording !== rec) return
            activeRecording = null
            _recording.value = null
        }
        removeVideoSink(rec.videoSink)
        removeAudioSink(rec.audioSink)
        rec.stop()
    }

    /** From the recorder thread or a sink call: never under [recordLock]. */
    private fun recordingEnded(rec: SessionRecording, result: RecordResult) {
        synchronized(recordLock) {
            if (activeRecording === rec) {
                activeRecording = null
                _recording.value = null
            }
        }
        unfinishedRecordings -= rec
        rec.timeLimitJob?.cancel()
        removeVideoSink(rec.videoSink)
        removeAudioSink(rec.audioSink)
        recordingResultChannel.trySend(result)
    }

    /** `record-orientation`, else `orientation` (v5.0 `--orientation` sets both); recording rejects flips. */
    private fun recordOrientation(): Int {
        val value = values.entries["record-orientation"] ?: values.entries["orientation"] ?: return 0
        return value.toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) }
            ?: throw IllegalArgumentException("Record orientation only supports rotation, not flipping: $value")
    }

    private fun markVideoAbsent() {
        videoAbsent = true
        synchronized(recordLock) { activeRecording }?.videoAbsent()
    }

    private fun markAudioAbsent() {
        audioAbsent = true
        synchronized(recordLock) { activeRecording }?.audioAbsent()
    }

    /** Stops the session and waits until teardown has finished. Safe to call more than once and from any state. */
    suspend fun close() {
        if (tornDown.compareAndSet(false, true)) {
            _status.update { if (it == SessionStatus.Failed) it else SessionStatus.Disconnected }
            teardown()
        } else {
            teardownDone.await()
        }
    }

    private suspend fun connect() {
        values.entries["record"]?.takeIf { it.isNotBlank() }?.let(::startInitialRecording)
        val conn = try {
            launcher.start(serial, values)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.log(System.Logger.Level.WARNING, "Could not start session $serial", t)
            fail(t.message ?: t.toString())
            return
        }
        connection = conn
        if (tornDown.get()) {
            conn.cleanup() // close() raced with the launch
            return
        }
        _deviceName.value = conn.deviceMeta.name
        if (conn.video == null) markVideoAbsent()
        if (conn.audio == null) markAudioAbsent()
        conn.video?.let { s -> launchStream("video") { readVideo(s) } }
        // Returns without disconnecting when the device disabled audio (scrcpy keeps mirroring video).
        conn.audio?.let { s -> launchStream("audio", disconnectOnReturn = false) { readAudio(s) } }
        conn.control?.let { s ->
            launchStream("control writer") { writeControl(s.getOutputStream()) }
            launchStream("device messages") { readDeviceMessages(s) }
        }
        _status.update { if (it == SessionStatus.Connecting) SessionStatus.Running else it }
        if ("turn-screen-off" in values.entries) send(ControlMessage.SetDisplayPower(false))
        // scrcpy.c: START_APP right after connecting, after turn-screen-off.
        values.entries["start-app"]?.takeIf { it.isNotBlank() }?.let { send(ControlMessage.StartApp(it)) }
    }

    /**
     * `record` option, resolved against [captureDir] when relative: a failure is reported in [recordingResults];
     * mirroring goes on.
     */
    private fun startInitialRecording(path: String) {
        val file = try {
            Paths.get(path).let { p -> if (p.isAbsolute) p else captureDir()?.resolve(p) ?: p }
        } catch (e: InvalidPathException) {
            recordingResultChannel.trySend(RecordResult.Failed(null, "Invalid record path: $path"))
            return
        }
        try {
            startRecording(file)
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Could not start recording to $file", e)
            recordingResultChannel.trySend(RecordResult.Failed(file, e.message ?: e.toString()))
        }
    }

    /** Runs [block] under [streamsJob]; its end (or failure) disconnects the session. */
    private fun launchStream(name: String, disconnectOnReturn: Boolean = true, block: suspend () -> Unit) {
        streamsScope.launch {
            try {
                block()
                if (disconnectOnReturn) disconnected(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                disconnected(if (tornDown.get()) null else "$name: ${e.message ?: e}")
            } catch (t: Throwable) {
                log.log(System.Logger.Level.ERROR, "Unexpected error in $name of $serial", t)
                disconnected("$name: ${t.message ?: t}")
            }
        }
    }

    private fun readVideo(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), STREAM_BUFFER))
        val meta = readVideoCodecMeta(input)
        _frameSize.value = IntSize(meta.width, meta.height)
        videoCodec = meta.codecId
        val playback = try {
            decoderFactory(meta, this)
        } catch (t: Throwable) {
            // No FFmpeg decoder for the codec, or it would not open: nothing could ever be shown.
            log.log(System.Logger.Level.ERROR, "Could not create the video decoder of $serial", t)
            if (videoPlayback) {
                fail("Cannot decode ${VideoDecoder.codecName(meta.codecId)} video: ${t.message ?: t}")
                return
            }
            null
        }
        playback?.let {
            playbackSink = it
            videoSinks += it
        }
        synchronized(videoLock) {
            videoMeta = meta
            dispatchVideo { onMeta(meta) }
        }
        while (true) {
            when (val item = readStreamItem(input) ?: return) {
                is StreamItem.Session -> {
                    _frameSize.value = IntSize(item.width, item.height)
                    synchronized(videoLock) { dispatchVideo { onSession(item.width, item.height) } }
                }
                is StreamItem.Packet -> synchronized(videoLock) {
                    if (item.packet.isConfig) videoConfig = item.packet
                    videoPacketSeen = true
                    dispatchVideo { onPacket(item.packet) }
                }
            }
        }
    }

    /**
     * Returns normally only when the device sent no audio (codec id 0 or 1): then the session keeps mirroring video
     * and flags [audioDisabledByDevice], or fails with `require-audio`.
     */
    private fun readAudio(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), STREAM_BUFFER))
        val codec = readAudioCodecId(input)
        if (codec == StreamProtocol.CODEC_DISABLED || codec == StreamProtocol.CODEC_ERROR) {
            val what = if (codec == StreamProtocol.CODEC_DISABLED) "disabled on the device" else "failed on the device"
            if ("require-audio" in values.entries) {
                fail("Audio $what (require-audio is set)")
                return
            }
            log.log(System.Logger.Level.WARNING, "Audio $what ($serial), mirroring video only")
            _audioDisabledByDevice.value = true
            markAudioAbsent()
            return
        }
        val player = try {
            audioFactory(codec, this)
        } catch (t: Throwable) {
            log.log(System.Logger.Level.WARNING, "Could not open audio playback of $serial", t)
            // Java Sound: IllegalArgumentException (no matching line) or LineUnavailableException (none free).
            val note = if (t is IllegalArgumentException || t is LineUnavailableException) {
                "No audio output device"
            } else {
                "Audio playback failed"
            }
            if ("require-audio" in values.entries) {
                fail("$note (require-audio is set): ${t.message ?: t}")
                return
            }
            _audioPlaybackError.value = note
            null
        }
        player?.let { audioSinks += it }
        synchronized(audioLock) {
            audioCodec = codec
            dispatchAudio { onCodec(codec) }
        }
        while (true) {
            val packet = readPacket(input) ?: throw IOException("Audio stream ended")
            synchronized(audioLock) {
                if (packet.isConfig) audioConfig = packet
                dispatchAudio { onPacket(packet) }
            }
        }
    }

    /** A message whose serialize() throws (e.g. out-of-range pressure) is logged and dropped. */
    private suspend fun writeControl(out: OutputStream) {
        for (msg in controlQueue) {
            val bytes = try {
                msg.serialize()
            } catch (t: Throwable) {
                log.log(System.Logger.Level.WARNING, "Dropping control message $msg", t)
                continue
            }
            out.write(bytes)
            out.flush()
        }
    }

    private fun readDeviceMessages(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        while (true) {
            val msg = readDeviceMessage(input) ?: return
            _deviceMessages.tryEmit(msg)
        }
    }

    private fun dispatchVideo(call: VideoSink.() -> Unit) {
        for (sink in videoSinks) {
            val failure = deliver(sink, sink::close, call) ?: continue
            videoSinks -= sink
            if (sink === playbackSink) videoPlaybackFailed(failure)
        }
    }

    private fun dispatchAudio(call: AudioSink.() -> Unit) {
        for (sink in audioSinks) if (deliver(sink, sink::close, call) != null) audioSinks -= sink
    }

    /** Calls [call] on [sink]; on failure logs, closes the sink and returns what it threw (null on success). */
    private fun <S> deliver(sink: S, close: () -> Unit, call: S.() -> Unit): Throwable? =
        try {
            sink.call()
            null
        } catch (t: Throwable) {
            log.log(System.Logger.Level.ERROR, "Removing failing sink $sink of $serial", t)
            runCatching(close)
            t
        }

    private fun fail(message: String) {
        if (!tornDown.compareAndSet(false, true)) return // already closed
        _error.value = message
        _status.value = SessionStatus.Failed
        launchTeardown()
    }

    private fun disconnected(reason: String?) {
        if (!tornDown.compareAndSet(false, true)) return
        if (reason != null) _error.value = reason
        _status.update { if (it == SessionStatus.Failed) it else SessionStatus.Disconnected }
        launchTeardown()
    }

    /** ATOMIC: once [tornDown] is set, teardown must run even if [scope] was cancelled meanwhile. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun launchTeardown() {
        sessionScope.launch(start = CoroutineStart.ATOMIC) { teardown() }
    }

    /**
     * Runs exactly once (guarded by [tornDown]). Order matters for recordings: the sockets are closed (which
     * unblocks the readers) and the recorders finish their files before [ServerConnection.cleanup], whose adb
     * calls and server wait may take seconds.
     */
    private suspend fun teardown() = withContext(NonCancellable) {
        try {
            controlQueue.close()
            connectJob.cancelAndJoin()
            val conn = connection
            conn?.let { listOfNotNull(it.video, it.audio, it.control).forEach { s -> runCatching { s.close() } } }
            streamsJob.cancelAndJoin()
            synchronized(videoLock) {
                videoSinks.forEach { runCatching { it.close() } }
                videoSinks.clear()
            }
            synchronized(audioLock) {
                audioSinks.forEach { runCatching { it.close() } }
                audioSinks.clear()
            }
            synchronized(recordLock) {
                activeRecording = null
                _recording.value = null
            }
            // The sink close() calls above stopped them; wait for the trailers.
            for (rec in unfinishedRecordings) {
                rec.stop()
                if (!runInterruptible(Dispatchers.IO) { rec.join(RECORDER_JOIN_MS) }) {
                    log.log(System.Logger.Level.WARNING, "Recorder of $serial still writing ${rec.file}")
                }
            }
            conn?.cleanup() // tunnel, server process
        } finally {
            recordingResultChannel.close() // buffered results are still delivered
            cancelGuard.cancel()
            teardownDone.complete(Unit)
            job.complete()
        }
    }

    init {
        cancelGuard.start()
        connectJob.start()
        if (!job.isActive && tornDown.compareAndSet(false, true)) {
            // scope is already cancelled, so the LAZY jobs may never have run; teardown still runs (ATOMIC).
            _status.value = SessionStatus.Disconnected
            launchTeardown()
        }
    }

    private companion object {
        const val CONTROL_QUEUE_CAPACITY = 1024
        const val STREAM_BUFFER = 1 shl 16
        const val RECORDER_JOIN_MS = 10_000L
        val log: System.Logger = System.getLogger("scrcpyviewer.ScrcpySession")
    }
}
