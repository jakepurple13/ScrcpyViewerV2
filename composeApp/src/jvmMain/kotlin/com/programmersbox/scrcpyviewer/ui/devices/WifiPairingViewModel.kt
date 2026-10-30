package com.programmersbox.scrcpyviewer.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.adb.AdbOutcome
import com.programmersbox.scrcpyviewer.adb.MdnsService
import com.programmersbox.scrcpyviewer.adb.MdnsType
import com.programmersbox.scrcpyviewer.adb.NetworkScanner
import com.programmersbox.scrcpyviewer.adb.WirelessAdb
import com.programmersbox.scrcpyviewer.adb.WirelessParsers
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.KoinViewModel
import kotlin.random.Random

enum class PairingTab { Qr, Code, Ip }

data class WifiPairingState(
    val tab: PairingTab = PairingTab.Qr,
    val qrName: String = "",
    val qrPassword: String = "",
    val qrPayload: String = "",
    /** Phones advertising a pairing port (the Pairing code tab's list). */
    val pairingServices: List<MdnsService> = emptyList(),
    /** Address of the phone picked on the Pairing code tab. */
    val chosen: String? = null,
    val code: String = "",
    val ip: String = "",
    val port: String = "5555",
    val pairPort: String = "",
    val pairCode: String = "",
    val ipError: String? = null,
    val portError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Shows a "Restart adb" button: macOS blocks the adb server another app started (see [WifiPairingViewModel.restartAdb]). */
    val canRestartAdb: Boolean = false,
    /** True while the IP address tab's network scan runs; [scanDone] of [scanTotal] hosts probed so far. */
    val scanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    /** Addresses that answered on the port, or null before the first scan. */
    val scanResults: List<String>? = null,
) {
    val primaryLabel: String get() = if (tab == PairingTab.Ip) "Connect" else "Pair"

    val primaryEnabled: Boolean
        get() = !busy && when (tab) {
            PairingTab.Qr -> false
            PairingTab.Code -> chosen != null && code.length == 6
            PairingTab.Ip -> ip.isNotBlank() && port.isNotBlank()
        }
}

/**
 * The "Pair device over Wi-Fi" dialog: polls `adb mdns services` while open, pairs automatically when the phone
 * scans the QR code, and pairs or connects on submit for the other tabs. [results] gets the snackbar text after a
 * successful pair or connect. Scope it to the dialog: clearing it stops polling and any pair or connect.
 */
@KoinViewModel
class WifiPairingViewModel(
    private val wireless: WirelessAdb,
    random: Random = Random.Default,
    private val pollMs: Long = 1000,
    private val connectWaitMs: Long = 10_000,
    private val scanner: NetworkScanner = NetworkScanner(),
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val _results = Channel<WifiNotice>(Channel.BUFFERED)

    /** A successful pair or connect, for the snackbar. */
    val results: Flow<WifiNotice> = _results.receiveAsFlow()

    private val _state = MutableStateFlow(
        run {
            val name = "scrcpy-viewer-" + WirelessParsers.randomAlnum(6, random)
            val password = WirelessParsers.randomAlnum(10, random)
            WifiPairingState(qrName = name, qrPassword = password, qrPayload = WirelessParsers.qrPayload(name, password))
        },
    )
    val state: StateFlow<WifiPairingState> = _state.asStateFlow()

    /** QR pairing addresses already tried; the phone advertises a new port when the code is scanned again. */
    private val triedQr = HashSet<String>()

    init {
        viewModelScope.launch {
            while (isActive) {
                val services = wireless.mdnsServices()
                val pairing = services.filter { it.type == MdnsType.Pairing }
                _state.update { it.copy(pairingServices = pairing) }
                val s = _state.value
                if (s.tab == PairingTab.Qr && !s.busy) {
                    pairing.firstOrNull { it.name == s.qrName && triedQr.add(it.address) }
                        ?.let { pairAndConnect(it, s.qrPassword) }
                }
                delay(pollMs)
            }
        }
    }

    fun selectTab(tab: PairingTab) = _state.update { it.copy(tab = tab, error = null) }

    fun choose(address: String) = _state.update { it.copy(chosen = address, error = null) }

    fun setCode(s: String) = _state.update { it.copy(code = s.filter(Char::isDigit).take(6), error = null) }

    fun setIp(s: String) = _state.update { it.copy(ip = s.trim(), ipError = null, error = null) }

    fun setPort(s: String) = _state.update { it.copy(port = s.filter(Char::isDigit), portError = null, error = null) }

    fun setPairPort(s: String) = _state.update { it.copy(pairPort = s.filter(Char::isDigit), error = null) }

    fun setPairCode(s: String) = _state.update { it.copy(pairCode = s.filter(Char::isDigit).take(6), error = null) }

    /** The primary button: Pair on the Pairing code tab, Connect on the IP address tab. */
    fun submit() {
        val s = _state.value
        if (s.busy) return
        when (s.tab) {
            PairingTab.Qr -> Unit
            PairingTab.Code -> {
                val service = s.pairingServices.firstOrNull { it.address == s.chosen }
                    ?: s.chosen?.let { MdnsService(it, MdnsType.Pairing, it) }
                    ?: return
                if (s.code.length != 6) return
                viewModelScope.launch { pairAndConnect(service, s.code) }
            }
            PairingTab.Ip -> submitIp(s)
        }
    }

    /**
     * The IP address tab's "Scan network" link: looks for devices accepting connections on the entered port across
     * this computer's networks. Fills in the address when exactly one answers.
     */
    fun startScan() {
        if (_state.value.scanning) return
        val port = _state.value.port.takeIf(::isPort)?.toInt()
        if (port == null) {
            _state.update { it.copy(portError = "Enter a port from 1 to 65535") }
            return
        }
        _state.update { it.copy(scanning = true, scanDone = 0, scanTotal = 0, scanResults = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                val found = scanner.scan(
                    port,
                    onProgress = { done, total -> _state.update { it.copy(scanDone = done, scanTotal = total) } },
                    onFound = { host -> _state.update { it.copy(scanResults = it.scanResults.orEmpty() + host) } },
                )
                _state.update {
                    it.copy(
                        scanning = false,
                        scanResults = found,
                        ip = if (found.size == 1) found.single() else it.ip,
                        port = if (found.size == 1) port.toString() else it.port,
                        ipError = if (found.size == 1) null else it.ipError,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(scanning = false) }
                throw e
            }
        }
    }

    /** The dialog's "Restart adb" button: restarts the adb server so this app owns it; the user then tries again. */
    fun restartAdb() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, canRestartAdb = false) }
            val error = try {
                wireless.restartServer()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't restart adb: ${e.message}"
            }
            _state.update { it.copy(busy = false, error = error) }
        }
    }

    private fun submitIp(s: WifiPairingState) {
        val ipError = if (isIpv4(s.ip)) null else "Enter an IP address"
        val portError = if (isPort(s.port)) null else "Enter a port from 1 to 65535"
        val pairPortError = s.pairPort.isNotEmpty() && !isPort(s.pairPort)
        if (ipError != null || portError != null || pairPortError) {
            _state.update {
                it.copy(ipError = ipError, portError = portError, error = if (pairPortError) "Enter a pairing port from 1 to 65535" else null)
            }
            return
        }
        val address = "${s.ip}:${s.port}"
        viewModelScope.launch {
            busy(true)
            if (s.pairPort.isNotEmpty() && s.pairCode.isNotEmpty()) {
                val paired = wireless.pair("${s.ip}:${s.pairPort}", s.pairCode)
                if (paired != AdbOutcome.Ok) return@launch fail(pairError(paired), paired)
            }
            when (val connected = wireless.connect(address)) {
                AdbOutcome.Ok -> succeed(WifiNotice("$address connected over Wi-Fi", address))
                else -> fail("Couldn't connect: ${message(connected)}", connected)
            }
        }
    }

    private suspend fun pairAndConnect(service: MdnsService, code: String) {
        busy(true)
        val paired = wireless.pair(service.address, code)
        if (paired != AdbOutcome.Ok) return fail(pairError(paired), paired)
        // adb usually connects by itself once the phone advertises its connect service; connecting again is harmless.
        val connectService = withTimeoutOrNull(connectWaitMs) {
            var found: MdnsService? = null
            while (found == null) {
                found = wireless.mdnsServices().firstOrNull { it.type == MdnsType.Connect && it.host == service.host }
                if (found == null) delay(pollMs)
            }
            found
        }
        if (connectService == null) return succeed(WifiNotice("Paired with ${label(service.name, service.address)}"))
        when (val connected = wireless.connect(connectService.address)) {
            AdbOutcome.Ok -> {
                val name = label(connectService.name, connectService.address)
                succeed(WifiNotice("$name connected over Wi-Fi", connectService.address, mdnsName = connectService.name))
            }
            else -> fail("Couldn't connect: ${message(connected)}", connected)
        }
    }

    private fun busy(value: Boolean) =
        _state.update { it.copy(busy = value, error = if (value) null else it.error, canRestartAdb = if (value) false else it.canRestartAdb) }

    private fun fail(error: String, outcome: AdbOutcome? = null) {
        val restart = (outcome as? AdbOutcome.Failed)?.restartCanFix == true
        _state.update { it.copy(busy = false, error = error, canRestartAdb = restart) }
    }

    private fun succeed(notice: WifiNotice) {
        _state.update { it.copy(busy = false) }
        _results.trySend(notice)
    }

    private fun pairError(outcome: AdbOutcome): String = when (outcome) {
        AdbOutcome.WrongCode -> "Wrong pairing code. Check the code on the phone and try again."
        else -> "Couldn't pair: ${message(outcome)}"
    }

    private fun message(outcome: AdbOutcome): String {
        val failed = outcome as? AdbOutcome.Failed ?: return outcome.toString()
        return if (failed.restartCanFix) "${failed.message}. $RESTART_ADB_HINT" else WirelessParsers.withLocalNetworkHint(failed.message)
    }

    /** The serial in an `adb-<serial>-<suffix>` service name, else [fallback]. */
    private fun label(serviceName: String, fallback: String): String =
        WirelessParsers.serialFromServiceName(serviceName).takeIf { it != serviceName } ?: fallback

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    private fun isPort(s: String): Boolean = s.toIntOrNull()?.let { it in 1..65535 } ?: false
}
