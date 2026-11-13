package com.programmersbox.scrcpyviewer.info

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun fixture(name: String): String =
    DeviceInfoParsersTest::class.java.getResource("/deviceinfo/sm-a156u1/$name.txt")!!.readText()

class DeviceInfoParsersTest {
    @Test
    fun `battery from samsung`() {
        assertEquals(
            BatteryInfo(100, ChargeStatus.Full, PowerSource.Usb, 29.2, 4334, BatteryHealth.Good),
            DeviceInfoParsers.battery(fixture("battery")),
        )
    }

    @Test
    fun `battery status codes`() {
        fun parse(status: Int, ac: Boolean = false) = DeviceInfoParsers.battery(
            "  AC powered: $ac\n  USB powered: false\n  Wireless powered: false\n  Dock powered: false\n" +
                "  status: $status\n  present: true\n  level: 50\n  scale: 100\n",
        )!!
        assertEquals(ChargeStatus.Charging, parse(2).status)
        assertEquals(ChargeStatus.Discharging, parse(3).status)
        assertEquals(ChargeStatus.NotCharging, parse(4).status)
        assertEquals(ChargeStatus.Unknown, parse(9).status)
        assertEquals(PowerSource.Ac, parse(2, ac = true).power)
        assertEquals(PowerSource.None, parse(2).power)
    }

    @Test
    fun `battery not present is null`() {
        assertNull(DeviceInfoParsers.battery("  present: false\n  level: 100\n"))
    }

    @Test
    fun `battery with negative level is null`() {
        assertNull(DeviceInfoParsers.battery("  present: true\n  level: -1\n  scale: 100\n"))
    }

    @Test
    fun `battery uses first key match`() {
        val text = "  charge counter: 1\n  charge counter: 1\n  level: 40\n  scale: 50\n"
        assertEquals(80, DeviceInfoParsers.battery(text)!!.level)
    }

    @Test
    fun `memory from samsung`() {
        val memory = DeviceInfoParsers.memory(fixture("meminfo"))!!
        assertEquals(MemoryInfo(3644192, 931408), memory)
        assertEquals(2712784, memory.usedKb)
    }

    @Test
    fun `memory without MemAvailable falls back to free plus cached`() {
        val memory = DeviceInfoParsers.memory("MemTotal: 1000 kB\nMemFree: 100 kB\nCached: 300 kB\n")!!
        assertEquals(400, memory.availableKb)
    }

    @Test
    fun `storage from samsung`() {
        val storage = DeviceInfoParsers.storage(fixture("df"), fixture("diskstats"))!!
        assertEquals(110534640L * 1024, storage.totalBytes)
        assertEquals(18165728L * 1024, storage.usedBytes)
        assertEquals(
            StorageBreakdown(
                apps = 10329260032 + 4519657306,
                media = 8888320 + 773464064,
                system = 24251482112,
                other = 1679273984,
            ),
            storage.breakdown,
        )
    }

    @Test
    fun `storage without diskstats has no breakdown`() {
        assertNull(DeviceInfoParsers.storage(fixture("df"), "")!!.breakdown)
    }

    @Test
    fun `display from samsung`() {
        val display = DeviceInfoParsers.display(fixture("wm"), fixture("display"))!!
        assertEquals(DisplayInfo(1080, 2340, 450, null, null, null, 90.0), display)
        assertEquals(384, display.smallestWidthDp)
    }

    @Test
    fun `display override`() {
        val wm = "Physical size: 1080x2340\nOverride size: 720x1560\nPhysical density: 450\nOverride density: 320"
        val display = DeviceInfoParsers.display(wm, "")!!
        assertEquals(720, display.overrideWidthPx)
        assertEquals(1560, display.overrideHeightPx)
        assertEquals(320, display.overrideDensityDpi)
        assertEquals(360, display.smallestWidthDp)
    }

    @Test
    fun `display refresh fallbacks`() {
        val wm = fixture("wm")
        assertEquals(60.0, DeviceInfoParsers.display(wm, "x refreshRate 60.0 y")!!.refreshHz)
        assertEquals(120.0, DeviceInfoParsers.display(wm, "x {fps=120.0, vsync=1}")!!.refreshHz)
        assertNull(DeviceInfoParsers.display(wm, "nothing")!!.refreshHz)
    }

    @Test
    fun `network from samsung`() {
        assertEquals(
            NetworkInfo("Office-5G", "192.168.1.36", 351),
            DeviceInfoParsers.network(fixture("ip"), fixture("wifi")),
        )
    }

    @Test
    fun `network unknown ssid and no wlan`() {
        assertEquals(
            NetworkInfo(null, null, null),
            DeviceInfoParsers.network("", "mWifiInfo SSID: <unknown ssid>, BSSID: 02:00"),
        )
    }

    private fun propsText(): String {
        val values = fixture("props").lines().filter { it.isNotBlank() }
        val keys = listOf(
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch",
            "ro.build.display.id", "ro.product.cpu.abi", "ro.product.manufacturer", "ro.product.model",
            "ro.build.version.oneui", "kernel",
        )
        return keys.zip(values).joinToString("\r\n") { (k, v) -> "$k=$v" }
    }

    @Test
    fun `software from samsung`() {
        assertEquals(
            SoftwareInfo(
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
            DeviceInfoParsers.software(propsText()),
        )
    }

    @Test
    fun `software empty values are null`() {
        assertNull(DeviceInfoParsers.software("ro.build.version.oneui=\n").oneUiVersion)
    }

    @Test
    fun `sections split on markers`() {
        val sections = DeviceInfoParsers.sections("@@a\n1\n2\n@@b\n3")
        assertEquals(mapOf("a" to "1\n2", "b" to "3"), sections)
        assertNull(sections["c"])
    }
}
