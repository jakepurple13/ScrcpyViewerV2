package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The stage's pointer input across the Fit and zoomed canvases. */
class StageInputTest {
    private val base = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(540, 1200),
        fittedScale = 0.5f,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun withStage(
        initial: MirrorUiState,
        block: (scene: ImageComposeScene, state: androidx.compose.runtime.MutableState<MirrorUiState>, sent: List<ControlMessage>, detached: () -> Int) -> Unit,
    ) = SwingUtilities.invokeAndWait {
        val state = mutableStateOf(initial)
        val frame = mutableStateOf<VideoFrame?>(null)
        var viewSize = IntSize.Zero
        // Wired like the mirror window: pointer events through the mapper, held buttons released when the view goes.
        val mapper = InputMapper(frameSize = { IntSize(540, 1200) }, viewRect = { Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat()) })
        val sent = mutableListOf<ControlMessage>()
        var detached = 0
        val scene = ImageComposeScene(900, 700, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MirrorContent(
                        state.value, frame, {}, {}, {},
                        onPointer = { sent += mapper.pointer(it) },
                        onViewSize = { viewSize = it },
                        onStageSize = {},
                        onInputDetached = {
                            detached++
                            sent += mapper.releaseButtons()
                        },
                    )
                }
            }
        }
        try {
            scene.render(0).close()
            block(scene, state, sent) { detached }
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.press(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.release(at: Offset) =
        sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)

    private fun List<ControlMessage>.touches(action: Int) = filterIsInstance<ControlMessage.InjectTouch>().filter { it.action == action }

    @Test
    fun `switching from Fit to zoom mid-drag releases the held button once`() = withStage(base) { scene, state, sent, detached ->
        val at = Offset(450f, 400f)
        scene.press(at)
        scene.render(16_000_000).close()
        assertEquals(1, sent.touches(InputMapper.ACTION_DOWN).size, "the press reached the device")

        state.value = base.copy(zoom = 1f) // Cmd/Ctrl+= (or the first frame's zoom) while the button is held
        scene.render(32_000_000).close()
        assertEquals(1, detached())
        assertEquals(1, sent.touches(InputMapper.ACTION_UP).size, "released as the Fit canvas went")

        // The real release comes later: no second UP, and the next moves hover rather than drag.
        scene.release(at)
        scene.sendPointerEvent(PointerEventType.Move, at + Offset(5f, 5f), type = PointerType.Mouse)
        scene.render(48_000_000).close()
        assertEquals(1, sent.touches(InputMapper.ACTION_UP).size)
        assertTrue(sent.touches(InputMapper.ACTION_MOVE).isEmpty(), "no drag after the release")
    }

    @Test
    fun `switching from zoom back to Fit mid-drag releases the held button`() = withStage(base.copy(zoom = 0.5f)) { scene, state, sent, detached ->
        val at = Offset(450f, 400f)
        scene.press(at)
        scene.render(16_000_000).close()
        assertEquals(1, sent.touches(InputMapper.ACTION_DOWN).size, "the press reached the device")

        state.value = base // Cmd/Ctrl+0
        scene.render(32_000_000).close()
        assertEquals(1, detached())
        assertEquals(1, sent.touches(InputMapper.ACTION_UP).size)
    }

    @Test
    fun `zoom steps within the zoomed canvas keep the input`() = withStage(base.copy(zoom = 0.5f)) { scene, state, sent, detached ->
        state.value = base.copy(zoom = 0.6f)
        scene.render(16_000_000).close()
        assertEquals(0, detached())
        assertTrue(sent.isEmpty())
    }
}
