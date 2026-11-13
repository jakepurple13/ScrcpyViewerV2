package com.programmersbox.scrcpyviewer.apps

import com.programmersbox.scrcpyviewer.adb.AdbClient
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.dongliu.apk.parser.bean.AdaptiveIcon
import net.dongliu.apk.parser.bean.Icon
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/** A [size]² PNG filled with [argb]. */
private fun png(argb: Int, size: Int = 48): ByteArray = Surface.makeRasterN32Premul(size, size).use { s ->
    s.canvas.clear(argb)
    s.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}

/** The ARGB color at ([x], [y]) of PNG [bytes]. */
private fun pixel(bytes: ByteArray, x: Int, y: Int): Int {
    val image = Image.makeFromEncoded(bytes)
    val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
    image.readPixels(bitmap, 0, 0)
    return bitmap.getColor(x, y)
}

private val BINARY_XML = byteArrayOf(3, 0, 8, 0, 0, 0, 0, 0)

class AppIconsTest {
    @Test
    fun `a legacy bitmap renders at the output size`() {
        val out = assertNotNull(IconRender.render(listOf(Icon("res/mipmap-mdpi/ic.png", 160, png(0xFF0000FF.toInt())))))
        val image = Image.makeFromEncoded(out)
        assertEquals(IconRender.SIZE, image.width)
        assertEquals(0xFF0000FF.toInt(), pixel(out, 96, 96))
    }

    @Test
    fun `the densest decodable legacy icon wins`() {
        val out = assertNotNull(
            IconRender.render(
                listOf(
                    Icon("res/a.png", 160, png(0xFFFF0000.toInt())),
                    Icon("res/b.xml", 640, BINARY_XML),
                    Icon("res/c.png", 480, png(0xFF00FF00.toInt())),
                ),
            ),
        )
        assertEquals(0xFF00FF00.toInt(), pixel(out, 10, 10))
    }

    @Test
    fun `an adaptive icon draws its foreground over its background`() {
        val fgBytes = Surface.makeRasterN32Premul(108, 108).use { s ->
            s.canvas.clear(0)
            s.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(36f, 36f, 36f, 36f), org.jetbrains.skia.Paint().apply { color = 0xFF00FF00.toInt() })
            s.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val adaptive = AdaptiveIcon(Icon("fg.png", 0, fgBytes), Icon("bg.png", 0, png(0xFF0000FF.toInt(), 108)))
        val out = assertNotNull(IconRender.render(listOf(adaptive)))
        assertEquals(0xFF00FF00.toInt(), pixel(out, 96, 96)) // the foreground's centre fills the middle
        assertEquals(0xFF0000FF.toInt(), pixel(out, 2, 2)) // the background shows at the corners
    }

    @Test
    fun `vector-only icons render nothing`() {
        assertNull(IconRender.render(listOf(AdaptiveIcon(Icon("fg.xml", 0, BINARY_XML), null), Icon("ic.xml", 0, BINARY_XML))))
    }

    @Test
    fun `an app without an APK path is cached as iconless`() = runBlocking {
        val dir = Files.createTempDirectory("icons")
        var calls = 0
        val adb = object : AdbClient(adbPath = { error("no adb") }) {
            override suspend fun shell(serial: String, cmd: String): String {
                calls++
                return ""
            }
        }
        val app = DeviceApp("com.a", "A", false, "1", 3)
        val loader = AppIconLoader(adb, "SER", dir)
        assertNull(loader.load(app))
        assertNull(loader.load(app))
        assertEquals(1, calls)
        assertEquals(listOf("com.a@3.none"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a cached icon is read from disk`() = runBlocking {
        val dir = Files.createTempDirectory("icons")
        val bytes = png(0xFF123456.toInt())
        Files.write(dir.resolve("com.a@3.png"), bytes)
        val adb = object : AdbClient(adbPath = { error("no adb") }) {
            override suspend fun shell(serial: String, cmd: String): String = error("must not ask the device")
        }
        assertContentEquals(bytes, AppIconLoader(adb, "SER", dir).load(DeviceApp("com.a", "A", false, "1", 3)))
    }

    @Test
    fun `an adb failure is thrown and not cached`() = runBlocking {
        val dir = Files.createTempDirectory("icons")
        val adb = object : AdbClient(adbPath = { error("no adb") }) {
            override suspend fun shell(serial: String, cmd: String): String = "package:/data/app/x/base.apk\n"
            override suspend fun execOut(serial: String, cmd: String): ByteArray = throw SocketTimeoutException("Read timed out")
        }
        assertFailsWith<EntryReadException> { AppIconLoader(adb, "SER", dir).load(DeviceApp("com.a", "A", false, "1", 3)) }
        assertEquals(0L, Files.list(dir).use { it.count() })
    }
}
