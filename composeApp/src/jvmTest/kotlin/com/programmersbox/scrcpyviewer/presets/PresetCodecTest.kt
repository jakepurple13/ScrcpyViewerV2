package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresetCodecTest {
    private val sample = listOf(
        Preset("a", "Fast", "low latency", OptionValues(mapOf("no-audio" to null, "max-size" to "1024"))),
        Preset("b", "Plain", "", OptionValues(emptyMap())),
    )

    @Test
    fun `round trip`() {
        val back = PresetCodec.decode(PresetCodec.encode(sample)).getOrThrow()
        assertEquals(sample.map { it.name }, back.presets.map { it.name })
        assertEquals(sample.map { it.description }, back.presets.map { it.description })
        assertEquals(sample.map { it.options }, back.presets.map { it.options })
        assertTrue(back.unknownOptions.isEmpty())
    }

    @Test
    fun `encode matches spec shape`() {
        val root = Json.parseToJsonElement(PresetCodec.encode(sample)).jsonObject
        assertEquals("scrcpyviewer-presets", root["format"]!!.jsonPrimitive.content)
        assertEquals(1, root["version"]!!.jsonPrimitive.int)
        assertEquals("5.0", root["scrcpyVersion"]!!.jsonPrimitive.content)
        val first = root["presets"]!!.jsonArray[0].jsonObject
        assertTrue(first["options"]!!.jsonObject["no-audio"]!!.jsonPrimitive.boolean)
        assertEquals("1024", first["options"]!!.jsonObject["max-size"]!!.jsonPrimitive.content)
        assertFalse("id" in first)
    }

    @Test
    fun `decode rejects wrong format`() {
        val r = PresetCodec.decode("""{"format":"other","version":1,"presets":[]}""")
        assertTrue(r.exceptionOrNull() is PresetImportException)
    }

    @Test
    fun `decode rejects version 2`() {
        val r = PresetCodec.decode("""{"format":"scrcpyviewer-presets","version":2,"presets":[]}""")
        assertEquals("This file was made by a newer version of Scrcpy Vwr.", r.exceptionOrNull()?.message)
    }

    @Test
    fun `decode rejects malformed JSON`() {
        val r = PresetCodec.decode("{not json")
        assertEquals("This file isn't a Scrcpy Vwr preset file.", r.exceptionOrNull()?.message)
    }

    @Test
    fun `decode reports unknown options`() {
        val text = """{"format":"scrcpyviewer-presets","version":1,"scrcpyVersion":"5.0","presets":[
            {"name":"Old","options":{"legacy-flag":true,"no-audio":true}}]}"""
        val d = PresetCodec.decode(text).getOrThrow()
        assertEquals(mapOf("Old" to listOf("legacy-flag")), d.unknownOptions)
        assertEquals(OptionValues(mapOf("no-audio" to null)), d.presets.single().options)
    }
}
