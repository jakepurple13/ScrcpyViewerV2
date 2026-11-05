package com.programmersbox.scrcpyviewer.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CustomAction {
    val id: String
    val name: String
    val icon: String
    val source: ActionSource
}

/** Who made an action. [User] is the default and is omitted from the file; [Ai] marks actions an AI skill created. */
@Serializable
enum class ActionSource {
    @SerialName("user") User,
    @SerialName("ai") Ai,
}

@Serializable
@SerialName("toggle")
data class ToggleAction(
    override val id: String,
    override val name: String,
    override val icon: String = "toggle_on",
    val on: String,
    val off: String,
    val read: String? = null,
    val onWhen: OnWhen? = null,
    override val source: ActionSource = ActionSource.User,
) : CustomAction

@Serializable
@SerialName("once")
data class RunOnceAction(
    override val id: String,
    override val name: String,
    override val icon: String = "play_arrow",
    val command: String,
    val output: OutputTarget = OutputTarget.Toast,
    val confirm: Boolean = false,
    val variables: Map<String, Variable> = emptyMap(),
    override val source: ActionSource = ActionSource.User,
) : CustomAction

@Serializable
enum class OutputTarget {
    @SerialName("toast") Toast,
    @SerialName("shell") Shell,
    @SerialName("none") None,
}

@Serializable
data class OnWhen(val mode: MatchMode, val value: String) {
    fun matches(output: String): Boolean {
        val t = output.trim()
        return when (mode) {
            MatchMode.Equals -> t == value
            MatchMode.Contains -> t.contains(value)
            MatchMode.Regex -> runCatching { Regex(value).containsMatchIn(t) }.getOrDefault(false)
        }
    }
}

@Serializable
enum class MatchMode {
    @SerialName("equals") Equals,
    @SerialName("contains") Contains,
    @SerialName("regex") Regex,
}

@Serializable
data class Variable(
    val label: String,
    val kind: VariableKind = VariableKind.Text,
    val default: String = "",
    val choices: List<String> = emptyList(),
)

@Serializable
enum class VariableKind {
    @SerialName("text") Text,
    @SerialName("list") List,
    @SerialName("app") App,
}
