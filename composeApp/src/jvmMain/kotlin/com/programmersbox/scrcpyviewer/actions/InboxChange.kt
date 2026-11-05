package com.programmersbox.scrcpyviewer.actions

/** What [ActionStore.applyInbox] did with one incoming action. [name] is the name it ended up with. */
sealed interface InboxChange {
    val name: String

    data class Added(override val name: String) : InboxChange
    data class Updated(override val name: String) : InboxChange
}
