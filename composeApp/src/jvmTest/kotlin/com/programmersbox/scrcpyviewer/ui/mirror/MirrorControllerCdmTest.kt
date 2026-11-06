package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.cdm.CdmHolds
import com.programmersbox.scrcpyviewer.cdm.FakeCdm
import com.programmersbox.scrcpyviewer.cdm.HoldState
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirror windows taking turns on devices through Claude Device Manager. */
class MirrorControllerCdmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cdm = FakeCdm()
    private val holds = CdmHolds(cdm.client(), "scrcpy-vwr", scope, renewIntervalMs = 100)
    private val devices = MutableStateFlow(listOf(device("A"), device("B")))
    private val started = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun device(serial: String) = Device(serial, DeviceState.Device, "device", null, null, 1, false)

    private val controller = MirrorController(
        scope, devices,
        optionsFor = { OptionValues(emptyMap()) },
        newSession = { serial, values ->
            val launcher = LocalPairLauncher()
            ScrcpySession(
                serial, values,
                { s, v ->
                    holds.awaitReady(s)
                    started += s
                    launcher.start(s, v)
                },
                { _, _ -> null }, { _, _ -> null }, scope,
            )
        },
        holds = holds,
    )

    @AfterTest
    fun tearDown() {
        runBlocking { controller.closeAll() }
        scope.cancel()
        cdm.close()
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    @Test
    fun `a busy device queues in the window and starts once granted`() = runBlocking<Unit> {
        cdm.hold("A", "claude-code:1")
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(5_000) { controller.holdStates.first { it["A"] == HoldState.Waiting(1) } }
        delay(100)
        assertTrue(started.isEmpty(), "nothing touches the device while queued")
        assertEquals(SessionStatus.Connecting, session.status.value)
        cdm.takeBack("A")
        withTimeout(5_000) { session.status.first { it == SessionStatus.Running } }
        assertEquals(listOf("A"), started.toList())
    }

    @Test
    fun `closing the window while queued leaves the queue`() = runBlocking<Unit> {
        cdm.hold("A", "claude-code:1")
        controller.open("A")
        eventually { cdm.queued().isNotEmpty() }
        controller.close("A")
        eventually { cdm.queued().isEmpty() }
        assertEquals("claude-code:1", cdm.holderOf("A"))
    }

    @Test
    fun `closing the window releases the device`() = runBlocking<Unit> {
        controller.open("A")
        val session = controller.open.value.getValue("A")
        withTimeout(5_000) { session.status.first { it == SessionStatus.Running } }
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
        controller.close("A")
        eventually { cdm.holderOf("A") == null }
    }

    @Test
    fun `taken back stops the session but keeps the window, and Wait again re-queues`() = runBlocking<Unit> {
        controller.open("A")
        val first = controller.open.value.getValue("A")
        withTimeout(5_000) { first.status.first { it == SessionStatus.Running } }
        cdm.takeBack("A")
        cdm.hold("A", "claude-code:1")
        withTimeout(5_000) { first.status.first { it == SessionStatus.Disconnected } }
        assertEquals(HoldState.Lost, controller.holdStates.value["A"])
        assertTrue("A" in controller.open.value, "the window stays open")

        controller.requeue("A")
        val second = controller.open.value.getValue("A")
        assertNotSame(first, second)
        withTimeout(5_000) { controller.holdStates.first { it["A"] == HoldState.Waiting(1) } }
        cdm.takeBack("A")
        withTimeout(5_000) { second.status.first { it == SessionStatus.Running } }
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
    }

    @Test
    fun `closeAll releases every device`() = runBlocking<Unit> {
        controller.open("A")
        controller.open("B")
        eventually { cdm.holderOf("A") != null && cdm.holderOf("B") != null }
        controller.closeAll()
        eventually { cdm.holderOf("A") == null && cdm.holderOf("B") == null }
        assertNull(controller.holdStates.value["A"])
    }
}
