package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
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

    @Test
    fun `the default animate timeout is one second`() =
        assertEquals(1_000L, AppShutdown.DEFAULT_ANIMATE_TIMEOUT_MS)
}
