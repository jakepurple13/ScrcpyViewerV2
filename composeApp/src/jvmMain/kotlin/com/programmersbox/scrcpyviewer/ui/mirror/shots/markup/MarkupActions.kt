package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.ui.mirror.shots.ScreenshotThumbnailState
import com.programmersbox.scrcpyviewer.ui.mirror.shots.Shot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * Copies [shot] with [marks] to the clipboard: rendered here (UI thread, while the editor holds the bitmap), then
 * encoded to a temp PNG and handed to [copyPng] on [io] (it blocks up to 5 s). The render is closed once [io] is done
 * with it, and the temp file deleted.
 */
internal suspend fun copyMarked(
    shot: Shot,
    marks: List<Mark>,
    copyPng: (Path) -> Result<Unit>,
    io: CoroutineContext = Dispatchers.IO,
): Result<Unit> {
    val rendered = try {
        renderMarked(shot.bitmap, marks)
    } catch (e: Exception) {
        return Result.failure(e)
    }
    try {
        return withContext(io) {
            runCatching {
                val temp = Files.createTempFile("scrcpy-vwr-markup-", ".png")
                try {
                    Files.write(temp, encodePng(rendered))
                    copyPng(temp).getOrThrow()
                } finally {
                    runCatching { Files.deleteIfExists(temp) }
                }
            }
        }
    } finally {
        // withContext returns (or throws on cancellation) only after the block is done with it.
        rendered.asSkiaBitmap().close()
    }
}

/**
 * Saves [shot] with [marks] next to its original (`<name>-edited.png`, …): rendered here, written on [io]. On success
 * the [thumbnail] shows the edited file (it takes the render), [notify] says "Saved <file name>" and the result is
 * true. On failure [failed] (default [notify]) says "Could not save: <reason>" and the result is false. A cancelled
 * save (the window closed) closes the render; the file is either fully written or not there (see [saveEdited]).
 */
internal suspend fun saveMarkedShot(
    shot: Shot,
    marks: List<Mark>,
    thumbnail: ScreenshotThumbnailState,
    notify: (String) -> Unit,
    io: CoroutineContext = Dispatchers.IO,
    failed: (String) -> Unit = notify,
): Boolean {
    val rendered = try {
        renderMarked(shot.bitmap, marks)
    } catch (e: Exception) {
        failed("Could not save: ${e.message ?: e}")
        return false
    }
    val file = try {
        withContext(io) { saveEdited(rendered, shot.original) }
    } catch (e: CancellationException) {
        rendered.asSkiaBitmap().close()
        throw e
    } catch (e: Exception) {
        rendered.asSkiaBitmap().close()
        failed("Could not save: ${e.message ?: e}")
        return false
    }
    thumbnail.showEdited(file, rendered, shot.original) // takes the render
    notify("Saved ${file.fileName}")
    return true
}
