package com.programmersbox.scrcpyviewer.files

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class FileKind { Folder, Image, Video, Apk, Audio, Document }

private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
private val VIDEO = setOf("mp4", "mkv", "mov", "webm", "3gp")
private val AUDIO = setOf("mp3", "m4a", "wav", "ogg", "flac", "opus")

fun kindOf(entry: DeviceEntry): FileKind {
    if (entry.isDir) return FileKind.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        in IMAGE -> FileKind.Image
        in VIDEO -> FileKind.Video
        in AUDIO -> FileKind.Audio
        "apk" -> FileKind.Apk
        else -> FileKind.Document
    }
}

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

/** "812 B", "2.1 KB" (one decimal below 10), "209 KB", "4.1 MB", "2.05 GB". */
fun formatSize(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        b < KB -> "$bytes B"
        b < MB -> if (b / KB < 10) "%.1f KB".format(Locale.US, b / KB) else "%.0f KB".format(Locale.US, b / KB)
        b < GB -> "%.1f MB".format(Locale.US, b / MB)
        else -> "%.2f GB".format(Locale.US, b / GB)
    }
}

fun formatModified(millis: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatItemCount(n: Int): String = if (n == 1) "1 item" else "$n items"

/** "41.2 GB free"; smaller amounts fall back to [formatSize]. */
fun formatFree(bytes: Long): String =
    if (bytes >= GB) "%.1f GB free".format(Locale.US, bytes / GB) else "${formatSize(bytes)} free"
