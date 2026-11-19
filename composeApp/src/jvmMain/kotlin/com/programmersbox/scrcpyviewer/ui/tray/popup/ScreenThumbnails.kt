package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.scrcpyviewer.media.Screenshot
import com.programmersbox.scrcpyviewer.media.VideoFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small live screen images of each mirroring device for the tray popup. Refreshes every [periodMs] only while
 * active (the popup is open); going inactive cancels the loop and clears [images]. [frames] is read each round, so
 * devices that start or stop mirroring are picked up. Sizes are pixels (2x the stage sizes in dp).
 */
class ScreenThumbnails(
    private val scope: CoroutineScope,
    private val frames: () -> Map<String, () -> VideoFrame?>,
    private val maxWidth: Int = 380,
    private val maxHeight: Int = 760,
    private val periodMs: Long = 1_000,
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

    fun setActive(active: Boolean) {
        if (active) {
            if (job?.isActive == true) return
            job = scope.launch {
                while (true) {
                    val sources = frames()
                    _images.value = withContext(dispatcher) {
                        buildMap {
                            for ((id, latest) in sources) {
                                thumbnailOf(latest)?.let { put(id, it) }
                            }
                        }
                    }
                    delay(periodMs)
                }
            }
        } else {
            job?.cancel()
            job = null
            _images.value = emptyMap()
        }
    }
}
