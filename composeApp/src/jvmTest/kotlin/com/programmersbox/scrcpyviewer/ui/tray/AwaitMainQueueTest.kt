package com.programmersbox.scrcpyviewer.ui.tray

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class AwaitMainQueueTest {
    @Test
    fun `returns only after work queued before it ran`() {
        assumeTrue("macOS-only test", Os.isMac)
        // Stands in for the block the native tray_update queues: hold the main queue, then mark it done.
        val started = CountDownLatch(1)
        val earlierRan = AtomicBoolean(false)
        ObjC.onMain {
            started.countDown()
            Thread.sleep(200)
            earlierRan.set(true)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "main queue never ran")

        assertTrue(awaitMainQueue(5_000), "main queue did not drain")
        assertTrue(earlierRan.get(), "returned before the earlier main-queue block finished")
    }
}
