package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SelectionHighlightTest {
    private var selected by mutableIntStateOf(0)
    private var state: SelectionHighlightState? = null

    @Test
    fun `highlight slides from the old row to the new one`() = onEdt {
        val scene = SceneDriver(ImageComposeScene(200, 200, Density(1f)) {
            val highlight = rememberSelectionHighlight().also { state = it }
            Column(Modifier.selectionHighlight(highlight, Color.Red, RoundedCornerShape(4.dp))) {
                repeat(3) { i ->
                    Box(Modifier.fillMaxWidth().height(40.dp).selectionHighlightTarget(highlight, selected == i))
                }
            }
        })
        try {
            scene.frames(5)
            assertEquals(0f, assertNotNull(state!!.current).top, "first placement snaps")
            selected = 2
            scene.frames(4) // recompose, place and start the spring take a couple of frames
            val mid = assertNotNull(state!!.current).top
            assertTrue(mid > 0f && mid < 80f, "mid-slide top: $mid")
            scene.frames(120)
            assertEquals(80f, state!!.current!!.top, 0.5f)
        } finally {
            scene.scene.close()
        }
    }
}
