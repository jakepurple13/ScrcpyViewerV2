package com.programmersbox.scrcpyviewer.actions

/**
 * The actions split by who made them, in list order: what the quick-actions sheet and the Actions screen show under
 * "Your actions" and "Made by AI".
 */
data class ActionGroups(val yours: List<CustomAction>, val madeByAi: List<CustomAction>) {
    /** The "Made by AI" header and group show only when there is something in it. */
    val showAiSection: Boolean get() = madeByAi.isNotEmpty()

    /** The Actions screen labels "Your actions" only when a second group follows it, and only when it has rows. */
    val showYoursHeader: Boolean get() = showAiSection && yours.isNotEmpty()

    companion object {
        fun of(actions: List<CustomAction>) = ActionGroups(
            yours = actions.filter { it.source == ActionSource.User },
            madeByAi = actions.filter { it.source == ActionSource.Ai },
        )
    }
}
