package com.programmersbox.scrcpyviewer.ui.devices.info

import com.programmersbox.scrcpyviewer.info.BatteryHealth
import com.programmersbox.scrcpyviewer.info.BatteryInfo
import com.programmersbox.scrcpyviewer.info.ChargeStatus
import com.programmersbox.scrcpyviewer.info.DisplayInfo
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.MemoryInfo
import com.programmersbox.scrcpyviewer.info.NetworkInfo
import com.programmersbox.scrcpyviewer.info.PowerSource
import com.programmersbox.scrcpyviewer.info.SoftwareInfo
import com.programmersbox.scrcpyviewer.info.StaticInfo
import com.programmersbox.scrcpyviewer.info.StorageBreakdown
import com.programmersbox.scrcpyviewer.info.StorageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class InfoTextTest {
    private val samsungStatic = StaticInfo(
        software = SoftwareInfo(
            androidVersion = "16",
            sdk = 36,
            securityPatch = "2026-07-05",
            buildId = "BP4A.251205.006.A156U1UESCEZG1",
            abi = "arm64-v8a",
            manufacturer = "samsung",
            model = "SM-A156U1",
            oneUiVersion = "8.5",
            kernel = "5.15.189-android13-8-33641424",
        ),
        display = DisplayInfo(1080, 2340, 450, null, null, null, 90.0),
        storage = StorageInfo(
            totalBytes = 110534640L * 1024,
            usedBytes = 18165728L * 1024,
            breakdown = StorageBreakdown(10329260032 + 4519657306, 8888320 + 773464064, 24251482112, 1679273984),
        ),
        network = NetworkInfo("Office-5G", "192.168.1.36", 351),
    )
    private val samsungBattery = BatteryInfo(100, ChargeStatus.Full, PowerSource.Usb, 29.2, 4334, BatteryHealth.Good)
    private val samsungMemory = MemoryInfo(3644192, 931408)

    private fun battery(status: ChargeStatus, power: PowerSource) =
        BatteryInfo(50, status, power, null, null, BatteryHealth.Good)

    @Test
    fun `gb uses decimal one place`() {
        assertEquals("18.6", InfoText.gb(18165728L * 1024))
        assertEquals("113.2", InfoText.gb(110534640L * 1024))
    }

    @Test
    fun `battery state text`() {
        assertEquals("Charging · USB", InfoText.batteryState(battery(ChargeStatus.Charging, PowerSource.Usb)))
        assertEquals("Full · plugged in", InfoText.batteryState(battery(ChargeStatus.Full, PowerSource.Usb)))
        assertEquals("On battery", InfoText.batteryState(battery(ChargeStatus.Discharging, PowerSource.None)))
        assertEquals("Plugged in, not charging", InfoText.batteryState(battery(ChargeStatus.NotCharging, PowerSource.Ac)))
    }

    @Test
    fun `android line`() {
        assertEquals("16 (API 36)", InfoText.androidLine(samsungStatic.software))
        assertNull(InfoText.androidLine(samsungStatic.software.copy(androidVersion = null, sdk = null)))
    }

    @Test
    fun `report matches spec example`() {
        val expected = """
            Galaxy A15 5G (SM-A156U1) · R5CX12GDWPZ
            Battery: 100%, full, USB, 29.2 °C, good
            Storage: 18.6 of 113.2 GB used
            Memory: 2.8 of 3.7 GB in use
            Display: 1080 × 2340, 450 dpi, 90 Hz, sw384dp
            Network: Office-5G, 192.168.1.36, 351 Mbps
            Android 16 (API 36), One UI 8.5, patch 2026-07-05
            Build: BP4A.251205.006.A156U1UESCEZG1, arm64-v8a, kernel 5.15.189-android13-8-33641424
        """.trimIndent()
        assertEquals(
            expected,
            InfoText.report("Galaxy A15 5G (SM-A156U1)", "R5CX12GDWPZ", samsungStatic, LiveInfo(samsungBattery, samsungMemory)),
        )
    }

    @Test
    fun `report skips missing parts`() {
        assertEquals(
            "Galaxy A15 5G (SM-A156U1) · R5CX12GDWPZ",
            InfoText.report("Galaxy A15 5G (SM-A156U1)", "R5CX12GDWPZ", null, LiveInfo(null, null)),
        )
    }

    @Test
    fun `no battery reads No battery`() {
        val report = InfoText.report("Pixel", "emulator-5554", null, LiveInfo(null, samsungMemory))
        assertFalse(report.lines().any { it.startsWith("Battery:") })
        assertEquals("Pixel · emulator-5554\nMemory: 2.8 of 3.7 GB in use", report)
        assertEquals("No battery", InfoText.NO_BATTERY)
    }

    @Test
    fun `storage parts sum to df used and drop system`() {
        val parts = InfoText.storageParts(samsungStatic.storage!!)!!
        val used = 18165728L * 1024
        assertEquals(10329260032 + 4519657306, parts.apps)
        assertEquals(8888320L + 773464064, parts.media)
        assertEquals(used - parts.apps - parts.media, parts.other)
        assertEquals(used, parts.apps + parts.media + parts.other)
        assertEquals(listOf("14.8", "0.8", "3.0"), listOf(parts.apps, parts.media, parts.other).map(InfoText::gb))
    }

    @Test
    fun `storage parts scale apps and media to fit used`() {
        val storage = StorageInfo(totalBytes = 5000, usedBytes = 1000, breakdown = StorageBreakdown(800, 400, 9000, 50))
        assertEquals(InfoText.StorageParts(apps = 667, media = 333, other = 0), InfoText.storageParts(storage))
    }

    @Test
    fun `storage parts need a breakdown`() {
        assertNull(InfoText.storageParts(StorageInfo(5000, 1000, null)))
    }

    @Test
    fun `breakable adds break chances only after dot dash and underscore`() {
        val z = "​"
        assertEquals("BP4A.${z}251205.${z}006.${z}A156U1UESCEZG1", InfoText.breakable("BP4A.251205.006.A156U1UESCEZG1"))
        assertEquals("5.${z}15.${z}189-${z}android13-${z}8-${z}33641424", InfoText.breakable("5.15.189-android13-8-33641424"))
        assertEquals("a_${z}b", InfoText.breakable("a_b"))
        assertEquals("arm64", InfoText.breakable("arm64"))
    }

    @Test
    fun `display override reads set and device values`() {
        val display = DisplayInfo(1080, 2340, 450, 720, 1560, 300, null)
        assertEquals("720 × 1560 (set, device is 1080 × 2340)", InfoText.resolution(display))
        assertEquals("300 dpi (set, device is 450)", InfoText.density(display))
    }
}
