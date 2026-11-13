package com.programmersbox.scrcpyviewer.ui.mirror.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ComposeDragOutStarter] against a platform drag manager that behaves like Tao's: it hands the request straight back
 * to the source and starts whatever transfer data the source gives it.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ComposeDragOutStarterTest {
    private class FakeManager : PlatformDragAndDropManager {
        val started = mutableListOf<DragAndDropTransferData>()

        override val isRequestDragAndDropTransferRequired get() = true

        override fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset) {
            var inProgress = false
            val scope = object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    started += transferData
                    inProgress = true
                    return true
                }
            }
            with(source) { scope.startDragAndDropTransfer(offset) { inProgress } }
        }
    }

    @Test
    fun `starts the OS drag when the pointer is outside the tab`() {
        val manager = FakeManager()
        val starter = ComposeDragOutStarter()
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(
            frameRecomposer = recomposer,
            density = Density(1f),
            size = IntSize(400, 300),
            platformContext = object : PlatformContext by PlatformContext.Empty() {
                override val dragAndDropManager: PlatformDragAndDropManager get() = manager
            },
        )
        try {
            scene.setContent { Box(Modifier.size(100.dp).dragOutSource(starter)) }
            recomposer.performFrame(0)
            scene.measureAndLayout()

            val file = File("Kid.png")
            // The drag-out starts only once the pointer has left the tab: well outside the 100 x 100 source.
            val started = starter.start(listOf(file), Offset(250f, 40f)) {}

            assertTrue(started)
            assertEquals(1, manager.started.size)
        } finally {
            scene.close()
            recomposer.close()
        }
    }
}
