package com.programmersbox.scrcpyviewer.actions

enum class TemplateGroup(val label: String) {
    Apps("Apps"),
    Intents("Intents"),
    Device("Device"),
    Input("Input"),
    Debug("Debug"),
}

/** A built-in starting point. Read-only and never stored; picking one fills a new unsaved draft from [action]. */
data class ActionTemplate(val group: TemplateGroup, val name: String, val description: String, val action: CustomAction)

object ActionTemplates {
    private val app = Variable("App", VariableKind.App)
    private val permissions = listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.BLUETOOTH_CONNECT",
    )
    private val permission = Variable("Permission", VariableKind.List, choices = permissions)

    private fun once(
        group: TemplateGroup,
        name: String,
        icon: String,
        command: String,
        description: String,
        variables: Map<String, Variable> = emptyMap(),
        output: OutputTarget = OutputTarget.Toast,
        confirm: Boolean = false,
    ) = ActionTemplate(
        group, name, description,
        RunOnceAction("template:$name", name, icon, command, output, confirm, variables),
    )

    private fun toggle(
        group: TemplateGroup,
        name: String,
        icon: String,
        on: String,
        off: String,
        read: String,
        onWhen: String,
        description: String,
    ) = ActionTemplate(
        group, name, description,
        ToggleAction("template:$name", name, icon, on, off, read, OnWhen(MatchMode.Equals, onWhen)),
    )

    private fun animations(scale: Int) = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        .joinToString("; ") { "settings put global $it $scale" }

    /** In picker order: grouped as [TemplateGroup] declares. */
    val all: List<ActionTemplate> = listOf(
        once(
            TemplateGroup.Apps, "Grant permission", "vpn_key", "pm grant {package} {permission}",
            "Grant a runtime permission to an app.",
            mapOf("package" to app, "permission" to permission),
        ),
        once(
            TemplateGroup.Apps, "Revoke permission", "lock", "pm revoke {package} {permission}",
            "Take a runtime permission away from an app.",
            mapOf("package" to app, "permission" to permission),
        ),
        once(
            TemplateGroup.Apps, "Start activity", "smartphone", "am start -n {package}/{activity}",
            "Open a specific screen of an app.",
            mapOf("package" to app, "activity" to Variable("Activity", VariableKind.Text, default = ".MainActivity")),
        ),
        once(
            TemplateGroup.Apps, "Launch app", "apps", "monkey -p {package} -c android.intent.category.LAUNCHER 1",
            "Open an app's launcher screen.",
            mapOf("package" to app), output = OutputTarget.None,
        ),
        once(
            TemplateGroup.Apps, "Force stop", "power_settings_new", "am force-stop {package}",
            "Stop an app and its background work.",
            mapOf("package" to app), output = OutputTarget.None,
        ),
        once(
            TemplateGroup.Apps, "Clear app data", "cleaning_services", "pm clear {package}",
            "Wipe an app's data and cache. Asks first.",
            mapOf("package" to Variable("App to clear", VariableKind.App)), confirm = true,
        ),
        once(
            TemplateGroup.Intents, "Send broadcast", "notifications", "am broadcast -a {action}",
            "Send a broadcast intent with an action.",
            mapOf("action" to Variable("Action", VariableKind.Text)),
        ),
        once(
            TemplateGroup.Intents, "Send broadcast to app", "android", "am broadcast -a {action} -p {package}",
            "Send a broadcast to one app only.",
            mapOf("action" to Variable("Action", VariableKind.Text), "package" to app),
        ),
        once(
            TemplateGroup.Intents, "Open link", "open_in_new", "am start -a android.intent.action.VIEW -d {url}",
            "Open a URL or deep link.",
            mapOf("url" to Variable("Link", VariableKind.Text)), output = OutputTarget.None,
        ),
        toggle(
            TemplateGroup.Device, "Stay awake", "battery_full", "svc power stayon true", "svc power stayon false",
            "settings get global stay_on_while_plugged_in", "7",
            "Keep the screen on while plugged in.",
        ),
        toggle(
            TemplateGroup.Device, "Animations off", "speed", animations(0), animations(1),
            "settings get global animator_duration_scale", "0",
            "Turn all three animation scales off and on.",
        ),
        once(
            TemplateGroup.Device, "Font scale", "text_fields", "settings put system font_scale {scale}",
            "Change the system font size.",
            mapOf("scale" to Variable("Scale", VariableKind.List, choices = listOf("0.85", "1.0", "1.15", "1.3"))),
            output = OutputTarget.None,
        ),
        once(
            TemplateGroup.Device, "Battery level", "battery_saver", "dumpsys battery | grep level",
            "Show the battery percentage.",
        ),
        once(
            TemplateGroup.Input, "Type text", "keyboard", "input text {text}",
            "Type text into the focused field.",
            mapOf("text" to Variable("Text", VariableKind.Text)), output = OutputTarget.None,
        ),
        once(
            TemplateGroup.Debug, "Current activity", "list_alt", "dumpsys activity activities | grep mResumedActivity",
            "Show which activity is on screen.",
        ),
        once(
            TemplateGroup.Debug, "Logcat for app", "terminal", "logcat --pid=$(pidof -s {package})",
            "Stream one app's log in the Shell tab.",
            mapOf("package" to app), output = OutputTarget.Shell,
        ),
    )

    /** Templates in [group] (null = all) whose name, description or command contains [query], ignoring case. */
    fun search(query: String, group: TemplateGroup?): List<ActionTemplate> {
        val q = query.trim().lowercase()
        return all.filter { t ->
            (group == null || t.group == group) &&
                (q.isEmpty() || listOf(t.name, t.description, commandText(t.action)).any { q in it.lowercase() })
        }
    }

    private fun commandText(action: CustomAction): String = when (action) {
        is RunOnceAction -> action.command
        is ToggleAction -> listOfNotNull(action.on, action.off, action.read).joinToString("\n")
    }
}
