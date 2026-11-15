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
        // Ready: the emulator shows, listed first, but the phone already shown as selected stays: no jump.
        hidden.value = emptySet()
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        selection.select("emulator-5554")
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
    }

    @Test
    fun `an emulator that finishes booting with no other device is selected`() = runTest {
        val devices = MutableStateFlow(listOf(emulator(DeviceState.Offline, info = false)))
        val hidden = MutableStateFlow(setOf("emulator-5554"))
        val selection = DeviceSelection(devices, MutableStateFlow(true), backgroundScope, hidden)
        runCurrent()
        assertNull(selection.state.value.serial)
        devices.value = listOf(emulator(DeviceState.Device, info = true))
        hidden.value = emptySet()
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
    }

    @Test
    fun `an emulator started elsewhere does not take the default from a shown phone`() = runTest {
        val devices = MutableStateFlow(listOf(phone))
        val selection = DeviceSelection(devices, MutableStateFlow(true), backgroundScope)
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        devices.value = listOf(emulator(DeviceState.Device, info = true), phone)
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        // The phone goes: the emulator is the default now, and stays when the phone is back.
        devices.value = listOf(emulator(DeviceState.Device, info = true))
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
        devices.value = listOf(emulator(DeviceState.Device, info = true), phone)
        runCurrent()
        assertEquals("emulator-5554", selection.state.value.serial)
    }

    private val tablet = Device("R52W70ABCDE", DeviceState.Device, "device", "SM_X710", null, 3, false)

    @Test
    fun `a real device's default selection is unchanged - the first ready device, even when a new one is listed first`() = runTest {
        val devices = MutableStateFlow(listOf(phone))
        val selection = DeviceSelection(devices, MutableStateFlow(true), backgroundScope)
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        devices.value = listOf(tablet, phone)
        runCurrent()
        assertEquals("R52W70ABCDE", selection.state.value.serial)
        // Not ready: the next ready one.
        devices.value = listOf(tablet.copy(state = DeviceState.Offline), phone)
        runCurrent()
        assertEquals("R5CX12GDWPZ", selection.state.value.serial)
        assertEquals(phone, defaultDevice(listOf(phone, tablet), previous = "R52W70ABCDE"))
        assertNull(defaultDevice(listOf(phone.copy(state = DeviceState.Unauthorized)), previous = null))
    }

    @Test
    fun `a connected emulator is titled with its AVD name once known, a real device by its model`() {
        val booted = emulator(DeviceState.Device, info = true)
        assertEquals("sdk_gphone64_arm64", connectedTitle(booted, avdName = null))
        assertEquals("heads_up_api34", connectedTitle(booted, avdName = "heads_up_api34"))
        assertEquals("Pixel 9", connectedTitle(emulator(DeviceState.Offline, info = false), avdName = "Pixel 9"))
        assertEquals("emulator-5554", connectedTitle(emulator(DeviceState.Offline, info = false), avdName = null))
        // A real device never takes an AVD name, and keeps its title.
        assertEquals("SM A156U1", connectedTitle(phone, avdName = "Pixel 9"))
        assertEquals(deviceTitle(phone), connectedTitle(phone, avdName = null))
    }

    @Test
    fun `an offline emulator says to stop and start it, a real device keeps its hints`() {
        assertEquals("Emulator offline. Stop and start it again from Emulators.", deviceHint(emulator(DeviceState.Offline, info = false)))
        assertNull(deviceHint(emulator(DeviceState.Device, info = true)))
        assertEquals("Reconnect the cable or run adb reconnect", deviceHint(phone.copy(state = DeviceState.Offline, rawState = "offline")))
        assertEquals("Accept the USB debugging prompt on the device", deviceHint(phone.copy(state = DeviceState.Unauthorized)))
        assertEquals("recovery", deviceHint(phone.copy(state = DeviceState.Other, rawState = "recovery")))
        assertNull(deviceHint(phone))
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
