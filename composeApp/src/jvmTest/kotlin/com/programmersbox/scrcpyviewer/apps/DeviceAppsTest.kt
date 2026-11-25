package com.programmersbox.scrcpyviewer.apps

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private class FakeAdb(
    private val pushHangs: Boolean = false,
    private val answer: (String) -> String,
) : AdbClient(adbPath = { error("no real adb in tests") }) {
    val commands = mutableListOf<String>()
    val execs = mutableListOf<List<String>>()

    override suspend fun shell(serial: String, cmd: String): String {
        commands += cmd
        return answer(cmd)
    }

    override fun shellStream(serial: String, cmd: String): Flow<String> {
        commands += cmd
        return flowOf(answer(cmd))
    }

    override suspend fun exec(vararg args: String): ProcessResult {
        execs += args.toList()
        if (pushHangs && "push" in args) kotlinx.coroutines.awaitCancellation()
        return ProcessResult(0, "", "")
    }
}

private const val LIST_APPS = """[server] INFO: Device: [NUU] NUU S6003L (Android 12)
[server] INFO: Processing Android apps... (this may take some time)
[server] INFO: List of apps:
 * Camera                         com.gabb.camera
 * Gabb Messenger                 com.gabb.messenger.android.app
 - 24 Game                        com.programmersbox.twentyfourgame
 - A very long app name com.example.long
"""

private const val DUMPSYS = """  Package [com.gabb.camera] (5403cea):
    versionCode=31 minSdk=28 targetSdk=36
    versionName=2.1.0
  Package [com.programmersbox.twentyfourgame] (86d1151):
    versionCode=7 minSdk=28 targetSdk=36
    versionName=1.4 beta
    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
  Package [com.gabb.messenger.android.app] (1111111):
    versionCode=120 minSdk=28 targetSdk=34
    versionName=3.8.2
  Package [com.gabb.camera] (9999999):
    versionCode=1 minSdk=28 targetSdk=36
    versionName=1.0
"""

class DeviceAppsTest {
    @Test
    fun `parses scrcpy's app list`() {
        assertEquals(
            listOf(
                ListedApp("com.gabb.camera", "Camera", system = true),
                ListedApp("com.gabb.messenger.android.app", "Gabb Messenger", system = true),
                ListedApp("com.programmersbox.twentyfourgame", "24 Game", system = false),
                ListedApp("com.example.long", "A very long app name", system = false),
            ),
            AppListParser.listApps(LIST_APPS),
        )
    }

    @Test
    fun `parses versions, keeping each package's first entry`() {
        val versions = AppListParser.versions(DUMPSYS)
        assertEquals(AppVersion("2.1.0", 31), versions["com.gabb.camera"])
        assertEquals(AppVersion("1.4 beta", 7, debuggable = true), versions["com.programmersbox.twentyfourgame"])
        assertEquals(AppVersion("3.8.2", 120), versions["com.gabb.messenger.android.app"])
    }

    @Test
    fun `parses debuggable from pkgFlags`() {
        val versions = AppListParser.versions(DUMPSYS)
        assertTrue(versions.getValue("com.programmersbox.twentyfourgame").debuggable)
        assertFalse(versions.getValue("com.gabb.camera").debuggable)
        // A SYSTEM flag line without DEBUGGABLE.
        val system = AppListParser.versions("  Package [com.android.chrome] (1):\n    versionCode=1\n    pkgFlags=[ SYSTEM HAS_CODE ]\n")
        assertFalse(system.getValue("com.android.chrome").debuggable)
    }

    @Test
    fun `listed apps carry the debuggable flag`() = runBlocking {
        val adb = FakeAdb { cmd -> if ("list_apps=true" in cmd) LIST_APPS else DUMPSYS }
        val apps = DeviceApps(adb, "SER", serverJar = { Path.of("/jar") }, serverVersion = "5.0").list()
        assertEquals(listOf("24 Game"), apps.filter { it.debuggable }.map { it.label })
        assertTrue("pkgFlags=" in adb.commands.single { "dumpsys" in it })
    }

    @Test
    fun `filters debuggable apps`() {
        val apps = listOf(
            DeviceApp("com.example.notes", "Notes", false, "1", 1, debuggable = true),
            DeviceApp("com.android.chrome", "Chrome", true, "1", 1),
        )
        assertEquals(listOf("Notes"), apps.matching("", AppFilter.Debuggable).map { it.label })
    }

    @Test
    fun `lists apps with versions, sorted by name`() = runBlocking {
        val adb = FakeAdb { cmd -> if ("list_apps=true" in cmd) LIST_APPS else DUMPSYS }
        val apps = DeviceApps(adb, "SER", serverJar = { Path.of("/jar") }, serverVersion = "5.0").list()
        assertEquals(listOf("24 Game", "A very long app name", "Camera", "Gabb Messenger"), apps.map { it.label })
        assertEquals(DeviceApp("com.gabb.camera", "Camera", true, "2.1.0", 31), apps[2])
        assertEquals(DeviceApp("com.example.long", "A very long app name", false, null, null), apps[1])
        assertEquals(listOf("-s", "SER", "push", Path.of("/jar").toString(), DeviceApps.JAR_PATH), adb.execs.single())
    }

    @Test
    fun `a failed listing reports the server's last line`() = runBlocking {
        val adb = FakeAdb { "[server] ERROR: Could not list apps\n" }
        val e = assertFailsWith<IOException> { DeviceApps(adb, "SER", { Path.of("/jar") }, "5.0").list() }
        assertEquals("[server] ERROR: Could not list apps", e.message)
    }

    @Test
    fun `launch, force stop and uninstall commands`() = runBlocking {
        val adb = FakeAdb { cmd -> if (cmd.startsWith("pm uninstall")) "Success\n__scrcpyviewer_exit=0" else "__scrcpyviewer_exit=0" }
        val apps = DeviceApps(adb, "SER", { null }, "5.0")
        apps.launch("com.a")
        apps.forceStop("com.a")
        apps.uninstall("com.a", system = false)
        apps.uninstall("com.sys", system = true)
        assertEquals(
            listOf(
                "monkey -p 'com.a' -c android.intent.category.LAUNCHER 1",
                "am force-stop 'com.a'",
                "pm uninstall 'com.a'",
                "pm uninstall --user current 'com.sys'",
            ),
            adb.commands.map { it.substringBefore("; echo") },
        )
    }

    @Test
    fun `uninstall fails on a Failure line`() = runBlocking {
        val adb = FakeAdb { "Failure [DELETE_FAILED_INTERNAL_ERROR]\n__scrcpyviewer_exit=0" }
        val e = assertFailsWith<IOException> { DeviceApps(adb, "SER", { null }, "5.0").uninstall("com.a", false) }
        assertEquals("Failure [DELETE_FAILED_INTERNAL_ERROR]", e.message)
    }

    @Test
    fun `launch fails when the app has no launcher activity`() = runBlocking {
        val adb = FakeAdb { "  bash arg: -p\n** No activities found to run, monkey aborted.\n__scrcpyviewer_exit=251" }
        val e = assertFailsWith<IOException> { DeviceApps(adb, "SER", { null }, "5.0").launch("com.a") }
        assertTrue("No activities" in e.message!!)
    }

    @Test
    fun `filters by name or package and by kind`() {
        val apps = listOf(
            DeviceApp("com.android.chrome", "Chrome", false, "1", 1),
            DeviceApp("com.android.settings", "Settings", true, "14", 34),
            DeviceApp("com.spotify.music", "Spotify", false, "9", 9),
        )
        assertEquals(listOf("Chrome", "Settings"), apps.matching("ANDROID", AppFilter.All).map { it.label })
        assertEquals(listOf("Chrome"), apps.matching("android", AppFilter.User).map { it.label })
        assertEquals(listOf("Settings"), apps.matching("", AppFilter.System).map { it.label })
        assertEquals(listOf("Spotify"), apps.matching(" spot ", AppFilter.All).map { it.label })
    }

    @Test
    fun `a hung adb push fails the listing with a clear message`() = runBlocking<Unit> {
        val adb = FakeAdb(pushHangs = true) { "" }
        val e = assertFailsWith<IOException> {
            kotlinx.coroutines.withTimeout(5_000) {
                DeviceApps(adb, "SER", { Path.of("/jar") }, "5.0", pushTimeoutMs = 200).list()
            }
        }
        assertEquals("adb push timed out", e.message)
        assertTrue(adb.commands.isEmpty(), "nothing runs after the failed push")
    }
}
