package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import com.programmersbox.scrcpyviewer.scrcpy.VideoSink
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/**
 * v5.0 `clock.c`: estimates the offset between the stream (device pts) and the system time, both in microseconds,
 * as a running average over the last [RANGE] points (the slope is assumed to be 1).
 */
internal class StreamClock {
    var range = 0
        private set
    private var offset = 0L

    fun update(systemUs: Long, streamUs: Long) {
        if (range < RANGE) range++
        val clockWeight = range - 1
        val valueWeight = RANGE - range + 1
        offset = (offset * clockWeight + (systemUs - streamUs) * valueWeight) / RANGE
    }

    fun toSystem(streamUs: Long): Long = streamUs + offset

    private companion object {
        const val RANGE = 32
    }
}

/**
 * The scheduling of `--video-buffer` (v5.0 `video_regulator.c`), without threads so it can be driven by a fake
 * clock. Every media packet is released at its pts mapped to the system clock ([StreamClock]) plus [delayUs], but
 * never later than [delayUs] after it reached the head of the queue; the clock is re-estimated on every push, so the
 * deadline moves with it. The first media packet goes out at once (`first_frame_asap`). Config packets and session
 * changes keep their place in the queue and are released as soon as they reach its head.
 *
 * The buffering is applied to packets before decoding (v5.0 buffers decoded frames): the decoder then only holds
 * the frame on screen, and the cost of decoding later is a few milliseconds at most.
 */
internal class VideoBufferQueue(private val delayUs: Long) {
    sealed interface Item {
        data class Packet(val packet: MediaPacket) : Item
        data class Session(val width: Int, val height: Int) : Item
    }

    private class Entry(val item: Item, val waits: Boolean, val ptsUs: Long) {
        var headSinceUs: Long? = null
    }

    private val clock = StreamClock()
    private val queue = ArrayDeque<Entry>()

    val isEmpty: Boolean get() = queue.isEmpty()

    fun push(item: Item, nowUs: Long) {
        val entry = when (item) {
            is Item.Session -> Entry(item, waits = false, ptsUs = 0)
            is Item.Packet -> if (item.packet.isConfig) {
                Entry(item, waits = false, ptsUs = 0)
            } else {
                clock.update(nowUs, item.packet.ptsUs)
                Entry(item, waits = clock.range > 1, ptsUs = item.packet.ptsUs)
            }
        }
        queue.addLast(entry)
    }

    /** When the head is due, or null when the queue is empty. Marks the head as started waiting at [nowUs]. */
    fun nextDeadline(nowUs: Long): Long? {
        val head = queue.firstOrNull() ?: return null
        if (!head.waits) return nowUs
        val since = head.headSinceUs ?: nowUs.also { head.headSinceUs = it }
        return minOf(clock.toSystem(head.ptsUs) + delayUs, since + delayUs)
    }

    /** Removes and returns the head if it is due at [nowUs]. */
    fun poll(nowUs: Long): Item? {
        val deadline = nextDeadline(nowUs) ?: return null
        if (nowUs < deadline) return null
        return queue.removeFirst().item
    }

    fun clear() = queue.clear()
}

/**
 * A [VideoSink] that delays what it passes to [delegate] by `--video-buffer` (see [VideoBufferQueue]), for display
 * only: the session's other sinks (the recorder) are not affected. A dedicated thread delivers the packets; [close]
 * drops what is still queued, stops it and closes [delegate]. A [delegate] failure is logged, stops the buffer and is
 * passed to [onFailure] (the session's [com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession.videoPlaybackFailed]):
 * this thread's failures never reach the session's own sink error handling.
 */
class DelayedVideoSink(
    private val delegate: VideoSink,
    delayMs: Long,
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
    private val onFailure: (Throwable) -> Unit = {},
) : VideoSink {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = VideoBufferQueue(delayMs * 1000)
    private var stopped = false
    private val delegateClosed = AtomicBoolean(false)
    private val thread = Thread(::run, "video-buffer").apply {
        isDaemon = true
        start()
    }

    override fun onMeta(meta: VideoCodecMeta) = delegate.onMeta(meta) // before any packet: nothing to delay

    override fun onSession(width: Int, height: Int) = push(VideoBufferQueue.Item.Session(width, height))

    override fun onPacket(p: MediaPacket) = push(VideoBufferQueue.Item.Packet(p))

    private fun push(item: VideoBufferQueue.Item): Unit = lock.withLock {
        if (stopped) return
        queue.push(item, nowUs())
        changed.signal()
    }

    /** The next due item, or null once stopped. */
    private fun awaitNext(): VideoBufferQueue.Item? = lock.withLock {
        while (!stopped) {
            val now = nowUs()
            queue.poll(now)?.let { return it }
            val deadline = queue.nextDeadline(now)
            if (deadline == null) changed.await() else changed.await(deadline - now, TimeUnit.MICROSECONDS)
        }
        null
    }

    private fun run() {
        while (true) {
            val item = awaitNext() ?: return
            try {
                when (item) {
                    is VideoBufferQueue.Item.Packet -> delegate.onPacket(item.packet)
                    is VideoBufferQueue.Item.Session -> delegate.onSession(item.width, item.height)
                }
            } catch (t: Throwable) {
                log.log(System.Logger.Level.ERROR, "Delayed video packet could not be pushed, stopping", t)
                lock.withLock {
                    stopped = true
                    queue.clear()
                }
                closeDelegate()
                runCatching { onFailure(t) }
                return
            }
        }
    }

    override fun close() {
        lock.withLock {
            stopped = true
            queue.clear()
            changed.signal()
        }
        if (Thread.currentThread() !== thread) thread.join()
        closeDelegate()
    }

    private fun closeDelegate() {
        if (delegateClosed.compareAndSet(false, true)) delegate.close()
    }

    companion object {
        private val log = System.getLogger("scrcpyviewer.VideoBuffer")

        /**
         * [sink] behind a [DelayedVideoSink] when `video-buffer` is set above 0, else [sink] itself. [onFailure] gets a
         * failure of [sink] on the buffer's thread.
         */
        fun forOptions(sink: VideoSink, values: OptionValues, onFailure: (Throwable) -> Unit = {}): VideoSink {
            val ms = values.entries["video-buffer"]?.toLongOrNull()?.takeIf { it > 0 } ?: return sink
            return DelayedVideoSink(sink, ms, onFailure = onFailure)
        }
    }
}
