package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionCodecTest {
    private val example = """
        {
          "format": "scrcpy-viewer.actions",
          "version": 1,
          "actions": [
            { "id": "a1", "name": "Stay awake", "type": "toggle", "icon": "battery_full",
              "on": "svc power stayon true", "off": "svc power stayon false",
              "read": "settings get global stay_on_while_plugged_in",
              "onWhen": { "mode": "equals", "value": "7" } },
            { "id": "a2", "name": "Clear app data", "type": "once",
              "command": "pm clear {package}", "output": "toast", "confirm": true,
              "variables": { "package": { "label": "App to clear", "kind": "app" } } }
          ]
        }
    """.trimIndent()

    @Test
    fun `round trips both kinds`() {
        val actions = listOf(
            ToggleAction(
                "a1", "Stay awake", "battery_full", "svc power stayon true", "svc power stayon false",
                "settings get global stay_on_while_plugged_in", OnWhen(MatchMode.Equals, "7"),
            ),
            RunOnceAction(
                "a2", "Clear app data", "delete", "pm clear {package}", OutputTarget.Toast, true,
                mapOf("package" to Variable("App to clear", VariableKind.App)),
            ),
        )
        assertEquals(actions, ActionCodec.decode(ActionCodec.encode(actions)))
    }

    @Test
    fun `source defaults to user when absent`() {
        assertEquals(ActionSource.User, ActionCodec.decode(example)[0].source)
    }

    @Test
    fun `source ai round trips`() {
        val a = RunOnceAction("x", "X", command = "echo", source = ActionSource.Ai)
        assertEquals(listOf(a), ActionCodec.decode(ActionCodec.encode(listOf(a))))
        assertTrue("\"source\": \"ai\"" in ActionCodec.encode(listOf(a)))
    }

    @Test
    fun `decodes the spec example file`() {
        val actions = ActionCodec.decode(example)
        val toggle = assertIs<ToggleAction>(actions[0])
        assertEquals("settings get global stay_on_while_plugged_in", toggle.read)
        assertEquals(OnWhen(MatchMode.Equals, "7"), toggle.onWhen)
        val once = assertIs<RunOnceAction>(actions[1])
        assertEquals(VariableKind.App, once.variables["package"]!!.kind)
        assertEquals("play_arrow", once.icon)
    }

    @Test
    fun `rejects newer version`() {
        val e = assertFailsWith<ActionFileException> {
            ActionCodec.decode(example.replace("\"version\": 1", "\"version\": 2"))
        }
        assertEquals("This file is from a newer version of Scrcpy Vwr", e.message)
    }

    @Test
    fun `rejects wrong format`() {
        val e = assertFailsWith<ActionFileException> {
            ActionCodec.decode(example.replace("scrcpy-viewer.actions", "x"))
        }
        assertEquals("Not a Scrcpy Vwr actions file", e.message)
    }

    @Test
    fun `rejects non json`() {
        val e = assertFailsWith<ActionFileException> { ActionCodec.decode("nope") }
        assertEquals("Not a Scrcpy Vwr actions file", e.message)
    }

    @Test
    fun `names the bad action`() {
        val e = assertFailsWith<ActionFileException> {
            ActionCodec.decode(example.replace("\"command\": \"pm clear {package}\",", ""))
        }
        assertTrue(e.message!!.startsWith("Action 2:"), e.message)
    }

    @Test
    fun `ignores unknown fields`() {
        val text = example.replace("\"version\": 1,", "\"version\": 1, \"extra\": true,")
            .replace("\"name\": \"Stay awake\",", "\"name\": \"Stay awake\", \"future\": 1,")
        assertEquals(2, ActionCodec.decode(text).size)
    }

    @Test
    fun `decodeInbox reads author`() {
        val file = ActionCodec.decodeInbox(example.replace("\"version\": 1,", "\"version\": 1, \"author\": \"Claude Code\","))
        assertEquals("Claude Code", file.author)
        assertEquals(2, file.actions.size)
    }

    @Test
    fun `decodeInbox without author is null`() {
        assertEquals(null, ActionCodec.decodeInbox(example).author)
    }
}
