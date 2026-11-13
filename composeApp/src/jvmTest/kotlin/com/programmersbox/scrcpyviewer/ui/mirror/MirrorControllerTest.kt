package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.RECORDER_START_MILLIS
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.programmersbox.scrcpyviewer.media.RecordResult
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MirrorControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = MutableStateFlow(listOf(device("A"), device("B")))
    private val launchers = HashMap<String, MutableList<LocalPairLauncher>>()
    private val clipboard = ArrayList<String>()
    @Volatile private var options = OptionValues(emptyMap())

    private fun device(serial: String, state: DeviceState = DeviceState.Device) =
        Device(serial, state, state.name.lowercase(), null, null, 1, false)

    private val controller = MirrorController(
        scope, devices,
        optionsFor = { options },
        newSession = { serial, values ->
            val launcher = LocalPairLauncher()
            launchers.getOrPut(serial) { ArrayList() } += launcher
            ScrcpySession(serial, values, launcher, { _, _ -> null }, { _, _ -> null }, scope)
        },
        setClipboard = { synchronized(clipboard) { clipboard += it } },
    )

    @AfterTest
    fun tearDown() {
        runBlocking { controller.closeAll() }
        scope.cancel()
    }

    @Test
    fun `open twice keeps one session and asks for focus`() = runBlocking<Unit> {
        val focus = scope.async { withTimeout(5_000) { controller.focusRequests.first() } }
        controller.open("A")
        val first = controller.open.value.getValue("A")
        delay(50) // let the focus collector subscribe
        controller.open("A")
        assertSame(first, controller.open.value.getValue("A"))
        assertEquals("A", focus.await())
    }

    @Test
    fun `close removes the session and closes it`() = runBlocking<Unit> {
        controller.open("A")
        controller.open("B")
        val a = controller.open.value.getValue("A")
        controller.close("A")
        assertEquals(setOf("B"), controller.open.value.keys)
        withTimeout(5_000) { a.status.first { it == SessionStatus.Disconnected } }
        val b = controller.open.value.getValue("B")
        withTimeout(5_000) { b.status.first { it == SessionStatus.Running } }
    }

    @Test
    fun `reconnect is enabled when the device is back and replaces the session`() = runBlocking<Unit> {
        controller.open("A")
        val old = controller.open.value.getValue("A")
        withTimeout(5_000) { old.status.first { it == SessionStatus.Running } }
        devices.value = listOf(device("B"))
        launchers.getValue("A").single().device.await().video!!.close() // the device side goes away
        withTimeout(5_000) { old.status.first { it == SessionStatus.Disconnected } }
        assertTrue("A" !in controller.reconnectable.value)
        controller.reconnect("A") // ignored: device not back yet
        assertSame(old, controller.open.value.getValue("A"))

        devices.value = listOf(device("A"), device("B"))
        withTimeout(5_000) { controller.reconnectable.first { "A" in it } }
        controller.reconnect("A")
        val fresh = controller.open.value.getValue("A")
        assertNotSame(old, fresh)
        withTimeout(5_000) { fresh.status.first { it == SessionStatus.Running } }
    }

    @Test
    fun `device clipboard goes to the system clipboard`() = runBlocking<Unit> {
        controller.open("A")
        val ends = launchers.getValue("A").single().device.await()
        DataOutputStream(ends.control!!.getOutputStream()).apply {
            writeByte(0); val b = "hello".toByteArray(); writeInt(b.size); write(b); flush()
        }
        withTimeout(5_000) {
            while (synchronized(clipboard) { clipboard.isEmpty() }) delay(10)
        }
        assertEquals(listOf("hello"), synchronized(clipboard) { clipboard.toList() })
    }

    @Test
    fun `a recording that ends after its window closed is still reported`() = runBlocking<Unit> {
        val file = Files.createTempFile("controller", ".mp4")
        options = OptionValues(mapOf("record" to file.toString()))
        val event = scope.async { withTimeout(RECORDER_START_MILLIS + 10_000) { controller.recordingResults.first() } }
        delay(50) // let the collector subscribe
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(RECORDER_START_MILLIS) { session.status.first { it == SessionStatus.Running } }
        assertTrue(session.hasUnfinishedRecording)
        controller.close("A") // no video was sent: the recording ends during teardown, after the window is gone
        val e = event.await()
        assertEquals("A", e.serial)
        assertIs<RecordResult.Failed>(e.result)
        Files.deleteIfExists(file)
    }
}
