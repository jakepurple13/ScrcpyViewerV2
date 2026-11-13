package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the real panes in an offscreen scene (1 px = 1 dp). The content width is a state so tests can resize it. */
@OptIn(ExperimentalComposeUiApi::class)
class AdaptiveListDetailTest {
    private var listWidth by mutableIntStateOf(-1)
    private var detailWidth by mutableIntStateOf(-1)
    private var detailComposed by mutableStateOf(false)
    private var presentation by mutableStateOf<ListPresentation?>(null)
    private var switcher by mutableStateOf<(() -> Unit)?>(null)
    private var contentWidth by mutableIntStateOf(1200)
    private var hasSelection by mutableStateOf(false)
    private var reduceMotion by mutableStateOf(false)
    private lateinit var panes: ListDetailPanes

    private fun scene() = SceneDriver(ImageComposeScene(1200, 600, Density(1f)) {
        panes = rememberListDetailPanes()
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
            Box(Modifier.width(contentWidth.dp).fillMaxHeight()) {
                AdaptiveListDetail(
                    panes = panes,
                    listMinWidth = 240.dp,
                    listPreferredWidth = 300.dp,
                    hasSelection = hasSelection,
                    list = { p ->
                        presentation = p
                        Box(Modifier.fillMaxSize().onSizeChanged { listWidth = it.width })
                    },
                    detail = { openSwitcher ->
                        switcher = openSwitcher
                        DisposableEffect(Unit) {
                            detailComposed = true
                            onDispose { detailComposed = false }
                        }
                        Box(Modifier.fillMaxSize().onSizeChanged { detailWidth = it.width })
                    },
                )
            }
        }
    })

    private fun withScene(block: SceneDriver.() -> Unit) = onEdt {
        val driver = scene()
        try {
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    @Test
    fun `divider starts at the preferred width and drags within the min widths`() = withScene {
        frames(5)
        assertEquals(300, listWidth)
        // Handle is centred in the 16 px gap after the list.
        drag(fromX = 308f, toX = 400f)
        // The drag slop swallows the first few px.
        assertTrue(listWidth in 370..392, "list grew with the drag: $listWidth")
        drag(fromX = listWidth + 8f, toX = 20f)
        assertEquals(240, listWidth, "list stops at its min width")
        drag(fromX = listWidth + 8f, toX = 1180f)
        assertEquals(1200 - 16 - 420, listWidth, "detail keeps its min width")
        assertEquals(420, detailWidth)
        assertEquals(ListPresentation.Full, presentation)
        assertNull(switcher, "no switcher beside the full list")
    }

    @Test
    fun `a dragged list is pulled back inside the limits when the window narrows`() = withScene {
        frames(5)
        drag(fromX = 308f, toX = 1180f) // past the max; drag slop would leave a drag to 768 short of it
        assertTrue(listWidth in 763..765, "dragged to the max, 1200 - 16 - 420: $listWidth")
        contentWidth = 800
        frames(90)
        assertTrue(listWidth <= 800 - 16 - 420, "list clamped: $listWidth")
        assertTrue(detailComposed, "still two panes")
        assertEquals(800 - 16 - listWidth, detailWidth)
        assertEquals(ListPresentation.Full, presentation)
    }

    @Test
    fun `selection in two panes shrinks to the strip beside the item when narrowed`() = withScene {
        frames(5)
        hasSelection = true
        frames(5)
        assertTrue(panes.navigation.detailOpen, "selecting in two panes opens the item")
        contentWidth = 600
        frames(90)
        assertEquals(StripWidth.value.toInt(), listWidth)
        assertEquals(600 - 76 - 8, detailWidth)
        assertEquals(ListPresentation.Strip, presentation)
        assertNotNull(switcher, "item view offers the switcher")
    }

    @Test
    fun `list view follows a live window resize in the same frame`() = withScene {
        contentWidth = 600
        frames(90)
        assertEquals(600, listWidth)
        contentWidth = 650 // still list view: two panes start at 240 + 16 + 420 = 676
        frame()
        assertEquals(650, listWidth, "no spring lag behind the window edge")
    }

    @Test
    fun `a resize during a mode spring still follows the window edge`() = withScene {
        frames(5)
        assertEquals(300, listWidth)
        contentWidth = 600 // two panes -> list view: the list springs from 300 to 600
        frames(10)
        val midSpring = listWidth
        assertTrue(midSpring in 301..599, "still springing: $midSpring")
        contentWidth = 640 // the window edge keeps moving
        frame()
        assertTrue(listWidth >= midSpring + 39, "list moved with the 40 px resize, not behind it: $midSpring -> $listWidth")
        frames(90)
        assertEquals(640, listWidth, "no blank strip once settled")
    }

    @Test
    fun `crossing from list view into two panes glides from the full width`() = withScene {
        contentWidth = 670 // list view, just under the 676 threshold
        frames(90)
        assertEquals(670, listWidth)
        contentWidth = 680 // two panes; the list's target is clamped to 680 - 16 - 420 = 244
        frame()
        assertTrue(listWidth >= 650, "no jump to the clamped width at the start of the glide: $listWidth")
        frames(90)
        assertEquals(244, listWidth)
        assertEquals(420, detailWidth)
    }

    @Test
    fun `a mode change during a pointer drag still glides`() = withScene {
        frames(5)
        // Press on the divider and drag without releasing.
        scene.sendPointerEvent(PointerEventType.Move, Offset(308f, 300f), type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, Offset(308f, 300f), type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        for (x in 320..400 step 10) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), 300f), type = PointerType.Mouse)
            frame()
        }
        val dragged = listWidth
        assertTrue(dragged > 340, "dragging: $dragged")
        contentWidth = 600 // two panes -> list view mid-drag
        frames(3)
        assertTrue(listWidth in (dragged + 1)..599, "list glides toward 600 instead of jumping: $dragged -> $listWidth")
        scene.sendPointerEvent(PointerEventType.Release, Offset(400f, 300f), type = PointerType.Mouse, button = PointerButton.Primary)
        frames(90)
        assertEquals(600, listWidth)
    }

    @Test
    fun `narrowing two panes keeps the detail at its min in the same frame`() = withScene {
        frames(5)
        drag(fromX = 308f, toX = 1180f)
        assertEquals(1200 - 16 - 420, listWidth)
        contentWidth = 1100
        frame()
        assertTrue(detailWidth >= 419, "detail kept its min while the window narrows: $detailWidth")
        assertTrue(listWidth <= 1100 - 16 - 420 + 1, "list follows the limit: $listWidth")
    }

    @Test
    fun `switching from item view to two panes springs the list width`() = withScene {
        hasSelection = true
        frames(5)
        contentWidth = 600
        frames(90)
        assertEquals(76, listWidth)
        contentWidth = 1200
        frames(3)
        assertTrue(listWidth in 77..299, "list mid-spring between the strip and 300: $listWidth")
        frames(90)
        assertEquals(300, listWidth)
    }

    @Test
    fun `reduced motion jumps straight to the end value`() = withScene {
        reduceMotion = true
        hasSelection = true
        frames(5)
        contentWidth = 600
        frame()
        assertEquals(76, listWidth, "strip width after one frame")
        contentWidth = 1200
        frame()
        assertEquals(300, listWidth, "pane width after one frame")
    }

    @Test
    fun `narrow with nothing selected shows the list alone`() = withScene {
        contentWidth = 600
        frames(90)
        assertEquals(600, listWidth)
        assertTrue(!detailComposed, "detail not composed in list view")
        assertEquals(ListPresentation.Full, presentation)
    }

    @Test
    fun `hoisted panes keep the dragged width when the screen leaves and returns`() = onEdt {
        var shown by mutableStateOf(true)
        val driver = SceneDriver(ImageComposeScene(1200, 600, Density(1f)) {
            val hoisted = rememberListDetailPanes()
            if (shown) {
                AdaptiveListDetail(
                    panes = hoisted,
                    listMinWidth = 240.dp,
                    listPreferredWidth = 300.dp,
                    hasSelection = false,
                    list = { Box(Modifier.fillMaxSize().onSizeChanged { listWidth = it.width }) },
                    detail = { Box(Modifier.fillMaxSize()) },
                )
            }
        })
        try {
            driver.frames(5)
            driver.drag(fromX = 308f, toX = 500f)
            val dragged = listWidth
            assertTrue(dragged > 400, "dragged to $dragged")
            shown = false
            driver.frames(5)
            listWidth = -1
            shown = true
            driver.frames(5)
            assertEquals(dragged, listWidth)
        } finally {
            driver.scene.close()
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun SceneDriver.key(key: Key) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key = key, type = type))
            frame()
        }
    }

    @Test
    fun `focused divider moves 24 px per arrow key within the limits`() = withScene {
        frames(5)
        assertEquals(300, listWidth)
        key(Key.Tab) // the handle is the only focusable
        key(Key.DirectionRight)
        frames(60)
        assertEquals(324, listWidth)
        key(Key.DirectionLeft)
        key(Key.DirectionLeft)
        frames(60)
        assertEquals(276, listWidth)
        repeat(10) { key(Key.DirectionLeft) }
        frames(60)
        assertEquals(240, listWidth, "stops at the list min")
    }
}
