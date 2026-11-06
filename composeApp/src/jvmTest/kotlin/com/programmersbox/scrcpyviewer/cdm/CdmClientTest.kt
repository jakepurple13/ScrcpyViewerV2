package com.programmersbox.scrcpyviewer.cdm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdmClientTest {
    private val cdm = FakeCdm()
    private val client = cdm.client()

    @AfterTest
    fun tearDown() = cdm.close()

    @Test
    fun `no port file means CDM is closed and nothing fails`() = runBlocking {
        val closed = CdmClient(portFile = Files.createTempDirectory("cdm").resolve("port"))
        assertFalse(closed.isRunning())
        assertEquals(AcquireOutcome.NotRunning, closed.acquire("scrcpy-vwr:A", "A"))
        assertNull(closed.renew("scrcpy-vwr:A"))
        assertNull(closed.release("scrcpy-vwr:A"))
        assertNull(closed.devices())
    }

    @Test
    fun `a port nobody listens on means CDM is closed`() = runBlocking {
        val port = cdm.port
        cdm.close()
        val file = Files.createTempFile("cdm-port", "").also { Files.writeString(it, " $port \n") }
        assertEquals(AcquireOutcome.NotRunning, CdmClient(portFile = file).acquire("scrcpy-vwr:A", "A"))
    }

    @Test
    fun `free device is granted, renewed and released`() = runBlocking {
        val granted = client.acquire("scrcpy-vwr:A", "A")
        assertEquals(AcquireOutcome.Granted("A", "Phone A"), granted)
        assertEquals(listOf("HOLDING android A A"), client.status("scrcpy-vwr:A"))
        assertEquals(true, client.renew("scrcpy-vwr:A"))
        assertEquals(true, client.release("scrcpy-vwr:A"))
        assertEquals(false, client.renew("scrcpy-vwr:A"))
        assertEquals(false, client.release("scrcpy-vwr:A"))
    }

    @Test
    fun `busy device reports the queue position, then is granted once released`() = runBlocking {
        cdm.hold("A", "claude-code:1")
        val positions = ArrayList<Int>()
        val wait = async(Dispatchers.IO) { client.acquire("scrcpy-vwr:A", "A") { synchronized(positions) { positions += it } } }
        withTimeout(5_000) { while (cdm.queued().isEmpty()) delay(10) }
        cdm.takeBack("A")
        assertEquals(AcquireOutcome.Granted("A", "Phone A"), withTimeout(5_000) { wait.await() })
        assertEquals(listOf(1), positions)
        assertEquals("scrcpy-vwr:A", cdm.holderOf("A"))
    }

    @Test
    fun `cancelling while queued leaves the queue`() = runBlocking {
        cdm.hold("A", "claude-code:1")
        val wait = async(Dispatchers.IO) { client.acquire("scrcpy-vwr:A", "A") }
        withTimeout(5_000) { while (cdm.queued().isEmpty()) delay(10) }
        wait.cancel()
        withTimeout(5_000) { while (cdm.queued().isNotEmpty()) delay(10) }
        assertEquals(listOf("NONE"), client.status("scrcpy-vwr:A"))
    }

    @Test
    fun `queue timeout is reported`() = runBlocking {
        cdm.hold("A", "claude-code:1")
        assertEquals(AcquireOutcome.Timeout, withTimeout(5_000) { client.acquire("scrcpy-vwr:A", "A", timeoutSec = 1) })
    }

    @Test
    fun `unknown device is refused with CDM's reason`() = runBlocking {
        val refused = client.acquire("scrcpy-vwr:Z", "Z")
        assertIs<AcquireOutcome.Refused>(refused)
        assertEquals("No device matches \"Z\".", refused.reason)
    }

    @Test
    fun `devices and events carry the holder`() = runBlocking {
        cdm.hold("B", "claude-code:1")
        assertEquals("claude-code:1", client.devices()!!.devices.single { it.key == "B" }.holder!!.id)
        val event = withTimeout(5_000) { client.events().first() }
        assertEquals(listOf("A", "B"), event.devices.map { it.key })
        assertEquals("claude-code:1", event.devices[1].holder?.label)
    }

    @Test
    fun `acquire lines`() {
        val positions = ArrayList<Int>()
        assertNull(CdmClient.parseAcquireLine(".", positions::add))
        assertNull(CdmClient.parseAcquireLine("QUEUED 3", positions::add))
        assertEquals(listOf(3), positions)
        assertEquals(
            AcquireOutcome.Granted("R5CX12GDWPZ", "Galaxy A15 5G"),
            CdmClient.parseAcquireLine("GRANTED android R5CX12GDWPZ Galaxy A15 5G") {},
        )
        assertEquals(AcquireOutcome.Timeout, CdmClient.parseAcquireLine("TIMEOUT 2") {})
        assertIs<AcquireOutcome.Refused>(CdmClient.parseAcquireLine("BLOCKED") {})
        assertIs<AcquireOutcome.Refused>(CdmClient.parseAcquireLine("REMOVED") {})
    }

    @Test
    fun `server-sent events are split at blank lines`() {
        val lines = ArrayDeque(listOf("event: devices", "data: {\"a\":1}", "", ": comment", "data: x", "data: y", "", "data: cut"))
        val got = ArrayList<Pair<String, String>>()
        CdmClient.readServerSentEvents({ lines.removeFirstOrNull() }) { e, d -> got += e to d }
        assertEquals(listOf("devices" to "{\"a\":1}", "message" to "x\ny"), got)
        assertTrue(lines.isEmpty())
    }
}
