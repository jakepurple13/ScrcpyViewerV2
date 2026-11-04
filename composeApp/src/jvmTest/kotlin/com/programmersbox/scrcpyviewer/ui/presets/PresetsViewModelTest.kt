package com.programmersbox.scrcpyviewer.ui.presets

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetCodec
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.ui.devices.SelectedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("presets-vm")
    private val store = PresetStore(dir.resolve("presets.json"), scope)
    private val options = DeviceOptionsStore(dir.resolve("devices.json"), store, scope)
    private val serial = MutableStateFlow<String?>("R1")

    private val qa = Preset("qa", "QA default", "Sharp", OptionValues(mapOf("max-size" to "1920", "no-audio" to null)))
    private val low = Preset("low", "Low bandwidth", options = OptionValues(mapOf("video-bit-rate" to "2M")))

    private val device = object : SelectedDevice {
        override val optionsKey = serial
        override fun label(key: String) = "Pixel 8"
    }

    private fun vm() = PresetsViewModel(store, options, device, scope).also {
        store.upsert(qa)
        store.upsert(low)
    }

    @Test
    fun `export selected produces spec JSON`() {
        val vm = vm()
        val decoded = PresetCodec.decode(vm.exportJson(listOf("qa"))).getOrThrow()
        assertEquals(1, decoded.presets.size)
        assertEquals("QA default", decoded.presets[0].name)
        assertEquals(qa.options, decoded.presets[0].options)
        assertTrue(vm.exportJson(listOf("qa")).contains("\"format\": \"scrcpyviewer-presets\""))
        assertTrue(vm.exportJson(listOf("qa")).contains("\"no-audio\": true"))
        // Export all keeps store order.
        assertEquals(listOf("QA default", "Low bandwidth"), PresetCodec.decode(vm.exportJson(listOf("low", "qa"))).getOrThrow().presets.map { it.name })
    }

    @Test
    fun `import malformed shows error and changes nothing`() {
        val vm = vm()
        val before = store.presets.value
        vm.beginImport("{ not json")
        val state = assertNotNull(vm.importState.value)
        assertNotNull(state.error)
        assertTrue(state.items.isEmpty())
        vm.confirmImport()
        assertNull(vm.importState.value)
        assertEquals(before, store.presets.value)
    }

    @Test
    fun `import conflict defaults to Replace`() {
        val vm = vm()
        vm.beginImport(vm.exportJson(listOf("qa")))
        val item = vm.importState.value!!.items.single()
        assertEquals(ConflictChoice.Replace, item.choice)
        assertEquals("qa", item.conflictsWith?.id)
    }

    @Test
    fun `unknown options are reported and ignored`() {
        val vm = vm()
        vm.beginImport("""{"format":"scrcpyviewer-presets","version":1,"presets":[{"name":"Old","options":{"no-audio":true,"legacy-flag":true}}]}""")
        val state = vm.importState.value!!
        assertEquals(mapOf("Old" to listOf("legacy-flag")), state.unknownOptions)
        assertEquals(setOf("no-audio"), state.items.single().preset.options.entries.keys)
    }

    @Test
    fun `confirm applies plan`() {
        val vm = vm()
        val json = PresetCodec.encode(
            listOf(
                Preset("x", "QA default", options = OptionValues(mapOf("max-fps" to "30"))),
                Preset("y", "Wi-Fi lab", options = OptionValues(mapOf("no-audio" to null))),
                Preset("z", "Low bandwidth", options = OptionValues(emptyMap())),
            ),
        )
        vm.beginImport(json)
        assertEquals(3, vm.importState.value!!.importCount)
        vm.setChoice(2, ConflictChoice.Skip)
        assertEquals(2, vm.importState.value!!.importCount)
        vm.setInclude(1, false)
        assertEquals(1, vm.importState.value!!.importCount)
        vm.setInclude(1, true)
        vm.confirmImport()

        assertNull(vm.importState.value)
        val byName = store.presets.value.associateBy { it.name }
        assertEquals(setOf("QA default", "Low bandwidth", "Wi-Fi lab"), byName.keys)
        assertEquals("qa", byName.getValue("QA default").id)
        assertEquals(mapOf("max-fps" to "30"), byName.getValue("QA default").options.entries)
        assertEquals(low, byName.getValue("Low bandwidth"))
    }

    @Test
    fun `import keep both adds a numbered copy`() {
        val vm = vm()
        vm.beginImport(vm.exportJson(listOf("qa")))
        vm.setChoice(0, ConflictChoice.KeepBoth)
        vm.confirmImport()
        assertEquals(listOf("QA default", "Low bandwidth", "QA default (2)"), store.presets.value.map { it.name })
    }

    @Test
    fun `round trip export all delete import keep both`() {
        val vm = vm()
        val file = vm.exportJson(store.presets.value.map { it.id })
        vm.delete("qa")
        vm.beginImport(file)
        // qa is gone, so only Low bandwidth conflicts.
        vm.setChoice(1, ConflictChoice.KeepBoth)
        vm.confirmImport()
        assertEquals(listOf("Low bandwidth", "QA default", "Low bandwidth (2)"), store.presets.value.map { it.name })
        assertEquals(qa.options, store.presets.value.first { it.name == "QA default" }.options)
    }

    @Test
    fun `edit rejects a name that another preset has`() {
        val vm = vm()
        vm.edit("low", "qa DEFAULT", "x", OptionValues(emptyMap()))
        assertEquals(low, store.presets.value.first { it.id == "low" })
        vm.edit("qa", "QA DEFAULT", "x", qa.options) // own name, different case: allowed
        assertEquals("QA DEFAULT", store.presets.value.first { it.id == "qa" }.name)
    }

    @Test
    fun `import newer version shows error and changes nothing`() {
        val vm = vm()
        val before = store.presets.value
        vm.beginImport("""{"format":"scrcpyviewer-presets","version":2,"presets":[{"name":"X","options":{}}]}""")
        val state = assertNotNull(vm.importState.value)
        assertEquals("This file was made by a newer version of Scrcpy Vwr.", state.error)
        vm.confirmImport()
        assertEquals(before, store.presets.value)
    }

    @Test
    fun `apply to selected device updates working set`() {
        val vm = vm()
        assertTrue(vm.state.value.canApply)
        val message = mutableListOf<String>()
        val job = scope.launchCollect(vm, message)
        assertTrue(vm.applyToSelectedDevice("qa"))
        job.cancel()
        assertEquals(qa.options, options.workingSet("R1").value.values)
        assertEquals("qa", options.workingSet("R1").value.basePresetId)
        assertEquals(listOf("Applied QA default to Pixel 8"), message)
    }

    @Test
    fun `apply disabled with no selected device`() {
        val vm = vm()
        serial.value = null
        assertFalse(vm.state.value.canApply)
        assertFalse(vm.applyToSelectedDevice("qa"))
    }

    @Test
    fun `new edit duplicate delete and default`() {
        val vm = vm()
        val created = vm.new()
        assertEquals("New preset", created.name)
        assertEquals(created.id, vm.selected.value?.id)
        assertEquals("New preset (2)", vm.new().name)

        vm.edit(created.id, " Demo ", "d", OptionValues(mapOf("no-audio" to null)))
        assertEquals("Demo", store.presets.value.first { it.id == created.id }.name)
        vm.edit(created.id, "  ", "d", OptionValues(emptyMap()))
        assertEquals("Demo", store.presets.value.first { it.id == created.id }.name)

        vm.duplicate("qa")
        assertEquals("QA default (2)", vm.selected.value?.name)

        vm.setDefault("qa")
        assertEquals("qa", vm.state.value.defaultId)
        vm.delete("qa")
        assertNull(vm.state.value.defaultId)
        assertTrue(vm.state.value.presets.none { it.id == "qa" })
    }

    private fun CoroutineScope.launchCollect(vm: PresetsViewModel, into: MutableList<String>) =
        launch(start = CoroutineStart.UNDISPATCHED) { vm.messages.collect { into += it } }

    @Test
    fun `edit saves name description and options and a device based on it sees the change as modified`() {
        val vm = vm()
        options.applyPreset("R1", "qa")
        assertFalse(options.isModified("R1"))
        val newValues = OptionValues(mapOf("max-size" to "1280"))
        vm.edit("qa", "  QA renamed ", "Fresh", newValues)
        val saved = store.presets.value.first { it.id == "qa" }
        assertEquals(Preset("qa", "QA renamed", "Fresh", newValues), saved)
        // The device keeps its own copy of the values; it now differs from the edited preset until re-applied.
        assertEquals("qa", options.effectiveBasePresetId("R1"))
        assertTrue(options.isModified("R1"))
        options.applyPreset("R1", "qa")
        assertEquals(newValues, options.workingSet("R1").value.values)
        assertFalse(options.isModified("R1"))
    }
}
