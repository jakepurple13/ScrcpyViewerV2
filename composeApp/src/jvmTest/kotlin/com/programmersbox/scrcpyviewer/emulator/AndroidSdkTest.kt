package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidSdkTest {
    private val tmp: Path = Files.createTempDirectory("sdk-test")

    private fun sdk(name: String, withEmulator: Boolean = true, os: OpenPlatform = OpenPlatform.Mac): Path {
        val root = tmp.resolve(name)
        Files.createDirectories(root)
        if (withEmulator) {
            val dir = Files.createDirectories(root.resolve("emulator"))
            Files.createFile(dir.resolve(if (os == OpenPlatform.Windows) "emulator.exe" else "emulator"))
        }
        return root
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private fun found(r: SdkLookup) = assertIs<SdkLookup.Found>(r).sdk.root

    @Test
    fun `override wins over env`() {
        val a = sdk("a")
        val b = sdk("b")
        val r = findSdk(a.toString(), env("ANDROID_HOME" to b.toString()), tmp, OpenPlatform.Mac)
        assertEquals(a, found(r))
    }

    @Test
    fun `ANDROID_HOME before ANDROID_SDK_ROOT before the platform default`() {
        val home = tmp.resolve("home")
        val def = sdk("home/Library/Android/sdk")
        val h = sdk("h")
        val r = sdk("r")
        val both = env("ANDROID_HOME" to h.toString(), "ANDROID_SDK_ROOT" to r.toString())
        assertEquals(h, found(findSdk(null, both, home, OpenPlatform.Mac)))
        assertEquals(r, found(findSdk(null, env("ANDROID_SDK_ROOT" to r.toString()), home, OpenPlatform.Mac)))
        assertEquals(def, found(findSdk(null, env(), home, OpenPlatform.Mac)))
        // A missing folder is skipped.
        val missing = env("ANDROID_HOME" to tmp.resolve("nope").toString(), "ANDROID_SDK_ROOT" to r.toString())
        assertEquals(r, found(findSdk(null, missing, home, OpenPlatform.Mac)))
        // A blank override is ignored.
        assertEquals(h, found(findSdk("  ", both, home, OpenPlatform.Mac)))
    }

    @Test
    fun `platform defaults per os`() {
        val home = tmp.resolve("h2")
        val linux = sdk("h2/Android/Sdk", os = OpenPlatform.Linux)
        assertEquals(linux, found(findSdk(null, env(), home, OpenPlatform.Linux)))
        val local = tmp.resolve("local")
        val win = sdk("local/Android/Sdk", os = OpenPlatform.Windows)
        val r = findSdk(null, env("LOCALAPPDATA" to local.toString()), home, OpenPlatform.Windows)
        val found = assertIs<SdkLookup.Found>(r).sdk
        assertEquals(win, found.root)
        assertEquals(win.resolve("emulator").resolve("emulator.exe"), found.emulator)
    }

    @Test
    fun `nothing found reports the default path`() {
        val home = tmp.resolve("empty-home")
        assertEquals(SdkLookup.NotFound("~/Library/Android/sdk"), findSdk(null, env(), home, OpenPlatform.Mac))
        assertEquals(SdkLookup.NotFound("~/Android/Sdk"), findSdk(null, env(), home, OpenPlatform.Linux))
        assertEquals(
            SdkLookup.NotFound("%LOCALAPPDATA%\\Android\\Sdk"),
            findSdk(null, env(), home, OpenPlatform.Windows),
        )
    }

    @Test
    fun `sdk without emulator folder is NoEmulator`() {
        val root = sdk("bare", withEmulator = false)
        val r = findSdk(root.toString(), env(), tmp, OpenPlatform.Mac)
        assertEquals(SdkLookup.NoEmulator(root), r)
    }

    @Test
    fun `emulator version from source properties`() {
        val root = sdk("versioned")
        Files.writeString(
            root.resolve("emulator").resolve("source.properties"),
            "Pkg.Desc=Android Emulator\nPkg.UserSrc=false\nPkg.Revision=37.3.9\n",
        )
        val withVersion = assertIs<SdkLookup.Found>(findSdk(root.toString(), env(), tmp, OpenPlatform.Mac))
        assertEquals("37.3.9", withVersion.sdk.emulatorVersion)
        val plain = sdk("unversioned")
        val without = assertIs<SdkLookup.Found>(findSdk(plain.toString(), env(), tmp, OpenPlatform.Mac))
        assertNull(without.sdk.emulatorVersion)
    }

    @Test
    fun `avd home from env or default`() {
        val home = tmp.resolve("userhome")
        val avds = tmp.resolve("avds")
        assertEquals(avds, avdHome(env("ANDROID_AVD_HOME" to avds.toString(), "ANDROID_USER_HOME" to "x"), home))
        val user = tmp.resolve("user")
        assertEquals(user.resolve("avd"), avdHome(env("ANDROID_USER_HOME" to user.toString()), home))
        assertEquals(home.resolve(".android").resolve("avd"), avdHome(env(), home))
    }
}
