package com.programmersbox.scrcpyviewer.apps

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.tools.BundledTools
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Lists the apps on a real device and loads every icon into `build/device-test-output/app-icons`, printing how many
 * came out (`-PdeviceTests -PdeviceSerials=<serial>`). Needs `composeApp/bundled` (downloadBundledTools).
 */
class AppsDeviceTest {
    private val serial: String? = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first()?.takeIf { it.isNotBlank() }

    @Test
    fun `lists apps and loads their icons`() = runBlocking {
        assumeTrue("no -PdeviceSerials given", serial != null)
        val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
            .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
        val adb = AdbClient(adbPath = { adbPath })
        val tools = BundledTools(dataDir = Files.createTempDirectory("apps-device-test"))
        val start = System.currentTimeMillis()
        val apps = DeviceApps(adb, serial!!, { tools.serverJar }, tools.serverVersion).list()
        println("Listed ${apps.size} apps in ${System.currentTimeMillis() - start} ms; ${apps.count { it.versionName != null }} with versions")
        assertTrue(apps.isNotEmpty())

        val out = Paths.get("build", "device-test-output", "app-icons").toAbsolutePath()
        out.toFile().deleteRecursively()
        val loader = AppIconLoader(adb, serial, out)
        val iconStart = System.currentTimeMillis()
        val missing = apps.filter { loader.load(it) == null }
        println(
            "Icons: ${apps.size - missing.size}/${apps.size} in ${System.currentTimeMillis() - iconStart} ms; " +
                "none for ${missing.joinToString { it.packageName }}",
        )
        val again = System.currentTimeMillis()
        apps.forEach { loader.load(it) }
        println("Cached pass: ${System.currentTimeMillis() - again} ms")
    }
}
