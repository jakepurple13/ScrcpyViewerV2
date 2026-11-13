package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.Rail
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class RailLabelsTest {
    @Test
    fun `labels show from 540 dp`() {
        assertFalse(railShowsLabels(539.dp))
        assertTrue(railShowsLabels(540.dp))
    }

    @Test
    fun `narrow rail is 60 wide with no labels and its Devices icon still clicks`() {
        val navigator = TopLevelNavigator(TopLevelRoute.Settings)
        var railWidth = 0
        onEdt {
            val driver = SceneDriver(ImageComposeScene(500, 600, Density(1f)) {
                ScrcpyViewerTheme {
                    Box(Modifier.onSizeChanged { railWidth = it.width }) {
                        Rail(navigator, settingsBadge = false, windowWidth = 500.dp)
                    }
                }
            })
            try {
                driver.frames(60)
                assertEquals(60, railWidth)
                // Without labels the items are centered in the rail; the first (Devices) sits near the top.
                val point = Offset(30f, 60f)
                driver.scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse)
                driver.scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, button = PointerButton.Primary)
                driver.frame()
                driver.scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, button = PointerButton.Primary)
                driver.frames(5)
                assertEquals(TopLevelRoute.Devices, navigator.current)
            } finally {
                driver.scene.close()
            }
        }
    }

    @Test
    fun `wide rail is 80 wide`() {
        val navigator = TopLevelNavigator()
        var railWidth = 0
        onEdt {
            val driver = SceneDriver(ImageComposeScene(700, 600, Density(1f)) {
                ScrcpyViewerTheme {
                    Box(Modifier.onSizeChanged { railWidth = it.width }) {
                        Rail(navigator, settingsBadge = false, windowWidth = 700.dp)
                    }
                }
            })
            try {
                driver.frames(60)
                assertEquals(80, railWidth)
            } finally {
                driver.scene.close()
            }
        }
    }
}
