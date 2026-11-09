package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceMotionTest {
    private fun device(serial: String, tcp: Boolean = false, state: DeviceState = DeviceState.Device) =
        Device(serial, state, "device", null, null, null, tcp)

    private fun List<DeviceEntry>.summary() = map { "${it.serial}:${if (it.present) "" else "leaving,"}${if (it.arrived) "new" else "old"}" }

    @Test
    fun `first load shows every device without arrival motion`() {
        val entries = nextDeviceEntries(null, listOf(device("a"), device("b")), animateArrivals = true)
        assertEquals(listOf("a:old", "b:old"), entries.summary())
    }

    @Test
    fun `a device that connects later is an arrival, the others are not`() {
        val first = nextDeviceEntries(null, listOf(device("a")), animateArrivals = false)
        val next = nextDeviceEntries(first, listOf(device("a"), device("b")), animateArrivals = true)
        assertEquals(listOf("a:old", "b:new"), next.summary())
    }

    @Test
    fun `devices within the first-load grace do not count as arrivals`() {
        val first = nextDeviceEntries(null, emptyList(), animateArrivals = false)
        val next = nextDeviceEntries(first, listOf(device("a")), animateArrivals = false)
        assertEquals(listOf("a:old"), next.summary())
    }

    @Test
    fun `an arrival stays an arrival on later updates`() {
        val first = nextDeviceEntries(null, listOf(device("a")), animateArrivals = false)
        val second = nextDeviceEntries(first, listOf(device("a"), device("b")), animateArrivals = true)
        val third = nextDeviceEntries(second, listOf(device("a"), device("b").copy(model = "Pixel")), animateArrivals = true)
        assertEquals(listOf("a:old", "b:new"), third.summary())
        assertEquals("Pixel", third[1].device.model)
    }

    @Test
    fun `a disconnected device stays at its place while it leaves`() {
        val first = nextDeviceEntries(null, listOf(device("a"), device("b"), device("c")), animateArrivals = false)
        val next = nextDeviceEntries(first, listOf(device("a"), device("c")), animateArrivals = true)
        assertEquals(listOf("a:old", "b:leaving,old", "c:old"), next.summary())
    }

    @Test
    fun `the first device leaving stays first`() {
        val first = nextDeviceEntries(null, listOf(device("a"), device("b")), animateArrivals = false)
        val next = nextDeviceEntries(first, listOf(device("b")), animateArrivals = true)
        assertEquals(listOf("a:leaving,old", "b:old"), next.summary())
    }

    @Test
    fun `a leaving row is dropped once its exit ends`() {
        val first = nextDeviceEntries(null, listOf(device("a"), device("b")), animateArrivals = false)
        val leaving = nextDeviceEntries(first, listOf(device("b")), animateArrivals = true)
        assertEquals(listOf("b:old"), dropDeparted(leaving, "a").summary())
    }

    @Test
    fun `a device that comes back while leaving is present again and not dropped`() {
        val first = nextDeviceEntries(null, listOf(device("a")), animateArrivals = false)
        val leaving = nextDeviceEntries(first, emptyList(), animateArrivals = true)
        val back = nextDeviceEntries(leaving, listOf(device("a")), animateArrivals = true)
        assertEquals(listOf("a:old"), back.summary())
        assertEquals(listOf("a:old"), dropDeparted(back, "a").summary())
    }

    @Test
    fun `entries state treats the list right after adb connects as the first load`() {
        val holder = DeviceEntriesState()
        holder.update(emptyList(), live = false, now = 0)
        holder.update(emptyList(), live = true, now = 1_000)
        holder.update(listOf(device("a")), live = true, now = 1_000 + INITIAL_LOAD_GRACE_MILLIS / 2)
        assertEquals(listOf("a:old"), holder.entries.summary())
        holder.update(listOf(device("a"), device("b")), live = true, now = 5_000)
        assertEquals(listOf("a:old", "b:new"), holder.entries.summary())
    }

    @Test
    fun `entries state starts over after adb restarts`() {
        val holder = DeviceEntriesState()
        holder.update(listOf(device("a")), live = true, now = 0)
        holder.update(listOf(device("a")), live = false, now = 5_000)
        assertTrue(holder.entries.isEmpty())
        holder.update(listOf(device("a"), device("b")), live = true, now = 6_000)
        assertEquals(listOf("a:old", "b:old"), holder.entries.summary())
    }

    @Test
    fun `entries state removes a row after its exit`() {
        val holder = DeviceEntriesState()
        holder.update(listOf(device("a"), device("b")), live = true, now = 0)
        holder.update(listOf(device("b")), live = true, now = 5_000)
        assertEquals(listOf("a:leaving,old", "b:old"), holder.entries.summary())
        holder.exited("a")
        assertEquals(listOf("b:old"), holder.entries.summary())
        // A later list that does not mention the departed device does not bring it back.
        holder.update(listOf(device("b"), device("c")), live = true, now = 6_000)
        assertEquals(listOf("b:old", "c:new"), holder.entries.summary())
    }

    @Test
    fun `chip says USB, connecting, or Wi-Fi with the address`() {
        assertEquals(ChipStatus("USB", wifi = false, tone = ChipTone.Connected), chipStatus(device("X"), switching = false))
        assertEquals(
            ChipStatus("Connecting over Wi-Fi…", wifi = false, tone = ChipTone.Pending),
            chipStatus(device("X"), switching = true),
        )
        assertEquals(
            ChipStatus("Wi-Fi · 192.168.1.31", wifi = true, tone = ChipTone.Connected),
            chipStatus(device("192.168.1.31:5555", tcp = true), switching = false),
        )
        assertEquals("Wi-Fi", chipStatus(device("adb-R5CT-xK2._adb-tls-connect._tcp", tcp = true), switching = false).label)
        assertEquals(ChipTone.Pending, chipStatus(device("X", state = DeviceState.Unauthorized), switching = false).tone)
        assertEquals(ChipTone.Problem, chipStatus(device("X", state = DeviceState.Offline), switching = false).tone)
    }

    @Test
    fun `wifi host parses ip and port serials only`() {
        assertEquals("10.0.0.7", wifiHost("10.0.0.7:41877"))
        assertNull(wifiHost("adb-R5CT-xK2._adb-tls-connect._tcp"))
        assertNull(wifiHost("39121FDJH004AB"))
    }

    @Test
    fun `spin settles on a whole turn with room to slow down`() {
        assertEquals(360f, settleTarget(10f))
        assertEquals(360f, settleTarget(259f))
        assertEquals(720f, settleTarget(300f))
        assertEquals(1080f, settleTarget(720f))
        for (angle in listOf(0f, 1f, 90f, 359f, 1000f)) {
            val target = settleTarget(angle)
            assertEquals(0f, target % 360f)
            assertTrue(target - angle >= 100f, "from $angle")
            assertTrue(target - angle < 460f, "from $angle")
        }
    }

    @Test
    fun `restart outcome comes from the restart notices`() {
        assertEquals(true, restartOutcome("Restarted adb."))
        assertEquals(true, restartOutcome("Restarted adb. Try connecting again."))
        assertEquals(false, restartOutcome("Couldn't restart adb: boom"))
        assertNull(restartOutcome("Connected to 10.0.0.7:5555"))
        assertFalse(restartOutcome("Couldn't restart adb: x")!!)
    }
}
