package com.programmersbox.scrcpyviewer.ui.mirror.follow

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InputFollowingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val open = MutableStateFlow(sessions("A", "B", "C"))
    private val following = InputFollowing(open, scope)

    private fun session(serial: String) =
        ScrcpySession(serial, OptionValues(emptyMap()), LocalPairLauncher(), { _, _ -> null }, { _, _ -> null }, sessionScope)

    private fun sessions(vararg serials: String) = serials.associateWith(::session)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        sessionScope.cancel()
    }

    @Test
    fun `setFollowers makes a leader`() {
        following.setFollowers("A", setOf("B", "C"))
        assertEquals(FollowState("A", setOf("B", "C")), following.state.value)
        assertEquals(FollowRole.Follower("A"), following.roleOf("B"))
        assertEquals(FollowRole.Leader(setOf("B", "C")), following.roleOf("A"))
        assertEquals(FollowRole.None, following.roleOf("Z"))
    }

    @Test
    fun `empty followers stops`() {
        following.setFollowers("A", setOf("B"))
        following.setFollowers("A", emptySet())
        assertEquals(FollowState(), following.state.value)
    }

    @Test
    fun `new leader replaces old link`() {
        following.setFollowers("A", setOf("B"))
        following.setFollowers("C", setOf("A"))
        assertEquals(FollowState("C", setOf("A")), following.state.value)
    }

    @Test
    fun `leader is never its own follower`() {
        following.setFollowers("A", setOf("A", "B"))
        assertEquals(FollowState("A", setOf("B")), following.state.value)
    }

    @Test
    fun `closing a follower drops it`() {
        following.setFollowers("A", setOf("B", "C"))
        open.value = sessions("A", "C")
        assertEquals(FollowState("A", setOf("C")), following.state.value)
        open.value = sessions("A")
        assertEquals(FollowState(), following.state.value)
    }

    @Test
    fun `closing the leader resets`() {
        following.setFollowers("A", setOf("B"))
        open.value = sessions("B", "C")
        assertEquals(FollowState(), following.state.value)
    }

    @Test
    fun `reconnect keeps following`() {
        following.setFollowers("A", setOf("B"))
        open.value = open.value + ("B" to session("B"))
        assertEquals(FollowState("A", setOf("B")), following.state.value)
    }

    @Test
    fun `followers must be open`() {
        following.setFollowers("A", setOf("Z"))
        assertEquals(FollowState(), following.state.value)
    }

    @Test
    fun `stop clears`() {
        following.setFollowers("A", setOf("B"))
        following.stop()
        assertEquals(FollowState(), following.state.value)
    }
}
