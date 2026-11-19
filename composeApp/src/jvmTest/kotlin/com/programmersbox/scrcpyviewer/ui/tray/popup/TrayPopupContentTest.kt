package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.tray.TrayAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The menu bar popup at its fixed 360 dp width. A new scene per case; motion is passed explicitly (CI reports reduce motion on). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TrayPopupContentTest {
    private val pixel = PopupDevice("PIX1", "Pixel 8 Pro", PopupStatus.Mirroring, "device")
    private val tablet = PopupDevice("TAB2", "Galaxy Tab", PopupStatus.Ready, "device")
    private val locked = PopupDevice("LOCK3", "Locked phone", PopupStatus.Unauthorized, "unauthorized")

    private fun state(vararg devices: PopupDevice, stage: String? = devices.firstOrNull()?.serial, extra: TrayPopupUiState.() -> TrayPopupUiState = { this }) =
        TrayPopupUiState(devices.toList(), stage).extra()

    private fun scene(ui: TrayPopupUiState, events: MutableList<PopupEvent> = mutableListOf(), block: SceneDriver.() -> Unit) = onEdt {
        val d = SceneDriver(ImageComposeScene(400, 900, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            TrayPopupContent(ui, sendOpen = false, onEvent = { events += it }, modifier = Modifier.width(360.dp))
                        }
                    }
                }
            }
        })
        try {
            d.frames(60)
            d.block()
        } finally {
            d.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SceneDriver.allTexts() = nodes().flatMap { it.texts() }
    private fun SceneDriver.has(label: String) = label in allTexts()

    private fun SceneDriver.click(label: String) {
        fun SemanticsNode.holds(): Boolean = label in texts() || children.any { it.holds() }
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.holds() }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(5)
    }

    private fun SceneDriver.clickDescribed(label: String) {
        val node = assertNotNull(
            nodes().lastOrNull { n -> SemanticsActions.OnClick in n.config && n.children.any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true } },
            "no $label to click",
        )
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(5)
    }

    @Test
    fun `empty shows No device connected`() = scene(state()) {
        assertTrue(has("No device connected"), "texts ${allTexts()}")
        assertTrue(has("0 connected"))
    }

    @Test
    fun `mirroring stage shows its actions`() = scene(state(pixel)) {
        assertTrue(has("Bring to front") && has("Screenshot") && has("Stop mirroring"), "texts ${allTexts()}")
        assertTrue(has("Mirroring"))
        assertTrue(allTexts().none { it.startsWith("Send input to") })
    }

    @Test
    fun `ready stage shows Mirror`() = scene(state(tablet)) {
        assertTrue(has("Mirror") && has("Ready"), "texts ${allTexts()}")
        assertTrue(!has("Bring to front"))
    }

    @Test
    fun `unauthorized stage shows the prompt text`() = scene(state(locked)) {
        assertTrue(has("Unlock the phone and tap Allow on the USB debugging prompt"), "texts ${allTexts()}")
    }

    @Test
    fun `strip lists every device and highlights the stage`() = scene(state(pixel, tablet, locked, stage = "TAB2")) {
        val texts = allTexts()
        assertTrue(listOf("Pixel 8 Pro", "Galaxy Tab", "Locked phone").all { it in texts }, "texts $texts")
        // The stage shows the tablet: Mirror, not Bring to front.
        assertTrue(has("Mirror") && !has("Bring to front"))
    }

    @Test
    fun `clicking a strip item emits Pick`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, tablet), events) {
            click("Galaxy Tab")
        }
        assertEquals(listOf<PopupEvent>(PopupEvent.Pick("TAB2")), events)
    }

    @Test
    fun `footer emits OpenMain, Settings and Quit`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel), events) {
            click("Open Scrcpy Vwr")
            click("Settings…")
            clickDescribed("Quit")
        }
        assertEquals(
            listOf<PopupEvent>(
                PopupEvent.Act(TrayAction.OpenMain, null), PopupEvent.Act(TrayAction.Settings, null), PopupEvent.Act(TrayAction.Quit, null),
            ),
            events,
        )
    }

    @Test
    fun `stage actions emit their events`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, tablet), events) {
            click("Bring to front")
            click("Screenshot")
            click("Stop mirroring")
        }
        assertEquals(
            listOf<PopupEvent>(PopupEvent.Act(TrayAction.Focus, "PIX1"), PopupEvent.Screenshot("PIX1"), PopupEvent.Stop("PIX1")),
            events,
        )
    }

    @Test
    fun `header counts devices`() = scene(state(pixel, tablet, locked)) {
        assertTrue(has("3 connected"), "texts ${allTexts()}")
    }

    @Test
    fun `follow role tags and the screenshot note show`() {
        val ui = state(pixel, tablet) { copy(follow = FollowState("TAB2", setOf("PIX1")), screenshotNote = "Saved to Screenshots") }
        scene(ui) {
            assertTrue(has("Follows Galaxy Tab") && has("Saved to Screenshots"), "texts ${allTexts()}")
        }
        scene(state(pixel, tablet) { copy(follow = FollowState("PIX1", setOf("TAB2"))) }) { assertTrue(has("Leads 1")) }
    }

    @Test
    fun `thumbnails render in portrait and landscape`() {
        for (bitmap in listOf(ImageBitmap(60, 120), ImageBitmap(120, 60))) {
            scene(state(pixel, tablet) { copy(thumbnails = mapOf("PIX1" to bitmap)) }) {
                assertTrue(has("Bring to front"), "texts ${allTexts()}")
            }
        }
    }
}
