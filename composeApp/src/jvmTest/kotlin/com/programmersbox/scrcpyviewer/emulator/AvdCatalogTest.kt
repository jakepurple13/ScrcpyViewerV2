package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvdCatalogTest {
    private val home: Path = Files.createTempDirectory("avd-test")

    private fun avd(id: String, config: String, withFolder: Boolean = true) {
        val dir = home.resolve("$id.avd")
        if (withFolder) {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("config.ini"), config)
        }
        Files.writeString(home.resolve("$id.ini"), "avd.ini.encoding=UTF-8\npath=$dir\ntarget=android-0\n")
    }

    @Test
    fun `ini with spaces around equals`() {
        val m = parseIni("a=1\nb = two words \n# c=3\n\nd=x=y\n  e  =  5\r\n")
        assertEquals("1", m["a"])
        assertEquals("two words", m["b"])
        assertNull(m["c"])
        assertEquals("x=y", m["d"])
        assertEquals("5", m["e"])
    }

    @Test
    fun `api from image sysdir including 36_1`() {
        avd("Pixel_9", "image.sysdir.1 = system-images/android-36.1/google_apis/arm64-v8a/\n")
        avd("Old", "image.sysdir.1=system-images/android-34/google_apis/x86_64/\n")
        avd("None", "hw.lcd.width=1080\n")
        val byId = readAvds(home).associateBy { it.id }
        assertEquals(36, byId.getValue("Pixel_9").api)
        assertEquals("16", byId.getValue("Pixel_9").androidVersion)
        assertEquals(34, byId.getValue("Old").api)
        assertNull(byId.getValue("None").api)
        assertNull(byId.getValue("None").androidVersion)
    }

    @Test
    fun `version table`() {
        val expected = mapOf(
            36 to "16", 35 to "15", 34 to "14", 33 to "13", 32 to "12L", 31 to "12", 30 to "11", 29 to "10",
            28 to "9", 27 to "8.1", 26 to "8.0", 25 to "7.1", 24 to "7.0", 23 to "6.0", 22 to "5.1", 21 to "5.0",
        )
        expected.forEach { (api, v) -> assertEquals(v, androidVersion(api), "api $api") }
        assertNull(androidVersion(20))
        assertNull(androidVersion(99))
    }

    @Test
    fun `watch tv tablet phone kinds`() {
        assertEquals(AvdKind.Watch, avdKind(mapOf("tag.id" to "android-wear")))
        assertEquals(AvdKind.Watch, avdKind(mapOf("tag.id" to "wear-cn")))
        assertEquals(AvdKind.Tv, avdKind(mapOf("tag.id" to "android-tv")))
        assertEquals(AvdKind.Tv, avdKind(mapOf("tag.id" to "google-tv")))
        val named = mapOf("hw.device.name" to "pixel_tablet", "tag.id" to "google_apis_playstore")
        assertEquals(AvdKind.Tablet, avdKind(named))
        // 1600x2560 at 320 dpi: shorter side 800 dp.
        val big = mapOf("hw.lcd.width" to "1600", "hw.lcd.height" to "2560", "hw.lcd.density" to "320")
        assertEquals(AvdKind.Tablet, avdKind(big))
        // 1080x2400 at 420 dpi: shorter side about 411 dp.
        val small = mapOf("hw.lcd.width" to "1080", "hw.lcd.height" to "2400", "hw.lcd.density" to "420")
        assertEquals(AvdKind.Phone, avdKind(small))
        assertEquals(AvdKind.Phone, avdKind(emptyMap()))
    }

    @Test
    fun `display name falls back to id`() {
        avd("heads_up_api34", "image.sysdir.1=system-images/android-34/x/\n")
        avd("Pixel_9", "avd.ini.displayname = Pixel 9\n")
        val byId = readAvds(home).associateBy { it.id }
        assertEquals("heads_up_api34", byId.getValue("heads_up_api34").name)
        assertEquals("Pixel 9", byId.getValue("Pixel_9").name)
        assertEquals(home.resolve("Pixel_9.avd"), byId.getValue("Pixel_9").dir)
    }

    @Test
    fun `ini with missing folder is skipped`() {
        avd("Real", "")
        avd("Gone", "", withFolder = false)
        assertEquals(listOf("Real"), readAvds(home).map { it.id })
        assertTrue(readAvds(home.resolve("nowhere")).isEmpty())
    }

    @Test
    fun `sorted by name`() {
        avd("a", "avd.ini.displayname=zeta\n")
        avd("b", "avd.ini.displayname=Alpha\n")
        avd("c", "avd.ini.displayname=beta\n")
        assertEquals(listOf("Alpha", "beta", "zeta"), readAvds(home).map { it.name })
    }

    @Test
    fun `size formatting`() {
        assertEquals("8.2 GB", formatSize(8_800_000_000L))
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
        assertEquals("512.0 MB", formatSize(512L * 1024 * 1024))
        assertEquals("0.0 MB", formatSize(0))
    }

    @Test
    fun `size of a folder`() {
        val dir = Files.createDirectories(home.resolve("sized"))
        Files.write(dir.resolve("f"), ByteArray(300_000))
        val size = avdSize(dir)!!
        assertTrue(size >= 300_000, "size $size")
        assertNull(avdSize(home.resolve("missing")))
    }

    @Test
    fun `size gives up when du hangs`() {
        if (OpenPlatform.current() == OpenPlatform.Windows) return
        val dir = Files.createDirectories(home.resolve("slow"))
        val started = System.nanoTime()
        assertNull(avdSize(dir, timeoutMs = 300, du = { listOf("sleep", "20") }))
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "returned after the timeout, not after du")
    }

    @Test
    fun `cancelling a size measurement kills du`() = runBlocking {
        if (OpenPlatform.current() == OpenPlatform.Windows) return@runBlocking
        val dir = Files.createDirectories(home.resolve("cancelled"))
        val pidFile = home.resolve("du.pid")
        val job = launch(Dispatchers.IO) {
            runInterruptible { avdSize(dir, du = { listOf("sh", "-c", "echo $$ > '$pidFile'; exec sleep 20") }) }
        }
        // du is running once the shell has written its pid (exec keeps the pid for sleep).
        var proc: ProcessHandle? = null
        withTimeout(10_000) {
            while (proc == null) {
                proc = runCatching { Files.readString(pidFile).trim().toLong() }.getOrNull()?.let { ProcessHandle.of(it).orElse(null) }
                if (proc == null) delay(20)
            }
        }
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "cancel did not wait for du")
        withTimeout(5_000) { while (proc!!.isAlive) delay(20) }
    }

    @Test
    fun `fingerprint changes when a file in the folder changes`() {
        val dir = Files.createDirectories(home.resolve("print"))
        Files.write(dir.resolve("userdata.img"), ByteArray(10))
        val before = avdFingerprint(dir)
        assertEquals(before, avdFingerprint(dir), "stable while nothing changes")
        Files.write(dir.resolve("userdata.img"), ByteArray(20))
        assertTrue(before != avdFingerprint(dir), "a grown image changes it")
        assertNull(avdFingerprint(home.resolve("missing")))
    }

    @Test
    fun `unreadable config still lists the avd`() {
        val dir = Files.createDirectories(home.resolve("Bad.avd"))
        Files.write(dir.resolve("config.ini"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x0A))
        Files.writeString(home.resolve("Bad.ini"), "path=$dir\n")
        val avd = readAvds(home).single()
        assertEquals("Bad", avd.id)
        assertEquals("Bad", avd.name)
        assertEquals(AvdKind.Phone, avd.kind)
    }
}
