package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectKeycode
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectScroll
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectText
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectTouch
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.RotateDevice
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.SetClipboard
import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_DOWN
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_MOVE
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_UP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowSenderTest {
    private val phone = IntSize(1080, 2340)
    private val tablet = IntSize(1600, 2560)

    private class Device(var frameSize: IntSize?, var flags: FollowerFlags = FollowerFlags()) {
        val sent = mutableListOf<ControlMessage>()
        var send: (ControlMessage) -> Unit = { sent += it }
        fun target() = FollowTarget(frameSize, flags) { send(it) }
    }

    private val devices = mutableMapOf<String, Device>()
    private val sender = FollowSender { devices[it]?.target() }

    private fun device(serial: String, frame: IntSize?, flags: FollowerFlags = FollowerFlags()) =
        Device(frame, flags).also { devices[serial] = it }

    private fun leader(x: Int, y: Int) = Position(x, y, phone.width, phone.height)

    private fun touch(action: Int, p: Position) =
        InjectTouch(action, ControlMessage.POINTER_ID_MOUSE, p, if (action == ACTION_UP) 0f else 1f, 1, if (action == ACTION_UP) 0 else 1)

    @Test
    fun `touch remaps by fraction`() {
        val b = device("B", tablet)
        sender.send(setOf("B"), listOf(touch(ACTION_DOWN, leader(540, 1170))))
        assertEquals(listOf<ControlMessage>(touch(ACTION_DOWN, Position(800, 1280, 1600, 2560))), b.sent)
    }

    @Test
    fun `touch remaps to rotated follower`() {
        val b = device("B", IntSize(2340, 1080))
        sender.send(setOf("B"), listOf(touch(ACTION_DOWN, leader(270, 585))))
        assertEquals(Position(585, 270, 2340, 1080), (b.sent.single() as InjectTouch).position)
    }

    @Test
    fun `scroll remaps by fraction`() {
        val b = device("B", tablet)
        sender.send(setOf("B"), listOf(InjectScroll(leader(540, 1170), hscroll = 1.5f, vscroll = -2f, buttons = 0)))
        assertEquals(listOf<ControlMessage>(InjectScroll(Position(800, 1280, 1600, 2560), 1.5f, -2f, 0)), b.sent)
    }

    @Test
    fun `follower without frame is skipped`() {
        val b = device("B", null)
        val c = device("C", tablet)
        sender.send(setOf("B", "C"), listOf(InjectScroll(leader(540, 1170), 0f, 1f, 0)))
        assertTrue(b.sent.isEmpty())
        assertEquals(1, c.sent.size)
    }

    @Test
    fun `key text clipboard rotate go verbatim`() {
        val b = device("B", tablet)
        val msgs = listOf(
            InjectKeycode(0, 4, 0, 0),
            InjectText("hi"),
            SetClipboard(7, paste = true, text = "clip"),
            RotateDevice,
        )
        sender.send(setOf("B"), msgs)
        assertEquals(msgs, b.sent)
    }

    @Test
    fun `key messages go even without a frame`() {
        val b = device("B", null)
        sender.send(setOf("B"), listOf(InjectText("hi")))
        assertEquals(listOf<ControlMessage>(InjectText("hi")), b.sent)
    }

    @Test
    fun `uhid is never forwarded`() {
        val b = device("B", tablet)
        sender.send(
            setOf("B"),
            listOf(
                ControlMessage.UhidCreate(1, 0, 0, "kbd", byteArrayOf(1)),
                ControlMessage.UhidInput(1, byteArrayOf(2)),
                ControlMessage.UhidDestroy(1),
            ),
        )
        assertTrue(b.sent.isEmpty())
    }

    @Test
    fun `disabled flags filter`() {
        val noControl = device("A", tablet, FollowerFlags(control = false))
        val noKeyboard = device("B", tablet, FollowerFlags(keyboard = false))
        val noMouse = device("C", tablet, FollowerFlags(mouse = false))
        val down = touch(ACTION_DOWN, leader(540, 1170))
        val scroll = InjectScroll(leader(540, 1170), 0f, 1f, 0)
        val key = InjectKeycode(0, 4, 0, 0)
        val text = InjectText("hi")
        val clip = SetClipboard(1, paste = true, text = "c")
        sender.send(setOf("A", "B", "C"), listOf(down, scroll, key, text, clip, RotateDevice))
        assertTrue(noControl.sent.isEmpty())
        assertEquals(listOf(InjectTouch::class, InjectScroll::class, RotateDevice::class), noKeyboard.sent.map { it::class })
        assertEquals(listOf<ControlMessage>(key, text, clip, RotateDevice), noMouse.sent)
    }

    @Test
    fun `release after down sends up`() {
        val b = device("B", tablet)
        sender.send(setOf("B"), listOf(touch(ACTION_DOWN, leader(540, 1170)), touch(ACTION_MOVE, leader(108, 234))))
        sender.release(setOf("B"))
        val up = b.sent.last() as InjectTouch
        assertEquals(ACTION_UP, up.action)
        assertEquals(Position(160, 256, 1600, 2560), up.position)
        assertEquals(0, up.buttons)

        b.sent.clear()
        sender.send(setOf("B"), listOf(touch(ACTION_DOWN, leader(10, 10)), touch(ACTION_UP, leader(10, 10))))
        b.sent.clear()
        sender.release(setOf("B"))
        assertTrue(b.sent.isEmpty())
    }

    @Test
    fun `release with nothing pressed sends nothing`() {
        val b = device("B", tablet)
        sender.release(setOf("B"))
        assertTrue(b.sent.isEmpty())
    }

    @Test
    fun `target looked up per call`() {
        val b = device("B", tablet)
        sender.send(setOf("B"), listOf(InjectText("one")))
        val reconnected = mutableListOf<ControlMessage>()
        b.send = { reconnected += it }
        sender.send(setOf("B"), listOf(InjectText("two")))
        assertEquals(listOf<ControlMessage>(InjectText("one")), b.sent)
        assertEquals(listOf<ControlMessage>(InjectText("two")), reconnected)
    }

    @Test
    fun `follower added mid-drag waits for next down`() {
        val b = device("B", tablet)
        val c = device("C", tablet)
        sender.send(setOf("B"), listOf(touch(ACTION_DOWN, leader(540, 1170))))
        sender.send(setOf("B", "C"), listOf(touch(ACTION_MOVE, leader(108, 234))))
        sender.send(setOf("B", "C"), listOf(touch(ACTION_UP, leader(108, 234))))
        assertTrue(c.sent.isEmpty())
        assertEquals(listOf(ACTION_DOWN, ACTION_MOVE, ACTION_UP), b.sent.map { (it as InjectTouch).action })

        sender.send(setOf("B", "C"), listOf(touch(ACTION_DOWN, leader(540, 1170))))
        assertEquals(listOf(ACTION_DOWN), c.sent.map { (it as InjectTouch).action })
    }

    @Test
    fun `follower removed mid-drag gets up`() {
        val b = device("B", tablet)
        val c = device("C", tablet)
        sender.send(setOf("B", "C"), listOf(touch(ACTION_DOWN, leader(540, 1170))))
        sender.send(setOf("B", "C"), listOf(touch(ACTION_MOVE, leader(108, 234))))
        sender.send(setOf("B"), listOf(touch(ACTION_MOVE, leader(216, 468))))
        val up = c.sent.last() as InjectTouch
        assertEquals(ACTION_UP, up.action)
        assertEquals(Position(160, 256, 1600, 2560), up.position)
        assertEquals(3, c.sent.size)
        assertEquals(ACTION_MOVE, (b.sent.last() as InjectTouch).action)

        // Re-added mid-drag: still waits for the next DOWN.
        sender.send(setOf("B", "C"), listOf(touch(ACTION_UP, leader(216, 468))))
        assertEquals(3, c.sent.size)
    }

    @Test
    fun `layoutDiffers`() {
        assertFalse(sender.layoutDiffers(phone, IntSize(1080, 2424)))
        assertTrue(sender.layoutDiffers(phone, tablet))
    }

    @Test
    fun `remap keeps the point on the frame`() {
        assertEquals(Position(1599, 2559, 1600, 2560), sender.remap(leader(1080, 2340), tablet))
    }
}
