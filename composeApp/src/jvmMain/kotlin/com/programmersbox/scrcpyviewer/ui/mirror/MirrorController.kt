package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.media.RecordResult
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.DeviceMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** How a recording of [serial]'s session ended. */
data class RecordingEvent(val serial: String, val result: RecordResult)

/** The snackbar text for [result]. */
fun recordResultMessage(result: RecordResult): String = when (result) {
    is RecordResult.Saved -> "Recording saved to ${result.file}"
    is RecordResult.Failed -> "Recording failed: ${result.message}"
}

/**
 * The open mirror sessions, one per serial. Sessions run on [scope] (the app graph scope), so they outlive any
 * window composition; closing a window calls [close], and closing the app calls [closeAll].
 *
 * [reconnectable] holds the serials whose session is Disconnected (or Failed) while [devices] lists the serial
 * in the `device` state again, which enables the window's Reconnect button.
 *
 * A [DeviceMessage.Clipboard] from a device is passed to [setClipboard] unless the session has
 * `no-clipboard-autosync` or [clipboardMuted] says so for its serial at the time (a window following another one: the
 * leader's paste lands on its device, and the computer clipboard is the leader's to sync).
 *
 * Every session's [ScrcpySession.recordingResults] is relayed to [recordingResults] for as long as the session
 * lives, so a recording that ends after its window closed (or across a Reconnect) is still reported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MirrorController(
    private val scope: CoroutineScope,
    val devices: StateFlow<List<Device>>,
    private val optionsFor: (serial: String) -> OptionValues,
    private val newSession: (serial: String, values: OptionValues) -> ScrcpySession,
    private val setClipboard: (String) -> Unit = {},
    private val clipboardMuted: (serial: String) -> Boolean = { false },
) {
    private val lock = Any()
    private val _open = MutableStateFlow<Map<String, ScrcpySession>>(emptyMap())
    val open: StateFlow<Map<String, ScrcpySession>> = _open.asStateFlow()

    private val clipboardJobs = HashMap<ScrcpySession, Job>()
    private val closing = ArrayList<Job>()

    private val _focusRequests = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** A serial whose window should come to the front (mirror requested while it is already open). */
    val focusRequests: SharedFlow<String> = _focusRequests.asSharedFlow()

    private val _recordingResults = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 64)
    /** How each recording of every session ended. */
    val recordingResults: SharedFlow<RecordingEvent> = _recordingResults.asSharedFlow()

    private val statuses = open.flatMapLatest { map ->
        if (map.isEmpty()) flowOf(emptyMap())
        else combine(map.map { (serial, s) -> s.status.map { serial to it } }) { it.toMap() }
    }

    val reconnectable: StateFlow<Set<String>> = combine(statuses, devices) { st, list ->
        val ready = list.filter { it.state == DeviceState.Device }.map { it.serial }.toSet()
        st.filter { (serial, status) -> serial in ready && (status == SessionStatus.Disconnected || status == SessionStatus.Failed) }
            .keys
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Opens a session for [serial] with its current working options, or focuses its window if it is open. */
    fun open(serial: String) {
        synchronized(lock) {
            if (serial in _open.value) {
                _focusRequests.tryEmit(serial)
                return
            }
            start(serial)
        }
    }

    /** Asks [serial]'s mirror window to come to the front. Does nothing (opens nothing) when it is not open. */
    fun focus(serial: String) {
        if (serial in _open.value) _focusRequests.tryEmit(serial)
    }

    /** Closes [serial]'s window and session. */
    fun close(serial: String) {
        val session = synchronized(lock) {
            val s = _open.value[serial] ?: return
            _open.update { it - serial }
            s
        }
        shutDown(session)
    }

    /** Replaces a disconnected session with a new one, keeping the window. No-op unless [serial] is reconnectable. */
    fun reconnect(serial: String) {
        val old = synchronized(lock) {
            if (serial !in reconnectable.value) return
            val old = _open.value[serial] ?: return
            start(serial)
            old
        }
        shutDown(old)
    }

    /** Closes every session and waits for their teardown (server stopped, tunnel removed). */
    suspend fun closeAll() {
        val sessions = synchronized(lock) {
            val all = _open.value.values.toList()
            _open.value = emptyMap()
            all
        }
        sessions.forEach(::shutDown)
        val pending = synchronized(lock) { closing.toList() }
        pending.joinAll()
    }

    /** Caller holds [lock]. */
    private fun start(serial: String) {
        val values = optionsFor(serial)
        val session = newSession(serial, values)
        _open.update { it + (serial to session) }
        // Ends when the session's teardown completes its result channel.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.recordingResults.collect { _recordingResults.tryEmit(RecordingEvent(serial, it)) }
        }
        if ("no-clipboard-autosync" !in values.entries) {
            // UNDISPATCHED: subscribed before this returns, so no early clipboard message is missed.
            clipboardJobs[session] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.deviceMessages.collect { msg ->
                    if (msg is DeviceMessage.Clipboard && !clipboardMuted(serial)) runCatching { setClipboard(msg.text) }
                }
            }
        }
    }

    private fun shutDown(session: ScrcpySession) {
        synchronized(lock) {
            clipboardJobs.remove(session)?.cancel()
            val job = scope.launch { session.close() }
            closing += job
            job.invokeOnCompletion { synchronized(lock) { closing -= job } }
        }
    }
}
