package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.scrcpyviewer.media.Screenshot
import com.programmersbox.scrcpyviewer.media.VideoFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small screen images of each mirroring device for the tray popup: one downscaled screenshot per device each time the
 * popup opens ([capture]), kept until the next open, with no live refresh; [clear] drops them once the popup has
 * closed. Nothing runs between those calls. Sizes are pixels (2x the stage sizes in dp). Call both from the UI thread.
 */
class ScreenThumbnails(
    private val scope: CoroutineScope,
    private val frames: () -> Map<String, () -> VideoFrame?>,
    private val maxWidth: Int = 380,
    private val maxHeight: Int = 760,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _images = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val images: StateFlow<Map<String, ImageBitmap>> = _images

    private var job: Job? = null

    /** A frame released mid-copy (a newer one was just published) is retried with the newer one. */
    private fun thumbnailOf(latest: () -> VideoFrame?): ImageBitmap? {
        repeat(3) {
            val frame = latest() ?: return null
            Screenshot.thumbnail(frame, maxWidth, maxHeight)?.let { return it }
        }
        return null
    }

    /** Takes one screenshot of each mirroring device, off the UI thread, replacing the images when done. */
    fun capture() {
        job?.cancel()
        job = scope.launch {
            val sources = frames()
            _images.value = withContext(dispatcher) {
                buildMap {
                    for ((id, latest) in sources) {
                        // One device's failed copy (a Skia error) skips only that device.
                        runCatching { thumbnailOf(latest) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                            ?.let { put(id, it) }
                    }
                }
            }
        }
    }

    /** Stops a capture still running and drops the images. */
    fun clear() {
        job?.cancel()
        job = null
        _images.value = emptyMap()
    }
}
