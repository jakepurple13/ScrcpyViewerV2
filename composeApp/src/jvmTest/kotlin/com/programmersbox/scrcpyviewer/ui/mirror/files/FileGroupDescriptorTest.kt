package com.programmersbox.scrcpyviewer.ui.mirror.files

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class FileGroupDescriptorTest {
    private fun file(path: String, size: Long, dir: Boolean = false) = VirtualFile(path, size, dir, null)

    private fun name(b: ByteBuffer, item: Int): String {
        val start = 4 + item * FILE_DESCRIPTOR_SIZE + 72
        val chars = StringBuilder()
        for (i in 0 until 260) {
            val c = b.getChar(start + i * 2)
            if (c == '\u0000') break
            chars.append(c)
        }
        return chars.toString()
    }

    @Test
    fun `one FILEDESCRIPTORW per item after the count, little-endian`() {
        val bytes = fileGroupDescriptor(listOf(file("Camera", 0, dir = true), file("Camera/1.jpg", 0x1_0000_0005L)))
        assertEquals(4 + 2 * 592, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, b.getInt(0))

        val dir = 4
        assertEquals(0x80004044.toInt(), b.getInt(dir), "FD_UNICODE | FD_PROGRESSUI | FD_FILESIZE | FD_ATTRIBUTES")
        assertEquals(0x10, b.getInt(dir + 36), "FILE_ATTRIBUTE_DIRECTORY")
        assertEquals("Camera", name(b, 0))

        val f = 4 + 592
        assertEquals(0x80, b.getInt(f + 36), "FILE_ATTRIBUTE_NORMAL")
        assertEquals(1, b.getInt(f + 64), "nFileSizeHigh")
        assertEquals(5, b.getInt(f + 68), "nFileSizeLow")
        assertEquals("Camera\\1.jpg", name(b, 1), "Windows separators")
    }

    @Test
    fun `the longest allowed path still ends in a terminator`() {
        val path = "x".repeat(VIRTUAL_PATH_MAX)
        val b = ByteBuffer.wrap(fileGroupDescriptor(listOf(file(path, 1)))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(path, name(b, 0))
        assertEquals('\u0000', b.getChar(4 + 72 + VIRTUAL_PATH_MAX * 2))
    }
}
