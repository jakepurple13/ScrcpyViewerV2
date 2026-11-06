package com.programmersbox.scrcpyviewer.cdm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdmHoldsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cdm = FakeCdm()
    private val holds = CdmHolds(cdm.client(), "scrcpy-vwr", scope, renewIntervalMs = 100)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cdm.close()
    }

    private suspend fun state(serial: String, until: (HoldState?) -> Boolean): HoldState? =
        withTimeout(5_000) { holds.states.first { until(holds.holderOf(serial)?.let(it::get)) } }
            .let { holds.holderOf(serial)?.let(it::get) }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    @Test
    fun `holder IDs name the device and stay readable`() {
        assertEquals("scrcpy-vwr:R5CX12GDWPZ", holds.holderFor("R5CX12GDWPZ"))
        assertEquals("scrcpy-vwr:192.168.1.5-5555", holds.holderFor("192.168.1.5:5555"))
        assertTrue(holds.isOurs("scrcpy-vwr:A"))
        assertTrue(!holds.isOurs("scrcpy-vwr-2:A"))
    }

    @Test
    fun `without CDM a lease is held unmanaged at once`() = runBlocking {
        val closed = CdmHolds(CdmClient(portFile = Files.createTempDirectory("cdm").resolve("port")), "scrcpy-vwr", scope)
        closed.lease("A")
        withTimeout(5_000) { closed.awaitReady("A") }
        assertEquals(HoldState.Held(managed = false), closed.states.value[closed.holderOf("A")])
    }

    @Test
    fun `waits in the queue, then holds`() = runBlocking {
        cdm.hold("A", "claude-code:1")
        holds.lease("A")
        assertEquals(HoldState.Waiting(1), state("A") { it == HoldState.Waiting(1) })
        val ready = async { holds.awaitReady("A") }
        cdm.takeBack("A")
        withTimeout(5_000) { ready.await() }
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
    }

    @Test
    fun `releasing while queued leaves the queue`() = runBlocking {
        cdm.hold("A", "claude-code:1")
        holds.lease("A")
        state("A") { it is HoldState.Waiting && it.position != null }
        holds.release("A")
        eventually { cdm.queued().isEmpty() }
        assertNull(holds.holderOf("A"))
    }

    @Test
    fun `leases of one device share one hold`() = runBlocking {
        holds.lease("A")
        holds.awaitReady("A")
        // A Wi-Fi switch while the mirror window holds the phone: no second acquire, no release afterwards.
        holds.withHold("A") { assertEquals("scrcpy-vwr:A", cdm.holderOf("A")) }
        delay(100)
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
        assertEquals(1, cdm.requests().count { it.startsWith("POST /cdm/acquire") })
        holds.release("A")
        eventually { cdm.holderOf("A") == null }
    }

    @Test
    fun `USB and Wi-Fi serials of one phone share its hold`() = runBlocking {
        holds.lease("A", device = "A")
        holds.lease("10.0.0.2:5555", device = "A")
        holds.awaitReady("10.0.0.2:5555")
        holds.release("A")
        delay(100)
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
        holds.release("10.0.0.2:5555")
        eventually { cdm.holderOf("A") == null }
    }

    @Test
    fun `taken back is noticed on renew`() = runBlocking {
        holds.lease("A")
        holds.awaitReady("A")
        cdm.takeBack("A")
        assertEquals(HoldState.Lost, state("A") { it == HoldState.Lost })
        assertFailsWith<DeviceNotGrantedException> { holds.awaitReady("A") }
        // Wait again
        holds.retry("A")
        withTimeout(5_000) { holds.awaitReady("A") }
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
    }

    @Test
    fun `refused device fails awaitReady with CDM's reason`() = runBlocking {
        holds.lease("Z")
        val e = assertFailsWith<DeviceNotGrantedException> { withTimeout(5_000) { holds.awaitReady("Z") } }
        assertEquals("No device matches \"Z\".", e.message)
    }

    @Test
    fun `leasing again right after a release keeps the new hold`() = runBlocking {
        holds.lease("A")
        holds.awaitReady("A")
        holds.release("A")
        holds.lease("A")
        holds.awaitReady("A")
        delay(200)
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
    }

    @Test
    fun `startup releases only this copy's stale holds`() {
        cdm.hold("A", "scrcpy-vwr:A")
        cdm.hold("B", "scrcpy-vwr-2:B")
        holds.releaseStale()
        assertNull(cdm.holderOf("A"))
        assertEquals("scrcpy-vwr-2:B", cdm.holderOf("B"))
    }

    @Test
    fun `startup releases a stale hold while another copy is queued for it`() = runBlocking {
        cdm.hold("A", "scrcpy-vwr:A")
        val other = CdmHolds(cdm.client(), "scrcpy-vwr-2", scope)
        other.lease("A")
        eventually { cdm.queued().isNotEmpty() }
        holds.releaseStale()
        withTimeout(5_000) { other.awaitReady("A") }
        assertEquals("scrcpy-vwr-2:A", cdm.holderOf("A"))
    }

    @Test
    fun `releaseAllNow releases what is held`() = runBlocking {
        holds.lease("A")
        holds.awaitReady("A")
        holds.releaseAllNow()
        assertNull(cdm.holderOf("A"))
    }
}
