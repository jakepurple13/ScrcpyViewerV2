package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.programmersbox.scrcpyviewer.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Result of switching a USB device to adb over Wi-Fi. */
sealed interface SwitchResult {
    data class Connected(val address: String) : SwitchResult
    data object NotOnWifi : SwitchResult
    data class TcpipFailed(val message: String) : SwitchResult
    data class ConnectFailed(val address: String, val message: String, val restartCanFix: Boolean = false) : SwitchResult
}

/** Why adb is not restarted while Claude Device Manager runs (the Restart adb button's tooltip says the same). */
const val RESTART_BLOCKED =
    "Claude Device Manager is running. Restarting adb would disconnect other sessions using its devices."

/** Wireless debugging commands (pair, connect, mDNS discovery, `tcpip`) run through [client]. */
open class WirelessAdb(
    private val client: AdbClient,
    private val retryDelayMs: Long = 1000,
    /** Discovery used alongside adb's own (e.g. macOS `dns-sd`); its services are added when adb missed them. */
    private val extraDiscovery: (suspend () -> List<MdnsService>)? = null,
    private val mac: Boolean = Os.isMac,
    /** Whether the adb server may be restarted without asking (it drops every adb connection, e.g. open mirrors). */
    private val canRestartServer: () -> Boolean = { true },
    /**
     * True while other tools share the adb server through Claude Device Manager: the app never restarts it then,
     * since that would cut off their sessions.
     */
    private val serverShared: () -> Boolean = { false },
) {
    /** Services from `adb mdns services` plus [extraDiscovery]; failures count as nothing found. */
    open suspend fun mdnsServices(): List<MdnsService> {
        val fromAdb = safely { WirelessParsers.mdnsServices(client.exec("mdns", "services").stdout) }
        val extra = extraDiscovery?.let { safely { it() } }.orEmpty()
        return (fromAdb + extra).distinctBy { it.name to it.type }
    }

    private inline fun safely(block: () -> List<MdnsService>): List<MdnsService> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    open suspend fun pair(address: String, code: String): AdbOutcome = withServerRestart {
        guarded {
            val result = client.execWithTimeout(PAIR_TIMEOUT_MS, "pair", address, code) ?: return@guarded AdbOutcome.Failed("timed out")
            WirelessParsers.pairOutcome(result)
        }
    }

    open suspend fun connect(address: String): AdbOutcome = withServerRestart {
        guarded { WirelessParsers.connectOutcome(client.exec("connect", address).stdout) }
    }

    /** Restarts the adb server so this app owns it (the "Restart adb" action). */
    open suspend fun restartServer() {
        check(!withContext(Dispatchers.IO) { serverShared() }) { RESTART_BLOCKED }
        client.restartServer()
    }

    /**
     * On macOS, Local Network privacy blocks an adb server started by another app (or an older build of this one):
     * connects fail with "No route to host" and pairing with a status-message protocol fault. When this app did not
     * start the server, restart it and try once more; when that is not allowed right now, flag the failure instead.
     */
    private suspend fun withServerRestart(op: suspend () -> AdbOutcome): AdbOutcome {
        val first = op()
        if (!mac || client.startedServer || first !is AdbOutcome.Failed || BLOCKED.none { it in first.message }) return first
        if (withContext(Dispatchers.IO) { serverShared() }) return first
        if (!canRestartServer()) return first.copy(restartCanFix = true)
        guarded {
            client.restartServer()
            AdbOutcome.Ok
        }.let { if (it is AdbOutcome.Failed) return first }
        return op()
    }

    /**
     * `adb disconnect` expects `host[:port]`. A device adb connected through mDNS has a
     * `<service>._adb-tls-connect._tcp` serial, so on failure retry with that service's address.
     */
    open suspend fun disconnect(serial: String): AdbOutcome = guarded {
        val first = disconnectOnce(serial)
        if (first == AdbOutcome.Ok || !serial.endsWith(MDNS_CONNECT_SUFFIX)) return@guarded first
        val name = serial.removeSuffix(MDNS_CONNECT_SUFFIX)
        val address = mdnsServices().firstOrNull { it.type == MdnsType.Connect && it.name == name }?.address
            ?: return@guarded first
        disconnectOnce(address)
    }

    private suspend fun disconnectOnce(target: String): AdbOutcome {
        val result = client.exec("disconnect", target)
        return if (result.exitCode == 0) AdbOutcome.Ok else AdbOutcome.Failed((result.stderr + result.stdout).trim())
    }

    /** The device's Wi-Fi IPv4 address, read over its current connection, or null. */
    open suspend fun wifiAddress(serial: String): String? = try {
        WirelessParsers.wlanAddress(client.shell(serial, "ip -f inet addr show wlan0"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Restarts adbd on [serial] in TCP mode on port 5555, then connects to it over Wi-Fi. */
    open suspend fun switchToWifi(serial: String): SwitchResult {
        val ip = wifiAddress(serial) ?: return SwitchResult.NotOnWifi
        val tcpip = try {
            client.exec("-s", serial, "tcpip", TCPIP_PORT.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SwitchResult.TcpipFailed(e.message ?: e.toString())
        }
        if (tcpip.exitCode != 0) return SwitchResult.TcpipFailed(tcpip.stderr.ifBlank { tcpip.stdout }.trim())
        val address = "$ip:$TCPIP_PORT"
        var last: AdbOutcome = AdbOutcome.Failed("not attempted")
        repeat(CONNECT_ATTEMPTS) { attempt ->
            // adbd takes a moment to restart in TCP mode before it accepts connections.
            if (attempt > 0) delay(retryDelayMs)
            last = connect(address)
            if (last == AdbOutcome.Ok) return SwitchResult.Connected(address)
        }
        val failed = last as? AdbOutcome.Failed
        return SwitchResult.ConnectFailed(address, failed?.message ?: "unknown error", failed?.restartCanFix ?: false)
    }

    private inline fun guarded(block: () -> AdbOutcome): AdbOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Failed(e.message ?: e.toString())
    }

    private companion object {
        const val PAIR_TIMEOUT_MS = 15_000L
        const val TCPIP_PORT = 5555
        const val CONNECT_ATTEMPTS = 5
        const val MDNS_CONNECT_SUFFIX = "._adb-tls-connect._tcp"
        val BLOCKED = listOf("No route to host", "couldn't read status message")
    }
}
