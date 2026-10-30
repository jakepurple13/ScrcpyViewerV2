package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkScannerTest {
    private fun subnet(ip: String, prefix: Int) = Subnet(NetworkScanner.ipToLong(ip), prefix)

    @Test
    fun `slash 24 yields 253 hosts without our own address`() {
        val hosts = NetworkScanner.hostsIn(subnet("192.168.1.10", 24))
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.10" in hosts)
    }

    @Test
    fun `wider networks narrow to the slash 24 around us`() {
        val hosts = NetworkScanner.hostsIn(subnet("10.0.5.77", 8))
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.0.5.") })
    }

    @Test
    fun `narrow networks keep their own range`() {
        val hosts = NetworkScanner.hostsIn(subnet("192.168.1.70", 26))
        assertEquals(61, hosts.size)
        assertEquals("192.168.1.65", hosts.first())
        assertEquals("192.168.1.126", hosts.last())
    }

    @Test
    fun `ip conversion round trips`() {
        assertEquals("192.168.0.255", NetworkScanner.longToIp(NetworkScanner.ipToLong("192.168.0.255")))
    }

    @Test
    fun `scan returns responding hosts sorted and reports progress`() = runBlocking {
        val scanner = NetworkScanner(
            subnets = { listOf(subnet("192.168.1.10", 24)) },
            probe = { host, port, _ -> port == 5555 && host in setOf("192.168.1.100", "192.168.1.9", "192.168.1.20") },
        )
        val found = ArrayList<String>()
        var last = 0 to 0
        val result = scanner.scan(5555, onProgress = { d, t -> last = d to t }, onFound = { found += it })
        assertEquals(listOf("192.168.1.9", "192.168.1.20", "192.168.1.100"), result)
        assertEquals(3, found.size)
        assertEquals(253 to 253, last)
    }

    @Test
    fun `scan finds nothing when no host answers`() = runBlocking {
        val scanner = NetworkScanner(subnets = { listOf(subnet("172.16.0.5", 24)) }, probe = { _, _, _ -> false })
        assertEquals(emptyList(), scanner.scan(5555))
    }

    @Test
    fun `overlapping networks are probed once`() {
        val scanner = NetworkScanner(subnets = { listOf(subnet("192.168.1.10", 24), subnet("192.168.1.11", 24)) })
        assertEquals(scanner.hosts().size, scanner.hosts().distinct().size)
    }
}
