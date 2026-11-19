package com.programmersbox.scrcpyviewer.update

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Where the update indicator draws: the Dock icon (macOS) or the taskbar button. Fractions are in 0.0..1.0. */
interface TaskbarSink {
    /** Bounce the Dock icon once / flash the taskbar button. */
    fun attention()
    fun progress(fraction: Double)
    fun indeterminate()
    fun error(fraction: Double)
    fun hide()
}

/**
 * Shows updates outside the window: one attention request per release a background check announces, and a progress
 * bar while it downloads (red when the download fails). Not thread safe: [run] collects on the caller's dispatcher
 * (the UI thread), so every call lands on one thread.
 */
class TaskbarUpdateIndicator(private val sink: TaskbarSink) {
    private val alerted = mutableSetOf<String>()
    private var lastPercent = NONE
    private var lastFraction = 0.0
    private var downloading = false

    /** Asks for attention once per release version; never while "Later" holds. */
    fun onAnnounced(release: Release?, popupDismissed: Boolean) {
        if (release == null || popupDismissed) return
        if (alerted.add(release.version)) sink.attention()
    }

    fun onState(state: UpdateState) {
        if (state is UpdateState.Downloading) {
            downloading = true
            if (state.total <= 0) {
                if (lastPercent != INDETERMINATE) sink.indeterminate()
                lastPercent = INDETERMINATE
                return
            }
            val fraction = (state.read.toDouble() / state.total).coerceIn(0.0, 1.0)
            val percent = floor(fraction * 100).toInt()
            lastFraction = fraction
            if (percent != lastPercent) {
                lastPercent = percent
                sink.progress(percent / 100.0)
            }
            return
        }
        val wasDownloading = downloading
        downloading = false
        lastPercent = NONE
        when {
            state is UpdateState.Installing -> Unit // the app quits
            state is UpdateState.Failed && wasDownloading -> sink.error(lastFraction)
            else -> sink.hide()
        }
    }

    /** Collects until cancelled. It does not hide the bar on exit; the caller's onDispose does. */
    suspend fun run(manager: UpdateManager): Unit = coroutineScope {
        launch { manager.state.collect(::onState) }
        launch {
            combine(manager.announced, manager.popupDismissed, ::Pair).collect { (release, dismissed) ->
                onAnnounced(release, dismissed)
            }
        }
    }

    private companion object {
        const val NONE = -1
        const val INDETERMINATE = -2
    }
}
