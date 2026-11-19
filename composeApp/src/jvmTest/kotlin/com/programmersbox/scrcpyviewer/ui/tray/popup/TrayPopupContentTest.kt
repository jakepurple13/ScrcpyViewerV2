package com.programmersbox.scrcpyviewer.ui.tray.popup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeState
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

    private fun scene(
        ui: TrayPopupUiState,
        events: MutableList<PopupEvent> = mutableListOf(),
        sendOpen: Boolean = false,
        holder: MutableState<TrayPopupUiState> = mutableStateOf(ui),
        block: SceneDriver.() -> Unit,
    ) = onEdt {
        val d = SceneDriver(ImageComposeScene(400, 900, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            TrayPopupContent(holder.value, sendOpen = sendOpen, onEvent = { events += it }, modifier = Modifier.width(360.dp))
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
        assertTrue(has("Unlock the phone and tap Allow on the USB debugging prompt."), "texts ${allTexts()}")
    }

    @Test
    fun `offline stage shows the Offline tag and hint`() = scene(state(PopupDevice("OFF4", "Dead phone", PopupStatus.Offline, "offline"))) {
        assertTrue(has("Offline") && has("Reconnect the cable or run adb reconnect"), "texts ${allTexts()}")
    }

    private fun SceneDriver.selectedItems(): List<String> {
        fun SemanticsNode.firstText(): String? = texts().firstOrNull() ?: children.firstNotNullOfOrNull { it.firstText() }
        return nodes().filter { SemanticsActions.OnClick in it.config && it.config.getOrNull(SemanticsProperties.Selected) == true }.mapNotNull { it.firstText() }
    }

    @Test
    fun `strip lists every device and highlights the stage`() = scene(state(pixel, tablet, locked, stage = "TAB2")) {
        val texts = allTexts()
        assertTrue(listOf("Pixel 8 Pro", "Galaxy Tab", "Locked phone").all { it in texts }, "texts $texts")
        // The stage shows the tablet: Mirror, not Bring to front.
        assertTrue(has("Mirror") && !has("Bring to front"))
        assertEquals(listOf("Galaxy Tab"), selectedItems())
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

    private val phone2 = PopupDevice("PH2", "Second phone", PopupStatus.Mirroring, "device")

    private fun SceneDriver.disabled(label: String): Boolean {
        fun SemanticsNode.holds(): Boolean = label in texts() || children.any { it.holds() }
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.holds() }, "no $label")
        return SemanticsProperties.Disabled in node.config
    }

    @Test
    fun `send button shows only with two mirrors`() {
        val one = state(pixel, tablet) { copy(sendPanel = SendPanel.Hidden) }
        scene(one) { assertTrue(allTexts().none { it.startsWith("Send input to") }, "texts ${allTexts()}") }
        val two = state(pixel, phone2) { copy(sendPanel = SendPanel.Choose(listOf(phone2), emptySet(), null)) }
        scene(two) { assertTrue(allTexts().any { it.startsWith("Send input to") }, "texts ${allTexts()}") }
    }

    @Test
    fun `open panel lists targets with selection`() {
        val third = PopupDevice("PH3", "Third phone", PopupStatus.Mirroring, "device")
        val ui = state(pixel, phone2, third) { copy(sendPanel = SendPanel.Choose(listOf(phone2, third), setOf("PH3"), null)) }
        scene(ui, sendOpen = true) {
            assertTrue(has("Send Pixel 8 Pro's taps, keys and text to:"), "texts ${allTexts()}")
            assertTrue("Third phone" in selectedItems(), "selected ${selectedItems()}")
            assertTrue("Second phone" !in selectedItems())
        }
    }

    @Test
    fun `chip click emits ToggleFollower`() {
        val events = mutableListOf<PopupEvent>()
        val ui = state(pixel, phone2) { copy(sendPanel = SendPanel.Choose(listOf(phone2), emptySet(), null)) }
        scene(ui, events, sendOpen = true) {
            fun SemanticsNode.holds(): Boolean = "Second phone" in texts() || children.any { it.holds() }
            val chip = assertNotNull(nodes().firstOrNull { SemanticsActions.OnClick in it.config && it.holds() }, "no chip")
            chip.config[SemanticsActions.OnClick].action?.invoke()
            frames(5)
        }
        assertEquals(listOf<PopupEvent>(PopupEvent.ToggleFollower("PH2")), events)
    }

    @Test
    fun `other leader note shows`() {
        val ui = state(pixel, phone2) { copy(sendPanel = SendPanel.Choose(listOf(phone2), emptySet(), "PH2")) }
        scene(ui, sendOpen = true) {
            assertTrue(has("Picking a device here stops Second phone sending input."), "texts ${allTexts()}")
        }
    }

    @Test
    fun `following state shows Stop following`() {
        val events = mutableListOf<PopupEvent>()
        val ui = state(pixel, phone2) { copy(sendPanel = SendPanel.Following("PH2")) }
        scene(ui, events, sendOpen = true) {
            assertTrue(has("Pixel 8 Pro follows Second phone, so its input comes from there."), "texts ${allTexts()}")
            click("Stop following")
        }
        assertEquals(listOf<PopupEvent>(PopupEvent.StopFollowing), events)
    }

    @Test
    fun `panel closes when stage device stops mirroring`() {
        val holder = mutableStateOf(state(pixel, phone2) { copy(sendPanel = SendPanel.Choose(listOf(phone2), emptySet(), null)) })
        scene(holder.value, sendOpen = true, holder = holder) {
            assertTrue(has("Send Pixel 8 Pro's taps, keys and text to:"))
            holder.value = state(pixel.copy(status = PopupStatus.Ready), phone2) { copy(sendPanel = SendPanel.Hidden) }
            frames(60)
            assertTrue(allTexts().none { it.startsWith("Send ") }, "texts ${allTexts()}")
            assertTrue(has("Ready") && has("Mirror"), "stage swapped; texts ${allTexts()}")
        }
    }

    @Test
    fun `arrange shows with two mirrors and emits layouts`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid, keepTiled = false)) }, events) {
            assertTrue(has("Arrange mirror windows") && has("Keep tiled"), "texts ${allTexts()}")
            click("Side by side")
            click("As placed")
        }
        assertEquals(
            listOf<PopupEvent>(
                PopupEvent.Act(TrayAction.Arrange(ArrangeLayout.SideBySide), null), PopupEvent.Act(TrayAction.Arrange(ArrangeLayout.AsPlaced), null),
            ),
            events,
        )
        scene(state(pixel, tablet) { copy(arrange = ArrangeState()) }) { assertTrue(!has("Arrange mirror windows")) }
        scene(state(pixel, phone2)) { assertTrue(!has("Arrange mirror windows")) }
    }

    @Test
    fun `keep tiled switch emits KeepTiled true`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid)) }, events) {
            click("Keep tiled")
        }
        assertEquals(listOf<PopupEvent>(PopupEvent.Act(TrayAction.KeepTiled(true), null)), events)
    }

    @Test
    fun `arrange buttons disabled when not movable`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid, movable = false)) }, events) {
            assertTrue(disabled("Cascade") && disabled("Keep tiled"))
        }
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.AsPlaced)) }) {
            assertTrue(!disabled("Cascade") && disabled("Keep tiled"))
        }
    }

    @Test
    fun `panel text changes to the new device when the stage changes`() {
        val choosePix = SendPanel.Choose(listOf(phone2), emptySet(), null)
        val holder = mutableStateOf(state(pixel, phone2) { copy(sendPanel = choosePix) })
        scene(holder.value, sendOpen = true, holder = holder) {
            assertTrue(has("Send Pixel 8 Pro's taps, keys and text to:"))
            holder.value = state(pixel, phone2, stage = "PH2") { copy(sendPanel = SendPanel.Choose(listOf(pixel), emptySet(), null)) }
            frames(60)
            assertTrue(has("Send Second phone's taps, keys and text to:"), "texts ${allTexts()}")
            assertTrue(!has("Send Pixel 8 Pro's taps, keys and text to:"), "texts ${allTexts()}")
        }
    }

    @Test
    fun `stage moving to a device that cannot send folds the panel without mixing names`() {
        val holder = mutableStateOf(state(pixel, phone2, tablet) { copy(sendPanel = SendPanel.Choose(listOf(phone2), emptySet(), null)) })
        scene(holder.value, sendOpen = true, holder = holder) {
            holder.value = state(pixel, phone2, tablet, stage = "TAB2") { copy(sendPanel = SendPanel.Hidden) }
            frames(60)
            assertTrue(allTexts().none { it.startsWith("Send ") && it.endsWith("to:") }, "texts ${allTexts()}")
        }
    }

    @Test
    fun `not movable shows the note and movable hides it`() {
        val note = "Your desktop doesn't let apps move windows"
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid, movable = false)) }) { assertTrue(has(note), "texts ${allTexts()}") }
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid)) }) { assertTrue(!has(note)) }
        val holder = mutableStateOf(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid, movable = false)) })
        scene(holder.value, holder = holder) {
            holder.value = state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid)) }
            frames(60)
            assertTrue(!has(note), "texts ${allTexts()}")
        }
    }

    @Test
    fun `focus layout is labelled Focus`() {
        val events = mutableListOf<PopupEvent>()
        scene(state(pixel, phone2) { copy(arrange = ArrangeState(ArrangeLayout.Grid)) }, events) {
            assertTrue(has("Focus") && !has("Focus this window"), "texts ${allTexts()}")
            click("Focus")
        }
        assertEquals(listOf<PopupEvent>(PopupEvent.Act(TrayAction.Arrange(ArrangeLayout.Focus), null)), events)
    }
}
