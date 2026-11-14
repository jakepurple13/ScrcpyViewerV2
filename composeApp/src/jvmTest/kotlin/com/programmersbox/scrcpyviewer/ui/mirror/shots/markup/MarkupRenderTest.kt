package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Rendering marks onto a copy, pixelate from the original, save naming and failures. */
class MarkupRenderTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val dirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        closeables.forEach { it.close() }
        dirs.forEach { runCatching { it.toFile().setWritable(true) }; runCatching { it.toFile().deleteRecursively() } }
    }

    private fun tempDir(): Path = Files.createTempDirectory("markup").also { dirs.add(it) }

    /** A [w]x[h] bitmap whose pixels all differ. */
    private fun gradient(w: Int, h: Int): Bitmap {
        val bmp = Bitmap().apply { allocN32Pixels(w, h) }
        closeables += bmp
        for (y in 0 until h) for (x in 0 until w) {
            val c = 0xFF000000.toInt() or ((x * 7 and 0xFF) shl 16) or ((y * 11 and 0xFF) shl 8) or ((x + y) * 3 and 0xFF)
            bmp.erase(c, IRect.makeXYWH(x, y, 1, 1))
        }
        bmp.setImmutable()
        return bmp
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val out = IntArray(bmp.width * bmp.height)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) out[y * bmp.width + x] = bmp.getColor(x, y)
        return out
    }

    private fun rendered(original: Bitmap, marks: List<Mark>): Bitmap =
        renderMarked(original.asComposeImageBitmap(), marks).asSkiaBitmap().also { closeables += it }

    @Test
    fun renderSizeEqualsOriginalAndOriginalUntouched() {
        val orig = gradient(120, 90)
        val before = pixels(orig)
        val out = rendered(orig, listOf(Mark.Box(MarkupColor.Red.argb, MarkPoint(5f, 5f), MarkPoint(60f, 50f))))
        assertEquals(120, out.width)
        assertEquals(90, out.height)
        assertContentEquals(before, pixels(orig))
    }

    @Test
    fun noMarksRendersIdenticalPixels() {
        val orig = gradient(40, 30)
        assertContentEquals(pixels(orig), pixels(rendered(orig, emptyList())))
    }

    @Test
    fun boxDrawsStrokeOnEdgeNotInside() {
        val orig = gradient(300, 300)
        val out = rendered(orig, listOf(Mark.Box(MarkupColor.Blue.argb, MarkPoint(50f, 50f), MarkPoint(250f, 250f))))
        assertEquals(MarkupColor.Blue.argb, out.getColor(150, 50))
        assertEquals(orig.getColor(150, 150), out.getColor(150, 150))
    }

    @Test
    fun penDrawsAlongItsPoints() {
        val orig = gradient(200, 200)
        val pen = Mark.Pen(
            MarkupColor.Yellow.argb,
            listOf(MarkPoint(20f, 100f), MarkPoint(100f, 100f), MarkPoint(180f, 100f)),
        )
        val out = rendered(orig, listOf(pen))
        assertEquals(MarkupColor.Yellow.argb, out.getColor(100, 100))
        assertEquals(orig.getColor(100, 20), out.getColor(100, 20))
    }

    @Test
    fun arrowDrawsShaftAndHead() {
        val orig = gradient(300, 300)
        val out = rendered(orig, listOf(Mark.Arrow(MarkupColor.Black.argb, MarkPoint(20f, 150f), MarkPoint(280f, 150f))))
        assertEquals(MarkupColor.Black.argb, out.getColor(100, 150))
        assertEquals(MarkupColor.Black.argb, out.getColor(270, 150))
        assertEquals(orig.getColor(100, 100), out.getColor(100, 100))
    }

    @Test
    fun pixelateAveragesOriginalBlocks() {
        val orig = gradient(200, 200) // block = 8
        val out = rendered(orig, listOf(Mark.Pixelate(MarkPoint(16f, 16f), MarkPoint(48f, 48f))))
        val block = (16 until 24).flatMap { y -> (16 until 24).map { x -> out.getColor(x, y) } }.toSet()
        assertEquals(1, block.size)
        var r = 0
        var g = 0
        var b = 0
        for (y in 16 until 24) for (x in 16 until 24) {
            val c = orig.getColor(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val c = block.first()
        assertTrue(abs(((c shr 16) and 0xFF) - r / 64) <= 1)
        assertTrue(abs(((c shr 8) and 0xFF) - g / 64) <= 1)
        assertTrue(abs((c and 0xFF) - b / 64) <= 1)
        assertNotEquals(out.getColor(16, 16), out.getColor(24, 16))
        // Outside the rect is untouched.
        assertEquals(orig.getColor(60, 60), out.getColor(60, 60))
        assertEquals(orig.getColor(10, 30), out.getColor(10, 30))
    }

    @Test
    fun pixelatingTwiceEqualsOncePass() {
        val orig = gradient(200, 200)
        val once = Mark.Pixelate(MarkPoint(16f, 16f), MarkPoint(96f, 80f))
        assertContentEquals(pixels(rendered(orig, listOf(once))), pixels(rendered(orig, listOf(once, once))))
    }

    @Test
    fun overlappingPixelateSamplesTheOriginalNotThePixelatedCanvas() {
        val orig = gradient(200, 200)
        val outer = Mark.Pixelate(MarkPoint(16f, 16f), MarkPoint(96f, 96f))
        val inner = Mark.Pixelate(MarkPoint(30f, 30f), MarkPoint(70f, 70f))
        val both = rendered(orig, listOf(outer, inner))
        val innerOnly = rendered(orig, listOf(inner))
        // Inside the inner rect the result is the same as sampling the original directly.
        for (y in 30 until 70 step 3) for (x in 30 until 70 step 3) {
            assertEquals(innerOnly.getColor(x, y), both.getColor(x, y), "at $x,$y")
        }
    }

    @Test
    fun penOverPixelateKeepsThePenAndPixelateOverPenHidesIt() {
        val orig = gradient(200, 200)
        val pen = Mark.Pen(MarkupColor.Red.argb, listOf(MarkPoint(10f, 60f), MarkPoint(190f, 60f)))
        val pix = Mark.Pixelate(MarkPoint(40f, 40f), MarkPoint(120f, 80f))
        assertEquals(MarkupColor.Red.argb, rendered(orig, listOf(pix, pen)).getColor(80, 60))
        // A pixelate drawn after the pen covers it with original-sourced blocks: no red remains inside.
        val covered = rendered(orig, listOf(pen, pix))
        for (y in 40 until 80) for (x in 40 until 120) assertNotEquals(MarkupColor.Red.argb, covered.getColor(x, y))
        // And inside a block no original detail survives.
        assertEquals(covered.getColor(48, 48), covered.getColor(55, 55))
    }

    @Test
    fun marksOutsideImageAreClamped() {
        val orig = gradient(100, 100)
        val out = rendered(orig, listOf(Mark.Pixelate(MarkPoint(-50f, -50f), MarkPoint(500f, 40f))))
        assertEquals(100, out.width)
        assertEquals(orig.getColor(50, 80), out.getColor(50, 80))
    }

    @Test
    fun drawMarksDrawsOnlyMarksOnTheGivenCanvas() {
        val orig = gradient(100, 100)
        val target = Bitmap().apply { allocN32Pixels(100, 100) }.also { closeables += it }
        Canvas(target).use {
            drawMarks(it, orig, listOf(Mark.Box(MarkupColor.Red.argb, MarkPoint(10f, 10f), MarkPoint(90f, 90f))))
        }
        assertEquals(MarkupColor.Red.argb, target.getColor(50, 10))
        assertEquals(0, target.getColor(50, 50))
    }

    @Test
    fun editedFileNaming() {
        val dir = tempDir()
        val orig = dir.resolve("Pixel 8-20261113-101500.png")
        assertEquals(dir.resolve("Pixel 8-20261113-101500-edited.png"), editedFile(orig))
        Files.write(dir.resolve("Pixel 8-20261113-101500-edited.png"), byteArrayOf(1))
        assertEquals(dir.resolve("Pixel 8-20261113-101500-edited-2.png"), editedFile(orig))
        Files.write(dir.resolve("Pixel 8-20261113-101500-edited-2.png"), byteArrayOf(1))
        assertEquals(dir.resolve("Pixel 8-20261113-101500-edited-3.png"), editedFile(orig))
    }

    @Test
    fun editedFileOfExtensionlessName() {
        val dir = tempDir()
        assertEquals(dir.resolve("shot-edited.png"), editedFile(dir.resolve("shot")))
    }

    private fun png(bmp: Bitmap): ByteArray =
        Image.makeFromBitmap(bmp).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }

    @Test
    fun saveWritesNewFileNeverOverwritesOriginalOrExisting() {
        val dir = tempDir()
        val orig = gradient(60, 40)
        val originalFile = dir.resolve("a.png").also { it.writeBytes(png(orig)) }
        val before = Files.readAllBytes(originalFile)
        val marks = listOf(Mark.Box(MarkupColor.Red.argb, MarkPoint(5f, 5f), MarkPoint(50f, 30f)))
        val first = saveMarked(orig.asComposeImageBitmap(), marks, originalFile)
        val second = saveMarked(orig.asComposeImageBitmap(), marks, originalFile)
        assertEquals("a-edited.png", first.name)
        assertEquals("a-edited-2.png", second.name)
        assertContentEquals(before, Files.readAllBytes(originalFile))
        val decoded = Image.makeFromEncoded(Files.readAllBytes(first)).also { closeables += it }
        assertEquals(60, decoded.width)
        assertEquals(40, decoded.height)
        // No temp files left behind.
        assertEquals(listOf("a-edited-2.png", "a-edited.png", "a.png"), dir.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun saveWorksWhenOriginalFileWasDeleted() {
        val dir = tempDir()
        val saved = saveMarked(gradient(20, 20).asComposeImageBitmap(), emptyList(), dir.resolve("gone.png"))
        assertEquals("gone-edited.png", saved.name)
        assertTrue(Files.exists(saved))
    }

    @Test
    fun saveFailureThrowsReadableIOException() {
        val dir = tempDir()
        val missing = dir.resolve("nope").resolve("a.png")
        val e = assertFailsWith<IOException> { saveMarked(gradient(20, 20).asComposeImageBitmap(), emptyList(), missing) }
        assertTrue(!e.message.isNullOrBlank())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun saveIntoReadOnlyFolderFailsCleanly() {
        val dir = tempDir()
        val file = dir.toFile()
        if (!file.setWritable(false) || file.canWrite()) return // not enforceable here (Windows, root)
        val e = assertFailsWith<IOException> {
            saveMarked(gradient(20, 20).asComposeImageBitmap(), emptyList(), dir.resolve("a.png"))
        }
        assertTrue(!e.message.isNullOrBlank())
        file.setWritable(true)
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun aCommittedPixelatesBlocksAreAveragedOnce() {
        val orig = gradient(200, 160)
        val marks = listOf(Mark.Pixelate(MarkPoint(10f, 10f), MarkPoint(150f, 120f)))
        val cache = PixelateCache()
        val first = Bitmap().apply { allocN32Pixels(200, 160) }.also { closeables += it }
        Canvas(first).use { drawMarks(it, orig, marks, cache) }
        val averaged = cache.averaged
        assertTrue(averaged > 0)
        val second = Bitmap().apply { allocN32Pixels(200, 160) }.also { closeables += it }
        repeat(3) { Canvas(second).use { drawMarks(it, orig, marks, cache) } }
        assertEquals(averaged, cache.averaged, "no block is averaged again")
        // The same pixels as without a cache.
        val plain = Bitmap().apply { allocN32Pixels(200, 160) }.also { closeables += it }
        Canvas(plain).use { drawMarks(it, orig, marks) }
        assertContentEquals(pixels(plain), pixels(second))
    }

    @Test
    fun aGrowingDragPreviewOnlyAveragesTheNewBlocks() {
        val orig = gradient(400, 400)
        val block = blockSize(400, 400) // 10
        val cache = PixelateCache()
        val bmp = Bitmap().apply { allocN32Pixels(400, 400) }.also { closeables += it }
        Canvas(bmp).use { drawMarks(it, orig, listOf(Mark.Pixelate(MarkPoint(0f, 0f), MarkPoint(300f, 300f))), cache) }
        val full = cache.averaged
        assertEquals((300 / block) * (300 / block), full)
        Canvas(bmp).use { drawMarks(it, orig, listOf(Mark.Pixelate(MarkPoint(0f, 0f), MarkPoint(305f, 300f))), cache) }
        assertEquals(full + 300 / block, cache.averaged, "only the new partial column")
    }

    @Test
    fun aCacheUsedWithAnotherOriginalStartsOver() {
        val a = gradient(60, 60)
        val b = Bitmap().apply { allocN32Pixels(60, 60); erase(0xFF0000FF.toInt()) }.also { closeables += it }
        val mark = listOf(Mark.Pixelate(MarkPoint(0f, 0f), MarkPoint(40f, 40f)))
        val cache = PixelateCache()
        val out = Bitmap().apply { allocN32Pixels(60, 60) }.also { closeables += it }
        Canvas(out).use { drawMarks(it, a, mark, cache) }
        Canvas(out).use { drawMarks(it, b, mark, cache) }
        assertEquals(0xFF0000FF.toInt(), out.getColor(5, 5))
    }

    @Test
    fun savedFileIsReadableByAllOnPosix() {
        val dir = tempDir()
        if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) return
        val saved = saveMarked(gradient(20, 20).asComposeImageBitmap(), emptyList(), dir.resolve("shot.png"))
        assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(saved)))
    }
}
