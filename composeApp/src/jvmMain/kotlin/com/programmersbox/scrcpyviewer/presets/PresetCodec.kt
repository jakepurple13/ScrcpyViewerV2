package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class PresetImportException(message: String) : Exception(message)

data class DecodedFile(val presets: List<Preset>, val unknownOptions: Map<String, List<String>>)

object PresetCodec {
    const val FORMAT = "scrcpyviewer-presets"
    const val VERSION = 1
    const val SCRCPY_VERSION = "5.0"

    private val pretty = Json { prettyPrint = true }

    fun encode(presets: List<Preset>): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("scrcpyVersion", SCRCPY_VERSION)
            put("presets", JsonArray(presets.map { p ->
                buildJsonObject {
                    put("name", p.name)
                    put("description", p.description)
                    put("options", p.options.toJson())
                }
            }))
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(text: String): Result<DecodedFile> = try {
        Result.success(parse(text))
    } catch (e: PresetImportException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(notPresetFile())
    }

    private fun notPresetFile() = PresetImportException("This file isn't a Scrcpy Vwr preset file.")

    private fun parse(text: String): DecodedFile {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw notPresetFile()
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw notPresetFile()
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw notPresetFile()
        if (version > VERSION) throw PresetImportException("This file was made by a newer version of Scrcpy Vwr.")
        if (version < 1) throw notPresetFile()
        val list = root["presets"] as? JsonArray ?: throw notPresetFile()

        val presets = ArrayList<Preset>()
        val unknown = LinkedHashMap<String, List<String>>()
        for (el in list) {
            val obj = el as? JsonObject ?: throw notPresetFile()
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw notPresetFile()
            val description = (obj["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            val optionsObj = obj["options"] as? JsonObject ?: JsonObject(emptyMap())
            val (known, unknownFlags) = optionsObj.keys.partition { OptionCatalog.byFlag(it) != null }
            val options: OptionValues = JsonObject(optionsObj.filterKeys { it in known }).toOptionValues()
            if (unknownFlags.isNotEmpty()) unknown[name] = unknownFlags
            presets += Preset(UUID.randomUUID().toString(), name, description, options)
        }
        return DecodedFile(presets, unknown)
    }
}
