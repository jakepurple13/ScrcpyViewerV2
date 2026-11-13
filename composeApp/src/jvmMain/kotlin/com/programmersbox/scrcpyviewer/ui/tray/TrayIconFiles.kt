package com.programmersbox.scrcpyviewer.ui.tray

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The tray icon as fixed files on disk, copied once from the bundled `tray/` resources.
 *
 * ComposeNativeTray's ImageVector/Painter `Tray` overloads re-render the icon to a fresh temp file on every
 * menu update, and on macOS the JNI wrapper frees the icon path strings while the Swift side still reads them
 * (a use-after-free that crashes the app). A stable path means the library sees an unchanged icon and never
 * calls back into native icon code. The native side needs a real file, so the resources are copied out.
 */
data class TrayIcons(val png: Path, val ico: Path)

/** Copies the bundled tray icons into [dir] (only when missing or changed) and returns their paths. */
fun trayIconFiles(dir: Path): TrayIcons {
    Files.createDirectories(dir)
    val icons = TrayIcons(png = dir.resolve("tray.png"), ico = dir.resolve("tray.ico"))
    writeIfChanged(icons.png, resource("tray/tray.png"))
    writeIfChanged(icons.ico, resource("tray/tray.ico"))
    return icons
}

private fun resource(name: String): ByteArray =
    checkNotNull(TrayIcons::class.java.classLoader.getResourceAsStream(name)) { "Missing resource $name" }
        .use { it.readBytes() }

private fun writeIfChanged(target: Path, bytes: ByteArray) {
    if (Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)) return
    val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    try {
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
