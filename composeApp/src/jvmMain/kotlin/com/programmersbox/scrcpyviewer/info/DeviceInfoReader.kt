package com.programmersbox.scrcpyviewer.info

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellReadTimeout
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Runs the Info tab's shell commands on one device and parses the output. Failures surface as [IOException].
 *
 * Each call is bounded by [timeoutMs] twice: [withTimeout] fails the coroutine after [timeoutMs] in total, and the
 * same value is the socket read timeout of [AdbClient.shell] (via [ShellReadTimeout]), because a cancelled coroutine
 * cannot interrupt the blocking socket read. The blocked read therefore ends at most [timeoutMs] after the device last
 * sent data, so a hung device frees its IO thread instead of holding it forever.
 */
open class DeviceInfoReader(
    private val adb: AdbClient,
    private val serial: String,
    private val timeoutMs: Long = 15_000,
) {
    open suspend fun readStatic(): StaticInfo {
        val sections = DeviceInfoParsers.sections(run(STATIC_COMMAND))
        return StaticInfo(
            software = DeviceInfoParsers.software(sections["props"].orEmpty()),
            display = DeviceInfoParsers.display(sections["wm"].orEmpty(), sections["display"].orEmpty()),
            storage = DeviceInfoParsers.storage(sections["df"].orEmpty(), sections["diskstats"].orEmpty()),
            network = DeviceInfoParsers.network(sections["ip"].orEmpty(), sections["wifi"].orEmpty()),
        )
    }

    open suspend fun readLive(): LiveInfo {
        val sections = DeviceInfoParsers.sections(run(LIVE_COMMAND))
        return LiveInfo(
            battery = DeviceInfoParsers.battery(sections["battery"].orEmpty()),
            memory = DeviceInfoParsers.memory(sections["mem"].orEmpty()),
        )
    }

    private suspend fun run(cmd: String): String = try {
        withTimeout(timeoutMs) {
            withContext(ShellReadTimeout(timeoutMs.toInt())) { adb.shell(serial, cmd) }
        }
    } catch (e: TimeoutCancellationException) {
        throw IOException("Timed out reading device info after $timeoutMs ms", e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "Could not read device info", e)
    }

    companion object {
        val STATIC_COMMAND: String = """
            echo @@props
            for p in ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.display.id \
              ro.product.cpu.abi ro.product.manufacturer ro.product.model ro.build.version.oneui; do echo "${'$'}p=${'$'}(getprop ${'$'}p)"; done
            echo "kernel=${'$'}(uname -r)"
            echo @@wm
            wm size; wm density
            echo @@display
            dumpsys display 2>/dev/null | grep -m1 DisplayDeviceInfo
            echo @@df
            df -k /data
            echo @@diskstats
            dumpsys diskstats 2>/dev/null | grep -E '^(App|App Data|Photos|Videos|Audio|Downloads|System|Other) Size:'
            echo @@ip
            ip -f inet addr show wlan0
            echo @@wifi
            dumpsys wifi 2>/dev/null | grep -m1 'mWifiInfo '
        """.trimIndent()

        val LIVE_COMMAND: String = """
            echo @@battery
            dumpsys battery
            echo @@mem
            cat /proc/meminfo
        """.trimIndent()
    }
}
