package com.programmersbox.scrcpyviewer.actions

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An inbox file: the actions plus who wrote them, when the writer said. */
data class InboxFile(val author: String?, val actions: List<CustomAction>)

class ActionFileException(message: String) : IllegalArgumentException(message)

object ActionCodec {
    const val FORMAT = "scrcpy-viewer.actions"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(actions: List<CustomAction>): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("actions", json.encodeToJsonElement(ListSerializer(CustomAction.serializer()), actions))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(text: String): List<CustomAction> = decodeActions(parseRoot(text))

    /** Like [decode] for a file an AI dropped in the inbox, which may also name its [InboxFile.author]. */
    fun decodeInbox(text: String): InboxFile {
        val root = parseRoot(text)
        val author = (root["author"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return InboxFile(author, decodeActions(root))
    }

    private fun parseRoot(text: String): JsonObject {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw notActionsFile()
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw notActionsFile()
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw notActionsFile()
        if (version > VERSION) throw ActionFileException("This file is from a newer version of Scrcpy Vwr")
        if (version < 1) throw notActionsFile()
        return root
    }

    private fun decodeActions(root: JsonObject): List<CustomAction> {
        val list = root["actions"] as? JsonArray ?: throw notActionsFile()
        return list.mapIndexed { i, el ->
            try {
                json.decodeFromJsonElement(CustomAction.serializer(), el)
            } catch (e: Exception) {
                throw ActionFileException("Action ${i + 1}: ${e.message}")
            }
        }
    }

    private fun notActionsFile() = ActionFileException("Not a Scrcpy Vwr actions file")
}
