package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `a stopped emulator leaves its row with the departure motion`() {
        val holder = DeviceEntriesState()
        holder.update(listOf(phone, emulator(DeviceState.Device, info = true)), live = true, now = 0)
        holder.update(listOf(phone), live = true, now = 5_000)
        assertEquals(listOf("R5CX12GDWPZ:old", "emulator-5554:leaving,old"), holder.entries.summary())
        holder.exited("emulator-5554")
        assertEquals(listOf("R5CX12GDWPZ:old"), holder.entries.summary())
    }
}
