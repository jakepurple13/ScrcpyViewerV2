package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Rect
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/**
 * Draws [marks], in order, onto [canvas] in image pixels (the canvas may carry a scale transform for display).
 * Draws only the marks, not [original]: the caller draws the image first. [original] is read for pixelate, which
 * always samples it and never the canvas, so overlapping or repeated pixelation reveals nothing beyond one pass.
 * Draws nothing for marks outside the image. Does not close anything. Pass the same [cache] on every redraw (the
 * editor does, per pointer move) so pixelate blocks are averaged once.
 */
fun drawMarks(canvas: Canvas, original: Bitmap, marks: List<Mark>, cache: PixelateCache = PixelateCache()) {
    cache.bind(original)
    val w = original.width
    val h = original.height
    val stroke = strokeWidth(w, h)
    for (mark in marks) {
        when (mark) {
            is Mark.Pen -> strokePaint(mark.color, stroke).use { paint ->
                val pts = mark.points
                if (pts.size == 1) {
                    paint.mode = PaintMode.FILL
                    canvas.drawCircle(pts[0].x, pts[0].y, stroke / 2f, paint)
                } else if (pts.size > 1) {
                    val builder = PathBuilder()
                    builder.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) builder.lineTo(pts[i].x, pts[i].y)
                    drawBuilt(canvas, builder, paint)
                }
            }

            is Mark.Arrow -> strokePaint(mark.color, stroke).use { paint ->
                val head = arrowHead(mark.from, mark.to, stroke)
                // End the shaft at the middle of the head's base so its round cap does not poke past the tip.
                val baseX = (head.left.x + head.right.x) / 2f
                val baseY = (head.left.y + head.right.y) / 2f
                val shaftLen = hypot(mark.to.x - mark.from.x, mark.to.y - mark.from.y)
                val headDepth = hypot(mark.to.x - baseX, mark.to.y - baseY)
                if (shaftLen > headDepth) canvas.drawLine(mark.from.x, mark.from.y, baseX, baseY, paint)
                paint.mode = PaintMode.FILL
                val builder = PathBuilder()
                builder.moveTo(head.tip.x, head.tip.y)
                builder.lineTo(head.left.x, head.left.y)
                builder.lineTo(head.right.x, head.right.y)
                builder.closePath()
                drawBuilt(canvas, builder, paint)
            }

            is Mark.Box -> strokePaint(mark.color, stroke).use { paint ->
                canvas.drawRect(
                    Rect.makeLTRB(
                        min(mark.from.x, mark.to.x), min(mark.from.y, mark.to.y),
                        max(mark.from.x, mark.to.x), max(mark.from.y, mark.to.y),
                    ),
                    paint,
                )
            }

            is Mark.Pixelate -> pixelate(canvas, original, mark, cache)
        }
    }
}

/** Draws the path [builder] built, then closes both (the builder is spent). */
private fun drawBuilt(canvas: Canvas, builder: PathBuilder, paint: Paint) {
    try {
        val path = builder.detach()
        try {
            canvas.drawPath(path, paint)
        } finally {
            path.close()
        }
    } finally {
        builder.close()
    }
}

private fun strokePaint(color: Int, stroke: Float) = Paint().apply {
    this.color = color
    isAntiAlias = true
    mode = PaintMode.STROKE
    strokeWidth = stroke
    strokeCap = PaintStrokeCap.ROUND
    strokeJoin = PaintStrokeJoin.ROUND
}

/**
 * Block colors of pixelate marks over one original, so each block is averaged once: a committed mark then only draws
 * rectangles, and a drag preview only averages the blocks its new edge exposes (blocks are aligned to the rectangle's
 * top-left, so the inner ones stay the same while the far corner moves). Keep one per editor; it starts over when used
 * with another original. UI thread only.
 */
class PixelateCache {
    private val colors = HashMap<Long, Int>()
    private var source: Bitmap? = null

    /** How many blocks were averaged so far (for tests). */
    var averaged = 0
        private set

    internal fun bind(original: Bitmap) {
        if (source !== original) {
            source = original
            colors.clear()
        }
        // A long drag leaves many partial edge blocks behind: never let them pile up.
        if (colors.size > MAX_BLOCKS) colors.clear()
    }

    internal operator fun get(key: Long): Int? = colors[key]

    internal fun put(key: Long, color: Int) {
        colors[key] = color
        averaged++
    }

    private companion object {
        const val MAX_BLOCKS = 200_000
    }
}

private fun blockKey(x: Int, y: Int, w: Int, h: Int): Long =
    (x.toLong() shl 48) or (y.toLong() shl 32) or (w.toLong() shl 16) or h.toLong()

/**
 * Fills the mark's rectangle (clamped to the image) with blocks, each the average of the original's pixels. Only the
 * pixels of blocks [cache] does not know yet are read, one strip per row of blocks.
 */
private fun pixelate(canvas: Canvas, original: Bitmap, mark: Mark.Pixelate, cache: PixelateCache) {
    val w = original.width
    val h = original.height
    val left = floor(max(0f, min(mark.from.x, mark.to.x))).toInt()
    val top = floor(max(0f, min(mark.from.y, mark.to.y))).toInt()
    val right = ceil(min(w.toFloat(), max(mark.from.x, mark.to.x))).toInt()
    val bottom = ceil(min(h.toFloat(), max(mark.from.y, mark.to.y))).toInt()
    val rw = right - left
    val rh = bottom - top
    if (rw <= 0 || rh <= 0) return
    val block = blockSize(w, h)
    val cols = (rw + block - 1) / block
    val rowColors = IntArray(cols)
    Paint().use { paint ->
        paint.isAntiAlias = false
        var by = 0
        while (by < rh) {
            val bh = min(block, rh - by)
            val y = top + by
            var firstMissing = -1
            var lastMissing = -1
            for (c in 0 until cols) {
                val x = left + c * block
                val color = cache[blockKey(x, y, min(block, right - x), bh)]
                if (color == null) {
                    if (firstMissing < 0) firstMissing = c
                    lastMissing = c
                } else {
                    rowColors[c] = color
                }
            }
            if (firstMissing >= 0) averageStrip(original, cache, left, right, y, bh, block, firstMissing, lastMissing, rowColors)
            for (c in 0 until cols) {
                val x = left + c * block
                // Screenshots are opaque, so the block is drawn opaque: it always hides what is under it.
                paint.color = rowColors[c]
                canvas.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), min(block, right - x).toFloat(), bh.toFloat()), paint)
            }
            by += block
        }
    }
}

/** Averages the blocks [first]..[last] of the block row at [y] (only those not in [cache]) into [out] and [cache]. */
private fun averageStrip(
    original: Bitmap, cache: PixelateCache, left: Int, right: Int, y: Int, bh: Int, block: Int, first: Int, last: Int,
    out: IntArray,
) {
    val sx = left + first * block
    val ex = min(right, left + (last + 1) * block)
    val sw = ex - sx
    val rowBytes = sw * 4
    // BGRA premultiplied, converted by Skia from whatever the original stores.
    val px = original.readPixels(ImageInfo(sw, bh, ColorType.BGRA_8888, ColorAlphaType.PREMUL), rowBytes, sx, y) ?: return
    for (c in first..last) {
        val x = left + c * block
        val bw = min(block, right - x)
        val key = blockKey(x, y, bw, bh)
        cache[key]?.let { out[c] = it; continue }
        var b = 0L
        var g = 0L
        var r = 0L
        for (row in 0 until bh) {
            var i = row * rowBytes + (x - sx) * 4
            for (k in 0 until bw) {
                b += px[i].toInt() and 0xFF
                g += px[i + 1].toInt() and 0xFF
                r += px[i + 2].toInt() and 0xFF
                i += 4
            }
        }
        val n = (bw * bh).toLong()
        val color = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        cache.put(key, color)
        out[c] = color
    }
}

/**
 * A new bitmap the size of [original] with [marks] drawn on a copy of it. The caller owns the result and must close
 * it; [original] is never modified or closed.
 */
fun renderMarked(original: ImageBitmap, marks: List<Mark>): ImageBitmap {
    val src = original.asSkiaBitmap()
    val out = Bitmap()
    try {
        out.allocN32Pixels(src.width, src.height)
        Canvas(out).use { canvas ->
            Image.makeFromBitmap(src).use { canvas.drawImage(it, 0f, 0f) }
            drawMarks(canvas, src, marks)
        }
        out.setImmutable()
        return out.asComposeImageBitmap()
    } catch (e: Throwable) {
        out.close()
        throw e
    }
}

/**
 * `<stem>-edited.png` next to [original], then `<stem>-edited-2.png`, `-3`, …: the first name not taken. Does not
 * touch the disk beyond checking for existing files.
 */
fun editedFile(original: Path): Path {
    val name = original.fileName.toString()
    val stem = name.substringBeforeLast('.', name).ifEmpty { name }
    var file = original.resolveSibling("$stem-edited.png")
    var n = 2
    while (Files.exists(file)) file = original.resolveSibling("$stem-edited-$n.png").also { n++ }
    return file
}

private val READABLE_BY_ALL = PosixFilePermissions.fromString("rw-r--r--")

/** rw-r--r-- where the file system has POSIX permissions; nothing elsewhere (Windows). */
private fun readableByAll(file: Path) {
    if ("posix" !in file.fileSystem.supportedFileAttributeViews()) return
    Files.setPosixFilePermissions(file, READABLE_BY_ALL)
}

/**
 * Renders [original] with [marks] and writes it as a PNG at [editedFile] of [originalFile], returning that path. Never
 * overwrites a file, including [originalFile]. The PNG is written to a temp file in the same folder and then moved
 * into place, so a failure leaves no half-written target. Does file I/O: call it off the UI thread.
 *
 * @throws IOException with a readable message when the image cannot be encoded or written.
 */
fun saveMarked(original: ImageBitmap, marks: List<Mark>, originalFile: Path): Path {
    val rendered = renderMarked(original, marks)
    try {
        return saveEdited(rendered, originalFile)
    } finally {
        rendered.asSkiaBitmap().close()
    }
}

/** [image] as PNG bytes. Does not close [image]. */
fun encodePng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).use { img -> img.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes } }
        ?: throw IOException("could not encode the image")

/**
 * Writes the already rendered [rendered] as [saveMarked] does (same naming, temp file, errors). Does not close
 * [rendered]. Does file I/O: call it off the UI thread, while nothing can close [rendered].
 */
fun saveEdited(rendered: ImageBitmap, originalFile: Path): Path {
    val bytes = encodePng(rendered)
    val dir = originalFile.toAbsolutePath().parent ?: throw IOException("the folder is unknown")
    var temp: Path? = null
    try {
        temp = Files.createTempFile(dir, ".markup-", ".tmp")
        Files.write(temp, bytes)
        // A temp file is owner-only; the saved screenshot reads like any other file the app writes.
        readableByAll(temp)
        repeat(5) {
            val target = editedFile(originalFile.toAbsolutePath())
            try {
                // No REPLACE_EXISTING: a name taken meanwhile fails instead of being overwritten.
                Files.move(temp, target)
                temp = null
                return target
            } catch (_: FileAlreadyExistsException) {
                // Taken since editedFile looked: pick the next name.
            }
        }
        throw IOException("could not find a free file name")
    } catch (e: NoSuchFileException) {
        throw IOException("the folder ${dir.fileName ?: dir} no longer exists", e)
    } catch (e: AccessDeniedException) {
        throw IOException("no permission to write to ${dir.fileName ?: dir}", e)
    } catch (e: IOException) {
        throw IOException(e.message?.takeIf { it.isNotBlank() } ?: "could not write the file", e)
    } catch (e: SecurityException) {
        throw IOException("no permission to write to ${dir.fileName ?: dir}", e)
    } finally {
        temp?.let { runCatching { Files.deleteIfExists(it) } }
    }
}
