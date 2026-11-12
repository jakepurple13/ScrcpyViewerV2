package com.programmersbox.scrcpyviewer

import kotlin.test.Test
import kotlin.test.assertEquals

class CoroutinesClasspathTest {
    /**
     * Jewel's icon libraries (the old title bar) depended on IntelliJ's coroutines fork, which has the same classes as
     * kotlinx-coroutines but lacks newer methods such as `Job.cancel$default`; whichever jar comes first wins, and the
     * fork came first in the packaged app, so cancelling a job (Wi-Fi pairing) crashed. Kept as a guard.
     */
    @Test
    fun `only one kotlinx-coroutines-core on the classpath`() {
        val cores = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { java.io.File(it).name }
            .filter { it.startsWith("kotlinx-coroutines-core") }
        assertEquals(1, cores.size, "kotlinx-coroutines-core jars: $cores")
    }
}
