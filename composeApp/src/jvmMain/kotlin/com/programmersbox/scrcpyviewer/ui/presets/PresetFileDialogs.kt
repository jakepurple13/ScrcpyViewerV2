package com.programmersbox.scrcpyviewer.ui.presets

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

const val PRESET_FILE_SUFFIX = ".scrcpypresets.json"

/** Native file pickers, behind an interface so tests never open a window. */
interface PresetFileDialogs {
    /** Asks where to save; null when cancelled. */
    fun chooseExportFile(suggestedName: String): Path?

    /** Asks which `.json` file to import; null when cancelled. */
    fun chooseImportFile(): Path?
}

/** `java.awt.FileDialog` (the native dialog on macOS). [what] names the content in the titles ("presets", "actions"). */
class AwtPresetFileDialogs(private val parent: () -> Frame? = { null }, private val what: String = "presets") : PresetFileDialogs {
    override fun chooseExportFile(suggestedName: String): Path? {
        val dialog = FileDialog(parent(), "Export $what", FileDialog.SAVE)
        dialog.file = suggestedName
        return show(dialog)
    }

    override fun chooseImportFile(): Path? {
        val dialog = FileDialog(parent(), "Import $what", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        return show(dialog)
    }

    private fun show(dialog: FileDialog): Path? {
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }
}

/** `<name>.scrcpypresets.json` with characters that are unsafe in file names replaced. */
fun exportFileName(presetName: String?): String {
    val base = presetName?.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")?.trim()?.ifEmpty { null } ?: "presets"
    return base + PRESET_FILE_SUFFIX
}
