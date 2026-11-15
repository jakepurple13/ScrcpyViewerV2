package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.optionsKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Presets screen needs from the Devices screen's selection. */
interface SelectedDevice {
    /** The key the selected device's options are stored under (see [optionsKey]), or null with no device. */
    val optionsKey: StateFlow<String?>

    /** Human name for the device whose options are stored under [key], used in the "Applied" message. */
    fun label(key: String): String
}

data class DeviceSelectionState(
    val devices: List<Device> = emptyList(),
    val connected: Boolean = false,
    val serial: String? = null,
    /** Where the selected device's options are stored (see [optionsKey]). */
    val key: String? = null,
)

/**
 * The device shown as selected on the Devices screen: the user's choice while it is connected, else the first ready
 * device. Never one of [hidden] (emulators still booting, left out of the device list until they are ready); once
 * one is shown, the same rules apply to it. App-wide, so it survives tab switches and the Presets screen can apply to it.
 */
class DeviceSelection(
    devices: StateFlow<List<Device>>,
    connected: StateFlow<Boolean>,
    scope: CoroutineScope,
    hidden: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) : SelectedDevice {
    private val userSelection = MutableStateFlow<String?>(null)

    val state: StateFlow<DeviceSelectionState> = combine(devices, connected, userSelection, hidden) { list, conn, chosen, off ->
        val shown = list.filter { it.serial !in off }
        val device = chosen?.let { s -> shown.firstOrNull { it.serial == s } }
            ?: shown.firstOrNull { it.state == DeviceState.Device }
        DeviceSelectionState(list, conn, device?.serial, device?.optionsKey())
    }.stateIn(scope, SharingStarted.Eagerly, DeviceSelectionState(devices.value, connected.value))

    val selectedSerial: StateFlow<String?> = state.map { it.serial }.stateIn(scope, SharingStarted.Eagerly, null)

    override val optionsKey: StateFlow<String?> = state.map { it.key }.stateIn(scope, SharingStarted.Eagerly, null)

    fun select(serial: String) {
        userSelection.value = serial
    }

    override fun label(key: String): String =
        state.value.devices.firstOrNull { it.optionsKey() == key }?.let(::deviceTitle) ?: key
}
