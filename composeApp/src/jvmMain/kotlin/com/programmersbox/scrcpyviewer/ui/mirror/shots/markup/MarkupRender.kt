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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/**
 * Draws [marks], in order, onto [canvas] in image pixels (the canvas may carry a scale transform for display).
 * Draws only the marks, not [original]: the caller draws the image first. [original] is read for pixelate, which
 * always samples it and never the canvas, so overlapping or repeated pixelation reveals nothing beyond one pass.
 * Draws nothing for marks outside the image. Does not close anything.
 */
fun drawMarks(canvas: Canvas, original: Bitmap, marks: List<Mark>) {
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

            is Mark.Pixelate -> pixelate(canvas, original, mark)
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

/** Fills the mark's rectangle (clamped to the image) with blocks, each the average of the original's pixels. */
private fun pixelate(canvas: Canvas, original: Bitmap, mark: Mark.Pixelate) {
    val w = original.width
    val h = original.height
    val left = floor(max(0f, min(mark.from.x, mark.to.x))).toInt()
    val top = floor(max(0f, min(mark.from.y, mark.to.y))).toInt()
    val right = ceil(min(w.toFloat(), max(mark.from.x, mark.to.x))).toInt()
    val bottom = ceil(min(h.toFloat(), max(mark.from.y, mark.to.y))).toInt()
    val rw = right - left
    val rh = bottom - top
    if (rw <= 0 || rh <= 0) return
    val rowBytes = rw * 4
    // BGRA premultiplied, converted by Skia from whatever the original stores.
    val px = original.readPixels(ImageInfo(rw, rh, ColorType.BGRA_8888, ColorAlphaType.PREMUL), rowBytes, left, top)
        ?: return
    val block = blockSize(w, h)
    Paint().use { paint ->
        paint.isAntiAlias = false
        var by = 0
        while (by < rh) {
            val bh = min(block, rh - by)
            var bx = 0
            while (bx < rw) {
                val bw = min(block, rw - bx)
                var b = 0L
                var g = 0L
                var r = 0L
                for (y in by until by + bh) {
                    var i = y * rowBytes + bx * 4
                    for (x in 0 until bw) {
                        b += px[i].toInt() and 0xFF
                        g += px[i + 1].toInt() and 0xFF
                        r += px[i + 2].toInt() and 0xFF
                        i += 4
                    }
                }
                val n = (bw * bh).toLong()
                // Screenshots are opaque, so the block is drawn opaque: it always hides what is under it.
                paint.color = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                canvas.drawRect(
                    Rect.makeXYWH((left + bx).toFloat(), (top + by).toFloat(), bw.toFloat(), bh.toFloat()),
                    paint,
                )
                bx += block
            }
            by += block
        }
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

/**
 * Renders [original] with [marks] and writes it as a PNG at [editedFile] of [originalFile], returning that path. Never
 * overwrites a file, including [originalFile]. The PNG is written to a temp file in the same folder and then moved
 * into place, so a failure leaves no half-written target. Does file I/O: call it off the UI thread.
 *
 * @throws IOException with a readable message when the image cannot be encoded or written.
 */
fun saveMarked(original: ImageBitmap, marks: List<Mark>, originalFile: Path): Path {
    val rendered = renderMarked(original, marks)
    val bytes = try {
        Image.makeFromBitmap(rendered.asSkiaBitmap()).use { it.encodeToData(EncodedImageFormat.PNG) }?.bytes
    } finally {
        rendered.asSkiaBitmap().close()
    } ?: throw IOException("could not encode the image")
    val dir = originalFile.toAbsolutePath().parent ?: throw IOException("the folder is unknown")
    var temp: Path? = null
    try {
        temp = Files.createTempFile(dir, ".markup-", ".tmp")
        Files.write(temp, bytes)
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
