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
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.ui.mirror.FollowRunner
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
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

/** [session] as a follower: its frame size and option flags right now, and its send. */
fun followTargetOf(session: ScrcpySession): FollowTarget =
    FollowTarget(frameSizeOf(session), followerFlags(session.values), session::send)

/**
 * One mirror window's side of following. While the window leads, [send] passes its pointer, nav and key messages on to
 * the [followers] through [sender]; it is called at the window's tee points only (the pointer path, the toolbar actions,
 * and `MirrorKeys.followerSend`), never for what `MirrorKeys` sends to this window's own device. [target] is looked up
 * on every message, so a reconnected follower is picked up. Every touch that reaches a follower goes to [onTouch] for
 * its click ring.
 */
class WindowFollow(
    private val followers: () -> Set<String>,
    private val target: (serial: String) -> FollowTarget?,
    /** Every open mirror's serial: where a held press may still be down. */
    private val serials: () -> Set<String>,
    private val onTouch: (FollowedTouch) -> Unit = {},
) {
    /** The plain key UP for the key held for followers (`MirrorKeys.followerHeldKeyUp`); set once the keys exist. */
    var heldKeyUp: () -> List<ControlMessage> = { emptyList() }

    val sender = FollowSender { s ->
        val t = target(s) ?: return@FollowSender null
        FollowTarget(t.frameSize, t.flags) { msg ->
            t.send(msg)
            if (msg is ControlMessage.InjectTouch) onTouch(FollowedTouch(s, msg.action, msg.position))
        }
    }

    /** The followers input last went to: where a held key may still be down if the link goes before a release. */
    private var lastFollowers: Set<String> = emptySet()

    fun send(messages: List<ControlMessage>) {
        if (messages.isEmpty()) return
        val f = followers()
        lastFollowers = f
        if (f.isNotEmpty()) sender.send(f, messages)
    }

    /**
     * The follower set went from [previous] to [now]: the ones that left get an `ACTION_UP` for a held press and the
     * key UP for a held key (a held Shift would otherwise stay down on them).
     */
    fun followersChanged(previous: Set<String>, now: Set<String>) {
        lastFollowers = now
        val gone = previous - now
        if (gone.isEmpty()) return
        sender.release(gone)
        sendKeyUp(gone)
    }

    /**
     * Lifts every press still held on a follower, and the held key on the followers it went to (focus lost, window
     * closing: a leader that closes can be disposed before the link resets, so [followersChanged] never runs).
     */
    fun releaseAll() {
        sender.release(serials())
        sendKeyUp(lastFollowers + followers())
    }

    private fun sendKeyUp(to: Set<String>) {
        val up = heldKeyUp()
        if (up.isEmpty()) return
        for (s in to) {
            val t = target(s) ?: continue
            if (t.flags.control && t.flags.keyboard) up.forEach(t.send)
        }
    }
}

/**
 * Where [serial]'s quick actions also run while it leads: one shell runner per follower, titled by [titleOf]. A
 * follower whose options turn control off is not driven, so it is left out; so is one whose session is gone or not
 * running (disconnected and waiting for a reconnect: its command would only wait out the timeout).
 */
fun followRunners(
    serial: String,
    state: FollowState,
    open: Map<String, ScrcpySession>,
    adb: AdbClient?,
    /** Whether a session is connected; a disconnected one waiting for a reconnect is left out. */
    running: (ScrcpySession) -> Boolean = { it.status.value == SessionStatus.Running },
    titleOf: (String) -> String,
): List<FollowRunner> {
    if (adb == null || state.leader != serial) return emptyList()
    return state.followers.filter { s -> open[s]?.let { followerFlags(it.values).control && running(it) } == true }
        .map { s -> FollowRunner(titleOf(s), ShellRunner(adb, s), s) }
}

/**
 * [WindowFollow] for [serial], releasing held presses and keys on the followers that leave (following stopped or the
 * set shrank) and held presses on all of them when the window goes away.
 */
@Composable
fun rememberWindowFollow(serial: String, following: InputFollowing, open: StateFlow<Map<String, ScrcpySession>>): WindowFollow {
    val follow = remember(serial, following, open) {
        WindowFollow(
            followers = { following.state.value.let { if (it.leader == serial) it.followers else emptySet() } },
            target = { s -> open.value[s]?.let(::followTargetOf) },
            serials = { open.value.keys },
            onTouch = following::touched,
        )
    }
    LaunchedEffect(follow) {
        var previous = emptySet<String>()
        following.state.collect { s ->
            val now = if (s.leader == serial) s.followers else emptySet()
            follow.followersChanged(previous, now)
            previous = now
        }
    }
    DisposableEffect(follow) { onDispose { follow.releaseAll() } }
    return follow
}

/**
 * The title bar's follow state for [serial]: its role, the other open windows (titled from [devices]), and while it
 * follows, the leader's title and whether the two screens' shapes differ ([ownFrame] against the leader's frame).
 * The candidate list and the change callback are remembered, so an unchanged strip compares equal.
 */
@Composable
fun rememberFollowStrip(
    serial: String,
    following: InputFollowing,
    open: StateFlow<Map<String, ScrcpySession>>,
    follow: WindowFollow,
    devices: List<Device>,
    captureOn: Boolean,
    ownFrame: IntSize?,
    reduceMotion: Boolean,
    control: Boolean = true,
): FollowStrip {
    val state by following.state.collectAsState()
    val sessions by open.collectAsState()
    val role = state.roleOf(serial)
    fun titleOf(s: String) = devices.firstOrNull { it.serial == s }?.let(::deviceTitle) ?: s
    val others = remember(serial, sessions.keys, devices) {
        sessions.keys.filter { it != serial }.map { FollowCandidate(it, titleOf(it)) }
    }
    val onChange = remember(serial, following) { { set: Set<String> -> following.setFollowers(serial, set) } }
    val leader = (role as? FollowRole.Follower)?.leader
    val leaderSession = leader?.let { sessions[it] }
    val leaderFrame by remember(leaderSession) { leaderSession?.frameSize ?: MutableStateFlow(null) }.collectAsState()
    val differs = ownFrame != null && leaderFrame != null && follow.sender.layoutDiffers(ownFrame, leaderFrame!!)
    return FollowStrip(
        role = role,
        others = others,
        captureOn = captureOn,
        leaderTitle = leader?.let(::titleOf),
        layoutDiffers = differs,
        onChange = onChange,
        reduceMotion = reduceMotion,
        control = control,
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
