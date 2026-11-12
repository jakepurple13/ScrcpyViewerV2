package com.programmersbox.scrcpyviewer.ui.common

import java.awt.Toolkit

/**
 * Keeps AWT's event queue pumping while a native dialog runs on a worker thread. Only reached from the EDT, so it
 * never starts the AWT toolkit itself; it goes away with AWT (Task 10).
 */
internal object AwtModalPump {
    fun <T> await(name: String, work: () -> T): T {
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        var result: Result<T>? = null
        val worker = Thread({
            result = runCatching(work)
            loop.exit() // safe before enter(): enter() then returns at once
        }, name)
        worker.isDaemon = true
        worker.start()
        loop.enter()
        worker.join()
        return result!!.getOrThrow()
    }
}
