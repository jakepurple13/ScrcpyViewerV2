package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowFollowTest {
    private val sent = mapOf("A" to ArrayList<ControlMessage>(), "B" to ArrayList(), "C" to ArrayList())
    private var flags = mapOf("A" to FollowerFlags(), "B" to FollowerFlags(), "C" to FollowerFlags(keyboard = false))
    private var followers = setOf("A", "B", "C")
    private val touches = ArrayList<FollowedTouch>()
    private val shiftUp = ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_UP, 59, 0, 0)

    private val follow = WindowFollow(
        followers = { followers },
        target = { s -> sent[s]?.let { list -> FollowTarget(IntSize(1000, 2000), flags.getValue(s)) { list += it } } },
        serials = { sent.keys },
        onTouch = { touches += it },
    ).apply { heldKeyUp = { listOf(shiftUp) } }

    private fun down() = ControlMessage.InjectTouch(
        action = InputMapper.ACTION_DOWN, pointerId = -1, position = Position(50, 100, 100, 200), pressure = 1f,
        actionButton = 1, buttons = 1,
    )

    @Test
    fun `a follower that leaves gets the held key's UP and its press lifted, the others nothing`() {
        follow.send(listOf(down()))
        sent.values.forEach { it.clear() }
        followers = setOf("A")
        follow.followersChanged(setOf("A", "B", "C"), setOf("A"))
        assertTrue(sent.getValue("A").isEmpty(), "A still follows: ${sent["A"]}")
        val b = sent.getValue("B")
        assertEquals(InputMapper.ACTION_UP, assertIs<ControlMessage.InjectTouch>(b[0]).action)
        assertEquals(shiftUp, b[1])
        // C's options turn the keyboard off: its press is lifted, no key goes.
        val c = sent.getValue("C")
        assertEquals(1, c.size)
        assertIs<ControlMessage.InjectTouch>(c.single())
    }

    @Test
    fun `nothing is sent when no follower leaves`() {
        follow.followersChanged(setOf("A"), setOf("A", "B"))
        assertTrue(sent.values.all { it.isEmpty() })
    }

    @Test
    fun `touches that reach followers are reported for their click rings`() {
        follow.send(listOf(down()))
        assertEquals(setOf("A", "B", "C"), touches.map { it.serial }.toSet())
        assertTrue(touches.all { it.action == InputMapper.ACTION_DOWN && it.position.screenWidth == 1000 })
    }
}
