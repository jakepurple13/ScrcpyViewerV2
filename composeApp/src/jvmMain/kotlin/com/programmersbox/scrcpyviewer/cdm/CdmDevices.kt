package com.programmersbox.scrcpyviewer.cdm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Who holds a device in CDM, as the device list shows it. */
sealed interface CdmUse {
    data object Free : CdmUse
    /** This copy of the app holds it (a mirror window). */
    data object Mine : CdmUse
    /** Another holder has it: [label] is CDM's name for it. */
    data class Other(val label: String) : CdmUse
}

/**
 * CDM's device list, live from `GET /cdm/events`. [snapshot] is null while CDM is closed. When the stream ends (CDM
 * closed or not started), it reconnects quietly every [retryMs].
 */
class CdmDevices(
    private val client: CdmClient,
    private val isOurs: (holderId: String) -> Boolean,
    private val retryMs: Long = RETRY_MS,
) {
    private val _snapshot = MutableStateFlow<CdmSnapshot?>(null)
    val snapshot: StateFlow<CdmSnapshot?> = _snapshot.asStateFlow()

    /** True while CDM is running (the event stream is connected). */
    val running: Boolean get() = _snapshot.value != null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                client.events().collect { _snapshot.value = it }
                _snapshot.value = null
                delay(retryMs)
            }
        }
    }

    /**
     * How CDM sees the device with adb serial [serial] (and hardware serial [hardwareSerial], when known); null when
     * CDM is closed or does not list it.
     */
    fun useOf(snapshot: CdmSnapshot?, serial: String, hardwareSerial: String?): CdmUse? {
        val device = snapshot?.devices?.firstOrNull {
            it.serial == serial || it.key == serial ||
                (hardwareSerial != null && (it.serial == hardwareSerial || it.key == hardwareSerial))
        } ?: return null
        val holder = device.holder ?: return CdmUse.Free
        return if (isOurs(holder.id)) CdmUse.Mine else CdmUse.Other(holder.label?.takeIf { it.isNotBlank() } ?: holder.id)
    }

    companion object {
        const val RETRY_MS = 10_000L
    }
}
