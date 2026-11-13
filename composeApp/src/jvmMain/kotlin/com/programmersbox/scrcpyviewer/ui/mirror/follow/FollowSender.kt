package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectKeycode
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectScroll
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectText
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.InjectTouch
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.SetClipboard
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.UhidCreate
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.UhidDestroy
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.UhidInput
import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_DOWN
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_MOVE
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_UP
import kotlin.math.abs

/** Which kinds of input a follower window's options let through. */
data class FollowerFlags(val control: Boolean = true, val keyboard: Boolean = true, val mouse: Boolean = true)

/** A follower as it is right now: its current frame size (null before the first frame), flags and send. */
class FollowTarget(val frameSize: IntSize?, val flags: FollowerFlags, val send: (ControlMessage) -> Unit)

/**
 * Sends the leader's control messages to follower sessions. Touch and scroll positions are moved by fraction of the
 * screen onto each follower's current frame (the server drops a touch whose screen size isn't the current one).
 * Keys, text, clipboard and the other position-free messages go verbatim; UHID messages never go (they belong to the
 * leader's own HID devices).
 *
 * Each follower only gets a gesture it saw start: a follower added while the leader holds a press gets no MOVE / UP
 * until the next DOWN, and a follower dropped (or [release]d) while its press is held gets an `ACTION_UP` at its last
 * forwarded position. [target] is looked up on every call, so a reconnected session is picked up.
 */
class FollowSender(private val target: (serial: String) -> FollowTarget?) {
    /** A press in progress on one follower: the last touch forwarded to it. */
    private val pressed = mutableMapOf<String, InjectTouch>()
    private var previous: Set<String> = emptySet()

    fun send(followers: Set<String>, messages: List<ControlMessage>) {
        release(previous - followers)
        previous = followers
        for (serial in followers) {
            val t = target(serial) ?: continue
            for (msg in messages) forward(serial, t, msg)
        }
    }

    /** Sends `ACTION_UP` at the last forwarded position to each of [followers] that has a press in progress. */
    fun release(followers: Set<String>) {
        for (serial in followers) {
            val last = pressed.remove(serial) ?: continue
            val t = target(serial) ?: continue
            val size = t.frameSize ?: continue
            val position = last.position.let { if (it.screenWidth == size.width && it.screenHeight == size.height) it else remap(it, size) }
            t.send(
                InjectTouch(
                    action = ACTION_UP,
                    pointerId = last.pointerId,
                    position = position,
                    pressure = 0f,
                    actionButton = last.buttons.takeIf { it != 0 }?.let { it and -it } ?: last.actionButton,
                    buttons = 0,
                ),
            )
        }
    }

    /** [position] (pixels of a `screenWidth` x `screenHeight` frame) at the same fraction of a [to] frame. */
    fun remap(position: Position, to: IntSize): Position {
        fun scale(v: Int, from: Int, to: Int) =
            if (from <= 0) 0 else (v.toLong() * to / from).toInt().coerceIn(0, (to - 1).coerceAtLeast(0))
        return Position(
            x = scale(position.x, position.screenWidth, to.width),
            y = scale(position.y, position.screenHeight, to.height),
            screenWidth = to.width,
            screenHeight = to.height,
        )
    }

    /** True when the aspect ratios of [a] and [b] differ by more than 5%. */
    fun layoutDiffers(a: IntSize, b: IntSize): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val ratio = (a.width.toDouble() / a.height) / (b.width.toDouble() / b.height)
        return abs(ratio - 1.0) > 0.05
    }

    private fun forward(serial: String, t: FollowTarget, msg: ControlMessage) {
        if (!t.flags.control) return
        when (msg) {
            is UhidCreate, is UhidInput, is UhidDestroy -> Unit
            is InjectTouch -> touch(serial, t, msg)
            is InjectScroll -> if (t.flags.mouse) {
                val size = t.frameSize ?: return
                t.send(msg.copy(position = remap(msg.position, size)))
            }
            is InjectKeycode, is InjectText, is SetClipboard -> if (t.flags.keyboard) t.send(msg)
            else -> t.send(msg)
        }
    }

    private fun touch(serial: String, t: FollowTarget, msg: InjectTouch) {
        val held = serial in pressed
        // MOVE / UP of a press this follower never saw start: wait for the next DOWN.
        if (!held && (msg.action == ACTION_MOVE || msg.action == ACTION_UP)) return
        // An UP for a held press always goes, so a flag turned off mid-gesture can't leave a finger down.
        if (!t.flags.mouse && !(held && msg.action == ACTION_UP)) return
        val size = t.frameSize ?: return
        val out = msg.copy(position = remap(msg.position, size))
        t.send(out)
        when {
            out.action == ACTION_DOWN -> pressed[serial] = out
            out.action == ACTION_UP && out.buttons == 0 -> pressed.remove(serial)
            held -> pressed[serial] = out
        }
    }
}
