package com.programmersbox.scrcpyviewer.ui.settings

import com.mikepenz.aboutlibraries.Libs
import com.programmersbox.scrcpyviewer.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The licence list the build generates (composeApp/build.gradle.kts, `aboutLibraries`) and what the dialog makes of it. */
class LicensesDataTest {
    private val json = assertNotNull(readLicensesJson(), "aboutlibraries.json is not on the classpath")
    private val libs = Libs.Builder().withJson(json).build()
    private val entries = licenseEntries(libs, adbVersion = "36.0.0")
    private fun entry(id: String) = assertNotNull(entries.firstOrNull { it.id == id }, "no $id")

    @Test
    fun `the generated list has the bundled tools first, then the libraries`() {
        assertEquals(BUNDLED_IDS, entries.take(3).map { it.id })
        assertTrue(entries.take(3).all { it.group == LicenseGroup.Bundled })
        val rest = entries.drop(3)
        assertTrue(rest.size > 20, "only ${rest.size} libraries")
        assertTrue(rest.all { it.group == LicenseGroup.Libraries })
        assertEquals(rest.map { it.name.lowercase() }.sorted(), rest.map { it.name.lowercase() })
    }

    @Test
    fun `known dependencies are listed once`() {
        val koin = entries.filter { it.name == "Koin" }
        assertEquals(1, koin.size, "Koin: ${koin.map { it.id }}")
        assertEquals("Apache 2.0", koin.single().licenseLabel)
        assertTrue(entries.any { it.id.startsWith("org.jetbrains.compose.") })
        assertTrue(entries.any { it.id == "com.mikepenz:aboutlibraries-core" })
        // The FFmpeg presets are the bundled FFmpeg entry, not a second one with the presets' licence.
        assertTrue(entries.none { it.id == "org.bytedeco:ffmpeg" })
    }

    @Test
    fun `FFmpeg is LGPL 3 or later with the javacpp-presets source`() {
        val ffmpeg = entry("bundled:ffmpeg")
        assertEquals("FFmpeg", ffmpeg.name)
        assertEquals(BuildInfo.FFMPEG_VERSION, ffmpeg.version, "config/libraries/ffmpeg.json is behind libs.versions.toml")
        assertEquals("LGPL 3.0+", ffmpeg.licenseLabel)
        assertEquals(listOf("GNU Lesser General Public License v3.0 or later"), ffmpeg.licenseNames)
        assertEquals(LicenseFamily.Lgpl, ffmpeg.family)
        val source = "https://github.com/bytedeco/javacpp-presets/tree/${BuildInfo.JAVACPP_VERSION}/ffmpeg"
        assertEquals(LicenseLink("Source (javacpp-presets ${BuildInfo.JAVACPP_VERSION})", source), ffmpeg.links.last())
        assertEquals(LicenseLink("ffmpeg.org", "https://ffmpeg.org/"), ffmpeg.links.first())
    }

    @Test
    fun `adb and scrcpy-server are Apache 2 with their sources`() {
        val adb = entry("bundled:adb")
        assertEquals("36.0.0", adb.version)
        assertEquals("Apache 2.0", adb.licenseLabel)
        assertEquals("The Android Open Source Project", adb.developers)
        assertEquals("Source (AOSP)", adb.links.last().label)
        val server = entry("bundled:scrcpy-server")
        assertEquals(BuildInfo.SCRCPY_SERVER_VERSION, server.version, "config/libraries/scrcpy-server.json is behind the build")
        assertEquals("Apache 2.0", server.licenseLabel)
        assertEquals("Genymobile, Romain Vimont", server.developers)
        assertEquals("https://github.com/Genymobile/scrcpy", server.links.single().url)
    }

    @Test
    fun `every licence has its text or a link to it`() {
        entries.flatMap { it.texts }.forEach { assertTrue(it.content != null || it.url != null, "${it.name}: no text or url") }
    }

    @Test
    fun `the JSON stays small`() {
        // It ships in the app and tests read it on a memory-tight runner; unused fields are excluded in the build.
        assertTrue(json.length < 400_000, "aboutlibraries.json is ${json.length} chars")
        assertTrue("\"funding\"" !in json && "\"developerConnection\"" !in json && "\"organisationUrl\"" !in json)
    }

    @Test
    fun `search matches names, licences and developers`() {
        assertEquals(listOf("bundled:ffmpeg"), filterLicenses(entries, "ffmpeg").map { it.id })
        assertTrue(filterLicenses(entries, "  KOIN ").any { it.name == "Koin" })
        assertTrue(filterLicenses(entries, "lgpl").any { it.id == "bundled:ffmpeg" })
        assertTrue(filterLicenses(entries, "Lesser General").any { it.id == "bundled:ffmpeg" })
        assertTrue(filterLicenses(entries, "genymobile").any { it.id == "bundled:scrcpy-server" })
        assertTrue(filterLicenses(entries, "MIT").any { it.licenseLabel == "MIT" })
        assertEquals(entries, filterLicenses(entries, " "))
        assertTrue(filterLicenses(entries, "zzzz").isEmpty())
    }

    @Test
    fun `count reads entries, or shown of all`() {
        assertEquals("112 entries", licenseCount(112, 112))
        assertEquals("1 of 112", licenseCount(1, 112))
    }

    @Test
    fun `labels`() {
        assertEquals("Source (scrcpy v5.0)", sourceLabel("https://github.com/Genymobile/scrcpy/tree/v5.0"))
        assertEquals("Source (scrcpy)", sourceLabel("https://github.com/Genymobile/scrcpy"))
        assertEquals("Source", sourceLabel("https://example.com/x"))
        assertEquals("github.com/kdroidFilter/ComposeNativeTray", websiteLabel("https://www.github.com/kdroidFilter/ComposeNativeTray/"))
    }
}
