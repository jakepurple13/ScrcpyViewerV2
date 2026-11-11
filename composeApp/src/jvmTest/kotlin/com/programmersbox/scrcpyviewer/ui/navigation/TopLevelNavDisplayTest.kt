package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** Saved UI state of a rail destination survives switching away and back. */
class TopLevelNavDisplayTest {
    private var scroll: ScrollState? = null
    private var draft: OptionValues? = null
    private var setDraft: (OptionValues) -> Unit = {}
    private var devicesCompositions = 0

    @Test
    fun `scroll position and saveable drafts survive a tab switch`() = onEdt {
        val navigator = TopLevelNavigator()
        val driver = SceneDriver(ImageComposeScene(800, 600, Density(1f)) {
            TopLevelNavDisplay(navigator) { route ->
                if (route == TopLevelRoute.Devices) {
                    val s = rememberScrollState()
                    var d by rememberSaveable { mutableStateOf(OptionValues(emptyMap())) }
                    scroll = s
                    draft = d
                    setDraft = { d = it }
                    devicesCompositions++
                    Column(Modifier.fillMaxSize().verticalScroll(s)) { Box(Modifier.height(3000.dp)) }
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        })
        try {
            driver.frames(5)
            val firstScroll = scroll!!
            firstScroll.dispatchRawDelta(300f)
            setDraft(OptionValues(mapOf("max-size" to "1024")))
            driver.frames(5)

            navigator.select(TopLevelRoute.Presets)
            driver.frames(40) // past the 390 ms shared-axis switch; Devices leaves composition
            navigator.select(TopLevelRoute.Settings)
            driver.frames(40)
            navigator.select(TopLevelRoute.Devices)
            driver.frames(40)

            assertNotSame(firstScroll, scroll, "Devices was recomposed from scratch")
            assertEquals(300, scroll!!.value)
            assertEquals(OptionValues(mapOf("max-size" to "1024")), draft)
        } finally {
            driver.scene.close()
        }
    }
}
