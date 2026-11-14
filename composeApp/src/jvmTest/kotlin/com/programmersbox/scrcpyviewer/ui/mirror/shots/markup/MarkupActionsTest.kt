package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The window's Copy and Save of the editor, and the editor host's hold on the thumbnail's timer. */
class MarkupActionsTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val thumbnail = ScreenshotThumbnailState(scope, clock = { scheduler.currentTime })
    private val dir: Path = Files.createTempDirectory("markup-actions")

    @AfterTest
    fun tearDown() {
        thumbnail.dispose()
        scope.cancel()
        dir.toFile().setWritable(true)
        dir.toFile().deleteRecursively()
    }

    private fun bitmap(): ImageBitmap = Bitmap().apply { allocN32Pixels(40, 80); erase(0xFF336699.toInt()) }.asComposeImageBitmap()

    private val box = listOf(Mark.Box(MarkupColor.Red.argb, MarkPoint(5f, 5f), MarkPoint(30f, 30f)))

    @Test
    fun `Save writes the edited file, shows it tagged on the thumbnail and says so`() = runBlocking {
        val original = dir.resolve("Pixel 8.png")
        Files.write(original, byteArrayOf(1))
        val shot = Shot(original, bitmap(), 1)
        val messages = mutableListOf<String>()
        assertTrue(saveMarkedShot(shot, box, thumbnail, { messages += it }, Dispatchers.IO))
        assertEquals(listOf("Saved Pixel 8-edited.png"), messages)
        val shown = assertNotNull(thumbnail.shown.value)
        assertTrue(shown.edited)
        assertEquals(dir.resolve("Pixel 8-edited.png"), shown.file)
        assertEquals(original, shown.original)
        Image.makeFromEncoded(Files.readAllBytes(shown.file)).use {
            assertEquals(40, it.width)
            assertEquals(80, it.height)
        }
        assertFalse(shown.bitmap.asSkiaBitmap().isClosed)
        assertFalse(shot.bitmap.asSkiaBitmap().isClosed, "the original is the thumbnail's, untouched")

        // Editing the edited one names the next file after the original.
        assertTrue(saveMarkedShot(shown, box, thumbnail, { messages += it }, Dispatchers.IO))
        assertEquals("Saved Pixel 8-edited-2.png", messages.last())
    }

    @Test
    fun `a failed Save says why, shows nothing and leaves no file`() = runBlocking {
        val gone = dir.resolve("missing").resolve("shot.png")
        val messages = mutableListOf<String>()
        assertFalse(saveMarkedShot(Shot(gone, bitmap(), 1), box, thumbnail, { messages += it }, Dispatchers.IO))
        assertEquals(listOf("Could not save: the folder missing no longer exists"), messages)
        assertNull(thumbnail.shown.value)
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `Copy hands a PNG of the marked image to the clipboard and deletes it after`() = runBlocking {
        var copied: Path? = null
        var size = 0
        val result = copyMarked(Shot(dir.resolve("a.png"), bitmap(), 1), box, { file ->
            copied = file
            Image.makeFromEncoded(Files.readAllBytes(file)).use { size = it.width }
            Result.success(Unit)
        }, Dispatchers.IO)
        assertTrue(result.isSuccess)
        assertEquals(40, size)
        assertTrue(copied!!.name.endsWith(".png"))
        assertFalse(copied!!.exists())
    }

    @Test
    fun `a failed Copy gives the reason`() = runBlocking {
        val result = copyMarked(Shot(dir.resolve("a.png"), bitmap(), 1), box, { Result.failure(IOException("Install xclip")) }, Dispatchers.IO)
        assertEquals("Install xclip", result.exceptionOrNull()?.message)
    }

    @Test
    fun `the host opens one edit at a time and holds the thumbnail's timer while open`() {
        val host = MarkupEditorHost(thumbnail)
        thumbnail.show(dir.resolve("a.png"), bitmap())
        val shot = thumbnail.shown.value!!
        host.open(shot)
        assertTrue(host.isOpen)
        assertTrue(thumbnail.held.value)
        host.open(Shot(dir.resolve("b.png"), bitmap(), 99))
        assertTrue(host.shot === shot, "one at a time")
        host.close()
        assertFalse(host.isOpen)
        assertFalse(thumbnail.held.value)
    }
}
