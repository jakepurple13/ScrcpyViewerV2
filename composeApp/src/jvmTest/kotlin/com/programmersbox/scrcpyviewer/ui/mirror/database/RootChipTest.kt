package com.programmersbox.scrcpyviewer.ui.mirror.database

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.database.RootState
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.settings.click
import com.programmersbox.scrcpyviewer.ui.settings.helpScene
import com.programmersbox.scrcpyviewer.ui.settings.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RootChipTest {
    @Test
    fun `each state has its words`() {
        assertEquals(
            listOf(null, "Not rooted", "Root available", "Asking for root…", "Rooted", "adb root", "Root denied"),
            RootState.entries.map { rootLabel(it) },
        )
    }

    @Test
    fun `available chip asks when clicked, rooted one doesn't`() = onEdt {
        var asks = 0
        var state by mutableStateOf(RootState.Available)
        val scene = helpScene(400, 120) { RootChip(state, motion = false) { asks++ } }
        scene.frames(3)
        scene.click("Root available")
        assertEquals(1, asks)
        state = RootState.Rooted
        scene.frames(5)
        assertNotNull(scene.node("Rooted"))
        scene.click("Rooted")
        assertEquals(1, asks)
        scene.scene.close()
    }

    @Test
    fun `unknown hides the chip`() = onEdt {
        val scene = helpScene(400, 120) { RootChip(RootState.Unknown, motion = false) {} }
        scene.frames(3)
        assertNull(scene.node("Not rooted"))
        scene.scene.close()
    }
}
