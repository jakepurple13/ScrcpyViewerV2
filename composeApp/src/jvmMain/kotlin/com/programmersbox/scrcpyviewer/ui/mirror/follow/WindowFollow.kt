package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.ui.mirror.ClickIndicator
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_DOWN
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_MOVE
import com.programmersbox.scrcpyviewer.ui.mirror.InputMapper.Companion.ACTION_UP
import com.programmersbox.scrcpyviewer.ui.mirror.monotonicMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The size of the frame on screen, which can lag the session size for a frame after a rotation (as the mapper uses). */
fun frameSizeOf(session: ScrcpySession): IntSize? =
    session.frames.value?.let { IntSize(it.width, it.height) } ?: session.frameSize.value

/**
 * One mirror window's side of following. While the window leads, [send] passes its pointer, nav and key messages on to
 * the followers through [sender]; it is called at the window's tee points only (the pointer path, the toolbar actions,
 * and `MirrorKeys.followerSend`), never for what `MirrorKeys` sends to this window's own device. Sessions are looked
 * up by serial on every message, so a reconnected follower is picked up. Every touch that reaches a follower is
 * reported to [InputFollowing.touched] for its click ring.
 */
class WindowFollow(
    private val serial: String,
    private val following: InputFollowing,
    private val open: () -> Map<String, ScrcpySession>,
) {
    val sender = FollowSender { s ->
        val session = open()[s] ?: return@FollowSender null
        FollowTarget(frameSizeOf(session), followerFlags(session.values)) { msg ->
            session.send(msg)
            if (msg is ControlMessage.InjectTouch) following.touched(FollowedTouch(s, msg.action, msg.position))
        }
    }

    /** The windows following this one right now (none unless it leads). */
    fun followers(): Set<String> = following.state.value.let { if (it.leader == serial) it.followers else emptySet() }

    fun send(messages: List<ControlMessage>) {
        if (messages.isEmpty()) return
        val f = followers()
        if (f.isNotEmpty()) sender.send(f, messages)
    }

    /** Lifts every press still held on a follower (focus lost, window closing). */
    fun releaseAll() = sender.release(open().keys)
}

/**
 * [WindowFollow] for [serial], releasing held presses on the followers that leave (following stopped or the set
 * shrank) and on all of them when the window goes away.
 */
@Composable
fun rememberWindowFollow(serial: String, following: InputFollowing, open: StateFlow<Map<String, ScrcpySession>>): WindowFollow {
    val follow = remember(serial, following, open) { WindowFollow(serial, following) { open.value } }
    LaunchedEffect(follow) {
        var previous = emptySet<String>()
        following.state.collect { s ->
            val now = if (s.leader == serial) s.followers else emptySet()
            val gone = previous - now
            if (gone.isNotEmpty()) follow.sender.release(gone)
            previous = now
        }
    }
    DisposableEffect(follow) { onDispose { follow.releaseAll() } }
    return follow
}

/**
 * The title bar's follow state for [serial]: its role, the other open windows (titled by [titleOf]), and while it
 * follows, the leader's title and whether the two screens' shapes differ ([ownFrame] against the leader's frame).
 */
@Composable
fun rememberFollowStrip(
    serial: String,
    following: InputFollowing,
    open: StateFlow<Map<String, ScrcpySession>>,
    follow: WindowFollow,
    titleOf: (String) -> String,
    captureOn: Boolean,
    ownFrame: IntSize?,
    reduceMotion: Boolean,
): FollowStrip {
    val state by following.state.collectAsState()
    val sessions by open.collectAsState()
    val role = when {
        state.leader == serial -> FollowRole.Leader(state.followers)
        serial in state.followers && state.leader != null -> FollowRole.Follower(state.leader!!)
        else -> FollowRole.None
    }
    val leader = (role as? FollowRole.Follower)?.leader
    val leaderSession = leader?.let { sessions[it] }
    val leaderFrame by remember(leaderSession) { leaderSession?.frameSize ?: MutableStateFlow(null) }.collectAsState()
    val differs = ownFrame != null && leaderFrame != null && follow.sender.layoutDiffers(ownFrame, leaderFrame!!)
    val currentTitleOf by rememberUpdatedState(titleOf)
    return FollowStrip(
        role = role,
        others = sessions.keys.filter { it != serial }.map { FollowCandidate(it, currentTitleOf(it)) },
        captureOn = captureOn,
        leaderTitle = leader?.let(currentTitleOf),
        layoutDiffers = differs,
        onChange = { following.setFollowers(serial, it) },
        reduceMotion = reduceMotion,
    )
}

/**
 * Shows the leader's touches on this follower window: [indicator] (the show-touches dot and click ring) is pressed,
 * moved and released at each touch's spot, put back on the view by [toView].
 */
@Composable
fun FollowedTouchRings(serial: String, following: InputFollowing, indicator: ClickIndicator, toView: (Position) -> Offset?) {
    val currentToView by rememberUpdatedState(toView)
    LaunchedEffect(serial, following, indicator) {
        following.touches.collect { t ->
            if (t.serial != serial) return@collect
            val p = currentToView(t.position) ?: return@collect
            when (t.action) {
                ACTION_DOWN -> indicator.press(p)
                ACTION_MOVE -> indicator.move(p)
                ACTION_UP -> indicator.release(monotonicMillis())
            }
        }
    }
}
