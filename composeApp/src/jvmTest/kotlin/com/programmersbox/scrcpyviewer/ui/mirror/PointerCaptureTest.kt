package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Rectangle
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records warps and hides; warps fail while [canWarp] is false (Wayland). */
private class FakeCursor(var canWarp: Boolean = true) : CursorControl {
    val warps = mutableListOf<IntOffset>()
    var pointerHidden = false

    override fun warp(screenX: Int, screenY: Int): Boolean {
        if (!canWarp) return false
        warps += IntOffset(screenX, screenY)
        return true
    }

    override fun setHidden(hidden: Boolean) {
        pointerHidden = hidden
    }
}

/** A window whose content starts at [origin] on screen. */
private class CaptureTestWindow(override var bounds: Rectangle, private val origin: IntOffset) : MirrorWindowHandle {
    override val size: IntSize get() = IntSize(bounds.width, bounds.height)
    override val isShowing = true
    override val focused: StateFlow<Boolean> = MutableStateFlow(true)
    override val focusEvents: SharedFlow<Boolean> = MutableSharedFlow()
    override val nsWindow: Pointer? = null
    override fun toFront() = Unit
    override fun screen(): ScreenArea? = null
    override fun contentOrigin(): IntOffset = origin
}

private fun mapperForUhid() = InputMapper.forOptions(
    OptionValues(mapOf("mouse" to "uhid", "keyboard" to "uhid")),
    { IntSize(1080, 2400) }, { Rect(0f, 0f, 500f, 500f) }, { false }, hidMouse = HidMouse(),
)

class PointerCaptureTest {
    private var focused = false
    private val sent = ArrayList<ControlMessage>()
    private val keyboard = HidKeyboard(lockState = { 0 }, forwardLockKeys = false)
    private val mapper = mapperForUhid()
    private val cursor = FakeCursor()
    private var unavailable = 0
    private val capture = WindowPointerCapture(
        MouseCapture(), mapper, keyboard, { sent += it }, isFocused = { focused }, cursor = cursor, onUnavailable = { unavailable++ },
    )

    // Content at (100, 60) on screen, density 2; the video view starts 40 pt below the content's top.
    private val density = 2f
    private val window = CaptureTestWindow(Rectangle(100, 32, 500, 868), IntOffset(100, 60))
    private val stage = Rect(0f, 80f, 1000f, 1680f)
    private val viewOrigin = Offset(0f, 80f)
    private val viewSize = IntSize(1000, 1600)
    private var time = 0L
    private var last: Offset? = null

    /** A move to [awtX], [awtY]: the position AWT gives relative to the content pane, in points. */
    private fun moveTo(awtX: Int, awtY: Int) {
        val local = Offset(awtX * density, awtY * density) - viewOrigin
        val from = last ?: local
        time += 8
        val change = PointerInputChange(
            id = PointerId(0), uptimeMillis = time, position = local, pressed = false,
            previousUptimeMillis = time - 8, previousPosition = from, previousPressed = false,
            isInitiallyConsumed = false, type = PointerType.Mouse,
        )
        last = local
        capture.pointer(PointerEvent(listOf(change)), window, viewSize, density, stage, viewOrigin)
    }

    /** A release at the last position (a click's second half: captures while not captured). */
    private fun release() {
        val at = last ?: Offset.Zero
        time += 8
        val change = PointerInputChange(
            id = PointerId(0), uptimeMillis = time, position = at, pressed = false,
            previousUptimeMillis = time - 8, previousPosition = at, previousPressed = true,
            isInitiallyConsumed = false, type = PointerType.Mouse,
        )
        capture.pointer(PointerEvent(listOf(change)), window, viewSize, density, stage, viewOrigin)
    }

    private fun bytes(msgs: List<ControlMessage>) = msgs.map { (it as ControlMessage.UhidInput).data.toList() }

    @Test
    fun `capture waits for focus`() {
        capture.requestCapture()
        assertFalse(capture.active.value, "an unfocused window must not grab the pointer")
        focused = true
        capture.focusGained()
        assertTrue(capture.active.value)
        focused = false
        capture.focusLost()
        assertFalse(capture.active.value)
        focused = true
        capture.focusGained()
        assertFalse(capture.active.value, "regaining focus does not recapture; a click does (mouse_capture.c)")
    }

    @Test
    fun `focused window captures at once`() {
        focused = true
        capture.requestCapture()
        assertTrue(capture.active.value)
    }

    @Test
    fun `uhid keyboard and mouse - MOD+key still does not reach the device`() {
        focused = true
        capture.requestCapture()
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        val std = AwtKeyEvent.KEY_LOCATION_STANDARD
        // The window routes capture keys to the capture first; it must pass them on to the HID keyboard's state.
        assertTrue(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left))
        assertFalse(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std))
        assertTrue(keyboard.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std).isEmpty(), "Cmd+H is a shortcut")
        assertFalse(capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_H, std))
        keyboard.awtKey(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_H, std)
        assertTrue(capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left))
        assertTrue(capture.active.value, "a key in between cancels the toggle")
        // After the release, plain keys go through again.
        assertFalse(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std))
        val h = keyboard.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std).single() as ControlMessage.UhidInput
        assertEquals(0x0B, h.data[2].toInt())
        assertTrue(sent.isEmpty(), "capture keys produce no reports")
    }

    @Test
    fun screenPoint_isTheContentOriginPlusTheWindowPosition() {
        assertEquals(IntOffset(350, 500), screenPoint(IntOffset(100, 60), Offset(500f, 880f), 2f))
        assertEquals(IntOffset(110, 70), screenPoint(IntOffset(100, 60), Offset(15f, 15f), 1.5f))
    }

    @Test
    fun deltasFromScreenPoint_matchAwtPath() {
        focused = true
        capture.requestCapture()
        // The AWT path saw these as xOnScreen / yOnScreen: the content pane's origin plus the event's points.
        val moves = listOf(250 to 440, 253 to 441, 260 to 430, 270 to 425)
        moves.forEach { (x, y) -> moveTo(x, y) }
        val awt = PointerDeltaTracker()
        val expectedMapper = mapperForUhid()
        val expected = moves.flatMap { (x, y) ->
            val d = awt.move(100 + x, 60 + y)
            expectedMapper.relativeMove(d.x, d.y)
        }
        assertEquals(3, expected.size)
        assertEquals(bytes(expected), bytes(sent))
        // The first move after capturing recentres on the stage's centre on screen, which also proves warping works.
        assertEquals(listOf(IntOffset(350, 500)), cursor.warps)
        assertTrue(cursor.pointerHidden)
    }

    @Test
    fun farMove_recentresOnTheStage() {
        focused = true
        capture.requestCapture()
        moveTo(250, 440)
        moveTo(400, 440) // 150 pt right of the centre: more than a quarter of the view (125 pt)
        assertEquals(listOf(IntOffset(350, 500), IntOffset(350, 500)), cursor.warps)
        // The next event lands at the centre: the warp itself is no motion.
        moveTo(250, 440)
        moveTo(252, 440)
        val expectedMapper = mapperForUhid()
        assertEquals(bytes(expectedMapper.relativeMove(150, 0) + expectedMapper.relativeMove(2, 0)), bytes(sent))
    }

    @Test
    fun focusLostMidCapture_releasesShowsCursorReleasesButtons() {
        focused = true
        capture.requestCapture()
        moveTo(250, 440)
        assertTrue(cursor.pointerHidden)
        // A button held on the device when the window loses focus.
        mapper.press(Offset(10f, 10f), PointerButton.Primary)
        sent.clear()
        val keys = MirrorKeys(
            mapper = mapper, hidKeyboard = keyboard, send = { sent += it }, clipboardText = { null },
            scope = CoroutineScope(Dispatchers.Unconfined), sheetOpen = { false }, sheetFocused = { false },
            closeSheet = {}, applyZoom = {},
            captureKey = { id, code, location -> capture.key(id, code, location) },
            captureFocus = { if (it) capture.focusGained() else capture.focusLost() },
            mac = true,
        )
        focused = false
        keys.onFocusChanged(false)
        assertFalse(capture.active.value, "focus loss releases the capture")
        assertFalse(cursor.pointerHidden, "and shows the cursor again")
        val mouse = sent.filterIsInstance<ControlMessage.UhidInput>().filter { it.id == HidMouse.ID }
        assertEquals(listOf(HidMouse().click(0).data.toList()), mouse.map { it.data.toList() }, "every HID button released")
        // Unfocused, moves never warp.
        val warps = cursor.warps.size
        moveTo(400, 440)
        assertEquals(warps, cursor.warps.size)
    }

    @Test
    fun releaseKey_showsTheCursor() {
        focused = true
        capture.requestCapture()
        assertTrue(cursor.pointerHidden)
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left)
        capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left)
        assertFalse(capture.active.value)
        assertFalse(cursor.pointerHidden)
    }

    @Test
    fun dispose_showsTheCursor() {
        focused = true
        capture.requestCapture()
        capture.dispose()
        assertFalse(cursor.pointerHidden)
    }

    @Test
    fun warpFails_captureStaysOff() {
        cursor.canWarp = false
        focused = true
        capture.requestCapture()
        moveTo(250, 440)
        assertFalse(capture.active.value, "without warping the pointer would run off the view: no capture")
        assertFalse(cursor.pointerHidden)
        assertEquals(1, unavailable, "the window says so once")
        // Neither a click, nor the capture key, nor a new request turns it on.
        release()
        capture.requestCapture()
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        assertTrue(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left))
        assertTrue(capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left))
        assertFalse(capture.active.value)
        assertFalse(cursor.pointerHidden)
        assertEquals(1, unavailable)
        // The mouse still works: moves go on as deltas.
        sent.clear()
        moveTo(255, 442)
        assertEquals(bytes(mapperForUhid().relativeMove(5, 2)), bytes(sent))
    }
}
