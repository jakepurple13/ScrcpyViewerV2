package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevicesScreenTest {
    private val phone = Device("R5CX12GDWPZ", DeviceState.Device, "device", "SM_A156U1", null, 1, false)
    private fun emulator(state: DeviceState, info: Boolean) = Device(
        "emulator-5554", state, state.name.lowercase(), null, null, 2, false,
        if (info) DeviceInfo("sdk_gphone64_arm64", "16", 36) else null,
    )

    private fun List<DeviceEntry>.summary() = map { "${it.serial}:${if (it.present) "" else "leaving,"}${if (it.arrived) "new" else "old"}" }

    @Test
    fun `booting emulators are left out of the connected rows`() {
        val booting = emulator(DeviceState.Offline, info = false)
        assertEquals(listOf(phone), connectedRows(listOf(phone, booting), hidden = setOf("emulator-5554")))
        assertEquals(listOf(phone, booting), connectedRows(listOf(phone, booting), hidden = emptySet()))
        assertEquals(listOf(phone), connectedRows(listOf(phone), hidden = setOf("emulator-5556")))
    }

    @Test
    fun `a booted emulator arrives like a new device`() {
        val holder = DeviceEntriesState()
        holder.update(connectedRows(listOf(phone), setOf("emulator-5554")), live = true, now = 0)
        // Listed by adb while it boots: still hidden.
        holder.update(connectedRows(listOf(phone, emulator(DeviceState.Offline, info = false)), setOf("emulator-5554")), live = true, now = 5_000)
        assertEquals(listOf("R5CX12GDWPZ:old"), holder.entries.summary())
        // Ready: no longer booting, so it enters with the arrival motion.
        holder.update(connectedRows(listOf(phone, emulator(DeviceState.Device, info = true)), emptySet()), live = true, now = 30_000)
        assertEquals(listOf("R5CX12GDWPZ:old", "emulator-5554:new"), holder.entries.summary())
    }

    @Test
    fun `a booting emulator is never selected, then normal rules apply once it shows`() = runTest {
        val waiting = emulator(DeviceState.Device, info = true) // "Waiting for adb…": listed as device, still booting
        val devices = MutableStateFlow(listOf(waiting))
        val hidden = MutableStateFlow(setOf("emulator-5554"))
        val selection = DeviceSelection(devices, MutableStateFlow(true), backgroundScope, hidden)
        runCurrent()
        // The only ready device is hidden: nothing is selected.
        assertNull(selection.state.value.serial)
        // A phone shows up: it is selected, not the hidden emulator listed before it.
        devices.value = listOf(waiting, phone)
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        // Ready: the emulator shows, and with no user choice the normal rule picks the first ready device, now it.
        hidden.value = emptySet()
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
        selection.select("R5CX12GDWPZ")
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
    }

    @Test
    fun `a chosen emulator that boots again is not kept selected while hidden`() = runTest {
        val devices = MutableStateFlow(listOf(phone, emulator(DeviceState.Device, info = true)))
        val hidden = MutableStateFlow(emptySet<String>())
        val selection = DeviceSelection(devices, MutableStateFlow(true), backgroundScope, hidden)
        selection.select("emulator-5554")
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
        // Stopped and started again on the same serial: booting, so hidden.
        hidden.value = setOf("emulator-5554")
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        // Ready again: the user's choice applies again.
        hidden.value = emptySet()
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
    }

    @Test
    fun `a stopped emulator leaves its row with the departure motion`() {
        val holder = DeviceEntriesState()
        holder.update(listOf(phone, emulator(DeviceState.Device, info = true)), live = true, now = 0)
        holder.update(listOf(phone), live = true, now = 5_000)
        assertEquals(listOf("R5CX12GDWPZ:old", "emulator-5554:leaving,old"), holder.entries.summary())
        holder.exited("emulator-5554")
        assertEquals(listOf("R5CX12GDWPZ:old"), holder.entries.summary())
    }
}
