package com.programmersbox.scrcpyviewer.files

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FileKindTest {
    private fun file(name: String) = DeviceEntry(name, isDir = false, size = 1, mtimeMillis = 0)

    @Test
    fun kinds() {
        assertEquals(FileKind.Folder, kindOf(DeviceEntry("x.jpg", true, 0, 0)))
        for (e in listOf("jpg", "jpeg", "png", "gif", "webp", "heic")) assertEquals(FileKind.Image, kindOf(file("a.$e")), e)
        for (e in listOf("mp4", "mkv", "mov", "webm", "3gp")) assertEquals(FileKind.Video, kindOf(file("a.$e")), e)
        for (e in listOf("mp3", "m4a", "wav", "ogg", "flac", "opus")) assertEquals(FileKind.Audio, kindOf(file("a.$e")), e)
        assertEquals(FileKind.Apk, kindOf(file("app.apk")))
        assertEquals(FileKind.Image, kindOf(file("PHOTO.JPG")))
        assertEquals(FileKind.Document, kindOf(file("notes.txt")))
        assertEquals(FileKind.Document, kindOf(file("noext")))
        assertEquals(FileKind.Document, kindOf(file(".png.")))
    }

    @Test
    fun sizes() {
        assertEquals("4.1 MB", formatSize(4_312_448))
        assertEquals("209 KB", formatSize(214_337))
        assertEquals("2.1 KB", formatSize(2_148))
        assertEquals("12.4 MB", formatSize(12_998_656))
        assertEquals("812 KB", formatSize(812 * 1024L))
        assertEquals("2.05 GB", formatSize((2.05 * 1024 * 1024 * 1024).toLong()))
        assertEquals("0 B", formatSize(0))
        assertEquals("999 B", formatSize(999))
    }

    @Test
    fun modifiedItemsFree() {
        val zone = ZoneId.of("America/Denver")
        val millis = ZonedDateTime.of(2026, 11, 8, 14, 32, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Nov 8, 2:32 PM", formatModified(millis, zone, Locale.US))
        assertEquals("1 item", formatItemCount(1))
        assertEquals("12 items", formatItemCount(12))
        assertEquals("0 items", formatItemCount(0))
        assertEquals("41.2 GB free", formatFree((41.2 * 1024 * 1024 * 1024).toLong()))
    }
}
