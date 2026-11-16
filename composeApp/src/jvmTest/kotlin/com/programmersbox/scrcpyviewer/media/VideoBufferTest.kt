package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.RecordingVideoSink
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VideoBufferTest {
    private val data = byteArrayOf(1) // shared: MediaPacket equality compares the array by identity
    private fun media(ptsUs: Long) = MediaPacket(ptsUs, isConfig = false, isKeyFrame = false, data = data)
    private val config = MediaPacket(0, isConfig = true, isKeyFrame = false, data = byteArrayOf(9))
    private fun packet(p: MediaPacket) = VideoBufferQueue.Item.Packet(p)

    @Test
    fun `frames are released pts plus the buffer later on the system clock`() {
        val q = VideoBufferQueue(delayUs = 100_000)
        q.push(packet(config), nowUs = 1_000_000)
        assertEquals(packet(config), q.poll(1_000_000), "config packets do not wait")
        // First frame: as soon as possible (first_frame_asap); it sets the clock offset to 1_000_000.
        q.push(packet(media(0)), nowUs = 1_000_000)
        assertEquals(packet(media(0)), q.poll(1_000_000))
        // The next frame arrives on time: due at its pts on the system clock + 100 ms.
        q.push(packet(media(33_000)), nowUs = 1_033_000)
        assertNull(q.poll(1_033_000))
        val deadline = q.nextDeadline(1_033_000)!!
        assertTrue(deadline in 1_120_000..1_133_000, "deadline $deadline") // the running average moved a bit
        assertNull(q.poll(deadline - 1))
        assertEquals(packet(media(33_000)), q.poll(deadline))
        assertTrue(q.isEmpty)
    }

    @Test
    fun `a frame never waits more than the buffer after reaching the head`() {
        val q = VideoBufferQueue(delayUs = 100_000)
        q.push(packet(media(0)), nowUs = 0)
        q.poll(0)
        // A pts far ahead of the clock (the device clock jumped): capped at head time + 100 ms.
        q.push(packet(media(10_000_000)), nowUs = 10_000)
        assertNull(q.poll(10_000))
        assertNull(q.poll(109_999))
        assertEquals(packet(media(10_000_000)), q.poll(110_000))
    }

    @Test
    fun `session changes keep their place and do not wait`() {
        val q = VideoBufferQueue(delayUs = 50_000)
        q.push(packet(media(0)), nowUs = 0)
        q.poll(0)
        q.push(packet(media(20_000)), nowUs = 20_000)
        q.push(VideoBufferQueue.Item.Session(1080, 2400), nowUs = 21_000)
        assertNull(q.poll(21_000), "behind a waiting frame")
        val due = q.nextDeadline(21_000)!!
        assertEquals(packet(media(20_000)), q.poll(due))
        assertEquals(VideoBufferQueue.Item.Session(1080, 2400), q.poll(due))
    }

    @Test
    fun `DelayedVideoSink delivers late in order and close drops the rest`() {
        val target = RecordingVideoSink()
        var now = 0L
        val sink = DelayedVideoSink(target, delayMs = 60_000, nowUs = { synchronized(this) { now } })
        sink.onMeta(VideoCodecMeta(0, 10, 10))
        assertEquals(1, target.metas.size, "meta goes straight through")
        sink.onPacket(media(0)) // first frame: at once
        val start = System.nanoTime()
        while (target.packets.isEmpty() && System.nanoTime() - start < 5_000_000_000) Thread.sleep(5)
        assertEquals(listOf(media(0)), target.packets.toList())
        synchronized(this) { now = 1_000 }
        sink.onPacket(media(1_000)) // waits 60 s on the fake clock
        Thread.sleep(100)
        assertEquals(1, target.packets.size)
        sink.close()
        assertEquals(1, target.packets.size, "queued packets are dropped")
        assertTrue(target.closed)
    }

    @Test
    fun `a failing delegate is reported, not only logged`() {
        val target = RecordingVideoSink(throwOnPacket = true)
        val failure = java.util.concurrent.CompletableFuture<Throwable>()
        val sink = DelayedVideoSink(target, delayMs = 50, onFailure = { failure.complete(it) })
        sink.onPacket(media(0))
        val cause = failure.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("sink bug", cause.message)
        val start = System.nanoTime()
        while (!target.closed && System.nanoTime() - start < 5_000_000_000) Thread.sleep(5)
        assertTrue(target.closed)
        sink.close()
    }

    @Test
    fun `forOptions wraps only with a positive video-buffer`() {
        val target = RecordingVideoSink()
        assertSame(target, DelayedVideoSink.forOptions(target, OptionValues(emptyMap())))
        assertSame(target, DelayedVideoSink.forOptions(target, OptionValues(mapOf("video-buffer" to "0"))))
        val wrapped = DelayedVideoSink.forOptions(target, OptionValues(mapOf("video-buffer" to "50")))
        assertTrue(wrapped is DelayedVideoSink)
        wrapped.close()
    }
}
