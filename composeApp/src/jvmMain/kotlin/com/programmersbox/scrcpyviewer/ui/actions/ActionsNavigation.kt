package com.programmersbox.scrcpyviewer.ui.actions

import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Asks the main window's Actions screen to open on [actionId], or on a blank new action when [new], or (with
 * [template]) on a new action filled from that built-in template.
 */
data class ActionsRequest(val actionId: String?, val new: Boolean, val template: ActionTemplate? = null)

/** Requests from mirror windows to open the Actions screen. Consumed by the main window (selects the tab, brings it to front). */
class ActionsNavigation {
    private val _requests = MutableSharedFlow<ActionsRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ActionsRequest> = _requests

    /** [actionId] opens that action; [new] a blank one; neither just shows the screen. */
    fun open(actionId: String?, new: Boolean = false) {
        _requests.tryEmit(ActionsRequest(actionId, new))
    }
}
