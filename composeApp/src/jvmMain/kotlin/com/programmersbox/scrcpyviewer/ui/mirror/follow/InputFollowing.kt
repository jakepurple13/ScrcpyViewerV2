package com.programmersbox.scrcpyviewer.ui.mirror.follow

import com.programmersbox.scrcpyviewer.scrcpy.Position
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** Who leads and who follows. One leader at a time; both are serials of open mirrors. */
data class FollowState(val leader: String? = null, val followers: Set<String> = emptySet())

/** A touch the leader sent to follower [serial]: its `InjectTouch` action at [position] in that follower's frame. */
data class FollowedTouch(val serial: String, val action: Int, val position: Position)

sealed interface FollowRole {
    data object None : FollowRole
    data class Leader(val followers: Set<String>) : FollowRole
    data class Follower(val leader: String) : FollowRole
}

/**
 * App-wide follow link: the leader's input is sent to its followers too.
 * Closing a window drops it from the link; a reconnect swaps the session under the same serial, so it keeps following.
 */
class InputFollowing(
    private val open: StateFlow<Map<String, ScrcpySession>>,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FollowState())
    val state: StateFlow<FollowState> = _state.asStateFlow()

    init {
        open.onEach { sessions -> _state.update { it.pruned(sessions.keys) } }.launchIn(scope)
    }

    /**
     * Makes [leader] lead [followers] (none: following stops). A window that closes while this runs is pruned again
     * after the write, so it never stays behind as a ghost follower.
     */
    fun setFollowers(leader: String, followers: Set<String>) {
        _state.update { FollowState(leader, followers - leader).pruned(open.value.keys) }
        _state.update { it.pruned(open.value.keys) }
    }

    fun stop() {
        _state.value = FollowState()
    }

    private val _touches = MutableSharedFlow<FollowedTouch>(extraBufferCapacity = 64)

    /** Touches sent to followers, so each follower window can show the click ring where the tap landed. */
    val touches: SharedFlow<FollowedTouch> = _touches.asSharedFlow()

    fun touched(touch: FollowedTouch) {
        _touches.tryEmit(touch)
    }

    fun roleOf(serial: String): FollowRole {
        val s = _state.value
        return when {
            s.leader == serial -> FollowRole.Leader(s.followers)
            serial in s.followers && s.leader != null -> FollowRole.Follower(s.leader)
            else -> FollowRole.None
        }
    }

    private fun FollowState.pruned(serials: Set<String>): FollowState {
        val l = leader ?: return FollowState()
        if (l !in serials) return FollowState()
        val f = followers.filterTo(HashSet()) { it in serials && it != l }
        return if (f.isEmpty()) FollowState() else FollowState(l, f)
    }
}
