package com.programmersbox.scrcpyviewer.info

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellReadTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private fun fixture(name: String): String =
    DeviceInfoReaderTest::class.java.getResource("/deviceinfo/sm-a156u1/$name.txt")!!.readText()

private class FakeAdb(private val answer: suspend (String) -> String) : AdbClient(adbPath = { error("no adb") }) {
    val commands = mutableListOf<String>()
    val readTimeouts = mutableListOf<Int?>()

    override suspend fun shell(serial: String, cmd: String): String {
        commands += cmd
        readTimeouts += currentCoroutineContext()[ShellReadTimeout]?.ms
        return answer(cmd)
    }
}

class DeviceInfoReaderTest {
    private fun propsText(): String {
        val values = fixture("props").lines().filter { it.isNotBlank() }
        val keys = listOf(
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch",
            "ro.build.display.id", "ro.product.cpu.abi", "ro.product.manufacturer", "ro.product.model",
            "ro.build.version.oneui", "kernel",
        )
        return keys.zip(values).joinToString("\n") { (k, v) -> "$k=$v" }
    }

    @Test
    fun `readStatic splits sections`() = runBlocking {
        val output = listOf(
            "props" to propsText(),
            "wm" to fixture("wm"),
            "display" to fixture("display"),
            "df" to fixture("df"),
            "diskstats" to fixture("diskstats"),
            "ip" to fixture("ip"),
            "wifi" to fixture("wifi"),
        ).joinToString("\n") { (name, body) -> "@@$name\n$body" }
        val adb = FakeAdb { output }
        val info = DeviceInfoReader(adb, "serial").readStatic()

        assertEquals(listOf(DeviceInfoReader.STATIC_COMMAND), adb.commands)
        assertEquals("SM-A156U1", info.software.model)
        assertEquals(36, info.software.sdk)
        assertEquals(DisplayInfo(1080, 2340, 450, null, null, null, 90.0), info.display)
        assertEquals(110534640L * 1024, info.storage!!.totalBytes)
        assertEquals(NetworkInfo("Office-5G", "192.168.1.36", 351), info.network)
    }

    @Test
    fun `readLive parses battery and memory`() = runBlocking {
        val adb = FakeAdb { "@@battery\n${fixture("battery")}\n@@mem\n${fixture("meminfo")}" }
        val live = DeviceInfoReader(adb, "serial").readLive()

        assertEquals(listOf(DeviceInfoReader.LIVE_COMMAND), adb.commands)
        assertEquals(
            BatteryInfo(100, ChargeStatus.Full, PowerSource.Usb, 29.2, 4334, BatteryHealth.Good),
            live.battery,
        )
        assertEquals(MemoryInfo(3644192, 931408), live.memory)
    }

    @Test
    fun `missing sections give nulls not errors`() = runBlocking {
        val info = DeviceInfoReader(FakeAdb { "@@props\nkernel=6.1" }, "serial").readStatic()

        assertNull(info.display)
        assertNull(info.storage)
        assertEquals(NetworkInfo(null, null, null), info.network)
        assertEquals("6.1", info.software.kernel)
    }

    @Test
    fun `adb failure becomes IOException`() {
        val reader = DeviceInfoReader(FakeAdb { throw SocketTimeoutException("boom") }, "serial")
        assertFailsWith<IOException> { runBlocking { reader.readStatic() } }
    }

    @Test
    fun `slow shell times out`() {
        val reader = DeviceInfoReader(FakeAdb { delay(60_000); "" }, "serial", timeoutMs = 50)
        assertFailsWith<IOException> { runBlocking { reader.readLive() } }
    }

    @Test
    fun `shell socket read timeout matches the reader timeout`() = runBlocking {
        val adb = FakeAdb { "" }
        DeviceInfoReader(adb, "serial", timeoutMs = 7_000).readLive()
        assertEquals(listOf<Int?>(7_000), adb.readTimeouts)
    }
}
