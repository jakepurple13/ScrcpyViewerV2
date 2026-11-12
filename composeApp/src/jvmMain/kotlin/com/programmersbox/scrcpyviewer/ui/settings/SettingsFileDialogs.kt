package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.ui.common.NativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import java.nio.file.Path

/** Native pickers for Settings, behind an interface so tests never open a window. */
interface SettingsFileDialogs {
    /** Asks for an adb executable; null when cancelled. */
    fun chooseAdbFile(): Path?

    /** Asks for a folder; null when cancelled. */
    fun chooseFolder(initial: Path?): Path?

    /** Asks for a folder, with [title] on the dialog; null when cancelled. */
    fun chooseFolder(title: String): Path?
}

/** The platform's own open dialog, for files and folders. */
class NativeSettingsFileDialogs(private val dialogs: NativeFileDialogs = nativeFileDialogs()) : SettingsFileDialogs {
    override fun chooseAdbFile(): Path? =
        dialogs.open("Choose adb executable", emptyList(), multiple = false).firstOrNull()

    override fun chooseFolder(initial: Path?): Path? = pickFolder("Choose folder", initial)

    override fun chooseFolder(title: String): Path? = pickFolder(title, null)

    private fun pickFolder(title: String, initial: Path?): Path? =
        dialogs.open(title, emptyList(), multiple = false, directories = true, initial = initial).firstOrNull()
}
