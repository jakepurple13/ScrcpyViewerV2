package com.programmersbox.scrcpyviewer.ui.mirror.follow

import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** Who leads and who follows. One leader at a time; both are serials of open mirrors. */
data class FollowState(val leader: String? = null, val followers: Set<String> = emptySet())

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

    fun setFollowers(leader: String, followers: Set<String>) {
        val serials = open.value.keys
        _state.value = FollowState(leader, followers - leader).pruned(serials)
    }

    fun stop() {
        _state.value = FollowState()
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
