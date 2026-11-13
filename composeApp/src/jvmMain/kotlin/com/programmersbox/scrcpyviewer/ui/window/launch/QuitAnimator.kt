package com.programmersbox.scrcpyviewer.ui.window.launch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import dev.nucleusframework.application.NucleusWindowBounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The main window's side of the quit. [effect] is decided when the quit is requested ([begin]). With the overlay the
 * window stays on screen, dimmed while sessions close ([dimmed]); [play] (AppShutdown's animateOut) undims it, takes a
 * picture, opens the overlay ([playing]), and returns after the overlay's last frame. The window is hidden on the
 * overlay's first frame ([hidden]).
 */
class QuitAnimator(private val picture: WindowPicture, private val bounds: () -> NucleusWindowBounds?) {
    /** What the overlay draws on quit. */
    data class Play(val bounds: NucleusWindowBounds, val anim: LaunchQuitAnimation, val picture: ImageBitmap?)

    var effect by mutableStateOf<LaunchEffect?>(null)
        private set

    /** Sessions are closing behind a scrim in the main window. */
    var dimmed by mutableStateOf(false)
        private set

    var playing by mutableStateOf<Play?>(null)
        private set

    /** The overlay has drawn its first frame: the real window can go. */
    var hidden by mutableStateOf(false)
        private set

    /** The fade (where it applies) has run: the window can go. */
    var faded by mutableStateOf(false)
        private set

    private var done: CompletableDeferred<Unit>? = null
    private val fadeDone = CompletableDeferred<Unit>()

    /** The window keeps showing while the sessions close: only the overlay needs it. */
    val keepsWindow: Boolean get() = effect is LaunchEffect.Overlay

    /** The quit was requested: [quitEffect] for it, and whether sessions are open (they close behind a scrim). */
    fun begin(quitEffect: LaunchEffect, sessionsOpen: Boolean) {
        if (effect != null) return
        effect = quitEffect
        dimmed = quitEffect is LaunchEffect.Overlay && sessionsOpen
        faded = quitEffect != LaunchEffect.Fade
    }

    fun fadeFinished() {
        faded = true
        fadeDone.complete(Unit)
    }

    /**
     * AppShutdown's animateOut: waits for the fade, or plays the overlay. Returns at once for [LaunchEffect.None] or
     * when the window has no bounds.
     */
    suspend fun play() {
        if (effect == LaunchEffect.Fade) return fadeDone.await()
        val overlay = effect as? LaunchEffect.Overlay ?: return
        val at = bounds() ?: return
        if (dimmed) {
            dimmed = false
            // The scrim is drawn over the content, outside the picture: let it fade out first, or the window would
            // jump from dimmed to clear when the overlay takes over.
            delay(QUIT_SCRIM_EXIT_MILLIS + FRAME_MILLIS)
        }
        val done = CompletableDeferred<Unit>().also { done = it }
        playing = Play(at, overlay.anim, picture.capture())
        done.await()
    }

    fun overlayFirstFrame() {
        hidden = true
    }

    fun overlayEnded() {
        done?.complete(Unit)
    }

    private companion object {
        const val FRAME_MILLIS = 40L
    }
}

/** How long [QuitScrim] takes to fade out. */
const val QUIT_SCRIM_EXIT_MILLIS = 120L

/**
 * Over the main window while the sessions close before the quit animation: a scrim that takes every click, with
 * "Finishing recording…" while a recorder writes its file. Fades in and out.
 */
@Composable
fun QuitScrim(visible: Boolean, finishingRecording: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(QUIT_SCRIM_EXIT_MILLIS.toInt()))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            contentAlignment = Alignment.Center,
        ) {
            if (finishingRecording) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                    Row(
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Finishing recording…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
