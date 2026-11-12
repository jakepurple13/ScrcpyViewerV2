package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.ui.presets.exportFileName
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeFileDialogsTest {
    @Test
    fun extensionsFilter_apk() {
        assertEquals(listOf("apk"), extensionsFilter(listOf("apk")))
        assertEquals(listOf("apk"), extensionsFilter(listOf(".APK", "apk", " ")))
        assertEquals(listOf("*.apk", "*.APK"), globPatterns(extensionsFilter(listOf("apk"))))
        assertEquals("APK files (*.apk)\u0000*.apk\u0000\u0000", windowsFilter(listOf("apk")))
        assertEquals("All files\u0000*.*\u0000\u0000", windowsFilter(emptyList()))
    }

    @Test
    fun suggestedName_getsExtension() {
        // PresetFileDialogs suggests exportFileName(...) as is: it already ends in .json.
        assertEquals("My Preset.scrcpypresets.json", suggestedFileName(exportFileName("My Preset"), "json"))
        assertEquals("scrcpy-actions.json", suggestedFileName("scrcpy-actions.json", ".json"))
        assertEquals("notes.json", suggestedFileName("notes", "json"))
        assertEquals("notes.JSON", suggestedFileName("notes.JSON", "json"))
    }

    @Test
    fun linuxPickerCommand_zenityThenKdialog() {
        val request = PickerRequest.Open("Install APK", listOf("apk"), multiple = true, directories = false, initial = null)
        val both = linuxPickerCommand(request) { true }!!
        assertEquals("zenity", both.tool)
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Install APK", "--multiple", "--separator=\n", "--file-filter=*.apk *.APK"),
            both.args,
        )

        val kde = linuxPickerCommand(request) { it == "kdialog" }!!
        assertEquals("kdialog", kde.tool)
        assertEquals(
            listOf("kdialog", "--title", "Install APK", "--getopenfilename", ".", "*.apk *.APK", "--multiple", "--separate-output"),
            kde.args,
        )

        assertNull(linuxPickerCommand(request) { false })
    }

    @Test
    fun linuxPickerCommand_folderAndSave() {
        val folder = PickerRequest.Open("Choose folder", emptyList(), multiple = false, directories = true, initial = Path.of("/tmp/caps"))
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Choose folder", "--directory", "--filename=/tmp/caps/"),
            linuxPickerCommand(folder) { true }!!.args,
        )
        assertEquals(
            listOf("kdialog", "--title", "Choose folder", "--getexistingdirectory", "/tmp/caps"),
            linuxPickerCommand(folder) { it == "kdialog" }!!.args,
        )

        val save = PickerRequest.Save("Export presets", "a.scrcpypresets.json", "json")
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Export presets", "--save", "--confirm-overwrite", "--filename=a.scrcpypresets.json", "--file-filter=*.json *.JSON"),
            linuxPickerCommand(save) { true }!!.args,
        )
        assertEquals(
            listOf("kdialog", "--title", "Export presets", "--getsavefilename", "a.scrcpypresets.json", "*.json *.JSON"),
            linuxPickerCommand(save) { it == "kdialog" }!!.args,
        )
    }

    @Test
    fun pickerOutput_splitsLinesAndAddsSaveExtension() {
        assertEquals(listOf(Path.of("/a/b.apk"), Path.of("/c d/e.apk")), parsePickerOutput("/a/b.apk\n/c d/e.apk\n"))
        assertEquals(emptyList(), parsePickerOutput("\n"))
        assertEquals(Path.of("/x/presets.json"), withExtension(Path.of("/x/presets"), "json"))
        assertEquals(Path.of("/x/presets.json"), withExtension(Path.of("/x/presets.json"), "json"))
    }

    @Test
    fun windowsMultiSelect_dirThenNames() {
        assertEquals(listOf(Path.of("C:\\dir\\a.apk")), parseWindowsSelection(listOf("C:\\dir\\a.apk")))
        assertEquals(
            listOf(Path.of("C:\\dir", "a.apk"), Path.of("C:\\dir", "b.apk")),
            parseWindowsSelection(listOf("C:\\dir", "a.apk", "b.apk")),
        )
        assertEquals(emptyList(), parseWindowsSelection(emptyList()))
    }

    @Test
    fun safeFileDialogs_failureReturnsCancelAndNotifiesOnce() {
        val failing = object : NativeFileDialogs {
            override fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean, initial: Path?): List<Path> =
                error("boom")

            override fun save(title: String, suggestedName: String, extension: String): Path? = error("boom")
        }
        val messages = mutableListOf<String>()
        val safe = SafeFileDialogs(failing) { messages += it }
        assertEquals(emptyList(), safe.open("t", listOf("apk"), multiple = true, directories = false, initial = null))
        assertEquals(1, messages.size)
        assertNull(safe.save("t", "x", "json"))
        assertEquals(2, messages.size)
    }
}
