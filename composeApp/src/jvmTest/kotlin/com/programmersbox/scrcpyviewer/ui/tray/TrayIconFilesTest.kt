package com.programmersbox.scrcpyviewer.ui.tray

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayIconFilesTest {
    @Test
    fun `copies both files`() {
        val dir = Files.createTempDirectory("tray-icons")
        val icons = trayIconFiles(dir)
        assertEquals(dir.resolve("tray.png"), icons.png)
        assertEquals(dir.resolve("tray.ico"), icons.ico)
        assertTrue(Files.size(icons.png) > 0)
        assertTrue(Files.size(icons.ico) > 0)
    }

    @Test
    fun `same paths on repeat`() {
        val dir = Files.createTempDirectory("tray-icons")
        assertEquals(trayIconFiles(dir), trayIconFiles(dir))
        assertEquals(2, Files.list(dir).use { it.count() })
    }

    @Test
    fun `does not rewrite identical bytes`() {
        val dir = Files.createTempDirectory("tray-icons")
        val icons = trayIconFiles(dir)
        val old = FileTime.fromMillis(1_000_000_000_000L)
        Files.setLastModifiedTime(icons.png, old)
        trayIconFiles(dir)
        assertEquals(old, Files.getLastModifiedTime(icons.png))
    }

    @Test
    fun `rewrites changed bytes`() {
        val dir = Files.createTempDirectory("tray-icons")
        val icons = trayIconFiles(dir)
        val good = Files.readAllBytes(icons.png)
        Files.write(icons.png, byteArrayOf(1, 2, 3))
        trayIconFiles(dir)
        assertContentEquals(good, Files.readAllBytes(icons.png))
    }

    @Test
    fun `png is 64 wide`() {
        val icons = trayIconFiles(Files.createTempDirectory("tray-icons"))
        val image = ImageIO.read(icons.png.toFile())
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun `ico header has 5 sizes`() {
        val icons = trayIconFiles(Files.createTempDirectory("tray-icons"))
        val buf = ByteBuffer.wrap(Files.readAllBytes(icons.ico)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buf.getShort(0).toInt())
        assertEquals(1, buf.getShort(2).toInt())
        assertEquals(5, buf.getShort(4).toInt())
    }
}
