package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.Os
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser

/** Native pickers for Settings, behind an interface so tests never open a window. */
interface SettingsFileDialogs {
    /** Asks for an adb executable; null when cancelled. */
    fun chooseAdbFile(): Path?

    /** Asks for a folder; null when cancelled. */
    fun chooseFolder(initial: Path?): Path?

    /** Asks for a folder, with [title] on the dialog; null when cancelled. */
    fun chooseFolder(title: String): Path?
}

class AwtSettingsFileDialogs(private val parent: () -> Frame? = { null }) : SettingsFileDialogs {
    override fun chooseAdbFile(): Path? {
        val dialog = FileDialog(parent(), "Choose adb executable", FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }

    override fun chooseFolder(initial: Path?): Path? = pickFolder("Choose folder", initial)

    override fun chooseFolder(title: String): Path? = pickFolder(title, null)

    private fun pickFolder(title: String, initial: Path?): Path? {
        if (Os.isMac) {
            // AWT's native macOS dialog picks folders when this property is set.
            val key = "apple.awt.fileDialogForDirectories"
            System.setProperty(key, "true")
            try {
                val dialog = FileDialog(parent(), title, FileDialog.LOAD)
                initial?.let { dialog.directory = it.toString() }
                dialog.isVisible = true
                val file = dialog.file ?: return null
                return Path.of(dialog.directory ?: "", file)
            } finally {
                System.setProperty(key, "false")
            }
        }
        val chooser = JFileChooser(initial?.toFile())
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (chooser.showOpenDialog(parent()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
}
