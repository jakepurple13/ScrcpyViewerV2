package com.programmersbox.scrcpyviewer.ui.presets

import com.programmersbox.scrcpyviewer.ui.common.NativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import java.nio.file.Path

const val PRESET_FILE_SUFFIX = ".scrcpypresets.json"

/** Native file pickers, behind an interface so tests never open a window. */
interface PresetFileDialogs {
    /** Asks where to save; null when cancelled. */
    fun chooseExportFile(suggestedName: String): Path?

    /** Asks which `.json` file to import; null when cancelled. */
    fun chooseImportFile(): Path?
}

/** The platform's own save / open dialogs. [what] names the content in the titles ("presets", "actions"). */
class NativePresetFileDialogs(
    private val dialogs: NativeFileDialogs = nativeFileDialogs(),
    private val what: String = "presets",
) : PresetFileDialogs {
    override fun chooseExportFile(suggestedName: String): Path? = dialogs.save("Export $what", suggestedName, "json")

    override fun chooseImportFile(): Path? = dialogs.open("Import $what", listOf("json"), multiple = false).firstOrNull()
}

/** `<name>.scrcpypresets.json` with characters that are unsafe in file names replaced. */
fun exportFileName(presetName: String?): String {
    val base = presetName?.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")?.trim()?.ifEmpty { null } ?: "presets"
    return base + PRESET_FILE_SUFFIX
}
