package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.media.FfmpegRuns
import com.programmersbox.scrcpyviewer.telemetry.Telemetry
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShutdownTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ArrayList<ScrcpySession>()
    @Volatile private var options = OptionValues(emptyMap())
    private val controller = MirrorController(
        scope,
        MutableStateFlow(listOf(Device("A", DeviceState.Device, "device", null, null, 1, false))),
        optionsFor = { options },
        newSession = { serial, values ->
            ScrcpySession(serial, values, LocalPairLauncher(), { _, _ -> null }, { _, _ -> null }, scope).also { sessions += it }
        },
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `run does nothing until requested`() = runBlocking {
        val exits = AtomicInteger()
        val shutdown = AppShutdown(controller, exit = { exits.incrementAndGet() })
        shutdown.run()
        assertEquals(0, exits.get())
        assertEquals(AppShutdown.State.Running, shutdown.state.value)
    }

    @Test
    fun `request closes every session before exiting, once`() = runBlocking<Unit> {
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(5_000) { session.status.first { it == SessionStatus.Running } }
        val statusAtExit = ArrayList<SessionStatus>()
        val shutdown = AppShutdown(controller, exit = { statusAtExit += session.status.value })
        shutdown.request()
        shutdown.request() // a second Quit while shutting down is ignored
        assertEquals(AppShutdown.State.ShuttingDown(finishingRecording = false), shutdown.state.value)
        withTimeout(5_000) { shutdown.run() }
        assertEquals(listOf(SessionStatus.Disconnected), statusAtExit)
        assertTrue(controller.open.value.isEmpty())
    }

    @Test
    fun `a recording in progress is reported while shutting down`() = runBlocking<Unit> {
        val file = Files.createTempFile("shutdown", ".mp4")
        options = OptionValues(mapOf("record" to file.toString()))
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(RECORDER_START_MILLIS) { session.status.first { it == SessionStatus.Running } }
        val shutdown = AppShutdown(controller, exit = {})
        shutdown.request()
        assertEquals(AppShutdown.State.ShuttingDown(finishingRecording = true), shutdown.state.value)
        withTimeout(15_000) { shutdown.run() }
        assertFalse(session.hasUnfinishedRecording)
        Files.deleteIfExists(file)
    }

    @Test
    fun fontAvailable_unknownFamily_false() {
        assertFalse(com.programmersbox.scrcpyviewer.ui.theme.fontAvailable("No Such Family 9f3a"))
    }

    @Test
    fun quitHook_invokesRequestOnce() = runBlocking<Unit> {
        var installed: (() -> Unit)? = null
        val exits = AtomicInteger()
        val shutdown = AppShutdown(controller, exit = { exits.incrementAndGet() }, quitHook = { installed = it })
        shutdown.installQuitHandler(shutdown::request)
        installed!!.invoke()
        installed!!.invoke() // double quit
        assertEquals(AppShutdown.State.ShuttingDown(finishingRecording = false), shutdown.state.value)
        withTimeout(5_000) { shutdown.run() }
        assertEquals(1, exits.get())
    }

    @Test
    fun run_releasesTheQuitHookBeforeExit() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val hook = object : QuitHook {
            override fun install(onQuit: () -> Unit) = Unit
            override fun release() {
                events += "release"
            }
        }
        val shutdown = AppShutdown(controller, exit = { events += "exit" }, quitHook = hook)
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(listOf("release", "exit"), events)
    }

    @Test
    fun run_installsTheUpdateOffTheCallingThreadAfterSessionsCloseThenExits() = runBlocking<Unit> {
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(5_000) { session.status.first { it == SessionStatus.Running } }
        val caller = Thread.currentThread()
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        var installThread: Thread? = null
        var exitThread: Thread? = null
        val shutdown = AppShutdown(
            controller,
            exit = {
                exitThread = Thread.currentThread()
                events += "exit"
            },
            install = {
                installThread = Thread.currentThread()
                events += "install sessions=${controller.open.value.size} status=${session.status.value}"
            },
        )
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(listOf("install sessions=0 status=${SessionStatus.Disconnected}", "exit"), events)
        assertTrue(installThread != null && installThread !== caller, "install ran on the calling thread")
        assertEquals(caller, exitThread)
    }

    @Test
    fun `animateOut runs after sessions close and before install and exit`() = runBlocking<Unit> {
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(5_000) { session.status.first { it == SessionStatus.Running } }
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val shutdown = AppShutdown(
            controller,
            exit = { events += "exit" },
            install = { events += "install" },
            animateOut = { events += "animate sessions=${controller.open.value.size}" },
        )
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(listOf("animate sessions=0", "install", "exit"), events)
    }

    @Test
    fun `a failing animateOut does not block exit`() = runBlocking<Unit> {
        val exits = AtomicInteger()
        val shutdown = AppShutdown(controller, exit = { exits.incrementAndGet() }, animateOut = { error("overlay broke") })
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(1, exits.get())
    }

    @Test
    fun `a stuck animateOut is cut off after the timeout`() = runBlocking<Unit> {
        val exits = AtomicInteger()
        val shutdown = AppShutdown(
            controller, exit = { exits.incrementAndGet() },
            animateOut = { kotlinx.coroutines.delay(60_000) }, animateTimeoutMs = 100,
        )
        shutdown.request()
        withTimeout(2_000) { shutdown.run() }
        assertEquals(1, exits.get())
    }

    @Test
    fun `an update still installs after the animation`() = runBlocking<Unit> {
        val installs = AtomicInteger()
        val shutdown = AppShutdown(
            controller, exit = {}, install = { installs.incrementAndGet() },
            animateOut = { kotlinx.coroutines.delay(50) },
        )
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(1, installs.get())
    }

    /** A process that runs until destroyed. */
    private class HangingProcess : Process() {
        private val exited = java.util.concurrent.CountDownLatch(1)
        @Volatile var destroyed = false
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int {
            exited.await()
            return 137
        }
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int = if (exited.count > 0) throw IllegalThreadStateException() else 137
        override fun isAlive(): Boolean = exited.count > 0
        override fun destroy() {
            destroyForcibly()
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            exited.countDown()
            return this
        }
    }

    @Test
    fun `run waits for exports to clean up by themselves`() = runBlocking<Unit> {
        val runs = FfmpegRuns()
        val process = HangingProcess()
        val partial = Files.createTempFile("shutdown", ".part")
        runs.add(process)
        runs.addFile(partial)
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        // The export's own cancel: FFmpeg stopped, its partial deleted, a little later.
        scope.launch {
            kotlinx.coroutines.delay(100)
            Files.deleteIfExists(partial)
            runs.removeFile(partial)
            events += "export cleaned up"
            runs.remove(process)
        }
        val shutdown = AppShutdown(controller, exit = { events += "exit" }, ffmpegRuns = runs, ffmpegTimeoutMs = 5_000)
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(listOf("export cleaned up", "exit"), events)
        assertFalse(process.destroyed, "not forced")
    }

    @Test
    fun `run kills FFmpeg and deletes its temp files when they outlast the wait`() = runBlocking<Unit> {
        val runs = FfmpegRuns()
        val process = HangingProcess()
        val partial = Files.createTempFile("shutdown", ".part")
        val gif = Files.createTempFile("shutdown", ".gif")
        runs.add(process)
        runs.addFile(partial)
        var stateAtExit: Pair<Boolean, Boolean>? = null
        val shutdown = AppShutdown(
            controller, exit = { stateAtExit = process.destroyed to Files.exists(partial) },
            ffmpegRuns = runs, ffmpegTimeoutMs = 100,
        )
        shutdown.request()
        withTimeout(5_000) { shutdown.run() }
        assertEquals(true to false, stateAtExit, "killed and deleted before exit")
        assertEquals(0, runs.active.value)
        assertTrue(Files.exists(gif), "unregistered files are left alone")
        Files.deleteIfExists(gif)
    }

    @Test
    fun `the default FFmpeg wait is two seconds`() =
        assertEquals(2_000L, AppShutdown.DEFAULT_FFMPEG_TIMEOUT_MS)

    @Test
    fun `the default animate timeout is one second`() =
        assertEquals(1_000L, AppShutdown.DEFAULT_ANIMATE_TIMEOUT_MS)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun shutdownFlushIsCappedAt2s() = kotlinx.coroutines.test.runTest {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val stuck = object : Telemetry by Telemetry.Noop {
            override suspend fun flush() {
                events += "flush"
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val shutdown = AppShutdown(
            controller, exit = { events += "exit" }, install = { events += "install" }, telemetry = stuck,
        )
        shutdown.request()
        shutdown.run()
        assertEquals(listOf("flush", "install", "exit"), events)
        assertEquals(2_000L, testScheduler.currentTime)
    }
}
