package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionTemplatesTest {
    private val all = ActionTemplates.all

    @Test
    fun `has the sixteen templates in group order`() {
        assertEquals(
            listOf(
                "Grant permission", "Revoke permission", "Start activity", "Launch app", "Force stop", "Clear app data",
                "Send broadcast", "Send broadcast to app", "Open link",
                "Stay awake", "Animations off", "Font scale", "Battery level",
                "Type text", "Current activity", "Logcat for app",
            ),
            all.map { it.name },
        )
        assertEquals(
            listOf(
                TemplateGroup.Apps to 6, TemplateGroup.Intents to 3, TemplateGroup.Device to 4,
                TemplateGroup.Input to 1, TemplateGroup.Debug to 2,
            ),
            all.groupBy { it.group }.map { it.key to it.value.size },
        )
        assertEquals(listOf("Apps", "Intents", "Device", "Input", "Debug"), TemplateGroup.entries.map { it.label })
    }

    @Test
    fun `every icon is in the catalog and names match the action`() {
        val known = ActionIcons.groups.flatMap { it.icons }.map { it.name }.toSet()
        all.forEach {
            assertTrue(it.action.icon in known, "${it.name}: icon ${it.action.icon} is not in ActionIcons")
            assertEquals(it.name, it.action.name)
        }
    }

    @Test
    fun `run once variables match the command exactly and toggles have no variables`() {
        all.forEach { t ->
            when (val a = t.action) {
                is RunOnceAction -> assertEquals(
                    CommandTemplate.variables(a.command), a.variables.keys.toList(), "${t.name}: variables",
                )
                is ToggleAction -> {
                    assertEquals(emptyList(), CommandTemplate.variables(a.on), "${t.name}: on")
                    assertEquals(emptyList(), CommandTemplate.variables(a.off), "${t.name}: off")
                    assertTrue(a.read != null && a.onWhen != null, "${t.name}: toggle has a read rule")
                }
            }
        }
    }

    @Test
    fun `grant permission has app and permission list variables`() {
        val a = assertIs<RunOnceAction>(all.first { it.name == "Grant permission" }.action)
        assertEquals("pm grant {package} {permission}", a.command)
        assertEquals(Variable("App", VariableKind.App), a.variables["package"])
        val permission = a.variables.getValue("permission")
        assertEquals(VariableKind.List, permission.kind)
        assertEquals(8, permission.choices.size)
        assertEquals("android.permission.CAMERA", permission.choices.first())
        assertEquals(OutputTarget.Toast, a.output)
    }

    @Test
    fun `clear data confirms and logcat goes to the shell tab`() {
        assertEquals(true, (all.first { it.name == "Clear app data" }.action as RunOnceAction).confirm)
        assertEquals(OutputTarget.Shell, (all.first { it.name == "Logcat for app" }.action as RunOnceAction).output)
    }

    @Test
    fun `animations off sets all three scales`() {
        val a = assertIs<ToggleAction>(all.first { it.name == "Animations off" }.action)
        assertEquals(3, a.on.split("; ").size)
        assertEquals(a.on.replace(" 0", " 1"), a.off)
        assertEquals(OnWhen(MatchMode.Equals, "0"), a.onWhen)
    }

    @Test
    fun `search matches name description and command, case-insensitively`() {
        assertEquals(listOf("Grant permission", "Revoke permission"), ActionTemplates.search("PERMISSION", null).map { it.name }.take(2))
        assertTrue(ActionTemplates.search("grep level", null).any { it.name == "Battery level" }, "command")
        assertTrue(ActionTemplates.search("plugged in", null).any { it.name == "Stay awake" }, "description")
        assertEquals(emptyList(), ActionTemplates.search("zzzz", null))
        assertEquals(16, ActionTemplates.search("  ", null).size)
    }

    @Test
    fun `group filter narrows and combines with the query`() {
        assertEquals(3, ActionTemplates.search("", TemplateGroup.Intents).size)
        assertEquals(listOf("Send broadcast", "Send broadcast to app"), ActionTemplates.search("broad", TemplateGroup.Intents).map { it.name })
        assertEquals(emptyList(), ActionTemplates.search("broad", TemplateGroup.Apps))
    }
}
