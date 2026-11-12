package com.programmersbox.scrcpyviewer.apps

import com.programmersbox.scrcpyviewer.adb.AdbClient
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.dongliu.apk.parser.AbstractApkFile
import net.dongliu.apk.parser.bean.AdaptiveIcon
import net.dongliu.apk.parser.bean.ApkSignStatus
import net.dongliu.apk.parser.bean.Icon
import net.dongliu.apk.parser.bean.IconFace
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** Reading an entry from the device failed (not a missing entry): the icon is not known to be missing. */
class EntryReadException(cause: Throwable) : IOException(cause.message, cause)

/**
 * An APK read one entry at a time through [readEntry] (null for a missing entry), so finding an icon fetches the
 * manifest, `resources.arsc` and the icon files, never the whole APK. Entries are kept once read. Signature checks
 * are not supported.
 */
class EntryApk(private val readEntry: (String) -> ByteArray?) : AbstractApkFile() {
    private val entries = HashMap<String, ByteArray?>()

    override fun getFileData(path: String): ByteArray? = entries.getOrPut(path) { readEntry(path) }

    override fun getAllCertificateData(): List<CertificateFile> = emptyList()

    override fun fileData(): ByteBuffer = throw UnsupportedOperationException("Not a whole APK")

    override fun verifyApk(): ApkSignStatus = ApkSignStatus.notSigned
}

/** Turns an APK's launcher icon faces into one square PNG, or null when none is a bitmap Skia can decode. */
object IconRender {
    /** Output edge in pixels. */
    const val SIZE = 192

    /**
     * An adaptive icon with a bitmap foreground wins (its background bitmap under it, or white): the layers are
     * 108 dp with the inner 72 dp visible, so they are drawn 1.5× and centred. Otherwise the densest legacy bitmap.
     * Vector (XML) layers cannot be drawn and are skipped.
     */
    fun render(faces: List<IconFace>): ByteArray? {
        for (adaptive in faces.filterIsInstance<AdaptiveIcon>()) {
            val fg = adaptive.foreground?.data?.let(::decode) ?: continue
            val bg = adaptive.background?.data?.let(::decode)
            return draw { canvas ->
                val layer = Rect.makeXYWH(-SIZE / 4f, -SIZE / 4f, SIZE * 1.5f, SIZE * 1.5f)
                if (bg != null) canvas.drawImageRect(bg, bounds(bg), layer, SamplingMode.LINEAR, null, true) else canvas.clear(WHITE)
                canvas.drawImageRect(fg, bounds(fg), layer, SamplingMode.LINEAR, null, true)
            }
        }
        val legacy = faces.filterIsInstance<Icon>().sortedByDescending { it.density }
            .firstNotNullOfOrNull { icon -> icon.data?.let(::decode) } ?: return null
        return draw { it.drawImageRect(legacy, bounds(legacy), Rect.makeWH(SIZE.toFloat(), SIZE.toFloat()), SamplingMode.LINEAR, null, true) }
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun bounds(image: Image): Rect = Rect.makeWH(image.width.toFloat(), image.height.toFloat())

    /** Null for anything Skia cannot decode, binary XML (a vector or a color) included. */
    private fun decode(bytes: ByteArray): Image? =
        if (bytes.isEmpty()) null else runCatching { Image.makeFromEncoded(bytes) }.getOrNull()

    private fun draw(block: (Canvas) -> Unit): ByteArray? = Surface.makeRasterN32Premul(SIZE, SIZE).use { surface ->
        block(surface.canvas)
        surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)?.bytes }
    }
}

/**
 * Loads app icons from [serial]: finds the APK with `pm path`, reads the icon out of it with the device's `unzip`
 * (Android 9+) and caches the PNG in [cacheDir] by package and version code. An app without a bitmap icon is
 * remembered as such and not fetched again; an adb failure is not remembered. At most [parallel] apps load at once.
 */
open class AppIconLoader(
    private val adb: AdbClient,
    private val serial: String,
    private val cacheDir: Path?,
    private val parallel: Semaphore = Semaphore(3),
) {
    /** [app]'s icon as PNG bytes; null when it has none Skia can draw. Throws [IOException] when adb fails. */
    open suspend fun load(app: DeviceApp): ByteArray? {
        val png = cacheDir?.resolve(cacheName(app, "png"))
        val none = cacheDir?.resolve(cacheName(app, "none"))
        val cached = withContext(Dispatchers.IO) {
            when {
                png != null && Files.isRegularFile(png) -> Cached.Icon(Files.readAllBytes(png))
                none != null && Files.exists(none) -> Cached.NoIcon
                else -> null
            }
        }
        when (cached) {
            is Cached.Icon -> return cached.bytes
            Cached.NoIcon -> return null
            null -> Unit
        }
        val bytes = parallel.withPermit { fetch(app.packageName) }
        withContext(Dispatchers.IO) {
            runCatching {
                cacheDir?.let { Files.createDirectories(it) }
                if (bytes != null) png?.let { Files.write(it, bytes) } else none?.let { Files.write(it, ByteArray(0)) }
            }
        }
        return bytes
    }

    private suspend fun fetch(packageName: String): ByteArray? {
        val apk = adb.shell(serial, "pm path ${DeviceApps.quote(packageName)}").lineSequence()
            .map { it.trim().removePrefix("package:") }.filter { it.endsWith(".apk") }
            .sortedBy { if (it.endsWith("/base.apk")) 0 else 1 }.firstOrNull()
            ?: return null
        return withContext(Dispatchers.IO) {
            val context = coroutineContext
            // apk-parser reads entries synchronously, so each read blocks this IO thread on its own adb call.
            val reader = EntryApk { entry ->
                context.ensureActive()
                try {
                    runBlocking { adb.execOut(serial, "unzip -p ${DeviceApps.quote(apk)} ${DeviceApps.quote(entry)} 2>/dev/null") }
                        .takeIf { it.isNotEmpty() }
                } catch (e: IOException) {
                    throw EntryReadException(e)
                }
            }
            runInterruptible {
                try {
                    reader.use { IconRender.render(it.allIcons) }
                } catch (e: EntryReadException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // no manifest (no `unzip` on the device) or a resource table apk-parser cannot read
                }
            }
        }
    }

    private fun cacheName(app: DeviceApp, ext: String): String =
        "${app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")}@${app.versionCode ?: 0}.$ext"

    private sealed interface Cached {
        class Icon(val bytes: ByteArray) : Cached
        data object NoIcon : Cached
    }
}
