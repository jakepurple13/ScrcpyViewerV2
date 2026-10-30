package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class Subnet(val address: Long, val prefix: Int)

class NetworkScanner(
    private val subnets: () -> List<Subnet> = ::localSubnets,
    private val probe: suspend (host: String, port: Int, timeoutMs: Int) -> Boolean = ::tcpProbe,
    private val timeoutMs: Int = 500,
    private val concurrency: Int = 64,
) {
    suspend fun scan(
        port: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (String) -> Unit = {},
    ): List<String> {
        val hosts = hosts()
        val found = ArrayList<String>()
        var done = 0
        val gate = Semaphore(concurrency)
        onProgress(0, hosts.size)
        coroutineScope {
            hosts.map { host ->
                async {
                    val open = gate.withPermit { probe(host, port, timeoutMs) }
                    synchronized(found) {
                        if (open) {
                            found += host
                            onFound(host)
                        }
                        onProgress(++done, hosts.size)
                    }
                }
            }.awaitAll()
        }
        return found.sortedBy(::ipToLong)
    }

    internal fun hosts(): List<String> = subnets().flatMap { hostsIn(it) }.distinct()

    companion object {
        private const val MAX_HOSTS = 1022
        private const val MIN_PREFIX = 24

        fun hostsIn(subnet: Subnet): List<String> {
            val prefix = subnet.prefix.coerceIn(MIN_PREFIX, 30)
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = subnet.address and mask
            val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
            return ((network + 1) until broadcast)
                .filter { it != subnet.address }
                .take(MAX_HOSTS)
                .map(::longToIp)
        }

        fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }

        fun longToIp(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }

        /** Private IPv4 networks of every interface that is up, skipping loopback and link-local. */
        fun localSubnets(): List<Subnet> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.interfaceAddresses }
                .filter { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
                .map { Subnet(ipToLong(it.address.hostAddress), it.networkPrefixLength.toInt()) }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }

        /** True when a TCP connection to [host]:[port] succeeds within [timeoutMs]. */
        suspend fun tcpProbe(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }
}
