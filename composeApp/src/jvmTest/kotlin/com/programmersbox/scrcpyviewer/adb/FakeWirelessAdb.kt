package com.programmersbox.scrcpyviewer.adb

import java.nio.file.Path
import java.util.Collections

/** [WirelessAdb] with canned results; records each call as text ("pair <address> <code>", "switch <serial>"...). */
open class FakeWirelessAdb : WirelessAdb(object : AdbClient({ error("FakeWirelessAdb never runs adb") as Path }) {}) {
    @Volatile var services: List<MdnsService> = emptyList()
    @Volatile var pairResult: AdbOutcome = AdbOutcome.Ok
    @Volatile var connectResult: AdbOutcome = AdbOutcome.Ok
    @Volatile var disconnectResult: AdbOutcome = AdbOutcome.Ok
    @Volatile var switchResult: SwitchResult = SwitchResult.NotOnWifi
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    override suspend fun mdnsServices(): List<MdnsService> = services

    override suspend fun pair(address: String, code: String): AdbOutcome {
        calls += "pair $address $code"
        return pairResult
    }

    override suspend fun connect(address: String): AdbOutcome {
        calls += "connect $address"
        return connectResult
    }

    override suspend fun disconnect(serial: String): AdbOutcome {
        calls += "disconnect $serial"
        return disconnectResult
    }

    /** Thrown by [restartServer] when set. */
    @Volatile var restartError: Exception? = null

    override suspend fun restartServer() {
        calls += "restart"
        restartError?.let { throw it }
    }

    override suspend fun switchToWifi(serial: String): SwitchResult {
        calls += "switch $serial"
        return switchResult
    }
}
