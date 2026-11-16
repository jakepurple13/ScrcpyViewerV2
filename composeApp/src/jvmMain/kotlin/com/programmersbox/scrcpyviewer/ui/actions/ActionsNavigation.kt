package com.programmersbox.scrcpyviewer.ui.actions

import com.programmersbox.scrcpyviewer.actions.ActionTemplate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Asks the main window's Actions screen to open on [actionId], or on a blank new action when [new], or (with
 * [template]) on a new action filled from that template. [banner] false hides the editor's "from a built-in template"
 * banner, for a template made on the fly (the Links tab's Save as quick action).
 */
data class ActionsRequest(
    val actionId: String?,
    val new: Boolean,
    val template: ActionTemplate? = null,
    val banner: Boolean = true,
)

/** Requests from mirror windows to open the Actions screen. Consumed by the main window (selects the tab, brings it to front). */
class ActionsNavigation {
    private val _requests = MutableSharedFlow<ActionsRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ActionsRequest> = _requests

    /** [actionId] opens that action; [new] a blank one; neither just shows the screen. */
    fun open(actionId: String?, new: Boolean = false) {
        _requests.tryEmit(ActionsRequest(actionId, new))
    }

    /** A new action filled from [template], without the built-in-template banner (it isn't one). */
    fun openNew(template: ActionTemplate) {
        _requests.tryEmit(ActionsRequest(null, new = true, template = template, banner = false))
    }
}
