package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.files.shellQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDataAccessTest {
    private val inner = "cat " + shellQuote("databases/a b's.db")

    @Test
    fun wrapRunAs() = assertEquals("run-as com.x sh -c " + shellQuote(inner), AppDataAccess.RunAs("com.x").wrap(inner))

    @Test
    fun wrapSuDashC() = assertEquals(
        "su -c " + shellQuote("cd /data/data/com.x && $inner"),
        AppDataAccess.Su("com.x", SuStyle.DashC).wrap(inner),
    )

    @Test
    fun wrapSuZero() = assertEquals(
        "su 0 sh -c " + shellQuote("cd /data/data/com.x && $inner"),
        AppDataAccess.Su("com.x", SuStyle.Zero).wrap(inner),
    )

    @Test
    fun wrapAdbRoot() = assertEquals("cd /data/data/com.x && $inner", AppDataAccess.AdbRoot("com.x").wrap(inner))

    @Test
    fun labels() {
        assertEquals("run-as", AppDataAccess.RunAs("p").label)
        assertEquals("root · su", AppDataAccess.Su("p", SuStyle.DashC).label)
        assertEquals("adb root", AppDataAccess.AdbRoot("p").label)
    }

    @Test
    fun accessForPicksRunAsBeforeRoot() {
        val debug = DeviceApp("com.debug", "Debug", system = false, versionName = null, versionCode = null, debuggable = true)
        val release = DeviceApp("com.release", "Release", system = false, versionName = null, versionCode = null)
        assertEquals(AppDataAccess.RunAs("com.debug"), accessFor(debug, RootState.Rooted, SuStyle.DashC))
        assertNull(accessFor(release, RootState.None, null))
        assertNull(accessFor(release, RootState.Available, null))
        assertEquals(AppDataAccess.Su("com.release", SuStyle.Zero), accessFor(release, RootState.Rooted, SuStyle.Zero))
        assertEquals(AppDataAccess.AdbRoot("com.release"), accessFor(release, RootState.AdbRoot, null))
    }
}
