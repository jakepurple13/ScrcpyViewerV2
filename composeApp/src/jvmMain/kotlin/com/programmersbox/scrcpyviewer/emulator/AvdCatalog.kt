package com.programmersbox.scrcpyviewer.emulator

import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

internal enum class AvdKind { Phone, Tablet, Watch, Tv }

internal data class Avd(
    val id: String,
    val name: String,
    val api: Int?,
    val androidVersion: String?,
    val kind: AvdKind,
    val dir: Path,
)

/** `key=value` or `key = value` lines; blank lines and `#` comments are ignored; the value keeps any later `=`. */
internal fun parseIni(text: String): Map<String, String> = buildMap {
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        put(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
    }
}

private val versions = mapOf(
    36 to "16", 35 to "15", 34 to "14", 33 to "13", 32 to "12L", 31 to "12", 30 to "11", 29 to "10",
    28 to "9", 27 to "8.1", 26 to "8.0", 25 to "7.1", 24 to "7.0", 23 to "6.0", 22 to "5.1", 21 to "5.0",
)

internal fun androidVersion(api: Int): String? = versions[api]

internal fun avdKind(config: Map<String, String>): AvdKind {
    val tag = config["tag.id"].orEmpty().lowercase()
    if ("wear" in tag) return AvdKind.Watch
    if ("tv" in tag) return AvdKind.Tv
    if ("tablet" in config["hw.device.name"].orEmpty().lowercase()) return AvdKind.Tablet
    val w = config["hw.lcd.width"]?.toIntOrNull()
    val h = config["hw.lcd.height"]?.toIntOrNull()
    val density = config["hw.lcd.density"]?.toIntOrNull()
    if (w != null && h != null && density != null && density > 0 && minOf(w, h) * 160.0 / density >= 600) {
        return AvdKind.Tablet
    }
    return AvdKind.Phone
}

private val apiRegex = Regex("""android-(\d+)""")

/** The API level comes from `image.sysdir.1`; the outer ini's `target=` reads `android-0` for 36.1 images. */
private fun apiOf(config: Map<String, String>): Int? =
    apiRegex.find(config["image.sysdir.1"].orEmpty())?.groupValues?.get(1)?.toIntOrNull()

/** The AVDs of [avdHome], sorted by name (case-insensitive). An `.ini` whose `.avd` folder is gone is skipped. */
internal fun readAvds(avdHome: Path): List<Avd> {
    val inis = runCatching { Files.list(avdHome).use { s -> s.filter { it.extension == "ini" }.toList() } }
        .getOrDefault(emptyList())
    return inis.mapNotNull { ini ->
        runCatching {
            val id = ini.nameWithoutExtension
            val dir = parseIni(Files.readString(ini))["path"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: return@runCatching null
            if (!Files.isDirectory(dir)) return@runCatching null
            val configFile = dir.resolve("config.ini")
            // An unreadable config.ini (not UTF-8, no permission) still lists the AVD, under its id.
            val config = if (Files.isRegularFile(configFile)) {
                runCatching { parseIni(Files.readString(configFile)) }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            val api = apiOf(config)
            Avd(
                id = id,
                name = config["avd.ini.displayname"]?.takeIf { it.isNotBlank() } ?: id,
                api = api,
                androidVersion = api?.let(::androidVersion),
                kind = avdKind(config),
                dir = dir,
            )
        }.getOrNull()
    }.sortedWith(compareBy<Avd, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
}

/**
 * Size on disk of [dir]: `du -sk` on macOS and Linux, the sum of file sizes on Windows. Null when unreadable, or
 * when `du` takes longer than [timeoutMs] (it is then killed). [du] builds the command (tests swap in a slow one).
 */
internal fun avdSize(
    dir: Path,
    timeoutMs: Long = 30_000,
    du: (Path) -> List<String> = { listOf("du", "-sk", it.toString()) },
): Long? {
    if (!Files.isDirectory(dir)) return null
    return runCatching {
        if (OpenPlatform.current() == OpenPlatform.Windows) {
            Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        } else {
            // Output to a file, so the wait below is what bounds the call (reading a pipe first would block on du).
            val out = Files.createTempFile("avd-size", ".txt")
            try {
                val p = ProcessBuilder(du(dir)).redirectErrorStream(true).redirectOutput(out.toFile()).start()
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    return@runCatching null
                }
                Files.readString(out).trim().substringBefore('\t').substringBefore(' ').toLongOrNull()?.times(1024)
            } finally {
                Files.deleteIfExists(out)
            }
        }
    }.getOrNull()
}

/** "8.2 GB" or "512.0 MB": one decimal, binary units. */
internal fun formatSize(bytes: Long): String {
    val gb = 1024.0 * 1024 * 1024
    return if (bytes >= gb) String.format(Locale.ROOT, "%.1f GB", bytes / gb)
    else String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}
