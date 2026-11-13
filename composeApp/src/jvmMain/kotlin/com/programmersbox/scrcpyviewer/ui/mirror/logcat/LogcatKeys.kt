package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/** What a key does in the Logcat tab (the spec's Keys). */
enum class LogcatKey { TogglePlay, FocusFilter, Clear }

/**
 * The Logcat tab's action for [event], or null when it is not one of the tab's keys. ⌘ on macOS ([isMac]) and Ctrl
 * elsewhere is the command modifier; the other platform's modifier never stands in for it. Plain Space is
 * [LogcatKey.TogglePlay] on every event it makes (press, typed, release, so a focused button never clicks too), except
 * while focus is in the filter field ([inField]), where it types. ⌘F and ⌘K act on the press, from the field too.
 */
fun logcatKeyAction(event: KeyEvent, isMac: Boolean, inField: Boolean): LogcatKey? {
    val ctrl = event.isCtrlPressed
    val meta = event.isMetaPressed
    val plain = !ctrl && !meta && !event.isAltPressed && !event.isShiftPressed
    val space = when (event.type) {
        KeyEventType.KeyDown, KeyEventType.KeyUp -> event.key == Key.Spacebar
        else -> event.utf16CodePoint == ' '.code
    }
    if (space) return LogcatKey.TogglePlay.takeIf { plain && !inField }
    if (event.type != KeyEventType.KeyDown) return null
    val cmd = if (isMac) meta && !ctrl else ctrl && !meta
    if (!cmd || event.isAltPressed || event.isShiftPressed) return null
    return when (event.key) {
        Key.F -> LogcatKey.FocusFilter
        Key.K -> LogcatKey.Clear
        else -> null
    }
}

/**
 * The filter field's focus as the tab's keys see it: whether it has [focused] focus, and [focusAndSelectAll] for ⌘F.
 * The field reads [selectAllRequests] and owns [requester].
 */
@Stable
internal class LogcatFilterFocus {
    val requester = FocusRequester()
    /** Written by the field, read by the tab's key handler (not by composition). */
    var focused = false
        internal set

    /** Bumped by [focusAndSelectAll]; the field selects its text and takes focus on each bump. */
    var selectAllRequests by mutableIntStateOf(0)
        private set

    fun focusAndSelectAll() {
        selectAllRequests++
    }
}
